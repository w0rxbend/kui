/**
 * One feature module's download, as a state machine with no way to get stuck in the middle.
 *
 * ## The defect this exists to prevent
 *
 * KUI shipped a permanent "Loading Messages…" spinner. It was not a rendering fault: it was a state
 * machine with no exit from its middle state. A dynamic `import()` is an HTTP request made minutes
 * after the page loaded, over whatever connection the user has *now*, and a request does not only
 * succeed or fail — it can also hang. A captive portal, a proxy holding the connection open, a chunk
 * that answers `200` and then stalls mid-body: in every one of those the promise never settles, the
 * continuation never runs, and the route spins for the life of the tab.
 *
 * So `loading` is bounded. When the deadline passes the state becomes `failed` with a sentence
 * saying so, and the shell renders a panel with a retry button. A bounded failure the user can see
 * and act on beats an unbounded wait they cannot.
 *
 * ## The late-arrival guard
 *
 * Every attempt carries a number, and an outcome is recorded only while it belongs to the current
 * attempt *and* that attempt is still loading. Without it an import that finally arrives after we
 * gave up on it overwrites the failure the user is now looking at with a retry button, and the panel
 * vanishes into a component that was never built. Retrying also has to increment the number, or the
 * abandoned attempt's timer fails the attempt that replaced it.
 *
 * ## Why the failure is recoverable, and the memoisation is not "remember the promise"
 *
 * `load()` is idempotent: ten calls import once, which is what makes it safe to call from a
 * computation that re-runs whenever capability state changes. But the memoisation is "do not call
 * the importer again while a call is outstanding **or has succeeded**", not "remember the promise" —
 * remembering the promise would make a failed import permanent, which is exactly the case `retry()`
 * exists for.
 */
import { createSignal, type Accessor } from "solid-js";

export type LoadState<A> =
  /** Nothing has been asked for. The browser has not fetched a byte of this feature. */
  | { readonly kind: "not-loaded" }
  | { readonly kind: "loading" }
  | { readonly kind: "loaded"; readonly value: A }
  /** Recoverable: `retry()` tries again. */
  | { readonly kind: "failed"; readonly cause: string };

export type LazyModule<A> = {
  readonly state: Accessor<LoadState<A>>;
  /** Starts the download if it has not started. Idempotent. */
  readonly load: () => void;
  /** Clears a `failed` state and tries again. Does nothing in any other state. */
  readonly retry: () => void;
};

/**
 * How long a feature's module may take to arrive before the shell calls it a failure.
 *
 * Twenty seconds, and the number is a judgement rather than a measurement: a feature chunk is tens of
 * kilobytes, so on any connection that is working at all it arrives in well under a second, and a
 * wait this long has almost certainly not got a download at the end of it. Erring long is the safe
 * direction — a spinner replaced by a retry button one second too early is worse than one second of
 * extra patience, because pressing retry starts the download again from nothing.
 */
export const DefaultLoadTimeoutMs = 20_000;

/** What the panel says when the wait ran out. A sentence, because it is shown to a person. */
export function timedOutMessage(timeoutMs: number): string {
  return `it did not arrive within ${Math.round(timeoutMs / 1000)} seconds`;
}

export type LazyModuleOptions = {
  readonly timeoutMs?: number | undefined;
  /**
   * The deadline's timer.
   *
   * A parameter rather than a call to `setTimeout` in the body, so a test can decide when the
   * deadline passes instead of waiting out a real one. A suite that waits twenty real seconds for
   * this is a suite nobody runs, and a bound nobody tests is a bound nobody has.
   */
  readonly schedule?: ((delayMs: number, run: () => void) => void) | undefined;
};

export function createLazyModule<A>(
  load: () => Promise<A>,
  options: LazyModuleOptions = {},
): LazyModule<A> {
  const timeoutMs = options.timeoutMs ?? DefaultLoadTimeoutMs;
  const schedule =
    options.schedule ??
    ((delayMs: number, run: () => void) => {
      setTimeout(run, delayMs);
    });

  const [state, setState] = createSignal<LoadState<A>>({ kind: "not-loaded" });
  let attempt = 0;

  /**
   * Records an outcome, unless the attempt it belongs to has been superseded or given up on.
   *
   * Read from the signal untracked-by-construction: this is called from a promise continuation and a
   * timer, neither of which is a reactive scope, so there is nothing to track.
   */
  const settle = (thisAttempt: number, outcome: LoadState<A>): void => {
    if (attempt === thisAttempt && state().kind === "loading") setState(outcome);
  };

  const start = (): void => {
    attempt += 1;
    const thisAttempt = attempt;
    setState({ kind: "loading" });

    schedule(timeoutMs, () => settle(thisAttempt, { kind: "failed", cause: timedOutMessage(timeoutMs) }));

    load().then(
      (value) => settle(thisAttempt, { kind: "loaded", value }),
      (cause: unknown) => settle(thisAttempt, { kind: "failed", cause: describe(cause) }),
    );
  };

  return {
    state,
    load: () => {
      // Already in flight, already here, or failed and waiting for an explicit retry. In none of
      // those does calling the importer again do anything useful.
      if (state().kind === "not-loaded") start();
    },
    retry: () => {
      // Straight to `start`, which sets `loading` as its first act. Passing through `not-loaded` on
      // the way would publish a frame saying "nothing has been requested" to every observer, and an
      // observer whose job is to start the import when it sees `not-loaded` — which is exactly what
      // the feature gate is — would then start a second import alongside this one.
      if (state().kind === "failed") start();
    },
  };
}

/** A sentence for a person, from whatever a rejected import threw. */
function describe(cause: unknown): string {
  if (cause instanceof Error && cause.message.length > 0) return cause.message;
  if (typeof cause === "string" && cause.length > 0) return cause;
  return "the browser did not say why";
}
