/**
 * How KUI prints a quantity.
 *
 * SPEC §6.6 is one sentence long — "numbers are formatted, not narrated" — and it decides more of
 * how a table reads than any colour does. Thousands separators everywhere, so `4212` and `42120`
 * are told apart by their shape rather than by counting digits; a signed figure where the sign is
 * the information; and an em dash, never a zero, where there is no value at all.
 *
 * This lives in the kernel beside `formatBytes` rather than in a feature, because the consumer
 * list, the broker list and the dashboard all print the same kinds of number and three copies of
 * `toLocaleString` is three chances for one screen to group its thousands differently from the one
 * beside it.
 *
 * ## The em dash is a constant and not a literal
 *
 * `MISSING` is exported so that a test can assert on it and so that nobody types a hyphen where the
 * design draws an em dash. "No value" and "a value of nothing" are different facts about a cluster
 * — a consumer group whose lag could not be computed is not a group that has caught up — and the
 * whole reason this string exists is to keep them apart on screen.
 */

/** What a cell holds when there is no value to hold. An em dash, U+2014, not a hyphen. */
export const MISSING = "—";

/**
 * A whole number with thousands separators: `4212` becomes `4,212`.
 *
 * The locale is pinned to `en-US` rather than taken from the browser. KUI prints Kafka's own
 * numbers — offsets, partition ids, lag — beside identifiers that are always ASCII, and a locale
 * that groups with spaces or swaps the decimal separator makes an offset ambiguous when it is
 * pasted into a shell. The prices of that choice is that a French operator sees `4,212`; the price
 * of the other is a support ticket about an offset that would not seek.
 */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) return MISSING;
  return value.toLocaleString("en-US", { maximumFractionDigits: 0 });
}

/**
 * A rate, to one decimal place, with its sign kept.
 *
 * A negative rate is committed offsets moving *backwards*, which is what somebody else's offset
 * reset looks like from this screen. It is shown as it is rather than clamped to zero, because
 * noticing it is most of the value of printing it at all.
 */
export function formatRate(value: number): string {
  if (!Number.isFinite(value)) return MISSING;
  const rounded = Math.abs(value) < 10 ? Math.round(value * 10) / 10 : Math.round(value);
  return rounded.toLocaleString("en-US", { maximumFractionDigits: 1 });
}

/**
 * A change, with the sign always printed — `+4,212` and `-4,212`.
 *
 * "This rewinds 4 212 records" and "this skips 4 212 records" are the two things an operator is
 * deciding between in the offset-reset preview, and the sign is the entire difference between
 * them. A `+` that is dropped because the number is positive makes the two look the same.
 */
export function formatDelta(value: number): string {
  if (!Number.isFinite(value)) return MISSING;
  if (value === 0) return "0";
  return `${value > 0 ? "+" : "-"}${formatCount(Math.abs(value))}`;
}

/**
 * A fraction of a whole, guarded against the denominator that is not there.
 *
 * `0 / 0` is `NaN`, and `NaN` fails every comparison, so it slips through `Math.min`/`Math.max`
 * unchanged and reaches the stylesheet as a width. A zero denominator therefore yields zero here,
 * which is the reading that cannot mislead: an empty bar for an unknown share, never a full one.
 */
export function share(value: number, of: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(of) || of <= 0) return 0;
  return Math.min(1, Math.max(0, value / of));
}
