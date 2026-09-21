/**
 * Reading server state from a component: one key, one request, and the six-case answer.
 *
 * ## Why this exists
 *
 * `createQueryCache` was finished, tested and called by nothing, while six route files each grew
 * their own `useFetch` — a signal, an attempt counter, an effect, and a cancellation flag, written
 * out again every time. Two of those six say in their own comments that they are a copy. The cost
 * is not the twenty lines; it is the ADR-032 rendering rule each of them has to get right and none
 * of them does — the last one in its Decision: *stale data from the session stays on screen greyed
 * with its timestamp, actions disabled*. Named rather than counted, because no count fits: ADR-032
 * defines a five-member `FeatureState` and lists rendering rules for four of them plus that one,
 * while the answer this module hands back has the six kinds `Fetched` declares. A sentence about
 * "four states" is about neither list, and it stood here for a wave.
 *
 * A hand-rolled `useFetch` starts every attempt at `loading`, so a refetch that fails blanks a
 * panel that was showing real figures a second ago, and a refetch that fails *while succeeding
 * elsewhere* asks the same endpoint once per component. Neither is visible in review: both look
 * exactly like the version that works.
 *
 * So the behaviour lives once, here, and it is the behaviour none of the six have:
 *
 * - **A failing refetch keeps the last good value** and marks the picture `stale` with the reason,
 *   which is ADR-032's rule and the whole reason `QueryState` carries `lastGood` separately from
 *   `outcome`.
 * - **Two components asking for the same key share one request.** The header, the breadcrumb and
 *   the table all begin by asking for the cluster; that is one call, not three, and one answer on
 *   screen rather than three arriving at different moments.
 *
 * ## What a key means
 *
 * A key is a promise: *everything that changes the request is in the string*. That is what lets two
 * components with the same key share an answer, and it is why the first loader bound to a key wins
 * — a second component's copy of the same request must not replace one that is already in flight.
 * A filter that is not in the key is a bug that shows up as one component's controls silently
 * driving another's data.
 *
 * ## Why the answer is `Fetched` and not `QueryState`
 *
 * `Fetched` is what every screen in this product already draws, and its `forbidden` and
 * `not-configured` cases are the two a cache cannot invent: they arrive inside a perfectly
 * successful HTTP response, in a section. So the loader hands back `Fetched` — which is what every
 * feature's `data.ts` already produces — and this module carries the refusal through the cache
 * intact rather than flattening it to "failed", which would put a retry button in front of an
 * operator for whom retrying is pointless.
 */
import { createMemo, onCleanup, untrack, type Accessor } from "solid-js";
import type { ApiError, ApiResult } from "@kui/api";

import { apiFailure, type Fetched } from "../fetched.js";
import {
  createQueryCache,
  DEFAULT_MAX_ENTRIES,
  type QueryCache,
  type QueryState,
} from "./cache.js";

/** The two `Fetched` cases that carry real data. Only these are cached as a success. */
type Answered<A> = Extract<Fetched<A>, { readonly kind: "ready" } | { readonly kind: "stale" }>;

/** The three that do not. Each is a different sentence, and none of them may become the others. */
type Refused = Extract<
  Fetched<never>,
  { readonly kind: "failed" } | { readonly kind: "forbidden" } | { readonly kind: "not-configured" }
>;

/** How one key's value is obtained. Never rejects, by the same rule `@kui/api` follows. */
export type QueryLoader<A> = (key: string) => Promise<Fetched<A>>;

/**
 * The refusal, riding along on the `ApiError` the cache stores.
 *
 * The cache's contract is `ApiResult`, whose failure slot is an `ApiError` — four transport shapes
 * that cannot express "you may not see this" or "this deployment has no such thing". Rather than
 * keep a second map beside the cache and have to reproduce its generation rule (a slow answer to a
 * superseded request must not overwrite a fresh one), the exact refusal travels *with* the outcome
 * the cache decides to keep. Whatever the cache holds, this is the answer it holds.
 */
const REFUSAL = Symbol("kui.query.refusal");

/** What a refusal says, in the `ApiError` the cache holds. Never rendered — see {@link REFUSAL}. */
function sentenceOf(refused: Refused): string {
  switch (refused.kind) {
    case "failed":
      return refused.message;
    case "forbidden":
      return "You are not allowed to see this.";
    case "not-configured":
      return "This deployment has nothing configured for it.";
  }
}

