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
 * ## The panels that were not real, and what is left of that list
 *
 * Five of the design's figures had no source in this product a wave ago and rendered as a sentence
 * saying so. `services/metrics` now answers all five: throughput, p99 latency, the request-handler
 * readings, the top producers and the mean record size. So this file no longer receives them as
 * `notCollected` readings on the model — it **asks**, and draws whatever comes back, including the
 * common answer that a deployment configured no exporter.
 *
 * Two refusals survive and they are refusals about *what a broker publishes* rather than about what
 * KUI collects, which is a different claim and is why they are drawn beside real figures rather
 * than instead of them: there is no purgatory *percentage* (`DelayedOperationPurgatory` publishes a
 * queue length) and no record-size *distribution* (only a mean). ADR-052 takes both decisions;
 * `metrics.ts` and `TrafficCards.tsx` carry them.
 *
 * ## Why this file fetches, and why the fetches are keyed on the tab
 *
 * Everything else arrives as a finished view model, and the header above says why. The metrics
 * reads are the exception because their requests are a function of the *address*: the window lives
 * in `?range=` so a colleague can be sent a link to what somebody is looking at, and the shell's
 * single overview fetch is keyed on the cluster alone and reads no query parameter. Folding the
 * window into it would mean refetching five endpoints to change one axis.
 *
 * The three cards that belong to one tab ask on that tab only — `useQuery` asks nothing for an
 * `undefined` key, which is what makes a tab strip cheap: opening Storage does not scrape a broker
 * for a card Storage does not draw. Each card is then handed a finished state exactly like every
 * other panel, so every state of every one of them is reachable from a story and from a test.
 */

import { For, Show, createMemo } from "solid-js";
import { Dynamic } from "@solidjs/web";
import type { JSX } from "@solidjs/web";
import { useParams, useSearchParams } from "@solidjs/router";

import {
  Button,
  Card,
  Donut,
  IconTile,
  MagnitudeBarList,
  PageHeader,
  ProgressBar,
  RingGauge,
  Sparkline,
  StatCard,
  TabStrip,
  formatBytes,
  formatCount,
  formatPercent,
  useKui,
  useQuery,
  type Fetched,
  type IconName,
  type MagnitudeEntry,
  type QueryRegistry,
  type StatFigure,
  type Tab,
} from "@kui/kernel";

import { MetricAbsence, NotMeasured, notConfiguredSentence } from "./NotMeasured.jsx";
import { StorageByBroker, topicsCounted } from "./StorageByBroker.jsx";
import { ThroughputCard } from "./ThroughputCard.jsx";
import { LatencyCard } from "./LatencyCard.jsx";
import { RecordSizeCard, RequestHandlersCard, TopProducersCard } from "./TrafficCards.jsx";
import {
  TOP_PRODUCERS,
  fetchLatency,
  fetchRecordSize,
  fetchRequestHandlers,
  fetchTopProducers,
  latencyKey,
  producersKey,
  recordSizeKey,
  requestHandlersKey,
  type HandlerDocument,
  type LatencySeries,
  type ProducerDocument,
  type RecordSizeDocument,
} from "./metrics.js";
import {
  RANGE_PARAM,
  fetchThroughput,
  hasMeasuredBucket,
  rateSeries,
  throughputKey,
  throughputRange,
  type RateSeries,
  type ThroughputRange,
  type ThroughputSeries,
} from "./throughput.js";
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
  /*
   * The five figures that used to sit here as `Reading<never>` — the produce rate, the latency
   * percentiles, the record-size distribution, the top producers and the request handlers — are
   * gone, and their absence is the shape of this wave's change. Each was a constant sentence on a
   * model, which meant the *screen* could not distinguish "this build does not measure it" from
   * "this deployment configured no exporter" from "the exporter stopped answering", because the
   * model gave it one answer for all three. They are now five queries whose six-case `Fetched`
   * states say which. Putting one back would be a second answer to a question the query already
   * answers, and the two would disagree the first time either was edited.
   */
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
  /**
   * Which shared answers the throughput query reads.
   *
   * Omitted in the product, where the shared registry is the right one: a browser tab has one view
   * of one server. A test passes its own, for the same reason `SettingsPage` takes its preferences
   * as props — module state outlives a case, and a second case asking for the same cluster would
   * otherwise read the first one's stub and pass for the wrong reason.
   */
  readonly queries?: QueryRegistry | undefined;
}

