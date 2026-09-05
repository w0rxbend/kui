/**
 * A figure with a proportional bar beside it.
 *
 * ## What it is for
 *
 * Almost every list in KUI is a list of quantities: topic sizes, partition counts, message rates,
 * consumer lag, connector task counts. Digits are slow to compare — the eye has to parse "112.9 GB"
 * and "48.2 GB" before it can say which is larger — and a column of them gives up no shape at all.
 * A bar drawn to one scale down the column answers "which is the big one" before a digit is read.
 *
 * The figure is always shown as well. The bar is deliberately redundant: it says *relative* size
 * and nothing else, and a reader who wants the actual number must not have to hover anything.
 *
 * ## What it does not do
 *
 * It does not decide the scale. `fraction` is the caller's, because only the caller knows what the
 * bar is relative *to* — the largest row on this page, the cluster total, a configured quota — and
 * guessing here would silently make two tables incomparable.
 *
 * The track carries `aria-hidden`: it encodes exactly the number printed next to it, and a screen
 * reader that read both would say the same quantity twice.
 */
import type { JSX } from "@solidjs/web";
import { Show } from "solid-js";

export interface MagnitudeBarProps {
  /** The figure, already formatted. Formatting bytes and rates is a product decision, not a style. */
  readonly value: string;
  /** How much of the track to fill, 0…1. Out-of-range and `NaN` are clamped rather than rejected. */
  readonly fraction: number;
  /** A name above the bar, for the stacked "top five" form. Omitted in a table cell, where the row
   *  already says what the bar belongs to. */
  readonly label?: string | undefined;
  /** Figure and bar on one line, for a table cell. */
  readonly inline?: boolean | undefined;
  /** Fills with the second accent, for when two of these sit side by side. */
  readonly accent?: boolean | undefined;
  readonly class?: string | undefined;
  readonly "data-testid"?: string | undefined;
}

/**
 * A fraction as a CSS width.
 *
 * Clamped, so a caller whose denominator was stale or zero gets a full or an empty bar rather than
 * one that paints outside its own track. `NaN` — which is what `0 / 0` produces, and which fails
 * every comparison, so `Math.min`/`Math.max` pass it straight through — is treated as empty
 * instead of reaching the stylesheet.
 *
 * One decimal place: a hundredth of a percent is well under a pixel on any bar this draws, and
 * rounding to whole percents makes the smallest rows in a list all render as nothing.
 */
export function percentage(fraction: number): string {
  const clamped = Number.isNaN(fraction) ? 0 : Math.min(1, Math.max(0, fraction));
  return `${Math.round(clamped * 1000) / 10}%`;
}

export function MagnitudeBar(props: MagnitudeBarProps): JSX.Element {
  return (
    <div
      class={[
        "kui-magnitude",
        {
          "kui-magnitude--inline": props.inline === true,
          "kui-magnitude--accent": props.accent === true,
        },
        props.class,
      ]}
      data-testid={props["data-testid"]}
    >
      <Show
        when={props.label}
        fallback={<span class="kui-magnitude__value">{props.value}</span>}
      >
        {(label) => (
          <div class="kui-magnitude__row">
            <span class="kui-magnitude__label">{label()}</span>
            <span class="kui-magnitude__value">{props.value}</span>
          </div>
        )}
      </Show>
      <div class="kui-magnitude__track" aria-hidden="true">
        <div class="kui-magnitude__fill" style={{ width: percentage(props.fraction) }} />
      </div>
    </div>
  );
}
