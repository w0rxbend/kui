/**
 * The key beside a chart — and, where the design puts a number in it, the readout.
 *
 * SPEC §4.22 makes the point worth keeping: the latency card's legend reads
 * `● produce 14ms  ● fetch 9ms`, so it is not merely a key saying which colour is which, it is
 * where the current value is printed. A reader who wants today's number does not have to hover
 * anything to get it, which is the whole reason the chart is allowed to have no y-axis.
 *
 * The swatch is `aria-hidden`: it repeats the label sitting next to it, and a screen reader that
 * announces both says the same thing twice. The label is the accessible content.
 */
import { For, Show, type Component } from "solid-js";
import { toneColor, type ChartTone } from "./tone.js";

export interface LegendItem {
  readonly label: string;
  readonly tone: ChartTone;
  /** Already formatted — the legend prints what it is given, it does not decide units. */
  readonly value?: string;
}

export interface ChartLegendProps {
  readonly items: readonly LegendItem[];
  /** `dot` for charts, `block` for the donut's larger swatch with a right-aligned count. */
  readonly variant?: "dot" | "block";
}

export const ChartLegend: Component<ChartLegendProps> = props => (
  <ul class={["kui-chart-legend", `kui-chart-legend--${props.variant ?? "dot"}`]}>
    <For each={props.items}>
      {item => (
        <li class="kui-chart-legend__item">
          <span class="kui-chart-legend__swatch" aria-hidden="true" style={{ background: toneColor(item.tone) }} />
          {/* The label truncates when the card is narrow, so the full string is also the title:
              the DOM keeps it for a screen reader either way, and this keeps it for everyone else. */}
          <span class="kui-chart-legend__label" title={item.label}>
            {item.label}
          </span>
          <Show when={item.value !== undefined}>
            <span class="kui-chart-legend__value">{item.value}</span>
          </Show>
        </li>
      )}
    </For>
  </ul>
);
