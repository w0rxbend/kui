import { createSignal } from "solid-js";

import { userMessage, type ApiError, type KuiApiClient, type components } from "@kui/api";
import type { MessageViewMode, PreferenceStorage } from "@kui/kernel";

import { opaqueBrowserScopeKey } from "./alertCache.js";
import type { AppearanceScope } from "./appearance.js";

const SETTINGS_PATH = "/api/v1/clusters/{clusterId}/settings/messages";
const CACHE_PREFIX = "kui.message-browser.";
export const MESSAGE_BROWSER_SAVE_DEBOUNCE_MS = 250;

type MessageBrowserSettingsDto = components["schemas"]["MessageBrowserSettingsDto"];

export interface MessageBrowserSnapshot {
  readonly pageSize: number;
  readonly mode: MessageViewMode;
}

export interface MessageBrowserPreference<A> {
  readonly choice: () => A;
  readonly select: (chosen: A) => void;
}

export interface MessageBrowserPreferences {
  readonly pageSize: MessageBrowserPreference<number>;
  readonly mode: MessageBrowserPreference<MessageViewMode>;
}

interface CachedMessageBrowser {
  readonly settings: MessageBrowserSnapshot;
  readonly dirty: boolean;
}

export type MessageBrowserSyncStatus =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "saving" }
  | { readonly kind: "saved" }
  | { readonly kind: "local-only"; readonly message: string };

export interface MessageBrowserSync {
  readonly preferences: MessageBrowserPreferences;
  readonly status: () => MessageBrowserSyncStatus;
  readonly selectScope: (scope: AppearanceScope | undefined) => void;
  readonly dispose: () => void;
}

export interface MessageBrowserSyncOptions {
  readonly api: KuiApiClient;
  readonly storage?: PreferenceStorage | undefined;
  readonly cacheKey?: ((scope: AppearanceScope) => Promise<string | undefined>) | undefined;
  readonly debounceMs?: number | undefined;
}

const DEFAULT_SETTINGS: MessageBrowserSnapshot = { pageSize: 100, mode: "pages" };

/**
 * Keeps the message screen's instant browser defaults in step with durable principal-scoped state.
 * A dirty local value is retried before any remote value may replace it.
 */
