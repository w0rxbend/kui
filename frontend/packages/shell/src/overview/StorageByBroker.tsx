/**
 * **Storage by broker** — one proportional bar per broker, attributed to topic prefixes
 * (SCREENS-V4.md §3.6 and §4.3).
 *
 * ## What it draws, and what the picture claims
 *
 * Each row is one broker's disk: the segments are what Kafka's own partitions occupy on it, sized
 * by share, over a remainder track that is everything else plus the free space. The denominator is
 * the disk, not the sum of the segments, which is `StackedBar`'s rule and the reason the remainder
 * is visible at all — a bar normalised to its own segments would show the same picture for a disk
 * at 12% and a disk at 92%.
 *
 * The used figure is on the row's head line rather than beside the bar. `StackedBar`'s `valueText`
 * is inked by how full *its segments* make the bar, and the used figure is `total - usable`, which
 * counts everything on the disk including the parts that are not Kafka's. Putting the second
 * quantity in the slot inked by the first would be a number and a colour disagreeing about the same
 * disk, so each figure is drawn beside the thing it is a measure of.
 *
 * ## One legend, below the rows
 *
 * `StackedBar` defaults `legend` off and its header says why: four rows each printing the same four
 * keys is the key said four times. So the rows pass no legend and this component draws one, from
 * the fold's own group list — which is also what guarantees that a colour means the same prefix on
 * every row, because the order is the fold's and not each row's.
 *
 * ## Why the markup borrows `.kui-broker-health`
 *
 * These rows are the same object as the broker-health rows: a name, a detail, and a bar under both.
 * The stylesheet that would hold a `.kui-storage-row` belongs to another packet in this wave, and
 * inventing class names nothing styles would ship a card that lays itself out by accident. Reusing
 * the rule that already describes this shape is the honest version of the same arrangement.
 */

import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

import {
  ChartLegend,
  StackedBar,
  formatBytes,
  formatCount,
  formatPercent,
  type ChartTone,
  type LegendItem,
} from "@kui/kernel";

import { INTERNAL_GROUP, OTHER_GROUP } from "../nav/prefixes.js";
import { diskShare, type StorageBreakdown, type StorageRow } from "./model.js";

/**
 * The ink each prefix row is painted in.
 *
 * Five series colours for the five prefix groups the fold may return (`STORAGE_PREFIX_GROUPS`), the
 * sixth reserved for `internal`, and `other` painted neutral. `internal` and `other` are given
 * fixed inks rather than the next one in the ramp because they are the two rows whose position
 * changes — a cluster with no internal topics has no `internal` row, and one whose prefixes all fit
 * has no `other` row — and a key whose colours shift when a row disappears is a key that has to be
 * re-read after every refresh.
 */
const SERIES: readonly ChartTone[] = ["series-1", "series-2", "series-3", "series-4", "series-5"];

export function segmentTone(prefix: string, index: number): ChartTone {
  if (prefix === OTHER_GROUP) return "neutral";
  if (prefix === INTERNAL_GROUP) return "series-6";
  return SERIES[index] ?? "neutral";
}

export interface StorageByBrokerProps {
  readonly breakdown: StorageBreakdown;
}

export function StorageByBroker(props: StorageByBrokerProps): JSX.Element {
  const tones = (): ReadonlyMap<string, ChartTone> =>
    new Map(props.breakdown.groups.map((group, index) => [group.prefix, segmentTone(group.prefix, index)]));

  /** Each group's bytes across every broker, which is what the legend prints beside its key. */
  const legend = (): readonly LegendItem[] =>
    props.breakdown.groups.map((group) => ({
      label: group.prefix,
      tone: tones().get(group.prefix) ?? "neutral",
      value: formatBytes(
        props.breakdown.rows.reduce(
          (total, row) => total + (row.segments.find((s) => s.prefix === group.prefix)?.bytes ?? 0),
          0,
        ),
      ),
    }));

  return (
    <Show
      when={props.breakdown.rows.length > 0}
      fallback={<p class="kui-overview__blank">No brokers answered.</p>}
    >
      <ul class="kui-broker-health" data-testid="storage-rows">
        <For each={props.breakdown.rows}>
          {(row) => <StorageBar row={row} tones={tones()} />}
        </For>
      </ul>
      {/* Only when there is something to key. A legend over three bare tracks names four prefixes
          the reader cannot see anywhere, which reads as a rendering fault rather than as an
          unmeasured disk. */}
      <Show when={props.breakdown.groups.length > 0}>
        <ChartLegend items={legend()} />
      </Show>
    </Show>
  );
}

interface StorageBarProps {
  readonly row: StorageRow;
  /** The legend's ink for each group, so a colour means the same prefix on every row. */
  readonly tones: ReadonlyMap<string, ChartTone>;
}

function StorageBar(props: StorageBarProps): JSX.Element {
  return (
    <li class="kui-broker-health__row">
      <p class="kui-broker-health__head">
        <span class="kui-broker-health__name">{props.row.name}</span>
        <span class="kui-broker-health__detail">{detailOf(props.row)}</span>
      </p>
      <StackedBar
        /* The bar itself is `aria-hidden` and this names its hidden table, so it has to say which
           broker *and* what the numbers are — "broker-1.kyiv" alone, three times over, tells a
           screen-reader user which row they are in and nothing about what it holds. */
        label={`${props.row.name} disk usage by topic prefix`}
        segments={props.row.segments.map((segment) => ({
          label: segment.prefix,
          value: segment.bytes,
          tone: props.tones.get(segment.prefix) ?? "neutral",
        }))}
        /* `undefined` rather than a fallback: a broker whose directories reported no size has no
           denominator, and `StackedBar` answers that with the bare track. A guessed capacity would
           draw a full-looking disk out of nothing. */
        capacity={props.row.capacityBytes}
        format={formatBytes}
        height={10}
      />
      <Show when={props.row.capacityBytes === undefined}>
        <p class="kui-broker-health__why">
          This broker's log directories do not report a disk size, so there is no capacity to divide
          up.
        </p>
      </Show>
    </li>
  );
}

/**
 * `347 GB of 1.0 TB · 83%`, or the sentence that says why there is no such line.
 *
 * The percentage is included because it is the figure an operator reads first and the one the two
 * byte counts make them compute. It is `diskShare` — literally the function the broker-health bar's
 * fill comes from, over the same skipped directories — so the number here and the bar on the
 * Overview tab cannot disagree. That sentence used to be written here and be false: this line did
 * its own division and answered a zero-byte disk with `0 B of 0 B` and no percentage, while the bar
 * refused with a reason. A quantity that cannot be computed is a sentence, never a pair of zeroes.
 */
function detailOf(row: StorageRow): string {
  if (row.capacityBytes === undefined || row.usedBytes === undefined) return "no disk size reported";
  const share = diskShare(row.usedBytes, row.capacityBytes);
  if (share.kind === "unknown") return share.why;
  return `${formatBytes(row.usedBytes)} of ${formatBytes(row.capacityBytes)} · ${formatPercent(share.value)}`;
}

/** How many topics the fold counted, for the card's caption. Absent when there are none. */
export function topicsCounted(breakdown: StorageBreakdown): string | undefined {
  const total = breakdown.groups.reduce((sum, group) => sum + group.count, 0);
  if (total === 0) return undefined;
  const noun = total === 1 ? "topic" : "topics";
  return `${formatCount(total)} ${noun} attributed, grouped by the prefix before the first dot.`;
}
