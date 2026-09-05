/**
 * The tones a chart mark may be painted in, and the token each one resolves to.
 *
 * Every value here is a `var()` reference. Nothing in the chart family may name a hex: the
 * palette has two themes and four accent seeds, and a component that hard-codes `#8FD36A` is
 * correct in exactly one of the eight combinations (SPEC §1, and the "a value that cannot be
 * expressed as a token is a finding about the code" rule).
 */

/**
 * `series-1..5` are the neutral choice for "a different line, and nothing more". The status
 * tones are for marks that genuinely mean healthy / degraded / failed — SPEC §1.6 is explicit
 * that series colour carries no meaning in a throughput chart, so never reach for `warning`
 * merely because a series happens to be the fourth one.
 */
export type ChartTone =
  | "series-1"
  | "series-2"
  | "series-3"
  | "series-4"
  | "series-5"
  | "success"
  | "warning"
  | "danger"
  | "primary"
  | "accent"
  | "neutral";

const TOKENS: Readonly<Record<ChartTone, string>> = {
  "series-1": "var(--kui-color-series-1)",
  "series-2": "var(--kui-color-series-2)",
  "series-3": "var(--kui-color-series-3)",
  "series-4": "var(--kui-color-series-4)",
  "series-5": "var(--kui-color-series-5)",
  success: "var(--kui-color-success)",
  warning: "var(--kui-color-warning)",
  danger: "var(--kui-color-danger)",
  primary: "var(--kui-color-primary)",
  accent: "var(--kui-color-accent)",
  // Measured, not guessed: the resting fill of a magnitude bar in `01-dashboard.png` at
  // (870,653) is #A6ACB8, which is `--kui-color-text-muted` exactly.
  neutral: "var(--kui-color-text-muted)",
};

export function toneColor(tone: ChartTone): string {
  return TOKENS[tone];
}

/**
 * The translucent version used for the area under a line. Measured from the design: the fill
 * under the produce line samples #2E3642, and `--kui-color-series-1` (#A8C7FA) composited over
 * `--kui-color-surface-elevated` (#1B1F25) at 12% is #2C333F — so 12% it is.
 *
 * `color-mix` rather than an `opacity` on the shape, because opacity on the shape would also
 * fade the 2px line drawn on top of it.
 */
export function toneAreaFill(tone: ChartTone): string {
  return `color-mix(in srgb, ${toneColor(tone)} 12%, transparent)`;
}
