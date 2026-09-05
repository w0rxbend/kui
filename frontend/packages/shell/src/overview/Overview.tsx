/**
 * The cluster overview — screenshots `01` and `05`.
 *
 * ## What this component is, and is not
 *
 * It is arrangement. Every judgement it renders was made by a pure function in `model.ts`: whether
 * "all in sync" is true, whether a disk is into the amber, which of the design's figures the backend
 * does not measure. This file decides where those answers go on the page and nothing else, which is
 * why it takes a fully-formed view model rather than an API client — the interesting states of this
 * screen (a cluster that has not answered, a broker too old to report disk sizes, a panel that will
 * never have data) are then all reachable from a test and from Storybook without a Kafka cluster.
 *
 * ## The three panels that are not real
 *
 * Throughput over time, the current produce rate, and latency percentiles are drawn in the design
 * and are not collected by this backend — there is no metrics service, no timeseries store, and no
 * endpoint in the gateway's OpenAPI documents that could answer any of them. They render as a
 * sentence saying so. See `NotMeasured.tsx` for why that is a `ready` card and not an `unavailable`
 * one, and `model.ts` for the sentences themselves.
 */

import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

import {
  Button,
  Card,
  Donut,
  IconTile,
  MagnitudeBarList,
  PageHeader,
  ProgressBar,
  StatCard,
  formatCount,
  formatPercent,
  type MagnitudeEntry,
  type StatFigure,
} from "@kui/kernel";

import { NotMeasured } from "./NotMeasured.jsx";
import {
  DISK_CRITICAL_PERCENT,
  DISK_WARN_PERCENT,
  type BrokerBar,
  type LagEntry,
  type PartitionHealth,
  type Tone,
} from "./model.js";
import type { Reading } from "./reading.js";

/**
 * Everything the screen draws, already decided.
 *
 * Each field is a `Reading`, so each panel independently knows whether it is waiting, blank, or
 * unmeasurable. That is what makes the dashboard partial by design (ADR-039): the consumer service
 * being down blanks the lag panel and leaves the other five reporting, rather than failing the page.
 */
export interface OverviewModel {
  readonly lede: string;
  readonly brokerCount: Reading<number>;
  readonly brokerPill: { text: string; tone: Tone } | undefined;
  readonly topicCount: Reading<number>;
  readonly partitionTotal: Reading<number>;
  readonly productionRate: Reading<never>;
  readonly throughput: Reading<never>;
  readonly latency: Reading<never>;
  readonly lag: Reading<{ total: number; incomplete: number }>;
  readonly lagPill: { text: string; tone: Tone } | undefined;
  readonly brokers: Reading<readonly BrokerBar[]>;
  readonly controllerNote: string | undefined;
  readonly partitions: Reading<PartitionHealth>;
  readonly topLag: Reading<readonly LagEntry[]>;
}

export interface OverviewProps {
  readonly model: OverviewModel;
  readonly onCreateTopic?: (() => void) | undefined;
}

/**
 * Turns a `Reading<number>` into the figure a `StatCard` draws.
 *
 * The three non-value cases are three different pictures, not one: `pending` is a skeleton,
 * `unknown` is an em dash, and `notCollected` is also an em dash but never appears here, because a
 * figure the product does not measure is not given a card with a dash in it — the whole card says
 * so instead. The mapping is written out rather than defaulted so that adding a fourth case to
 * `Reading` is a compile error here rather than a silent dash.
 */
function figureOf(reading: Reading<number>, unit?: string): StatFigure {
  switch (reading.kind) {
    case "value":
      return unit === undefined
        ? { kind: "value", text: formatCount(reading.value) }
        : { kind: "value", text: formatCount(reading.value), unit };
    case "pending":
      return { kind: "pending" };
    case "unknown":
    case "notCollected":
      return { kind: "unknown" };
  }
}

