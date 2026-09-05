/**
 * What a screen is given: the data, and an honest account of its state.
 *
 * ## Why a union and not `{ data, loading, error }`
 *
 * Three independent fields describe eight combinations, five of which are nonsense — loading *and*
 * errored, data *and* loading with no indication which is authoritative — and the nonsense is
 * precisely what renders when a request fails halfway. A union has exactly the states that exist.
 *
 * ## Why six cases and not three
 *
 * The three extra ones are the whole point, and each is a different sentence to the operator:
 *
 * - **`stale`** — real data, out of date, shown with the reason. Hiding it leaves a blank screen at
 *   the moment something is wrong, which is when the last known figures are most wanted.
 * - **`forbidden`** — this principal may not see it. A retry button here is a button that cannot
 *   work, and offering one teaches the operator that retrying does nothing.
 * - **`not-configured`** — this deployment has no such thing. Nothing is broken; the answer is to
 *   configure something, not to wait.
 *
 * Collapsing any of them into `failed` produces a screen that says "try again" when trying again is
 * either pointless or the wrong action entirely.
 *
 * ## Why this lives in the kernel
 *
 * It was written inside `feature-clusters`, and three more features were about to copy it. A
 * feature may not import another feature — that is the workspace edge the microfrontend split
 * exists to keep — so the choice was one shared definition in the kernel or four divergent ones.
 * Four copies of a six-case union is four opportunities to quietly drop `not-configured`.
 */
import { userMessage, type ApiError, type Section } from "@kui/api";

export type Fetched<T> =
  | { readonly kind: "loading" }
  | { readonly kind: "ready"; readonly value: T }
  /** The data is real and out of date. Shown, with the reason — never hidden. */
  | { readonly kind: "stale"; readonly value: T; readonly reason: string }
  | { readonly kind: "failed"; readonly message: string; readonly code: string }
  /** The principal may not see this. Distinct from failed: retrying will never help. */
  | { readonly kind: "forbidden" }
  /** This deployment has not configured the thing. Also distinct: nothing is broken. */
  | { readonly kind: "not-configured" };

/**
 * Turns a decoded section into a screen's state.
 *
 * Worth testing directly: five statuses in, six states out, and the two that look alike —
 * `forbidden` and `failed` — must never be collapsed.
 */
export function fromSection<A, B>(section: Section<A>, map: (data: A) => B): Fetched<B> {
  switch (section.status) {
    case "ok":
      return { kind: "ready", value: map(section.data) };
    case "stale":
      return {
        kind: "stale",
        value: map(section.data),
        reason: section.reason.message ?? "This is the last answer KUI received.",
      };
    case "forbidden":
      return { kind: "forbidden" };
    case "not_configured":
      return { kind: "not-configured" };
    case "unavailable":
    case "unreadable":
      return {
        kind: "failed",
        message: section.reason.message ?? "The service did not answer.",
        code: section.reason.code,
      };
  }
}

/**
 * A transport or envelope failure, as a screen's state.
 *
 * `userMessage(error)` rather than `error.message`: only the `envelope` case has a message. An
 * unreachable gateway, a timeout and a body that would not decode each need a sentence of their
 * own, and reaching for `.message` on those is how a screen renders the word "undefined" at exactly
 * the moment something is wrong.
 */
export function apiFailure(error: ApiError): Fetched<never> {
  return {
    kind: "failed",
    message: userMessage(error),
    // The code is what somebody quotes when they ask for help. Only an envelope carries one; for
    // the other three kinds the kind itself is more use than an empty string.
    code: error.kind === "envelope" ? error.code : error.kind.toUpperCase(),
  };
}

/**
 * The data, or a fallback.
 *
 * `stale` yields its value deliberately — see the header.
 */
export function valueOf<T>(state: Fetched<T>, fallback: T): T {
  return state.kind === "ready" || state.kind === "stale" ? state.value : fallback;
}

/**
 * A number the server may not have been able to read, as `null` rather than `0`.
 *
 * The single most important line in any mapping layer. `0` is a claim — no partitions, no bytes, no
 * lag — and it is the most reassuring possible rendering of "we do not know", which makes it the
 * most expensive default in this product.
 */
export function figure(value: number | null | undefined): number | null {
  return typeof value === "number" ? value : null;
}
