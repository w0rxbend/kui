/**
 * A number the dashboard might not have, and the four different reasons it might not have it.
 *
 * ## Why this type exists
 *
 * Every figure on the cluster overview is one of four things, and the whole point of this module is
 * that they are four things rather than two:
 *
 * - **value** — we asked, the cluster answered, this is the answer.
 * - **pending** — we asked and have not been answered yet. Draws a skeleton.
 * - **unknown** — we asked and the answer did not come, or the broker is too old to know. Draws an
 *   em dash.
 * - **notCollected** — *nobody asked, because KUI does not measure this.* Draws a sentence saying
 *   so, and never a chart.
 *
 * Collapsing the last two is the mistake this file exists to prevent. "Unknown" says the cluster
 * failed to tell us and invites a retry; "not collected" says the product has no such feature and a
 * retry will never help. A throughput panel that renders as "unavailable — retry" is telling an
 * operator that their metrics pipeline is broken, when the truth is that KUI has never had one.
 * That sends somebody to debug a system that is working perfectly, which is worse than saying
 * nothing at all.
 *
 * Collapsing `pending` into `unknown` is the other half of the same rule, and is the defect the
 * brief calls out directly: a value still in flight must not look like a value that is absent.
 */

/** Why a figure is not a number. Always a sentence, because it is shown to a person. */
export type MissingReason = string;

export type Reading<A> =
  | { readonly kind: "value"; readonly value: A }
  | { readonly kind: "pending" }
  | { readonly kind: "unknown"; readonly why: MissingReason }
  /**
   * KUI does not measure this. `why` names the thing that is not measured, in words an operator can
   * act on — normally by not going looking for a broken exporter.
   */
  | { readonly kind: "notCollected"; readonly why: MissingReason };

export const value = <A>(v: A): Reading<A> => ({ kind: "value", value: v });
export const pending = <A>(): Reading<A> => ({ kind: "pending" });
export const unknown = <A>(why: MissingReason): Reading<A> => ({ kind: "unknown", why });
export const notCollected = <A>(why: MissingReason): Reading<A> => ({ kind: "notCollected", why });

/** The value, or `undefined` for all three of the ways there might not be one. */
export function readingValue<A>(reading: Reading<A>): A | undefined {
  return reading.kind === "value" ? reading.value : undefined;
}

/**
 * Applies a function to a reading's value, keeping the reason when there is no value.
 *
 * The reason is carried through rather than replaced, so that a figure derived from a missing input
 * still says why *the input* was missing. A derived figure that reports its own vague "unavailable"
 * loses the only sentence that would have explained it.
 */
export function mapReading<A, B>(reading: Reading<A>, f: (a: A) => B): Reading<B> {
  return reading.kind === "value" ? { kind: "value", value: f(reading.value) } : reading;
}

/**
 * Combines two readings, and the precedence rule is the interesting part.
 *
 * `notCollected` wins over `pending`, which wins over `unknown`. Read from the bottom up:
 *
 * - if either half is something KUI never measures, the combination is never measurable, and
 *   waiting for it is pointless — say so immediately rather than spinning forever;
 * - otherwise, if either half is still in flight, the combination is still in flight, because it
 *   may yet turn out fine and calling it broken early is a false alarm;
 * - only when nothing is outstanding is a failure final.
 */
export function combineReadings<A, B, C>(a: Reading<A>, b: Reading<B>, f: (a: A, b: B) => C): Reading<C> {
  if (a.kind === "notCollected") return a;
  if (b.kind === "notCollected") return b;
  if (a.kind === "pending" || b.kind === "pending") return { kind: "pending" };
  if (a.kind === "unknown") return a;
  if (b.kind === "unknown") return b;
  return { kind: "value", value: f(a.value, b.value) };
}