export function Overview(props: OverviewProps): JSX.Element {
  return (
    <div class="kui-overview" data-testid="overview">
      {/* The kernel's `PageHeader`, not markup of this screen's own. It already draws the title,
          the voice line and the actions in the arrangement SPEC §4.12 specifies, and a second
          implementation here would be a second thing to keep in step with the topic and consumer
          pages that use it. */}
      <PageHeader
        title="Cluster overview"
        /* The voice line. Conditional on the cluster actually being healthy — see `overviewLede`.
           A cheerful sentence over a broken cluster is worse than a plain one. */
        voice={props.model.lede}
        actions={
          <Button variant="primary" icon="plus" onClick={() => props.onCreateTopic?.()}>
            Create topic
          </Button>
        }
        testId="overview-header"
      />

      <div class="kui-overview__stats">
        <StatCard
          label="BROKERS ONLINE"
          icon="brokers"
          tone="success"
          figure={figureOf(props.model.brokerCount)}
          pill={props.model.brokerPill}
          testId="stat-brokers"
        />
        <StatCard
          label="TOPICS"
          icon="topics"
          tone="accent"
          figure={figureOf(props.model.topicCount)}
          pill={pillForPartitions(props.model.partitionTotal)}
          testId="stat-topics"
        />
        {/* The design's "PRODUCTION 86.4 MB/s". Nothing samples broker byte rates, so this card
            carries the sentence rather than a figure — a card reading `— MB/s` would say the rate
            is momentarily unreadable, which is a different and untrue claim. */}
        <ProductionCard reading={props.model.productionRate} />
        <StatCard
          label="CONSUMER LAG"
          icon="lag"
          tone="warning"
          figure={figureOf(mapLagTotal(props.model.lag))}
          pill={props.model.lagPill}
          testId="stat-lag"
        />
      </div>

      <div class="kui-overview__charts">
        <Card
          title="Throughput"
          icon="chart-bars"
          testId="panel-throughput"
          /* No `RangeSelector`. The design puts 24h / 7d / 30d here, and offering a range control
             over data that does not exist is a control whose every setting produces the same
             nothing — the same defect class as the `⌘K` hint that was bound to nothing. */
        >
          <NotMeasured why={props.model.throughput.kind === "notCollected" ? props.model.throughput.why : ""} />
        </Card>

        <Card title="Broker health" icon="brokers" testId="panel-broker-health" caption={props.model.controllerNote}>
          <BrokerHealth reading={props.model.brokers} />
        </Card>
      </div>

      <div class="kui-overview__panels">
        <Card title="Partition health" icon="topology" testId="panel-partitions">
          <PartitionDonut reading={props.model.partitions} />
        </Card>

        <Card title="Top consumer lag" icon="lag" testId="panel-top-lag">
          <TopLag reading={props.model.topLag} />
        </Card>

        <Card title="Latency · p99" icon="chart-line" testId="panel-latency">
          <NotMeasured why={props.model.latency.kind === "notCollected" ? props.model.latency.why : ""} />
        </Card>
      </div>
    </div>
  );
}

/** The lag card's figure is the total; the count of uncounted groups goes in the pill. */
function mapLagTotal(reading: Reading<{ total: number; incomplete: number }>): Reading<number> {
  return reading.kind === "value" ? { kind: "value", value: reading.value.total } : reading;
}

/**
 * The partition-count pill under the topics figure.
 *
 * Absent, rather than a dash, when the count is not known. A `StatusPill` reading `—` is a pill
 * whose only content is an admission, and an empty-looking chip below a number reads as a rendering
 * fault. Saying nothing is the honest rendering of "no second fact to add".
 */
function pillForPartitions(reading: Reading<number>): { text: string; tone: Tone } | undefined {
  return reading.kind === "value" ? { text: `${formatCount(reading.value)} partitions`, tone: "neutral" } : undefined;
}

/**
 * The design's "PRODUCTION 86.4 MB/s" card, with a sentence where the figure would be.
 *
 * It borrows `.kui-stat`'s own head — the icon tile and the label — rather than being a bare note,
 * so that the row of four reads as a row of four. Dropping the label as well as the figure made
 * this card visibly a different kind of object from its three neighbours, which draws the eye to
 * the one card that has nothing to say.
 *
 * What it does *not* borrow is the figure. A `StatCard` with a `—` in it would say the rate is
 * momentarily unreadable; the truth is that KUI never reads it, and that is a sentence, not a dash.
 */
function ProductionCard(props: { readonly reading: Reading<never> }): JSX.Element {
  return (
    <div class="kui-stat kui-stat--note" data-testid="stat-production">
      <span class="kui-stat__head">
        <IconTile icon="chart-bars" tone="neutral" />
        <span class="kui-stat__label">PRODUCTION</span>
      </span>
      <NotMeasured
        why={props.reading.kind === "notCollected" ? props.reading.why : ""}
        instead="Per-topic message counts are on each topic's page."
        testId="stat-production-note"
      />
    </div>
  );
}