/** What each tab's segment says and shows. The icons are the design's (§3.1). */
const TAB_LABELS: Readonly<Record<DashboardTab, { readonly label: string; readonly icon: Tab["icon"] }>> = {
  overview: { label: "Overview", icon: "dashboard" },
  traffic: { label: "Traffic", icon: "stream" },
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

  /**
   * The window the throughput card is drawing, read from the address and written back to it.
   *
   * A search parameter rather than component state, for the reason the tab is a path segment: the
   * address is what somebody pastes into a message, and a range held in a signal would make every
   * link land on the default window whatever the sender was looking at. An unrecognised spelling
   * resolves to the default rather than being an error, exactly as `dashboardTab` does — `?range=90d`
   * is a typo, not a page that does not exist.
   */
  const [search, setSearch] = useSearchParams<{ readonly range?: string }>();
  const range = (): ThroughputRange => throughputRange(search[RANGE_PARAM]);

  /**
   * The throughput series, keyed on the cluster and the window.
   *
   * `createMemo` around the key rather than a bare accessor so that a re-render on any other part
   * of the model does not re-derive it; `useQuery` does the rest — one request per key, shared, and
   * a failing refetch that keeps the last good answer and marks it stale rather than blanking a
   * chart that was showing real bytes a second ago.
   */
  const key = createMemo<string | undefined>(() => {
    const id = cluster();
    return id === undefined ? undefined : throughputKey(id, range());
  });
  /**
   * What a loader answers when it is somehow run without a cluster.
   *
   * Unreachable: every key below is `undefined` without a cluster, and `useQuery` asks nothing for
   * an undefined key. It is a value rather than a throw because a loader that rejects takes the
   * page down, which is what `ApiResult` exists to prevent.
   */
  const noCluster = <A,>(): Fetched<A> => ({
    kind: "failed",
    message: "No cluster is selected.",
    code: "NO_CLUSTER",
  });

  /** One query, keyed and registered the same way. Five of them, so the shape is written once. */
  const metric = <A,>(
    keyOf: () => string | undefined,
    load: (id: string) => Promise<Fetched<A>>,
  ) =>
    useQuery<A>({
      key: keyOf,
      load: async () => {
        const id = cluster();
        return id === undefined ? noCluster<A>() : load(id);
      },
      ...(props.queries === undefined ? {} : { registry: props.queries }),
    });

  const throughput = metric<ThroughputSeries>(key, (id) => fetchThroughput(kui.api, id, range()));

  /**
   * The p99 latency, over the same window the address names.
   *
   * Asked for on every tab that draws row 3 — which is the Overview and Traffic tabs, because §4.3
   * replaces the whole body on Storage. The key is `undefined` on Storage, so nothing is asked.
   */
  const latency = metric<LatencySeries>(
    createMemo<string | undefined>(() => {
      const id = cluster();
      return id === undefined || tab() === "storage" ? undefined : latencyKey(id, range());
    }),
    (id) => fetchLatency(kui.api, id, range()),
  );

  /** The Traffic tab's own last row: two of its three cards are asked for only when it is open. */
  const handlers = metric<HandlerDocument>(
    createMemo<string | undefined>(() => {
      const id = cluster();
      return id === undefined || tab() !== "traffic" ? undefined : requestHandlersKey(id);
    }),
    (id) => fetchRequestHandlers(kui.api, id),
  );

  const producers = metric<ProducerDocument>(
    createMemo<string | undefined>(() => {
      const id = cluster();
      return id === undefined || tab() !== "traffic" ? undefined : producersKey(id, TOP_PRODUCERS);
    }),
    (id) => fetchTopProducers(kui.api, id, TOP_PRODUCERS),
  );

  /** Drawn on Traffic and on Storage (§4.2, §4.3), so it is asked for on both and on neither else. */
  const recordSize = metric<RecordSizeDocument>(
    createMemo<string | undefined>(() => {
      const id = cluster();
      return id === undefined || tab() === "overview" ? undefined : recordSizeKey(id);
    }),
    (id) => fetchRecordSize(kui.api, id),
  );

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
        voice={ledeFor(tab(), props.model, throughput.state())}
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
      <StatRow model={props.model} throughput={throughput.state()} range={range()} />

      {/* `Dynamic` rather than a call, and this is not a style choice. The JSX compiler treats an
          expression container holding a call as dynamic and wraps it in a tracked computation, so
          `{bodyFor(tab(), props.model)}` read the model *at the dispatch* — and every model change
          re-ran the switch and replaced the whole body. Measured: moving LOADING → HEALTHY kept the
          tab-invariant stat row's DOM node and replaced the broker-health card's. Handing the model
          across as a prop instead leaves it a getter the children read, so the only thing this
          container tracks is `tab()`, and the panels below update in place. That is what makes
          `BrokerHealth`'s keying argument below true rather than merely written. */}
      <Dynamic
        component={bodyFor(tab())}
        model={props.model}
        throughput={throughput.state()}
        latency={latency.state()}
        handlers={handlers.state()}
        producers={producers.state()}
        recordSize={recordSize.state()}
        range={range()}
        onRange={(chosen: ThroughputRange) => {
          /* `setSearchParams` and not a `navigate`: this replaces one parameter and leaves the
             path, the tab and anything else in the query alone. */
          setSearch({ [RANGE_PARAM]: chosen });
        }}
        onRetry={throughput.reload}
      />
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
function ledeFor(
  tab: DashboardTab,
  model: OverviewModel,
  throughput: Fetched<ThroughputSeries>,
): string {
  switch (tab) {
    case "overview":
      return model.lede;
    case "traffic":
      return trafficLede(throughput);
    case "storage":
      return model.storageLede;
  }
}

/**
 * The Traffic tab's voice line, and why it is not the design's sentence unaltered.
 *
 * SCREENS-V4.md §4.2 gives this tab the line *"Throughput, latency and who is producing all of
 * it."* — a promise of three things, of which this build measures one. Printed unqualified over a
 * tab whose last row is three cards saying they cannot measure anything, it is the cheerful-line-
 * over-a-broken-cluster failure `overviewLede` exists to avoid, wearing a different hat: a reader
 * who believes the header goes looking for the latency chart.
 *
 * So the design's sentence is kept and a second one is added saying what of it is true here. The
 * second sentence is chosen by what the throughput request actually answered, which is the only
 * thing on this tab that can vary.
 */
function trafficLede(throughput: Fetched<ThroughputSeries>): string {
  const promise = "Throughput, latency and who is producing all of it.";
  switch (throughput.kind) {
    case "loading":
      return "Asking this cluster how much is going through it.";
    case "not-configured":
      return `${promise} This cluster has no metrics source, so KUI is measuring none of them.`;
    case "forbidden":
      return `${promise} You may not read this cluster's metrics, so none of it is drawn here.`;
    case "failed":
      return `${promise} The throughput reading did not arrive, so nothing here is measured.`;
    case "ready":
    case "stale":
      return hasMeasuredBucket(throughput.value)
        ? `${promise} KUI measures the first of those.`
        : `${promise} Nothing has been sampled in this window yet.`;
  }
}

/**
 * Which component draws the body — the component itself, not its rendering.
 *
 * Returning the component keeps the exhaustive switch that the header argues for (a third tab is a
 * compile error here) while keeping the model out of this function entirely, which is the half that
 * matters: a dispatch that also read the model would rebuild the body on every poll.
 */
function bodyFor(tab: DashboardTab): BodyComponent {
  switch (tab) {
    case "overview":
      return OverviewBody;
    case "traffic":
      return TrafficBody;
    case "storage":
      return StorageBody;
  }
}

/**
 * What every tab's body is handed.
 *
 * One shape for all three rather than a union, so that the `Dynamic` above passes one set of props
 * and `bodyFor` stays a dispatch over the tab alone. The Storage body reads only `model`, which is
 * what it means for the throughput query to belong to the two tabs that draw the card.
 */
interface BodyProps {
  readonly model: OverviewModel;
  readonly throughput: Fetched<ThroughputSeries>;
  readonly latency: Fetched<LatencySeries>;
  readonly handlers: Fetched<HandlerDocument>;
  readonly producers: Fetched<ProducerDocument>;
  readonly recordSize: Fetched<RecordSizeDocument>;
  readonly range: ThroughputRange;
  readonly onRange: (range: ThroughputRange) => void;
  readonly onRetry: () => void;
}

type BodyComponent = (props: BodyProps) => JSX.Element;

/**
 * The row of stat cards, which every tab carries unchanged.
 *
 * The two rate cards read the throughput series rather than a figure on the model, and that is not
 * a shortcut: `bytesInPerSecond` **is** the produce rate the design's `86.4 MB/s` card names, from
 * the same broker metric the chart below is drawn from. Computing it from anything else — a message
 * browse, a partition sweep — would be a card that quietly became a different measurement, which is
 * what the contract between this packet and the feature packets forbids.
 */
function StatRow(props: {
  readonly model: OverviewModel;
  readonly throughput: Fetched<ThroughputSeries>;
  readonly range: ThroughputRange;
}): JSX.Element {
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
      {/* The design's "PRODUCTION 86.4 MB/s" and "CONSUME 71.2 MB/s" (§3.2), each with the jagged
          sparkline the same table gives it. Two of §3.2's four sparkline cards; the other two —
          Topics and Partitions in sync — draw none, because KUI keeps no history of either figure
          and §3.2's own absent rule is that a card with no series has no sparkline rather than a
          flat line at zero. */}
      <RateCard
        label="PRODUCTION"
        testId="stat-production"
        icon="chart-bars"
        noun="this cluster's produce rate"
        instead="Per-topic message counts are on each topic's page."
        state={props.throughput}
        pick={(rates) => ({ current: rates.latest?.produce ?? null, points: rates.produce })}
      />
      <RateCard
        label="CONSUME"
        testId="stat-consume"
        icon="stream"
        noun="this cluster's consume rate"
        state={props.throughput}
        pick={(rates) => ({ current: rates.latest?.consume ?? null, points: rates.consume })}
      />
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
function OverviewBody(props: BodyProps): JSX.Element {
  return (
    <>
      <ChartsRow {...props} />
      <PanelsRow model={props.model} latency={props.latency} range={props.range} />

      <div class="kui-overview__charts">
        <StorageCard reading={props.model.storage} />
      </div>
    </>
  );
}

/**
 * The Traffic tab (`M03`): the same stat cards, the same rows 2 and 3, and a last row of its own.
 *
 * §4.2's composition rule, and the reason the two shared rows are components rather than markup
 * repeated here: *the tab selects the last row only*. Written out twice, the two tabs would be two
 * places for the broker-health card to drift, and the rule the design proves would be a comment.
 *
 * None of the three may be filled from something this browser happens to hold: a producer rate
 * computed from a message browse is not a broker metric, and a card that quietly became a different
 * measurement would be the most expensive kind of wrong on a screen whose whole promise is that it
 * says what it knows. Each is handed the state of its own read and nothing else.
 */
function TrafficBody(props: BodyProps): JSX.Element {
  return (
    <>
      <ChartsRow {...props} />
      <PanelsRow model={props.model} latency={props.latency} range={props.range} />

      <div class="kui-overview__panels">
        <TopProducersCard state={props.producers} />
        <RecordSizeCard state={props.recordSize} />
        <RequestHandlersCard state={props.handlers} />
      </div>
    </>
  );
}

/** Row 2, on both tabs that have one: the throughput chart and the broker-health list. */
function ChartsRow(props: BodyProps): JSX.Element {
  return (
    <div class="kui-overview__charts">
      <ThroughputCard
        state={props.throughput}
        range={props.range}
        onRange={props.onRange}
        onRetry={props.onRetry}
      />

      <Card title="Broker health" icon="brokers" testId="panel-broker-health" caption={props.model.controllerNote}>
        <BrokerHealth reading={props.model.brokers} />
      </Card>
    </div>
  );
}

/** Row 3, on both tabs that have one: partition health, top consumer lag, latency. */
function PanelsRow(props: {
  readonly model: OverviewModel;
  readonly latency: Fetched<LatencySeries>;
  readonly range: ThroughputRange;
}): JSX.Element {
  return (
    <div class="kui-overview__panels">
      <Card title="Partition health" icon="topology" testId="panel-partitions">
        <PartitionDonut reading={props.model.partitions} />
      </Card>

      <Card title="Top consumer lag" icon="lag" testId="panel-top-lag">
        <TopLag reading={props.model.topLag} />
      </Card>

      <LatencyCard state={props.latency} range={props.range} />
    </div>
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
function StorageBody(props: BodyProps): JSX.Element {
  return (
    <div class="kui-overview__charts">
      <StorageCard reading={props.model.storage} />
      <RecordSizeCard state={props.recordSize} />
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
 * The design's "PRODUCTION 86.4 MB/s" and "CONSUME 71.2 MB/s" cards (§3.2), from the throughput
 * series that is already on the screen.
 *
 * ## Why one component draws both, and why it has two bodies
 *
 * The two cards differ in one accessor and nothing else, and a second copy would be a second place
 * for the never-a-zero rule to be got wrong. Each has two renderings and the choice between them is
 * the whole point:
 *
 *  - a **measured** rate — including a measured zero, which is a fact about a quiet cluster — draws
 *    a `StatCard` with the figure and a `Sparkline` of the window;
 *  - anything else draws the card's head and a **sentence**, because a `StatCard` reading `— MB/s`
 *    says the rate is momentarily unreadable, and "no exporter is configured", "you may not read
 *    this", "the exporter stopped answering" and "nothing has been sampled yet" are four different
 *    claims that a dash makes indistinguishable.
 *
 * The measured-zero case is why the switch below is written `rate() !== undefined` rather than as a
 * truthiness test: a produce rate of exactly `0 B/s` is the reading a quiet cluster gives, and
 * `<Show when={0}>` would have sent it to the sentence — reporting an idle cluster as an unmeasured
 * one, which is this screen's central mistake made backwards.
 *
 * `loading` reaches the sentence half too, and draws the waiting box `MetricAbsence` owns rather
 * than a figure: a figure that has not arrived must not look like one that is missing, and the box
 * is the shape the rest of this dashboard reserves for exactly that.
 */
function RateCard(props: {
  readonly label: string;
  readonly testId: string;
  readonly icon: IconName;
  /** What is not measured, in the possessive, for the two shared absence sentences. */
  readonly noun: string;
  readonly instead?: string | undefined;
  readonly state: Fetched<ThroughputSeries>;
  /** Which of the two rates this card is. The only thing that differs between the two callers. */
  readonly pick: (rates: RateSeries) => {
    readonly current: number | null;
    readonly points: readonly (number | null)[];
  };
}): JSX.Element {
  /* `rateSeries` and not `throughputChart`: this card draws one figure and a mark, and the chart's
     other half is 288 `toLocaleTimeString` calls for labels no stat card prints. And a `createMemo`
     rather than a bare accessor, for the reason the throughput key above is one — `Sparkline` reads
     its `points` prop several times per render, and recomputed on each read the summary cost more
     than the chart it summarises. */
  const rates = createMemo<RateSeries | undefined>(() => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale" ? rateSeries(state.value) : undefined;
  });

  const reading = createMemo(() => {
    const built = rates();
    return built === undefined ? undefined : props.pick(built);
  });

  /** The current rate, or `undefined` when there is not one. `0` is a rate. */
  const rate = createMemo<number | undefined>(() => {
    const value = reading()?.current;
    return value === null || value === undefined ? undefined : value;
  });

  return (
    <Show when={rate() !== undefined} fallback={<RateNote {...props} />}>
      <StatCard
        label={props.label}
        icon={props.icon}
        tone="accent"
        figure={{ kind: "value", text: `${formatBytes(rate() as number)}/s` }}
        /* §3.3: the mark is read only for its shape, and the figure beside it carries the
           magnitude. A `null` step breaks the line rather than being drawn through, which is the
           same gap rule the chart below the card keeps. */
        visual={<Sparkline points={reading()?.points ?? []} />}
        testId={props.testId}
      />
    </Show>
  );
}

/**
 * The same card with a sentence where the figure would be.
 *
 * It borrows `.kui-stat`'s own head — the icon tile and the label — rather than being a bare note,
 * so that the row reads as a row of equals. Dropping the label as well as the figure made this card
 * visibly a different kind of object from its neighbours, which draws the eye to the one card that
 * has nothing to say.
 */
function RateNote(props: {
  readonly label: string;
  readonly testId: string;
  readonly icon: IconName;
  readonly noun: string;
  readonly instead?: string | undefined;
  readonly state: Fetched<ThroughputSeries>;
}): JSX.Element {
  return (
    <div class="kui-stat kui-stat--note" data-testid={props.testId}>
      <span class="kui-stat__head">
        <IconTile icon={props.icon} tone="neutral" />
        <span class="kui-stat__label">{props.label}</span>
      </span>
      <Show
        when={rateAbsence(props.state, props.noun)}
        fallback={<MetricAbsence state={props.state} noun={props.noun} />}
      >
        {(why) => (
          <NotMeasured
            why={why()}
            instead={props.instead}
            testId={`${props.testId}-note`}
          />
        )}
      </Show>
    </div>
  );
}

/**
 * Which sentence a rate card with no figure prints, or `undefined` when the state draws itself.
 *
 * `loading` and `forbidden` fall through to `MetricAbsence`, which owns the waiting box and the
 * permission note for every metrics card on this screen. The two written out here are the two that
 * are specific to a *rate*: a deployment with no exporter, and a window a reachable exporter has
 * sampled nothing in. The second is the one that has to exist — without it a cluster whose exporter
 * is up and idle prints a cheerful figure of nothing at all.
 */
function rateAbsence(state: Fetched<ThroughputSeries>, noun: string): string | undefined {
  switch (state.kind) {
    case "not-configured":
      return notConfiguredSentence(noun);
    case "failed":
      return `${state.message} (${state.code})`;
    case "ready":
    case "stale":
      return (
        "Nothing has been sampled in this window yet, so there is no current rate to print — " +
        "a blank window rather than a rate of zero."
      );
    case "loading":
    case "forbidden":
      return undefined;
  }
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
            {/* Keyed by the broker's id, not by the default identity of the row object. `model.ts`
                builds a fresh `BrokerBar` on every read, so identity keying replaced all three rows
                on every poll — measured — and the sentence that used to sit here claiming a broker
                keeps its DOM node was simply false. With the id as the key it is true: the row
                stays, its accessor changes, and the bar animates from where it was rather than
                restarting its transition from empty. */}
            <For each={brokers()} keyed={(broker) => broker.id}>
              {(broker) => <BrokerRow broker={broker()} />}
            </For>
          </ul>
        </Show>
      )}
    </Show>
  );
}

/**
 * What the disk bar prints when there is no percentage to print.
 *
 * Exported so that a case can assert the words rather than a string it typed itself, and so that
 * the two renderings of an unmeasurable disk on this screen — this bar and the storage card's
 * detail line — cannot drift into two different admissions.
 */
export const UNMEASURED_DISK = "not measured";

/**
 * One broker's row.
 *
 * A component rather than the body of the `For` above, because a keyed `For` hands its child an
 * *accessor* and TypeScript cannot narrow a discriminated union across two calls of one — the disk
 * reading is read four times here. Through a prop it is one reference chain, narrows once, and the
 * JSX compiler still makes it a getter, so the row stays as fine-grained as it was.
 */
function BrokerRow(props: { readonly broker: BrokerBar }): JSX.Element {
  return (
    <li class="kui-broker-health__row">
      {/* The broker's name, drawn. `ProgressBar` puts its `label` on `aria-label` and nowhere else
          — correct for a bare bar, wrong here — so a row that relied on it would name every broker
          to a screen reader and none to anybody looking at the screen. The design draws the name
          and its detail on a line above the bar, which is what this is. */}
      <p class="kui-broker-health__head">
        <span class="kui-broker-health__name">{props.broker.name}</span>
        <span class="kui-broker-health__detail">{props.broker.detail}</span>
      </p>
      <ProgressBar
        /* The accessible name still says which broker, because a screen-reader user reaching the
           bar alone has not necessarily just read the line above it. */
        label={`${props.broker.name} disk usage`}
        caption="disk"
        /* `undefined`, not `0`, when the disk is unmeasurable. This is the brief's "a quantity bar
           must not draw zero as a full-width track" rule seen from the other side: an unknown drawn
           as zero is an empty track that reads as an empty disk, which is the most reassuring
           possible rendering of "we have no idea". */
        value={props.broker.diskPercent.kind === "value" ? props.broker.diskPercent.value : undefined}
        max={100}
        thresholds={{ warn: DISK_WARN_PERCENT, critical: DISK_CRITICAL_PERCENT }}
        /* Words, not a dash. `ProgressBar`'s own default for an unmeasurable value is
           `formatPercent(undefined)`, which is an em dash — correct for a bare bar in a table, and
           wrong here: this row reads `disk — this broker reported a zero-byte disk`, and the dash
           between the caption and the sentence reads as a missing figure rather than as the
           admission it is. The product's rule is that a figure that cannot be measured says so in
           words, and this is the one place on this screen that was still spelling it with
           punctuation. The sentence below still says *why*; this says *that*. */
        valueText={
          props.broker.diskPercent.kind === "value"
            ? formatPercent(props.broker.diskPercent.value)
            : UNMEASURED_DISK
        }
      />
      {/* The reason a bar is empty, in words, for the one case where it matters: an operator
          comparing three brokers needs to know that the blank one is unmeasured rather than idle. */}
      <Show when={props.broker.diskPercent.kind === "unknown" ? props.broker.diskPercent.why : undefined}>
        {(why) => <p class="kui-broker-health__why">{why()}</p>}
      </Show>
    </li>
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
