/**
 * The visually-hidden table of the numbers a chart draws.
 *
 * SPEC §7.9 requires one for every chart, and §4.21 calls it "the only honest way to make a chart
 * accessible". It is: a `<canvas>`-style picture described by a one-line `aria-label` tells a
 * screen-reader user that a chart exists and nothing about what it says, whereas a table of the
 * same numbers is navigable cell by cell with the reader's own table commands.
 *
 * It is hidden by clipping, not by `display: none`, because a display-none subtree is removed
 * from the accessibility tree — which would leave the chart with a hidden table that helps
 * nobody. The plot points at it with `aria-describedby`.
 */
import { For, type Component } from "solid-js";
import { ABSENT } from "./format.js";
import type { Series } from "./plot.js";

export interface ChartDataTableProps {
  readonly id: string;
  readonly caption: string;
  readonly categories: readonly string[];
  readonly series: readonly Series[];
  readonly format?: (value: number) => string;
}

export const ChartDataTable: Component<ChartDataTableProps> = props => {
  const format = (value: number | null | undefined): string =>
    value === null || value === undefined || !Number.isFinite(value)
      ? ABSENT
      : (props.format?.(value) ?? String(value));

  return (
    <table class="kui-visually-hidden" id={props.id}>
      <caption>{props.caption}</caption>
      <thead>
        <tr>
          <th scope="col">Time</th>
          <For each={props.series}>{s => <th scope="col">{s.label}</th>}</For>
        </tr>
      </thead>
      <tbody>
        <For each={props.categories}>
          {(category, index) => (
            <tr>
              <th scope="row">{category}</th>
              <For each={props.series}>{s => <td>{format(s.points[index()])}</td>}</For>
            </tr>
          )}
        </For>
      </tbody>
    </table>
  );
};
