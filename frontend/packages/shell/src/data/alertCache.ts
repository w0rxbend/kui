import type { AlertFeed, AlertFeedCache } from "@kui/kernel";

const DATABASE_NAME = "kui-shell-cache";
const DATABASE_VERSION = 1;
const STORE_NAME = "alert-feeds";
const RECORD_VERSION = 1;
const FUTURE_CLOCK_TOLERANCE_MS = 60_000;

export const ALERT_FEED_CACHE_MAX_AGE_MS = 5 * 60_000;
export const ALERT_FEED_CACHE_MAX_ENTRIES = 16;

export interface AlertCacheScope {
  readonly cluster: string;
  readonly principalKind: string;
  readonly principalName: string;
  /** Stable serialization of the current session's roles and grants. */
  readonly authorization: string;
}

export interface AlertCacheRecord {
  readonly key: string;
  readonly version: typeof RECORD_VERSION;
  readonly savedAt: number;
  readonly feed: AlertFeed;
}

/** A tiny persistence port so the expiry and isolation policy is testable without browser globals. */
export interface AlertCacheDatabase {
  readonly get: (key: string) => Promise<unknown | undefined>;
  readonly put: (record: AlertCacheRecord) => Promise<void>;
  readonly delete: (key: string) => Promise<void>;
  readonly records: () => Promise<readonly unknown[]>;
  readonly clear: () => Promise<void>;
}

export interface BrowserAlertFeedCache extends AlertFeedCache {
  /** Clears all principal-scoped alert snapshots, used when the session ends. */
  readonly clear: () => Promise<void>;
}

interface AlertFeedCacheOptions {
  readonly scope: () => AlertCacheScope | undefined;
  readonly database?: AlertCacheDatabase | undefined;
  readonly hash?: ((material: string) => Promise<string | undefined>) | undefined;
  readonly now?: (() => number) | undefined;
  readonly maxAgeMs?: number | undefined;
  readonly maxEntries?: number | undefined;
}

/**
 * Creates a short-lived IndexedDB warm cache for the signed-in principal's current cluster.
 *
 * This is not another source of truth. The kernel validates the payload with the network decoder,
 * labels it stale, and replaces it as soon as the backend answers. Keys are SHA-256 digests so the
 * database's index does not disclose cluster or principal names; the payload is bounded by age and
 * count, and signing out clears the store.
 */
export function createAlertFeedCache(options: AlertFeedCacheOptions): BrowserAlertFeedCache {
  const database = options.database ?? createIndexedDbAlertCacheDatabase();
  const hash = options.hash ?? sha256;
  const now = options.now ?? Date.now;
  const maxAgeMs = options.maxAgeMs ?? ALERT_FEED_CACHE_MAX_AGE_MS;
  const maxEntries = Math.max(1, options.maxEntries ?? ALERT_FEED_CACHE_MAX_ENTRIES);

  const key = async (): Promise<string | undefined> => {
    const scope = options.scope();
    if (database === undefined || scope === undefined) return undefined;
    return hash(
      JSON.stringify([
        "v1",
        scope.principalKind,
        scope.principalName,
        scope.cluster,
        scope.authorization,
      ]),
    );
  };

  const removeQuietly = async (cacheKey: string): Promise<void> => {
    try {
      await database?.delete(cacheKey);
    } catch {
      // A warm cache is optional; failed maintenance must not affect the live feed.
    }
  };

  return {
    async read(): Promise<unknown | undefined> {
      const cacheKey = await key();
      if (cacheKey === undefined || database === undefined) return undefined;
      try {
        const raw = await database.get(cacheKey);
        if (raw === undefined) return undefined;
        const record = decodeRecord(raw, now(), maxAgeMs);
        if (record === undefined || record.key !== cacheKey) {
          await removeQuietly(cacheKey);
          return undefined;
        }
        return record.feed;
      } catch {
        return undefined;
      }
    },

    async write(feed: AlertFeed): Promise<void> {
      const cacheKey = await key();
      if (cacheKey === undefined || database === undefined) return;
      try {
        const savedAt = now();
        await database.put({ key: cacheKey, version: RECORD_VERSION, savedAt, feed });
        await prune(database, savedAt, maxAgeMs, maxEntries);
      } catch {
        // IndexedDB may be disabled, full, or evicted. The backend feed remains fully functional.
      }
    },

    async remove(): Promise<void> {
      const cacheKey = await key();
      if (cacheKey !== undefined) await removeQuietly(cacheKey);
    },

    async clear(): Promise<void> {
      try {
        await database?.clear();
      } catch {
        // Session teardown must proceed even when browser storage is unavailable.
      }
    },
  };
}