/** What a loader that answered `loading` is recorded as. It has told the cache nothing. */
const INCOMPLETE: Refused = {
  kind: "failed",
  message: "KUI asked for something and was told the answer is still coming.",
  code: "QUERY_INCOMPLETE",
};

/** A loader's answer, in the shape the cache understands. */
function asResult<A>(answer: Fetched<A>): ApiResult<Answered<A>> {
  if (answer.kind === "ready" || answer.kind === "stale") return { ok: true, value: answer };
  // The cache has to hold something, and `loading` is not an answer any screen can act on. Every
  // other kind is stored exactly as the loader gave it.
  const refused: Refused = answer.kind === "loading" ? INCOMPLETE : answer;
  const error: ApiError = { kind: "unreachable", cause: sentenceOf(refused) };
  return { ok: false, error: Object.assign(error, { [REFUSAL]: refused }) };
}

function refusalOf(error: ApiError): Refused | undefined {
  return (error as { [REFUSAL]?: Refused })[REFUSAL];
}

/**
 * The shared answers: one cache, and which loader answers for each key.
 *
 * A registry rather than a cache per hook, because sharing is the point — a cache one component
 * owns is a cache no other component can hit. Freshness is a property of the registry rather than
 * of a single query: a screen whose figures move faster than the default thirty seconds asks for
 * its own registry rather than teaching every key a private clock.
 */
export interface QueryRegistry {
  /** Marks a key stale. Anything watching it refetches now; anything else, when next watched. */
  invalidate(key: string): void;
  /** The same for every key matching a predicate — "everything about cluster `a`" after a write. */
  invalidateWhere(matches: (key: string) => boolean): void;
  /** How many keys hold an answer. For tests and the diagnostics panel. */
  size(): number;
  /** How many keys something is currently reading. Zero when every screen has been left. */
  bound(): number;
  /** @internal `useQuery`'s side of the arrangement; not for features. */
  readonly internals: {
    bind(key: string, load: QueryLoader<unknown>): void;
    unbind(key: string): void;
    watch(key: string): Accessor<QueryState<Answered<unknown>>>;
  };
}

export interface QueryRegistryOptions {
  readonly staleAfterMs?: number;
  readonly maxEntries?: number;
  /** The clock, for the tests that would otherwise wait thirty real seconds. */
  readonly now?: () => number;
}

/** What is asked when a key is watched with nothing bound to it. Defensive; see `bind`. */
const UNBOUND: Fetched<never> = {
  kind: "failed",
  message: "KUI asked for something nothing is loading.",
  code: "QUERY_UNBOUND",
};

/** Builds an independent registry. Features use {@link sharedQueries}; tests use their own. */
export function createQueryRegistry(options: QueryRegistryOptions = {}): QueryRegistry {
  interface Binding {
    /** The first loader bound to this key. See the header on what a key promises. */
    readonly load: QueryLoader<unknown>;
    /** How many live `useQuery` calls are reading it. */
    readers: number;
  }

  const bindings = new Map<string, Binding>();

  const cache: QueryCache<Answered<unknown>> = createQueryCache<Answered<unknown>>({
    fetch: async (key) => {
      const binding = bindings.get(key);
      // Only a watched key is ever fetched, and watching binds first, so this is unreachable in the
      // product. It is a value rather than a throw because a cache that rejects is a cache that
      // takes a page down, which is the defect `ApiResult` exists to prevent.
      if (binding === undefined) return asResult(UNBOUND);
      return asResult(await binding.load(key));
    },
    ...(options.staleAfterMs === undefined ? {} : { staleAfterMs: options.staleAfterMs }),
    maxEntries: options.maxEntries ?? DEFAULT_MAX_ENTRIES,
    ...(options.now === undefined ? {} : { now: options.now }),
  });

  return {
    invalidate: (key) => {
      cache.invalidate(key);
    },
    invalidateWhere: (matches) => {
      cache.invalidateWhere(matches);
    },
    size: () => cache.size(),
    bound: () => bindings.size,
    internals: {
      bind(key, load) {
        const existing = bindings.get(key);
        if (existing === undefined) bindings.set(key, { load, readers: 1 });
        else existing.readers += 1;
      },
      unbind(key) {
        const existing = bindings.get(key);
        if (existing === undefined) return;
        existing.readers -= 1;
        if (existing.readers <= 0) bindings.delete(key);
      },
      watch: (key) => cache.watch(key),
    },
  };
}