export function createMessageBrowserSync(options: MessageBrowserSyncOptions): MessageBrowserSync {
  const [settings, setSettings] = createSignal<MessageBrowserSnapshot>(DEFAULT_SETTINGS);
  const [status, setStatus] = createSignal<MessageBrowserSyncStatus>({ kind: "idle" });
  const debounceMs = options.debounceMs ?? MESSAGE_BROWSER_SAVE_DEBOUNCE_MS;
  const cacheKeyOf =
    options.cacheKey ??
    ((scope: AppearanceScope) =>
      opaqueBrowserScopeKey("message-browser-v1", [
        scope.principalKind,
        scope.principalName,
        scope.cluster,
      ]));
  let selectedCluster: string | undefined;
  let selectedCacheKey: string | undefined;
  let episode = 0;
  let revision = 0;
  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  let current = DEFAULT_SETTINGS;

  const apply = (next: MessageBrowserSnapshot): void => {
    current = next;
    setSettings(next);
  };

  const setLocalOnly = (
    cacheKey: string | undefined,
    snapshot: MessageBrowserSnapshot,
    error: ApiError,
  ): void => {
    writeCache(options.storage, cacheKey, { settings: snapshot, dirty: true });
    setStatus({
      kind: "local-only",
      message: `${userMessage(error)} Your message browsing defaults remain saved in this browser.`,
    });
  };

  const persist = async (
    cluster: string,
    cacheKey: string | undefined,
    snapshot: MessageBrowserSnapshot,
    expectedEpisode: number,
    expectedRevision: number,
  ): Promise<void> => {
    const answer = await options.api.put(SETTINGS_PATH, {
      params: { path: { clusterId: cluster } },
      body: snapshot,
    });
    if (
      expectedEpisode !== episode ||
      expectedRevision !== revision ||
      selectedCluster !== cluster
    )
      return;

    if (!answer.ok) {
      setLocalOnly(cacheKey, snapshot, answer.error);
      return;
    }
    const saved = decode(answer.value);
    if (saved === undefined) {
      setLocalOnly(cacheKey, snapshot, decodingError());
      return;
    }
    apply(saved);
    writeCache(options.storage, cacheKey, { settings: saved, dirty: false });
    setStatus({ kind: "saved" });
  };

  const scheduleSave = (cluster: string, delay = debounceMs): void => {
    if (saveTimer !== undefined) clearTimeout(saveTimer);
    const expectedEpisode = episode;
    const expectedRevision = revision;
    const snapshot = current;
    const cacheKey = selectedCacheKey;
    setStatus({ kind: "saving" });
    saveTimer = setTimeout(() => {
      saveTimer = undefined;
      void persist(cluster, cacheKey, snapshot, expectedEpisode, expectedRevision);
    }, delay);
  };

  const changed = (next: MessageBrowserSnapshot): void => {
    apply(next);
    const cluster = selectedCluster;
    if (cluster === undefined) return;
    revision += 1;
    writeCache(options.storage, selectedCacheKey, { settings: next, dirty: true });
    scheduleSave(cluster);
  };

  const selectScope = (scope: AppearanceScope | undefined): void => {
    selectedCluster = undefined;
    selectedCacheKey = undefined;
    episode += 1;
    revision += 1;
    if (saveTimer !== undefined) {
      clearTimeout(saveTimer);
      saveTimer = undefined;
    }
    if (scope === undefined) {
      setStatus({ kind: "idle" });
      return;
    }

    apply(DEFAULT_SETTINGS);
    const expectedEpisode = episode;
    const expectedRevision = revision;
    selectedCluster = scope.cluster;
    setStatus({ kind: "loading" });
    void cacheKeyOf(scope)
      .catch(() => undefined)
      .then((cacheKey) => {
        if (expectedEpisode !== episode || expectedRevision !== revision) return;
        selectedCacheKey = cacheKey;
        const cached = readCache(options.storage, cacheKey);
        if (cached !== undefined) apply(cached.settings);

        if (cached?.dirty === true) {
          scheduleSave(scope.cluster, 0);
          return;
        }

        void options.api
          .get(SETTINGS_PATH, { params: { path: { clusterId: scope.cluster } } })
          .then((answer) => {
            if (
              expectedEpisode !== episode ||
              expectedRevision !== revision ||
              selectedCluster !== scope.cluster
            )
              return;
            if (!answer.ok) {
              setLocalOnly(cacheKey, current, answer.error);
              return;
            }
            const remote = decode(answer.value);
            if (remote === undefined) {
              setLocalOnly(cacheKey, current, decodingError());
              return;
            }
            apply(remote);
            writeCache(options.storage, cacheKey, { settings: remote, dirty: false });
            setStatus({ kind: "saved" });
          });
      });
  };

  return {
    preferences: {
      pageSize: {
        choice: () => settings().pageSize,
        select: (pageSize) => changed({ ...current, pageSize }),
      },
      mode: {
        choice: () => settings().mode,
        select: (mode) => changed({ ...current, mode }),
      },
    },
    status,
    selectScope,
    dispose(): void {
      episode += 1;
      if (saveTimer !== undefined) clearTimeout(saveTimer);
    },
  };
}

function decode(value: MessageBrowserSettingsDto): MessageBrowserSnapshot | undefined {
  if (!Number.isSafeInteger(value.pageSize) || value.pageSize < 1 || value.pageSize > 500)
    return undefined;
  if (value.mode !== "pages" && value.mode !== "infinite") return undefined;
  return { pageSize: value.pageSize, mode: value.mode };
}

function readCache(
  storage: PreferenceStorage | undefined,
  cacheKey: string | undefined,
): CachedMessageBrowser | undefined {
  if (storage === undefined || cacheKey === undefined) return undefined;
  try {
    const raw = storage.getItem(`${CACHE_PREFIX}${cacheKey}`);
    if (raw === null) return undefined;
    const parsed = JSON.parse(raw) as unknown;
    if (typeof parsed !== "object" || parsed === null) return undefined;
    const record = parsed as Record<string, unknown>;
    const decoded = decodeUnknown(record.settings);
    return decoded === undefined || typeof record.dirty !== "boolean"
      ? undefined
      : { settings: decoded, dirty: record.dirty };
  } catch {
    return undefined;
  }
}

function writeCache(
  storage: PreferenceStorage | undefined,
  cacheKey: string | undefined,
  cached: CachedMessageBrowser,
): void {
  if (storage === undefined || cacheKey === undefined) return;
  try {
    storage.setItem(`${CACHE_PREFIX}${cacheKey}`, JSON.stringify(cached));
  } catch {
    // The current tab remains functional when storage is unavailable or full.
  }
}

function decodeUnknown(value: unknown): MessageBrowserSnapshot | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const record = value as Record<string, unknown>;
  if (typeof record.pageSize !== "number" || typeof record.mode !== "string") return undefined;
  return decode({ pageSize: record.pageSize, mode: record.mode });
}

function decodingError(): ApiError {
  return { kind: "decoding", cause: "invalid message browser settings" };
}
