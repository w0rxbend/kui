/**
 * The Throughput card — `SCREENS-V4.md` §4.1 row 2, and the first chart in this product drawn from
 * a broker metric.
 *
 * ## What replaced the sentence that used to be here
 *
 * Until this wave the card held a `NotMeasured` line and, above it, a comment explaining that there
 * was no range selector because "a control over data that does not exist is a control whose every
 * setting produces the same nothing". Both were true and both are gone together: the endpoint now
 * answers a series, so the control has three settings that produce three different windows. The
 * sentence has not disappeared, though — a deployment that configures no metrics source still gets
 * it, and that is the common case rather than the failure case.
 *
 * ## The rule this card exists to keep
 *
 * A bar of height zero and a bar that was never measured are the same picture unless somebody
 * decides they must not be. Three things decide it here:
 *
 *  - the plot leaves an unmeasured bucket empty and prints `—` for it in its hidden data table,
 *    where a measured zero prints `0 B/s`;
 *  - the coverage strip under the axis paints the runs KUI did not sample, so the gaps are visible
 *    as gaps rather than as an absence of ink;
 *  - the caption counts them in words.
 *
 * The first is `BarChart`'s and the other two are this file's. None of them survives a fold that
 * turns a null rate into a zero, which is what makes the rule gateable.
 *
 * ## Why the header keeps its controls in every state
 *
 * `Card` renders `headerEnd` whatever the body is doing, and that is the behaviour this card wants:
 * changing the range is a legitimate thing to try when a window is empty, and a selector that
 * vanished with the data would remove the only way out of an empty window.
 */

import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

import {
  BarChart,
  Button,
  Card,
  ChartLegend,
  RangeSelector,
  formatBytes,
  type Fetched,
  type LegendItem,
  type Series,
} from "@kui/kernel";

import { MetricAbsence, forbiddenSentence, notConfiguredSentence } from "./NotMeasured.jsx";
import {
  THROUGHPUT_RANGES,
  throughputChart,
  type ThroughputChart,
  type ThroughputRange,
  type ThroughputSeries,
} from "./throughput.js";

/** What this card calls the thing it is not measuring, in both absence sentences. */
export const THROUGHPUT_NOUN = "this cluster's throughput";

/**
 * What a cluster with no metrics source is told.
 *
 * `not_configured` is a deployment choice, not a fault: the operator configured no exporter and KUI
 * is saying so rather than drawing an axis over nothing. The sentence names what would have to
 * exist, which is the difference between "this is broken" and "this is not switched on".
 *
 * Built from the shared sentence rather than written out, because five cards on this tab reach this
 * state and an operator looking at five spellings of it reads five problems. Kept as an export so
 * that a case can assert the words rather than a fragment it typed itself — which is what the
 * render tests now do, and what nothing did for the wave this constant existed unused.
 */
export const NOT_CONFIGURED_SENTENCE = notConfiguredSentence(THROUGHPUT_NOUN);

/** The one this card shows when the principal may not read the cluster's metrics. */
export const FORBIDDEN_SENTENCE = forbiddenSentence(THROUGHPUT_NOUN);

/**
 * The window is real and every step in it is blank.
 *
 * Drawn *with* the axis, unlike the not-configured case: KUI has a source and has sampled nothing
 * yet, so the window genuinely exists and its emptiness is a measurement. That is the distinction
 * `reading.ts` is built on, arriving in a chart.
 */
export const NO_SAMPLES_SENTENCE =
  "Nothing has been sampled in this window yet, so every step in it is blank rather than zero.";

/** The two series, in the design's order and its inks (§1.6: series colour carries no meaning). */
const PRODUCE_TONE = "series-1" as const;
const CONSUME_TONE = "series-2" as const;

export interface ThroughputCardProps {
  readonly state: Fetched<ThroughputSeries>;
  readonly range: ThroughputRange;
  readonly onRange: (range: ThroughputRange) => void;
  /** Ask again. Wired to the query's own `reload`, so the Retry button is not decorative. */
  readonly onRetry?: (() => void) | undefined;
}