function BrokerHealth(props: { readonly reading: Reading<readonly BrokerBar[]> }): JSX.Element {
  return (
    <Show
      when={props.reading.kind === "value" ? props.reading.value : undefined}
      fallback={<ReadingFallback reading={props.reading} noun="broker health" />}
    >
      {(brokers) => (
        <Show when={brokers().length > 0} fallback={<p class="kui-overview__blank">No brokers answered.</p>}>
          <ul class="kui-broker-health">
            {/* Keyed by identity (the default), so a broker that keeps its place keeps its DOM node
                and its bar does not restart its transition on every poll. */}
            <For each={brokers()}>
              {(broker) => (
                <li class="kui-broker-health__row">
                {/* The broker's name, drawn. `ProgressBar` puts its `label` on `aria-label` and
                    nowhere else — correct for a bare bar, wrong here — so a row that relied on it
                    would name every broker to a screen reader and none to anybody looking at the
                    screen. The design draws the name and its detail on a line above the bar, which
                    is what this is. */}
                <p class="kui-broker-health__head">
                  <span class="kui-broker-health__name">{broker.name}</span>
                  <span class="kui-broker-health__detail">{broker.detail}</span>
                </p>
                <ProgressBar
                  /* The accessible name still says which broker, because a screen-reader user
                     reaching the bar alone has not necessarily just read the line above it. */
                  label={`${broker.name} disk usage`}
                  caption="disk"
                  /* `undefined`, not `0`, when the disk is unmeasurable. This is the brief's "a
                     quantity bar must not draw zero as a full-width track" rule seen from the
                     other side: an unknown drawn as zero is an empty track that reads as an empty
                     disk, which is the most reassuring possible rendering of "we have no idea". */
                  value={broker.diskPercent.kind === "value" ? broker.diskPercent.value : undefined}
                  max={100}
                  thresholds={{ warn: DISK_WARN_PERCENT, critical: DISK_CRITICAL_PERCENT }}
                  valueText={broker.diskPercent.kind === "value" ? formatPercent(broker.diskPercent.value) : undefined}
                />
                {/* The reason a bar is empty, in words, for the one case where it matters: an
                    operator comparing three brokers needs to know that the blank one is
                    unmeasured rather than idle. */}
                <Show when={broker.diskPercent.kind === "unknown" ? broker.diskPercent.why : undefined}>
                  {(why) => <p class="kui-broker-health__why">{why()}</p>}
                </Show>
              </li>
              )}
            </For>
          </ul>
        </Show>
      )}
    </Show>
  );
}

function PartitionDonut(props: { readonly reading: Reading<PartitionHealth> }): JSX.Element {
  return (
    <Show
      when={props.reading.kind === "value" ? props.reading.value : undefined}
      fallback={<ReadingFallback reading={props.reading} noun="partition health" />}
    >
      {(health) => (
        <div class="kui-partition-health">
          <Donut
            segments={[
              { label: "In sync", value: health().inSync, tone: "success" },
              { label: "Under-replicated", value: health().underReplicated, tone: "warning" },
              { label: "Offline", value: health().offline, tone: "danger" },
            ]}
            healthyPercent={health().healthyPercent}
            centreCaption="IN SYNC"
          />
          {/* No legend here. `Donut` draws its own, and it is not decoration — it is the
              accessible rendering of the chart, printing every label and count as text. A second
              one below it listed all three segments twice. */}
        </div>
      )}
    </Show>
  );
}

function TopLag(props: { readonly reading: Reading<readonly LagEntry[]> }): JSX.Element {
  return (
    <Show
      when={props.reading.kind === "value" ? props.reading.value : undefined}
      fallback={<ReadingFallback reading={props.reading} noun="consumer lag" />}
    >
      {(entries) => (
        <MagnitudeBarList
          entries={entries().map(
            (entry): MagnitudeEntry => ({
              label: entry.groupId,
              value: entry.lag,
              valueText: formatCount(entry.lag),
              /* Coloured only when it is large. A group that is caught up is not an achievement
                 worth a green bar, and colouring every row makes the one row that matters
                 invisible. */
              tone: entry.lag >= 1000 ? "warning" : "neutral",
            }),
          )}
          emptyMessage="No consumer groups are running."
        />
      )}
    </Show>
  );
}

/**
 * What a panel draws when its reading is not a value.
 *
 * Three renderings for three states, which is the whole point: a skeleton says "wait", a sentence
 * with a reason says "this failed and here is why", and the not-collected case is routed away from
 * here entirely and never reaches this component.
 */
function ReadingFallback(props: { readonly reading: Reading<unknown>; readonly noun: string }): JSX.Element {
  return (
    <Show
      when={props.reading.kind === "pending"}
      fallback={
        <p class="kui-overview__blank" role="note">
          {props.reading.kind === "unknown" || props.reading.kind === "notCollected"
            ? props.reading.why
            : `KUI could not read ${props.noun}.`}
        </p>
      }
    >
      {/* `aria-busy` rather than a live region: a screen reader should learn that this panel is
          still filling in when it reaches it, not be interrupted about it. */}
      {/* `role="status"` because a bare `<div>` may not carry `aria-label` — ARIA forbids naming an
          element with no role, so the label was being dropped and the placeholder announced nothing
          at all. `status` is what this is: a polite statement that a figure is on its way. */}
      <div
        class="kui-overview__waiting"
        role="status"
        aria-busy="true"
        aria-label={`Reading ${props.noun}`}
      />
    </Show>
  );
}
