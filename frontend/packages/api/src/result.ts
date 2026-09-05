import type { ApiError } from "./errors.js";

/**
 * The answer to one API call: a success carrying a value, or a failure carrying data.
 *
 * **Nothing in this package ever rejects a promise or throws.** That is not a stylistic preference,
 * it is the fix for a defect this product shipped. In the previous implementation a failure
 * travelled as an error through the reactive graph, where it reached the unhandled-error handler and
 * took the subscription down with it: a page that was rendering a list stopped rendering anything at
 * all, and the user saw a blank screen instead of "the cluster is unreachable" (ADR-011 §3.6). In
 * SolidJS the same shape of accident exists — an error thrown inside a computation propagates to the
 * nearest `<Errored>` boundary and unmounts everything below it — so a failure has to be an ordinary
 * value that a signal can hold and a component can draw.
 *
 * Hence: every call answers with `ApiResult`, and the caller branches on `ok`. TypeScript narrows
 * the union on that field, so reading `.value` on a failure does not compile.
 *
 * @example
 * ```ts
 * const answer = await client.get("/api/v1/clusters", {});
 * if (answer.ok) {
 *   setClusters(answer.value.clusters);
 * } else {
 *   setFailure(answer.error); // a value, drawn by the page
 * }
 * ```
 */
export type ApiResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: ApiError };

/** The success case. */
export function ok<T>(value: T): ApiResult<T> {
  return { ok: true, value };
}

/** The failure case. */
export function err<T = never>(error: ApiError): ApiResult<T> {
  return { ok: false, error };
}

/**
 * Applies a function to the value of a success and leaves a failure alone.
 *
 * Present so that a caller mapping a wire shape onto a display shape does not have to write the
 * four-line `if (answer.ok)` dance and, in writing it, accidentally lose the error.
 */
export function mapResult<A, B>(result: ApiResult<A>, transform: (value: A) => B): ApiResult<B> {
  return result.ok ? ok(transform(result.value)) : result;
}

/** The value, or a fallback. For the places that genuinely have a sensible empty answer. */
export function valueOr<T>(result: ApiResult<T>, fallback: T): T {
  return result.ok ? result.value : fallback;
}
