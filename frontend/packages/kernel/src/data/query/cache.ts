/**
 * Server state, fetched once and shared.
 *
 * ## The problem it solves
 *
 * A screen is made of independent components, and several of them usually want the same thing: a
 * header showing the cluster's name, a breadcrumb showing the cluster's name, and a table of the
 * cluster's topics all begin by asking for the cluster. Written naively that is three identical
 * requests on every render, and three different answers on screen while they are in flight.
 *
 * ## What "watching" means here
 *
 * {@link QueryCache.watch} returns an accessor and fetches nothing. The request happens when
 * something *reads that accessor inside a reactive scope* — a component's JSX, a memo, an effect —
 * and only if what is held is missing or stale. When the last reader is disposed the entry stops
 * being refreshed and becomes a candidate for eviction. That is what stops a page the user has left
 * behind from continuing to poll.
 *
 * Solid 2 gives this for free and exactly: a `createMemo(..., { lazy: true })` computes on its first
 * subscriber and is torn down when its last one goes away, and it recomputes if somebody watches
 * again later. Under Laminar the same behaviour needed a custom `EventStream` source with `start`
 * and `stop` callbacks. The behaviour is the one being preserved; the mechanism is the framework's.
 *
 * ## What is deliberately *not* here
 *
 * No suspense. A failure is a value in {@link QueryState}, not a thrown promise, because a thrown
 * failure reaches the nearest `<Errored>` boundary and takes the whole subtree down — the blank
 * screen this product has already shipped once (see `@kui/api`'s `ApiResult`). A screen renders its
 * own failure, next to the stale data it is still showing.
 */
import type { ApiError, ApiResult } from "@kui/api";
import { createMemo, createSignal, onCleanup, untrack, type Accessor } from "solid-js";

/** How long a successful answer is trusted before the next watcher refetches it. */
export const DEFAULT_STALE_AFTER_MS = 30_000;

/**
 * How long a *failed* answer is kept.
 *
 * Much shorter than a success, because a failure is usually transient and the user is usually
 * waiting. Not zero, because zero means every component that wanted the data retries independently
 * and a struggling endpoint is hit by the whole page at once, which is how a slow service becomes a
 * dead one.
 */
export const NEGATIVE_STALE_AFTER_MS = 5_000;

/**
 * How many keys are kept. Bounded because a user browsing a thousand topics would otherwise
 * accumulate a thousand cached answers in a tab that stays open all day.
 */
export const DEFAULT_MAX_ENTRIES = 200;

/**
 * Everything a screen needs in order to draw itself: what is happening now, the last value that was
 * ever good, and when that value arrived.
 *
 * All three in one type, because ADR-032's stale rule needs all three at once — the old numbers to
 * keep showing, the timestamp to put on the badge, and the current failure to explain why the
 * numbers are not moving. Deriving them separately is what leads every screen to keep a private
 * shadow copy of its own last good answer, which is the duplication this type removes.
 */
export interface QueryState<A> {
  /** True while nothing has been answered yet for this key. */
  readonly pending: boolean;
  /** The newest answer, success or failure, once there is one. */
  readonly outcome: ApiResult<A> | undefined;
  /** The last answer that was a success. A failing refetch never clears it. */
  readonly lastGood: A | undefined;
  /** When {@link lastGood} arrived, in epoch milliseconds. */
  readonly lastGoodAt: number | undefined;
  /**
   * True when there is something worth showing and the newest thing we know is a failure.
   *
   * False when a key has only ever failed: that is an empty screen with an error on it, which is a
   * fallback panel's job. Telling the two apart is the whole reason this is a field rather than
   * `!outcome.ok` written out at each call site.
   */
  readonly stale: boolean;
}

export interface QueryCache<A> {
  /**
   * The state of one key, and the subscription that keeps it fresh.
   *
   * Read the returned accessor inside a reactive scope. Reading it outside one still answers, but
   * subscribes nothing and therefore fetches nothing — use {@link peek} when that is what you mean.
   */
  watch(key: string): Accessor<QueryState<A>>;
  /** The state of one key without subscribing, fetching, or keeping it alive. */
  peek(key: string): QueryState<A>;
  /**
   * Marks one key stale. A key something is watching is refetched at once; one nothing is watching
   * is refetched the next time it is watched.
   */
  invalidate(key: string): void;
  /**
   * The same, for every key matching a predicate.
   *
   * This is prefix invalidation: after creating a topic on cluster `a`, every cached list belonging
   * to cluster `a` is wrong and every list belonging to cluster `b` is still perfectly good.
   * Invalidating everything would be correct and would also refetch the whole application.
   */
  invalidateWhere(matches: (key: string) => boolean): void;
  /**
   * Puts a value in without asking the server.
   *
   * For the answer a mutation already returned: creating a topic answers with the topic, so
   * re-fetching it immediately afterwards asks a question that has just been answered.
   */
  set(key: string, value: A): void;
  /** How many keys are held. For tests and for the diagnostics panel. */
  size(): number;
}