function decodeRecord(
  value: unknown,
  now: number,
  maxAgeMs: number,
): AlertCacheRecord | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const record = value as Record<string, unknown>;
  if (
    typeof record.key !== "string" ||
    record.version !== RECORD_VERSION ||
    typeof record.savedAt !== "number" ||
    !Number.isFinite(record.savedAt) ||
    typeof record.feed !== "object" ||
    record.feed === null ||
    record.savedAt > now + FUTURE_CLOCK_TOLERANCE_MS ||
    now - record.savedAt > maxAgeMs
  )
    return undefined;
  return record as unknown as AlertCacheRecord;
}

async function prune(
  database: AlertCacheDatabase,
  now: number,
  maxAgeMs: number,
  maxEntries: number,
): Promise<void> {
  const records = await database.records();
  const retained: AlertCacheRecord[] = [];
  const remove: string[] = [];

  for (const raw of records) {
    const cacheKey = recordKey(raw);
    if (cacheKey === undefined) continue;
    const decoded = decodeRecord(raw, now, maxAgeMs);
    if (decoded === undefined) remove.push(cacheKey);
    else retained.push(decoded);
  }

  retained.sort((left, right) => right.savedAt - left.savedAt);
  remove.push(...retained.slice(maxEntries).map((record) => record.key));
  await Promise.all(remove.map((cacheKey) => database.delete(cacheKey)));
}

function recordKey(value: unknown): string | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const key = (value as Record<string, unknown>).key;
  return typeof key === "string" ? key : undefined;
}

async function sha256(material: string): Promise<string | undefined> {
  const subtle = globalThis.crypto?.subtle;
  if (subtle === undefined) return undefined;
  const digest = await subtle.digest("SHA-256", new TextEncoder().encode(material));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Builds an opaque, stable browser-storage key without exposing scope fields in storage indexes. */
export async function opaqueBrowserScopeKey(
  namespace: string,
  parts: readonly string[],
): Promise<string | undefined> {
  const digest = await sha256(JSON.stringify([namespace, ...parts]));
  return digest === undefined ? undefined : `${namespace}.${digest}`;
}

function createIndexedDbAlertCacheDatabase(): AlertCacheDatabase | undefined {
  if (typeof indexedDB === "undefined") return undefined;
  let opened: Promise<IDBDatabase> | undefined;

  const open = (): Promise<IDBDatabase> => {
    opened ??= new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
      request.onupgradeneeded = () => {
        if (!request.result.objectStoreNames.contains(STORE_NAME))
          request.result.createObjectStore(STORE_NAME, { keyPath: "key" });
      };
      request.onsuccess = () => {
        const database = request.result;
        database.onversionchange = () => {
          database.close();
          opened = undefined;
        };
        resolve(database);
      };
      request.onerror = () => reject(request.error ?? new Error("IndexedDB could not be opened"));
      request.onblocked = () => reject(new Error("IndexedDB upgrade is blocked"));
    });
    return opened;
  };

  const request = async <T>(
    mode: IDBTransactionMode,
    action: (store: IDBObjectStore) => IDBRequest<T>,
  ): Promise<T> => {
    const database = await open();
    return new Promise<T>((resolve, reject) => {
      const transaction = database.transaction(STORE_NAME, mode);
      const operation = action(transaction.objectStore(STORE_NAME));
      let result: T;
      operation.onsuccess = () => {
        result = operation.result;
      };
      operation.onerror = () => reject(operation.error ?? new Error("IndexedDB request failed"));
      transaction.oncomplete = () => resolve(result);
      transaction.onerror = () =>
        reject(transaction.error ?? new Error("IndexedDB transaction failed"));
      transaction.onabort = () =>
        reject(transaction.error ?? new Error("IndexedDB transaction was aborted"));
    });
  };

  return {
    get: (key) => request("readonly", (store) => store.get(key)),
    put: async (record) => {
      await request("readwrite", (store) => store.put(record));
    },
    delete: async (key) => {
      await request("readwrite", (store) => store.delete(key));
    },
    records: () => request("readonly", (store) => store.getAll()),
    clear: async () => {
      await request("readwrite", (store) => store.clear());
    },
  };
}
