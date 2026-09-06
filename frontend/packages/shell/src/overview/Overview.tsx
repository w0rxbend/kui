/**
 * The cluster dashboard — screenshots `01`, `04` and `05`.
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
 * ## The route is the only source of truth for which cluster and which tab
 *
 * Both come from `useParams()` and neither is a prop. The address is `/clusters/:clusterId/dashboard`
 * and `/dashboard/:tab`, both of which the router already resolves here, and a link somebody pastes
 * carries both — so reading either from a stored selection or from local component state would give
 * the page a second answer to a question the URL has already answered. The two answers only ever
 * differ when it matters: after a pasted link, or after the back button. The one place the stored
 * selection is still consulted is the address that names no cluster at all (`/ui` itself), and the
 * comment on `cluster()` below says why.
 *
 * `tab` is deliberately forgiving. A path segment is user-editable and a typo is not an error state;
 * `tabs.ts` carries that rule and the default that keeps it in step with `paths.dashboard`.
 *
 * ## What each tab owns
 *
 * SCREENS-V4.md §3.1: a tab changes the voice line, the card set and the address, and nothing else.
 * §4.2's composition rule and §4.3's shape together say where the seam is — the stat cards are
 * tab-invariant and the body below them belongs to the tab. That is exactly how this file is laid
 * out, so a third tab is a case in one switch rather than a rearrangement.
 *
 * ## The panels that are not real
 *
 * Throughput over time, the current produce rate, latency percentiles and the record-size
 * distribution are drawn in the design and are not collected by this backend — `services/metrics`
 * answers `not_configured` by design, and no other endpoint could answer any of them. They render as
 * a sentence saying so. See `NotMeasured.tsx` for why that is a `ready` card and not an
 * `unavailable` one, and `model.ts` for the sentences themselves.
 */

import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useParams } from "@solidjs/router";

import {
  Button,
  Card,
  Donut,
  IconTile,
  MagnitudeBarList,
  PageHeader,
  ProgressBar,
  RingGauge,
  StatCard,
  TabStrip,
  formatCount,
  formatPercent,
  useKui,
  type MagnitudeEntry,
  type StatFigure,
  type Tab,
} from "@kui/kernel";

import { NotMeasured } from "./NotMeasured.jsx";
import { StorageByBroker, topicsCounted } from "./StorageByBroker.jsx";
import { DASHBOARD_TABS, dashboardTab, type DashboardTab } from "./tabs.js";
import {
  DISK_CRITICAL_PERCENT,
  DISK_WARN_PERCENT,
  type BrokerBar,
  type LagEntry,
  type PartitionHealth,
  type StorageBreakdown,
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
  /** The Storage tab's own voice line. Conditional for the same reason `lede` is — see `model.ts`. */
  readonly storageLede: string;
  readonly brokerCount: Reading<number>;
  readonly brokerPill: { text: string; tone: Tone } | undefined;
  readonly topicCount: Reading<number>;
  readonly partitionTotal: Reading<number>;
  readonly inSync: Reading<number>;
  readonly productionRate: Reading<never>;
  readonly throughput: Reading<never>;
  readonly latency: Reading<never>;
  readonly messageSizes: Reading<never>;
  readonly lag: Reading<{ total: number; incomplete: number }>;
  readonly lagPill: { text: string; tone: Tone } | undefined;
  readonly brokers: Reading<readonly BrokerBar[]>;
  readonly controllerNote: string | undefined;
  readonly partitions: Reading<PartitionHealth>;
  readonly topLag: Reading<readonly LagEntry[]>;
  readonly storage: Reading<StorageBreakdown>;
}

export interface OverviewProps {
  readonly model: OverviewModel;
  readonly onCreateTopic?: (() => void) | undefined;
}

/** What each tab's segment says and shows. The icons are the design's (§3.1). */
const TAB_LABELS: Readonly<Record<DashboardTab, { readonly label: string; readonly icon: Tab["icon"] }>> = {
  overview: { label: "Overview", icon: "dashboard" },
  storage: { label: "Storage", icon: "disk" },
};

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
  const params = useParams<{ readonly clusterId?: string; readonly tab?: string }>();
  const kui = useKui();

  const tab = (): DashboardTab => dashboardTab(params.tab);

  /**
   * Which cluster's dashboard this is.
   *
   * The route parameter wins, always. It is `undefined` on exactly one address — `/ui` itself, the
   * table's root entry, which resolves to this same component and names no cluster — and there the
   * selection the shell is already fetching for is the only cluster there is. Falling back the other
   * way round, or not falling back at all, would give the root address a tab strip whose links point
   * nowhere.
   */
  const cluster = (): string | undefined => params.clusterId ?? kui.cluster();

  const tabs = (): readonly Tab[] => {
    const id = cluster();
    if (id === undefined) return [];
    return DASHBOARD_TABS.map((name) => ({
      id: name,
      label: TAB_LABELS[name].label,
      icon: TAB_LABELS[name].icon,
      /* Built rather than written. `paths.dashboard` is the one place the tab's default lives, so
         the strip and a hand-written link cannot spell the same page two ways — which is the whole
         reason the tab is a route parameter and not a piece of component state. */
      href: kui.paths.dashboard(id, name),
    }));
  };

  return (
    <div class="kui-overview" data-testid="overview">
      {/* The kernel's `PageHeader`, not markup of this screen's own. It already draws the title,
          the voice line and the actions in the arrangement SPEC §4.12 specifies, and a second
          implementation here would be a second thing to keep in step with the topic and consumer
          pages that use it. */}
      <PageHeader
        title="Cluster overview"
        /* The voice line, chosen by the tab. Conditional on the cluster actually being healthy —
           see `overviewLede` and `storageLede`. A cheerful sentence over a broken cluster is worse
           than a plain one. */
        voice={ledeFor(tab(), props.model)}
        actions={
          <Button variant="primary" icon="plus" onClick={() => props.onCreateTopic?.()}>
            Create topic
          </Button>
        }
        testId="overview-header"
      />

      {/* Nothing at all when there is no cluster to build hrefs for. A strip of segments that
          navigate nowhere is worse than no strip: it is the `⌘K` hint bound to nothing, drawn
          across the top of the page. */}
      <Show when={tabs().length > 0}>
        <TabStrip tabs={tabs()} currentId={tab()} label="Cluster sections" />
      </Show>

      {/* Tab-invariant, and drawn once above the switch rather than inside each arm. §4.2 proves
          the rule the design only implies: the stat cards are identical on every tab, so a reader
          who switches tabs is not made to re-read them. */}
      <StatRow model={props.model} />

      {bodyFor(tab(), props.model)}
    </div>
  );
}