export interface QueryCacheOptions<A> {
  /** How to get one key's value. Never rejects: `@kui/api` answers failures as values. */
  readonly fetch: (key: string) => Promise<ApiResult<A>>;
  readonly staleAfterMs?: number;
  readonly negativeStaleAfterMs?: number;
  readonly maxEntries?: number;
  /**
   * The clock. A parameter, because a test for a thirty-second staleness rule that waits thirty real
   * seconds is a test nobody runs.
   */
  readonly now?: () => number;
}

interface Held<A> {
  readonly outcome: ApiResult<A>;
  readonly at: number;
}

/** Builds a cache. */
export function createQueryCache<A>(options: QueryCacheOptions<A>): QueryCache<A> {
  const staleAfterMs = options.staleAfterMs ?? DEFAULT_STALE_AFTER_MS;
  const negativeStaleAfterMs = options.negativeStaleAfterMs ?? NEGATIVE_STALE_AFTER_MS;
  const maxEntries = options.maxEntries ?? DEFAULT_MAX_ENTRIES;
  const now = options.now ?? (() => Date.now());

  interface Entry {
    /** The newest answer, and when it arrived. */
    readonly held: Accessor<Held<A> | undefined>;
    readonly setHeld: (value: Held<A> | undefined) => void;
    /**
     * The last answer that was a success.
     *
     * Held separately rather than derived, because the newest answer is overwritten by a failure and
     * the whole point is that the failure must not take the previous good answer with it.
     */
    readonly good: Accessor<Held<A> | undefined>;
    readonly setGood: (value: Held<A> | undefined) => void;
    readonly pending: Accessor<boolean>;
    readonly setPending: (value: boolean) => void;
    /** How many live readers are watching. Zero means nothing on screen wants it. */
    watchers: number;
    /**
     * Which fetch is the current one.
     *
     * A refetch can start while the previous request is still outstanding — invalidating a key that
     * is on screen does exactly that. Both will eventually answer, and the older answer is by
     * definition the staler one, so it has to be dropped rather than written over the newer. Without
     * this the cache would occasionally settle on the value it asked for first, which is a bug that
     * only appears under a slow network and is therefore never reproduced.
     */
    generation: number;
    /**
     * Set by `invalidate`, cleared by the next completed fetch. Kept separately from the timestamp so
     * that invalidating does not throw the value away: ADR-032 wants stale data to stay on screen.
     */
    invalidated: boolean;
  }

  const entries = new Map<string, Entry>();

  function createEntry(): Entry {
    // Every one of these is written from a promise callback and from `invalidate`, both of which can
    // be reached from inside an owned scope (a component body reading `watch` for the first time, an
    // effect that invalidates after a mutation). `ownedWrite` is the sanctioned opt-in for exactly
    // that: state with one writer path, owned by the cache and not by any component.
    const [held, setHeld] = createSignal<Held<A> | undefined>(undefined, { ownedWrite: true });
    const [good, setGood] = createSignal<Held<A> | undefined>(undefined, { ownedWrite: true });
    const [pending, setPending] = createSignal(false, { ownedWrite: true });
    return {
      held,
      setHeld,
      good,
      setGood,
      pending,
      setPending,
      watchers: 0,
      generation: 0,
      invalidated: false,
    };
  }

  function entryFor(key: string): Entry {
    const existing = entries.get(key);
    if (existing !== undefined) return existing;
    const created = createEntry();
    entries.set(key, created);
    return created;
  }

  function isStale(entry: Entry): boolean {
    const held = entry.held();
    if (held === undefined) return true;
    if (entry.invalidated) return true;
    const ttl = held.outcome.ok ? staleAfterMs : negativeStaleAfterMs;
    return now() - held.at >= ttl;
  }

  function store(entry: Entry, outcome: ApiResult<A>): void {
    const arrived: Held<A> = { outcome, at: now() };
    entry.setHeld(arrived);
    if (outcome.ok) entry.setGood(arrived);
    entry.setPending(false);
    entry.invalidated = false;
    evictIfOverBound();
  }

  function startFetch(key: string, entry: Entry): void {
    entry.generation += 1;
    const generation = entry.generation;
    entry.setPending(true);
    options.fetch(key).then(
      (outcome) => {
        if (entry.generation === generation) store(entry, outcome);
      },
      (cause: unknown) => {
        // `@kui/api` never rejects, so reaching here means a caller supplied a `fetch` that does.
        // Turning it into a value rather than letting it become an unhandled rejection keeps the
        // cache's promise — a failure is data — even when its input breaks it.
        if (entry.generation !== generation) return;
        const error: ApiError = { kind: "unreachable", cause: String(cause) };
        store(entry, { ok: false, error });
      },
    );
  }

  /** Somebody started watching this key. Fetches only if what is held is missing or too old. */
  function acquire(key: string): void {
    const entry = entryFor(key);
    entry.watchers += 1;
    if (isStale(entry) && !entry.pending()) startFetch(key, entry);
  }

  function release(key: string): void {
    const entry = entries.get(key);
    if (entry === undefined) return;
    entry.watchers = Math.max(0, entry.watchers - 1);
    if (entry.watchers === 0) evictIfOverBound();
  }

  /**
   * Drops unwatched entries, oldest answer first, until the bound is met.
   *
   * Two things are never evicted, whatever the bound says. A **watched** entry, because throwing
   * away something that is on screen would blank a panel to save memory, which is not a trade
   * anybody would choose. And an entry whose **request is still in flight**, because dropping it
   * would leave a promise whose answer has nowhere to go — the component that asked would sit on a
   * spinner until something else happened to invalidate it. The bound is therefore a bound on
   * settled, unwatched answers, which is what it was always meant to be.
   *
   * It runs when an entry settles and when a watcher goes away — the two moments the cache actually
   * grows in a way worth reclaiming. Running it as entries are *created* was worse than useless: it
   * evicted the entry that was in the middle of being set up.
   */
  function evictIfOverBound(): void {
    const excess = entries.size - maxEntries;
    if (excess <= 0) return;
    const evictable = [...entries.entries()]
      .filter(([, entry]) => entry.watchers === 0 && entry.held() !== undefined && !entry.pending())
      .sort(([, left], [, right]) => (left.held()?.at ?? 0) - (right.held()?.at ?? 0))
      .slice(0, excess);
    for (const [key] of evictable) entries.delete(key);
  }

  function stateOf(entry: Entry): QueryState<A> {
    const held = entry.held();
    const good = entry.good();
    const outcome = held?.outcome;
    return {
      pending: outcome === undefined,
      outcome,
      lastGood: good?.outcome.ok === true ? good.outcome.value : undefined,
      lastGoodAt: good?.at,
      stale: good !== undefined && outcome !== undefined && !outcome.ok,
    };
  }

  return {
    watch(key: string): Accessor<QueryState<A>> {
      const entry = entryFor(key);

      /**
       * The subscription, as a lazy memo.
       *
       * `lazy` is what makes it demand-driven: it computes when something first reads it inside a
       * reactive scope, and Solid tears it down — running the `onCleanup` below — when its last
       * reader is disposed. That pair is the whole lifecycle, and it is per `watch` call rather than
       * per key so that two components watching the same key are two independent subscriptions to
       * one shared entry, exactly as they were under Laminar.
       *
       * The compute is wrapped in `untrack` because `acquire` reads the entry's own signals to
       * decide whether to fetch. Tracked, that read would make every answer recompute the memo,
       * which would release and re-acquire the subscription on each write — the watcher count would
       * flap, and with it the eviction bookkeeping.
       */
      const lifecycle = createMemo(
        () =>
          untrack(() => {
            acquire(key);
            onCleanup(() => {
              release(key);
            });
            return true;
          }),
        { lazy: true },
      );

      return () => {
        lifecycle();
        return stateOf(entry);
      };
    },

    peek(key: string): QueryState<A> {
      const entry = entries.get(key);
      return entry === undefined
        ? { pending: true, outcome: undefined, lastGood: undefined, lastGoodAt: undefined, stale: false }
        : stateOf(entry);
    },

    invalidate(key: string): void {
      this.invalidateWhere((candidate) => candidate === key);
    },

    invalidateWhere(matches: (key: string) => boolean): void {
      for (const [key, entry] of entries) {
        if (!matches(key)) continue;
        entry.invalidated = true;
        // Only what is on screen is refetched now. An entry nobody is watching is refetched when
        // somebody looks at it again, which is the difference between invalidating a cache and
        // reloading the application.
        if (entry.watchers > 0) startFetch(key, entry);
      }
    },

    set(key: string, value: A): void {
      const entry = entryFor(key);
      store(entry, { ok: true, value });
    },

    size: () => entries.size,
  };
}

/**
 * Builds a cache key out of its parts.
 *
 * A cache key is a string because that is what makes {@link QueryCache.invalidateWhere} expressible
 * — "everything about cluster `a`" is a prefix test, and a structured key would need an equality
 * function per screen. The separator is a character no cluster id, topic name or group id may
 * contain, so two different key lists cannot collide.
 */
export function queryKey(...parts: readonly (string | number)[]): string {
  return parts.join("|");
}
