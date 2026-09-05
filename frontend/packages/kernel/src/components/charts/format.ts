/**
 * Number and percentage formatting for the chart family.
 *
 * SPEC §6 rule 6 says numbers are formatted, not narrated: thousands separators everywhere,
 * and a figure the operator has to compare down a column is rendered with tabular figures so
 * the digits line up. The formatting lives here rather than in each component because six
 * components printing the same quantity in five different shapes is how a dashboard stops
 * looking like one product.
 */

/**
 * `undefined` means "we do not know". It is deliberately not `0`: SPEC §4.0 and §4.6 both turn
 * on the difference between a cluster with no consumer lag (`0`, a fact) and a cluster whose lag
 * could not be computed (`—`, an admission). Every chart prop that can be unknown is typed
 * `number | undefined` so the type system forces each component to say which picture it draws.
 */
export type MaybeNumber = number | undefined;

/** The em dash reserved by SPEC §4.0 for "this value is genuinely absent". */
export const ABSENT = "—";

const grouped = new Intl.NumberFormat(undefined, { useGrouping: true });

/** `4212` -> `4,212`. An unknown value is the em dash, never a zero. */
export function formatCount(value: MaybeNumber): string {
  return value === undefined || !Number.isFinite(value) ? ABSENT : grouped.format(value);
}

/**
 * `61` -> `61%`, to the given number of decimals. Unknown is the em dash *without* a per cent
 * sign: `—%` reads as a formatting bug, and a reader who sees one stops trusting the others.
 */
export function formatPercent(value: MaybeNumber, decimals = 0): string {
  if (value === undefined || !Number.isFinite(value)) return ABSENT;
  return `${value.toFixed(decimals)}%`;
}

/**
 * The share of `value` in `max`, clamped to 0..1, with the denominator guarded.
 *
 * This function is the whole of SPEC §4.19's "a zero must never draw a full-width track". The
 * defect it prevents is arithmetic, not styling: `0 / 0` is `NaN` and `1 / 0` is `Infinity`, and
 * a browser handed `width: NaN%` or `width: Infinity%` does not throw — it drops the declaration
 * or clamps to 100%, so an empty panel renders as a row of completely full bars and looks like
 * the worst possible news. A non-positive or absent maximum yields 0 here, and the caller says
 * in words that there is nothing to compare.
 */
export function fraction(value: MaybeNumber, max: MaybeNumber): number {
  if (value === undefined || max === undefined) return 0;
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return 0;
  return Math.min(1, Math.max(0, value / max));
}

/** The three levels SPEC §4.18 allows. More steps read as a gradient, which is to say as nothing. */
export type ThresholdLevel = "normal" | "warning" | "critical";

/** The default thresholds SPEC §4.20 fixes for the product, in per cent. */
export const DEFAULT_THRESHOLDS = { warn: 75, critical: 90 } as const;

export interface Thresholds {
  readonly warn: number;
  readonly critical: number;
}

/** Where a percentage sits relative to its limits. Unknown is never "normal" — see the callers. */
export function levelFor(percent: number, thresholds: Thresholds = DEFAULT_THRESHOLDS): ThresholdLevel {
  if (percent >= thresholds.critical) return "critical";
  if (percent >= thresholds.warn) return "warning";
  return "normal";
}