/**
 * The registry every feature shares, so that two features asking for one cluster ask once.
 *
 * Module state, deliberately: the alternative is a provider every test and every story has to
 * remember to wrap, and the thing being shared is a browser tab's view of one server.
 */
export const sharedQueries: QueryRegistry = createQueryRegistry();

export interface QueryOptions<A> {
  /**
   * What is being asked for. `undefined` means "not yet" — a screen whose cluster id has not been
   * resolved asks nothing and stays `loading`, rather than fetching a key with `undefined` in it.
   */
  readonly key: () => string | undefined;
  readonly load: QueryLoader<A>;
  /** Which shared answers to read. Defaults to {@link sharedQueries}. */
  readonly registry?: QueryRegistry;
}

export interface Query<A> {
  /**
   * The state to draw.
   *
   * Read it inside a reactive scope — a component's JSX, a memo, an effect. That is what starts the
   * request and what keeps the entry alive. Read outside one it answers `loading` and lets the
   * subscription go again at once, so the answer arrives with nothing left watching for it.
   */
  readonly state: Accessor<Fetched<A>>;
  /** Ask again now — the retry button on a failure panel, and the refresh control on a page. */
  readonly reload: () => void;
}

/**
 * One key's server state, as a screen's six-case {@link Fetched}.
 *
 * @param options the key, how to load it, and optionally which registry to share.
 */
export function useQuery<A>(options: QueryOptions<A>): Query<A> {
  const registry = options.registry ?? sharedQueries;

  /**
   * The subscription: bound when something first reads {@link Query.state} in a reactive scope, and
   * torn down when the last reader goes away or the key changes.
   *
   * `lazy` is what makes that true — a memo nothing reads never computes, so a `useQuery` in a
   * component that renders a skeleton and never touches the data fetches nothing. The `onCleanup`
   * runs both on disposal and before the next recompute, which is exactly the unbind a key change
   * needs: the old key's binding is released before the new one is taken.
   */
  const bound = createMemo(
    () => {
      const key = options.key();
      if (key === undefined) return undefined;
      registry.internals.bind(key, options.load);
      onCleanup(() => {
        registry.internals.unbind(key);
      });
      // `watch` only *creates* the subscription; the fetch happens when the accessor below is read.
      return { key, watch: registry.internals.watch(key) };
    },
    { lazy: true },
  );

  const state: Accessor<Fetched<A>> = () => {
    const current = bound();
    if (current === undefined) return { kind: "loading" };
    // The cache holds `Answered<unknown>` because one registry holds every key. The key is what
    // makes this sound: a key names one request, and one request has one answer type.
    return toFetched(current.watch() as QueryState<Answered<A>>);
  };

  return {
    state,
    reload: () => {
      // Untracked: a retry button's handler is not a place to take a dependency on the key.
      const key = untrack(options.key);
      if (key !== undefined) registry.invalidate(key);
    },
  };
}

/**
 * The cache's bookkeeping, as the sentence a screen shows.
 *
 * The one rule worth stating: a **failure** over a value the cache already has becomes `stale`
 * rather than `failed`, because the operator would rather see yesterday's figures with a badge on
 * them than a blank card. A **refusal** does not. `forbidden` over a value that arrived when the
 * principal still had the grant is data they may no longer see, and `not-configured` over a value
 * describes something that has been taken out of the deployment — showing either as "stale" would
 * be reassuring and wrong, which is the pairing this product treats as the expensive one.
 */
function toFetched<A>(state: QueryState<Answered<A>>): Fetched<A> {
  const outcome = state.outcome;
  if (outcome === undefined) return { kind: "loading" };
  if (outcome.ok) return outcome.value;

  // `apiFailure` is the fallback for the one error the cache makes itself: a loader that broke its
  // promise and rejected. Everything this module stores carries its own refusal.
  const refusal = refusalOf(outcome.error) ?? apiFailure(outcome.error);
  const lastGood = state.lastGood;
  // `state.stale` is the cache's own judgement — "there is something worth showing and the newest
  // thing we know is a failure" — and its docstring says it exists so that this call site does not
  // re-derive it. Reading it rather than rebuilding it out of `lastGood` and `outcome.ok` is the
  // whole point of the field; the `undefined` test that follows narrows the type for the value
  // below and is not a second opinion about staleness.
  if (!state.stale || refusal.kind !== "failed" || lastGood === undefined) return refusal;
  return { kind: "stale", value: lastGood.value, reason: refusal.message };
}