export function ThroughputCard(props: ThroughputCardProps): JSX.Element {
  const chart = (): ThroughputChart | undefined => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale"
      ? throughputChart(state.value, props.range)
      : undefined;
  };

  return (
    <Card
      title="Throughput"
      icon="chart-bars"
      testId="panel-throughput"
      state={props.state.kind === "failed" ? "unavailable" : "ready"}
      message={props.state.kind === "failed" ? props.state.message : undefined}
      code={props.state.kind === "failed" ? props.state.code : undefined}
      stateAction={
        props.state.kind === "failed" && props.onRetry !== undefined ? (
          <Button variant="secondary" onClick={() => props.onRetry?.()}>
            Retry
          </Button>
        ) : undefined
      }
      headerEnd={
        <div class="kui-throughput__controls">
          {/* The legend is in the header because it is also the readout: §3.1 puts the current
              value in the chip, so a reader who wants today's number does not have to hover a bar
              to get it. It is drawn only when there is a chart — a key beside a sentence names
              inks the reader cannot see anywhere. */}
          <Show when={chart()}>{(built) => <ChartLegend items={legendOf(built())} />}</Show>
          <RangeSelector
            label="Throughput range"
            options={THROUGHPUT_RANGES.map((range) => ({ value: range, label: range }))}
            value={props.range}
            onChange={(chosen) => props.onRange(chosen as ThroughputRange)}
          />
        </div>
      }
      caption={captionOf(props.state, chart())}
    >
      <ThroughputBody state={props.state} range={props.range} chart={chart()} />
    </Card>
  );
}

/**
 * The chips, and the reason a chip can carry no figure.
 *
 * A window in which nothing was measured has no current rate, and the chip prints the label alone
 * rather than an em dash: a dash in a chip reads as a rendering fault, and the sentence in the plot
 * has already said what is missing and why. Where a rate *was* measured — including a measured
 * zero — the figure is printed, because "0 B/s" is a fact about a quiet cluster and is exactly the
 * reading a dash would destroy.
 */
function legendOf(chart: ThroughputChart): readonly LegendItem[] {
  const latest = chart.latest;
  const chip = (label: string, tone: LegendItem["tone"], value: number | null): LegendItem =>
    value === null ? { label, tone } : { label, tone, value: `${formatBytes(value)}/s` };
  return [
    chip("produce", PRODUCE_TONE, latest?.produce ?? null),
    chip("consume", CONSUME_TONE, latest?.consume ?? null),
  ];
}

/**
 * The sentence under the card.
 *
 * `stale` gets its reason here rather than through `Card`'s stale badge, and that is deliberate:
 * the badge takes an `asOf` date and this state carries none — `Fetched.stale` keeps the reason and
 * drops the section's `fetchedAt` — so drawing the badge would mean inventing a timestamp. A
 * fabricated "last successful check was 24s ago" is the exact defect the brokers screen is being
 * repaired for in this same wave.
 */
function captionOf(
  state: Fetched<ThroughputSeries>,
  chart: ThroughputChart | undefined,
): string | undefined {
  const gaps = chart?.caption;
  if (state.kind !== "stale") return gaps;
  const stale = `This is the last answer KUI received: ${state.reason}`;
  return gaps === undefined ? stale : `${stale} ${gaps}`;
}

function ThroughputBody(props: {
  readonly state: Fetched<ThroughputSeries>;
  readonly range: ThroughputRange;
  readonly chart: ThroughputChart | undefined;
}): JSX.Element {
  return (
    <Show
      when={props.chart}
      fallback={
        /* The waiting box, the not-configured sentence and the permission note, in the one place
           every metrics card on this screen draws them. `failed` is absent from that component on
           purpose: `Card` is already drawing that state's own body, with the code and the Retry
           button, and a second sentence underneath would say the same thing twice. */
        <MetricAbsence state={props.state} noun={THROUGHPUT_NOUN} testId="throughput-not-measured" />
      }
    >
      {(chart) => (
        <div class="kui-throughput">
          <BarChart
            label={`Throughput over the last ${props.range}`}
            categories={chart().categories}
            series={seriesOf(chart())}
            ticks={chart().ticks}
            format={(value) => `${formatBytes(value)}/s`}
            emptyMessage={NO_SAMPLES_SENTENCE}
            height={180}
          />
          {/*
           * The coverage strip, and it is the visible half of the gap rule.
           *
           * `aria-hidden` because it says nothing the caption does not already say in words, and a
           * screen reader reading two hundred boxes would be told about the picture rather than
           * about the cluster. Each run's width is its bucket count, which is the same division
           * `BarChart` makes of the same width, so the strip lines up by arithmetic rather than by
           * a measurement that could drift.
           */}
          <div class="kui-throughput__coverage" aria-hidden="true">
            <For each={chart().coverage}>
              {(run) => (
                <span
                  class={[
                    "kui-throughput__coverage-run",
                    run.measured
                      ? "kui-throughput__coverage-run--measured"
                      : "kui-throughput__coverage-run--absent",
                  ]}
                  style={{ flex: `${run.length} 0 0%` }}
                />
              )}
            </For>
          </div>
        </div>
      )}
    </Show>
  );
}

/** The two plotted series. `null` points are carried through untouched — see the file header. */
function seriesOf(chart: ThroughputChart): readonly Series[] {
  return [
    { label: "produce", tone: PRODUCE_TONE, points: chart.produce },
    { label: "consume", tone: CONSUME_TONE, points: chart.consume },
  ];
}
