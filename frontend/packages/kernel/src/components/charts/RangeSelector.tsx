/**
 * The `24h | 7d | 30d` segmented control in a chart card's header.
 *
 * ## Why this is a group of real radios
 *
 * It is a single choice from a small fixed set, which is what a radio group is. Building it from
 * `<button>`s would mean re-implementing arrow-key movement, the roving tab stop, the "one tab
 * stop for the whole group" behaviour and the announcement of "2 of 3" — and getting one of them
 * subtly wrong. The inputs are visually hidden but *present*, so every one of those behaviours is
 * the browser's, and the drawn segments are labels pointing at them.
 *
 * Visually hidden means clipped, not `display: none` and not `visibility: hidden`: both of those
 * remove the input from the accessibility tree and from the tab order, which would defeat the
 * entire arrangement.
 *
 * ## The half of it that is easy to get wrong
 *
 * SPEC §7.9 ends with the rule this project keeps re-learning: *a control must look like what it
 * does, not merely be correct in the accessibility tree*, and it names a segmented control with
 * no selected-segment fill as one of the three that shipped invisible. So the selected segment
 * carries a fill — `--kui-color-selected` (#3A4657, sampled from the design at (1000,312)) — and
 * the focus ring is drawn on the segment the hidden input belongs to, because a focus ring on a
 * clipped 1px input is a focus ring nobody sees.
 */
import { For, createUniqueId, type Component } from "solid-js";

export interface RangeOption {
  /** The value handed back to `onChange`. */
  readonly value: string;
  /** What the segment reads. Kept short — `24h`, not `Last 24 hours`. */
  readonly label: string;
  /**
   * Disabled ranges stay in the control (SPEC §4.24): a range the backend keeps no history for
   * is disabled *with a reason*, never omitted, because omitting it makes the retention limit
   * invisible and the operator wonders why the product only offers two.
   */
  readonly disabled?: boolean;
  /** The reason, shown as a tooltip and as the input's accessible description. */
  readonly disabledReason?: string;
}

export interface RangeSelectorProps {
  readonly options: readonly RangeOption[];
  readonly value: string;
  readonly onChange: (value: string) => void;
  /** Names the group for a screen reader: "Throughput range", not "range". */
  readonly label: string;
  readonly disabled?: boolean;
}

export const RangeSelector: Component<RangeSelectorProps> = props => {
  // One name per instance, or two range selectors on the same dashboard become one radio group
  // and selecting `7d` in the throughput card clears the selection in the latency card.
  const name = createUniqueId();

  return (
    <div class={["kui-range", { "kui-range--disabled": props.disabled === true }]} role="radiogroup" aria-label={props.label}>
      <For each={props.options}>
        {option => {
          const id = `${name}-${option.value}`;
          const isDisabled = (): boolean => props.disabled === true || option.disabled === true;
          return (
            <span
              class={["kui-range__segment", { "kui-range__segment--disabled": isDisabled() }]}
              title={option.disabled === true ? option.disabledReason : undefined}
            >
              <input
                class="kui-range__input"
                type="radio"
                id={id}
                name={name}
                value={option.value}
                checked={props.value === option.value}
                disabled={isDisabled()}
                aria-describedby={option.disabled === true && option.disabledReason ? `${id}-why` : undefined}
                onChange={() => props.onChange(option.value)}
              />
              <label class="kui-range__label" for={id}>
                {option.label}
              </label>
              {/* The reason is in the accessibility tree as well as in the tooltip: a tooltip is
                  a hover affordance, and a keyboard user never hovers anything. */}
              {option.disabled === true && option.disabledReason ? (
                <span class="kui-visually-hidden" id={`${id}-why`}>
                  {option.disabledReason}
                </span>
              ) : null}
            </span>
          );
        }}
      </For>
    </div>
  );
};
