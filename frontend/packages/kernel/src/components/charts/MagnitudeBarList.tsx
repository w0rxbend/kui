/**
 * The horizontal bar list — "Top consumer lag" on the dashboard (SPEC §4.19).
 *
 * ## What the bar is for, and what it is not for
 *
 * The bar says *relative size* and nothing else. The figure is always printed beside the label,
 * because a reader who needs the number must not have to hover anything to get it. That makes the
 * bar strictly redundant, which is why it carries `aria-hidden`: a screen reader reading both
 * would announce the same quantity twice, once as a number and once as "83 per cent".
 *
 * ## The defect this component exists to prevent
 *
 * A zero must never draw a full-width track. The failure is arithmetic — `value / max` with a max
 * of zero is `NaN`, or `Infinity` for a positive value, and a browser handed `width: NaN%` does
 * not throw; it drops the declaration and the bar keeps whatever width the track gave it. An
 * empty panel then renders as a row of completely full bars, which reads as the worst possible
 * news. `fraction()` in `format.ts` guards the denominator and this component says in words that
 * there is nothing to compare.
 *
 * Three pictures that must stay three pictures:
 *
 * | The data                  | The bar                    | The figure |
 * | ------------------------- | -------------------------- | ---------- |
 * | a value, however small    | at least a 3px stub        | the number |
 * | a genuine zero            | the same 3px stub          | `0`        |
 * | a value we do not know    | **no fill at all**         | `—`        |
 *
 * The first two share a drawing on purpose: the design draws the zero entry with a visible stub
 * (`payments-processor 0` in `01-dashboard.png`, sampled #94DC6C at (829,719)) and the printed
 * figure is the authority for which of the two it is. The third differs from both, because
 * "nothing" and "we did not manage to ask" are the distinction the whole panel turns on.
 */
import { For, Show, type Component } from "solid-js";
import { ABSENT, formatCount, fraction, type MaybeNumber } from "./format.js";
import { toneColor, type ChartTone } from "./tone.js";

export interface MagnitudeEntry {
  readonly label: string;
  /** `undefined` draws no bar and prints the em dash. */
  readonly value: MaybeNumber;
  /** Overrides the printed figure — `86.4 MB/s` rather than a bare count. */
  readonly valueText?: string;
  /** Defaults to the neutral fill the design uses for an ordinary entry. */
  readonly tone?: ChartTone;
}

export interface MagnitudeBarListProps {
  readonly entries: readonly MagnitudeEntry[];
  /**
   * The value that means "full". Defaults to the largest known entry, which is what the design
   * does; supply a ceiling when the bars have to be comparable with another panel's.
   */
  readonly max?: number;
  /** Shown when there is nothing to draw. Plain, and not a joke: SPEC §6 rule 4. */
  readonly emptyMessage?: string;
}

/** Below this, a bar would be invisible and "small" would render as "none". */
const MIN_STUB_PX = 3;

export const MagnitudeBarList: Component<MagnitudeBarListProps> = props => {
  const known = (): number[] =>
    props.entries.map(e => e.value).filter((v): v is number => v !== undefined && Number.isFinite(v));

  const max = (): number | undefined => {
    if (props.max !== undefined) return props.max;
    const values = known();
    return values.length === 0 ? undefined : Math.max(...values);
  };

  /** True when no bar can carry any meaning — every value zero, unknown, or the list empty. */
  const nothingToCompare = (): boolean => {
    const m = max();
    return m === undefined || m <= 0;
  };

  return (
    <div class="kui-magnitude-list">
      <Show
        when={props.entries.length > 0}
        fallback={<p class="kui-magnitude-list__empty">{props.emptyMessage ?? "Nothing is behind."}</p>}
      >
        <ul class="kui-magnitude-list__items">
          <For each={props.entries}>
            {entry => {
              const isKnown = (): boolean => entry.value !== undefined && Number.isFinite(entry.value);
              const width = (): string => {
                if (!isKnown() || nothingToCompare()) return "0";
                // A zero draws nothing at all. The stub below exists to keep a *small* value
                // visible, and applying it to zero makes "this group has no lag" look exactly like
                // "this group has a little lag" — the two readings a lag panel exists to separate.
                if (entry.value === 0) return "0";
                const pct = fraction(entry.value, max()) * 100;
                // `max()` of the computed share and the stub keeps a small value visible without
                // ever letting the stub win against a share that is genuinely larger.
                return `max(${MIN_STUB_PX}px, ${pct}%)`;
              };
              return (
                <li class="kui-magnitude-list__entry">
                  <span class="kui-magnitude-list__label">{entry.label}</span>
                  <span class={["kui-magnitude-list__value", { "kui-magnitude-list__value--absent": !isKnown() }]}>
                    {isKnown() ? (entry.valueText ?? formatCount(entry.value)) : ABSENT}
                  </span>
                  {/* An unknown value draws no bar *at all* — not an empty one.

                      The fill was already omitted here, but the track was still drawn, so a row
                      whose value could not be read looked exactly like a row whose value is zero:
                      two identical grey tracks, one meaning "this group is not behind" and the
                      other meaning "we could not find out". Those are the two readings a lag panel
                      exists to separate, and SPEC §4.19 asks for them to stay separate pictures —
                      "an empty track and a zero-length fill are different pictures". The em dash
                      beside it is now the only mark on the row, which is what makes it read as an
                      absence rather than a measurement.

                      aria-hidden: the number above says exactly this, in words. */}
                  <Show when={isKnown()}>
                    <span class="kui-magnitude-list__track" aria-hidden="true">
                      <span
                        class="kui-magnitude-list__fill"
                        style={{ width: width(), background: toneColor(entry.tone ?? "neutral") }}
                      />
                    </span>
                  </Show>
                </li>
              );
            }}
          </For>
        </ul>
        {/* When every bar is zero the bars say nothing, so the panel says it instead — otherwise
            a row of empty tracks reads as "still loading". */}
        <Show when={nothingToCompare() && props.entries.length > 0}>
          <p class="kui-magnitude-list__empty" role="status">
            {props.emptyMessage ?? "Nothing is behind."}
          </p>
        </Show>
      </Show>
    </div>
  );
};
