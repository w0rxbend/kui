/**
 * Parts of a capacity, sized by their share, over a remainder track — the **Storage by broker**
 * rows of SCREENS-V4.md §3.6.
 *
 * ## How this differs from `SegmentBar`, which is also a row of segments
 *
 * `SegmentBar`'s own header states its rule: its segments are **equal by design**, because a
 * connector with three tasks has three equal tasks and sizing them would invent a quantity that
 * does not exist. It answers "how many of these, and what state is each in".
 *
 * This one is the case that header excludes. Its segments are sized by share, because
 * `analytics.*` really does occupy more of broker-1's disk than `orders.*` does, and the point of
 * the picture is that ratio. It answers "what is this capacity made of".
 *
 * The two are not interchangeable in either direction: drawing tasks proportionally would claim a
 * size Kafka never reported, and drawing disk usage in equal blocks would hide the one prefix
 * that is eating the broker.
 *
 * ## The remainder, and the capacity nobody knows
 *
 * The track behind the segments is not another category — it is the part of the capacity that is
 * free. So the denominator is `capacity`, not the sum of the segments, and `capacity` is a
 * **required** `MaybeNumber`: a caller that does not know how big the disk is has to say so in the
 * type rather than pass a plausible number.
 *
 * An unknown capacity draws the neutral track and no fill at all. It is the rule `diskPercentOf`
 * already applies and the one `ProgressBar` states first: a broker whose directories reported no
 * capacity has no denominator, and a bar with no denominator that draws its segments anyway is
 * showing a ratio it computed against nothing.
 */
import { For, Show, createUniqueId, type Component } from "solid-js";
import { ChartDataTable } from "./ChartDataTable.jsx";
import { ChartLegend, type LegendItem } from "./ChartLegend.jsx";
import { DEFAULT_THRESHOLDS, formatCount, fraction, levelFor, type MaybeNumber, type Thresholds } from "./format.js";
import { toneColor, type ChartTone } from "./tone.js";

export interface StackedBarSegment {
  readonly label: string;
  readonly value: number;
  readonly tone: ChartTone;
}

export interface StackedBarProps {
  readonly segments: readonly StackedBarSegment[];
  /**
   * The denominator: the whole the segments are parts of. `undefined` means it is not known, and
   * is drawn as the bare track — see the header.
   */
  readonly capacity: MaybeNumber;
  /** Names the bar for a screen reader and captions its hidden table: `broker-1 disk usage`. */
  readonly label: string;
  /** Formats a segment's value for the hover title, the legend and the table. */
  readonly format?: ((value: number) => string) | undefined;
  /** The figure printed to the right of the track — `347 GB`. Inked by how full the bar is. */
  readonly valueText?: string | undefined;
  readonly thresholds?: Thresholds | undefined;
  readonly height?: number | undefined;
  /**
   * Off by default, and that is the legend contract: the design draws **one** legend under a
   * stack of broker rows, because four rows each printing `analytics. inventory. orders. other`
   * is the same key said four times. A single bar standing alone passes `legend`; a caller
   * drawing rows renders one `ChartLegend` itself, from the same segment keys.
   */
  readonly legend?: boolean | undefined;
}

export const StackedBar: Component<StackedBarProps> = props => {
  const tableId = createUniqueId();
  const format = (value: number): string => props.format?.(value) ?? formatCount(value);
  const known = (): boolean => props.capacity !== undefined && Number.isFinite(props.capacity) && props.capacity > 0;

  /**
   * Each segment's width in per cent, clamped **cumulatively**.
   *
   * Clamping each segment on its own would let four segments of 40% draw a 160% bar that overflows
   * its track; clamping the running total instead means the overflow is absorbed by the segments
   * that would have fallen off the end, and the bar still ends where the capacity does. That case
   * is not hypothetical — a log directory sampled a moment after a compaction can report more used
   * than the capacity read a moment before.
   */
  const widths = (): readonly number[] => {
    if (!known()) return props.segments.map(() => 0);
    let used = 0;
    return props.segments.map(segment => {
      const share = fraction(segment.value, props.capacity) * 100;
      const width = Math.max(0, Math.min(share, 100 - used));
      used += width;
      return width;
    });
  };

  /** How full the bar is overall, which is what inks the figure beside it. */
  const filled = (): number => widths().reduce((sum, w) => sum + w, 0);
  const level = (): string =>
    known() ? levelFor(filled(), props.thresholds ?? DEFAULT_THRESHOLDS) : "unknown";

  const legendItems = (): LegendItem[] =>
    props.segments.map(s => ({ label: s.label, tone: s.tone, value: format(s.value) }));

  return (
    <div class="kui-stacked">
      <div class="kui-stacked__row">
        {/* The track is the remainder. It is always drawn — in the unknown case it is the only
            thing drawn, which is what stops "we could not measure this disk" from looking like
            "this disk is empty". */}
        <div
          class="kui-stacked__track"
          style={{ height: `${props.height ?? 8}px` }}
          aria-hidden="true"
        >
          <Show when={known()}>
            <For each={props.segments}>
              {(segment, index) => (
                <Show when={(widths()[index()] ?? 0) > 0}>
                  <span
                    class="kui-stacked__segment"
                    style={{ width: `${widths()[index()]}%`, "background-color": toneColor(segment.tone) }}
                    // Hovering a segment names it, which is how the operator finds *which* prefix
                    // is filling the broker. The same string is in the table below for everyone
                    // who is not hovering anything.
                    title={`${segment.label} · ${format(segment.value)}`}
                  />
                </Show>
              )}
            </For>
          </Show>
        </div>

        <Show when={props.valueText !== undefined}>
          <span class={["kui-stacked__value", `kui-stacked__value--${level()}`]}>{props.valueText}</span>
        </Show>
      </div>

      <Show when={props.legend}>
        <ChartLegend items={legendItems()} />
      </Show>

      {/* The bar itself is `aria-hidden`; this is its accessible rendering. One row per segment,
          which is also what makes "three segments" a fact a test can assert. */}
      <ChartDataTable
        id={tableId}
        caption={props.label}
        categories={props.segments.map(s => s.label)}
        series={[{ label: props.label, tone: "neutral", points: props.segments.map(s => s.value) }]}
        format={format}
      />
    </div>
  );
};