/**
 * The voice line and the body, each chosen by a switch over the tab rather than by a ternary.
 *
 * `noImplicitReturns` over an exhaustive switch is what makes a third tab a compile error in this
 * file instead of a silently-empty body. That matters more than it looks: Traffic and Alerts are
 * both in the design and both arrive with the service that measures them, and the failure a ternary
 * would produce — a new segment in the strip that draws the overview under a different name — is
 * one nothing else in this package would notice.
 */
function ledeFor(tab: DashboardTab, model: OverviewModel): string {
  switch (tab) {
    case "overview":
      return model.lede;
    case "storage":
      return model.storageLede;
  }
}

function bodyFor(tab: DashboardTab, model: OverviewModel): JSX.Element {
  switch (tab) {
    case "overview":
      return <OverviewBody model={model} />;
    case "storage":
      return <StorageBody model={model} />;
  }
}

/** The row of stat cards, which every tab carries unchanged. */
function StatRow(props: { readonly model: OverviewModel }): JSX.Element {
  return (
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
      <StatCard
        label="PARTITIONS IN SYNC"
        icon="partitions"
        tone="primary"
        figure={inSyncFigure(props.model.inSync)}
        /* The design's third micro-visual (§3.2), and the one card on this screen whose good end of
           the domain is the *high* end — hence `goodDirection`, which `RingGauge` refuses to guess.
           The slot is left empty rather than filled with a plain track when the share is not a
           number: §3.2's own rule is that a card with no series draws no visual, because an
           unmeasured ring beside an em dash says the same absence twice and reserves a box for it. */
        visual={
          props.model.inSync.kind === "value" ? (
            <RingGauge
              value={props.model.inSync.value}
              goodDirection="high"
              caption="IN SYNC"
              diameter={44}
              strokeWidth={5}
              decimals={1}
            />
          ) : undefined
        }
        testId="stat-in-sync"
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
  );
}

/** Rows 2, 3 and 4 of `M01`: the two chart cards, the three panels, and the storage card. */
function OverviewBody(props: { readonly model: OverviewModel }): JSX.Element {
  return (
    <>
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

      <div class="kui-overview__charts">
        <StorageCard reading={props.model.storage} />
      </div>
    </>
  );
}

/**
 * The Storage tab: exactly two cards, and then the page ends (§4.3).
 *
 * It is short, and it is not padded to fill the viewport. §4.3 records that the Storage capture
 * replaces the whole body rather than only the last row — which is why rows 2 and 3 are absent here
 * rather than repeated. A tab that repeated the Overview's panels under a different name would make
 * the strip a control whose settings mostly agree with each other.
 */
function StorageBody(props: { readonly model: OverviewModel }): JSX.Element {
  return (
    <div class="kui-overview__charts">
      <StorageCard reading={props.model.storage} />
      <Card title="Message size distribution" icon="chart-bars" testId="panel-message-sizes">
        <NotMeasured
          why={props.model.messageSizes.kind === "notCollected" ? props.model.messageSizes.why : ""}
        />
      </Card>
    </div>
  );
}

/** The storage card, drawn identically on both tabs because it is the same card (§4.1 row 4). */
function StorageCard(props: { readonly reading: Reading<StorageBreakdown> }): JSX.Element {
  return (
    <Card
      title="Storage by broker"
      icon="disk"
      testId="panel-storage"
      caption={props.reading.kind === "value" ? topicsCounted(props.reading.value) : undefined}
    >
      <Show
        when={props.reading.kind === "value" ? props.reading.value : undefined}
        fallback={<ReadingFallback reading={props.reading} noun="the log directories" />}
      >
        {(breakdown) => <StorageByBroker breakdown={breakdown()} />}
      </Show>
    </Card>
  );
}

/**
 * The in-sync share as a stat figure.
 *
 * Its own function rather than `figureOf`, because the unit belongs *inside* the formatted string:
 * `formatPercent` writes the sign, and a card printing `99.1` beside a separate `%` would have two
 * places where the share could be spelled and one of them would eventually drift.
 */
function inSyncFigure(reading: Reading<number>): StatFigure {
  switch (reading.kind) {
    case "value":
      return { kind: "value", text: formatPercent(reading.value, 1) };
    case "pending":
      return { kind: "pending" };
    case "unknown":
    case "notCollected":
      return { kind: "unknown" };
  }
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
