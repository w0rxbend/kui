/**
 * The **Latency · p99** card — `SCREENS-V4.md` §4.1 row 3, and the second chart drawn from a broker
 * metric rather than from something this browser happens to hold.
 *
 * ## What replaced the sentence that used to be here
 *
 * This card carried a `NotMeasured` line saying *"KUI does not record request latency"*, which was
 * true while nothing scraped `kafka.network:type=RequestMetrics`. The family publishes
 * `50thPercentile`, `99thPercentile`, `Mean`, `Max` and `Count` for `Produce` and `FetchConsumer`,
 * so the p99 line is ordinary work and the endpoint answers it. The sentence has not disappeared: a
 * deployment that configures no exporter still gets it, and that is the common case rather than the
 * failure case.
 *
 * ## Why there is no range selector on this card
 *
 * There is one on the Throughput card and this reads the same `?range=` out of the address. Two
 * selectors would be two windows on one screen, and the first thing a reader does with a latency
 * spike is look at the throughput chart above it for the same hour. §4.1 draws this card's own
 * ticks as `-60 min / -30 min / now`; the window that produces them is the tab's, not the card's.
 *
 * ## The rule this card keeps, which is the throughput card's rule
 *
 * A step nothing sampled is a gap. `LineChart` breaks its line at a `null` and prints `—` for it in
 * the hidden data table, where a measured value prints its figure — and a fold that turned a null
 * into a zero would draw a broker that stopped answering as a broker answering instantly, which is
 * the most reassuring possible rendering of "we were not looking".
 */

import { Show, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";

import { Card, ChartLegend, LineChart, type Fetched, type LegendItem, type Series } from "@kui/kernel";

import { MetricAbsence } from "./NotMeasured.jsx";
import {
  formatMillis,
  latencyChart,
  type LatencyChart,
  type LatencySeries,
  type MetricsWindow,
} from "./metrics.js";

/** What this card calls the thing it is not measuring, in both absence sentences. */
export const LATENCY_NOUN = "this cluster's request latency";

/** The window is real and nothing in it was sampled. Drawn *with* the axis: the window exists. */
export const NO_LATENCY_SENTENCE =
  "No request latency has been sampled in this window yet, so every step in it is blank rather " +
  "than zero.";

/** The two series, in the design's order and its inks (§1.6: series colour carries no meaning). */
const PRODUCE_TONE = "series-1" as const;
const FETCH_TONE = "series-3" as const;

export interface LatencyCardProps {
  readonly state: Fetched<LatencySeries>;
  readonly range: MetricsWindow;
}

export function LatencyCard(props: LatencyCardProps): JSX.Element {
  /* A memo, not a bare accessor: the fold labels every bucket through `Intl`, the card reads it
     from the legend, the caption and the plot, and 288 buckets recomputed three times is the whole
     cost of this card paid three times over. */
  const chart = createMemo<LatencyChart | undefined>(() => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale"
      ? latencyChart(state.value, props.range)
      : undefined;
  });

  return (
    <Card
      title="Latency · p99"
      icon="chart-line"
      testId="panel-latency"
      state={props.state.kind === "failed" ? "unavailable" : "ready"}
      message={props.state.kind === "failed" ? props.state.message : undefined}
      code={props.state.kind === "failed" ? props.state.code : undefined}
      headerEnd={
        /* The legend is also the readout: §3.1 puts the current value in the chip, which is what
           lets this plot have no y-axis labels at all. Drawn only when there is a chart — a key
           beside a sentence names inks the reader cannot see anywhere. */
        <Show when={chart()}>{(built) => <ChartLegend items={legendOf(built())} />}</Show>
      }
      caption={captionOf(props.state, chart())}
    >
      <Show
        when={chart()}
        fallback={
          <MetricAbsence state={props.state} noun={LATENCY_NOUN} testId="latency-not-measured" />
        }
      >
        {(built) => (
          <LineChart
            label={`Produce and fetch p99 latency over the last ${props.range}`}
            categories={built().categories}
            series={seriesOf(built())}
            ticks={built().ticks}
            format={formatMillis}
            emptyMessage={NO_LATENCY_SENTENCE}
            height={160}
          />
        )}
      </Show>
    </Card>
  );
}

/**
 * The chips, and the reason a chip can carry no figure.
 *
 * A window in which nothing was measured has no current latency, and the chip prints its label
 * alone rather than an em dash: a dash in a chip reads as a rendering fault, and the sentence in
 * the plot has already said what is missing. A measured zero would still be printed — it is a fact
 * about a broker answering inside its own clock resolution, and it is exactly the reading a dash
 * would destroy.
 */
function legendOf(chart: LatencyChart): readonly LegendItem[] {
  const latest = chart.latest;
  const chip = (label: string, tone: LegendItem["tone"], value: number | null): LegendItem =>
    value === null ? { label, tone } : { label, tone, value: formatMillis(value) };
  return [
    chip("produce", PRODUCE_TONE, latest?.produce ?? null),
    chip("fetch", FETCH_TONE, latest?.fetch ?? null),
  ];
}

/**
 * The sentence under the card.
 *
 * `stale` gets its reason here rather than through `Card`'s stale badge, for the reason the
 * throughput card gives: the badge takes an `asOf` date and this state carries none, so drawing it
 * would mean inventing a timestamp. Last-known-good data drawn as though it were current, with no
 * badge and no sentence, is the defect the brokers screen was repaired for.
 */
function captionOf(state: Fetched<LatencySeries>, chart: LatencyChart | undefined): string | undefined {
  const gaps = chart?.caption;
  if (state.kind !== "stale") return gaps;
  const stale = `This is the last answer KUI received: ${state.reason}`;
  return gaps === undefined ? stale : `${stale} ${gaps}`;
}

/** The two plotted series. `null` points are carried through untouched — see the file header. */
function seriesOf(chart: LatencyChart): readonly Series[] {
  return [
    { label: "produce", tone: PRODUCE_TONE, points: chart.produce },
    { label: "fetch", tone: FETCH_TONE, points: chart.fetch },
  ];
}
