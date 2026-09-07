/**
 * The cluster states the overview has to be right about.
 *
 * Each of these is a *situation*, not a blob of plausible data. They exist so that the states that
 * are hardest to reach against a real cluster — a broker too old to report disk sizes, a consumer
 * service that is down while everything else is up, a cluster mid-incident — are one line away in a
 * story and in a test.
 */

import type { OverviewData } from "./load.js";
import { pending, unknown, value } from "./reading.js";
import type { Broker, ClusterSummary, ConsumerGroup, LogDir } from "./model.js";

const summary = (over: Partial<ClusterSummary> = {}): ClusterSummary => ({
  version: "3.7.0",
  controllerId: 1,
  brokerCount: 3,
  onlinePartitionCount: 1536,
  offlinePartitionCount: 0,
  underReplicatedPartitionCount: 0,
  scrapedAt: "2026-09-05T12:00:00Z",
  ...over,
});

/** The three brokers from screenshot `01`, with its leader counts. */
export const BROKERS: readonly Broker[] = [
  { id: 1, host: "broker-1.kyiv", port: 9092, isController: true, leaderCount: 512 },
  { id: 2, host: "broker-2.kyiv", port: 9092, isController: false, leaderCount: 498 },
  { id: 3, host: "broker-3.kyiv", port: 9092, isController: false, leaderCount: 526 },
];

/**
 * Disks at 61%, 58% and 83% — the third one over the amber threshold, as the design draws it.
 *
 * The replica breakdown is what the storage card attributes by, and the names are chosen to exercise
 * the fold rather than to look plausible: three ordinary prefixes that each really continue past
 * their segment (so the rows read `orders.*` and not `orders`), and one internal topic, which has to
 * come out under `internal` however its own name is spelled. The sizes sum to a little under each
 * broker's used bytes, because a Kafka disk holds more than its partitions and a fixture whose
 * segments exactly filled the used total would hide the remainder the bar is supposed to show.
 */
export const LOG_DIRS: readonly LogDir[] = [
  {
    brokerId: 1,
    path: "/var/lib/kafka",
    totalBytes: 1000,
    usableBytes: 390,
    replicas: [
      { topic: "orders.payments", sizeBytes: 250 },
      { topic: "analytics.clicks", sizeBytes: 180 },
      { topic: "inventory.stock", sizeBytes: 90 },
      { topic: "__consumer_offsets", sizeBytes: 40 },
    ],
  },
  {
    brokerId: 2,
    path: "/var/lib/kafka",
    totalBytes: 1000,
    usableBytes: 420,
    replicas: [
      { topic: "orders.payments", sizeBytes: 240 },
      { topic: "analytics.clicks", sizeBytes: 170 },
      { topic: "inventory.stock", sizeBytes: 85 },
      { topic: "__consumer_offsets", sizeBytes: 35 },
    ],
  },
  {
    brokerId: 3,
    path: "/var/lib/kafka",
    totalBytes: 1000,
    usableBytes: 170,
    replicas: [
      { topic: "orders.payments", sizeBytes: 300 },
      { topic: "orders.refunds", sizeBytes: 120 },
      { topic: "analytics.clicks", sizeBytes: 200 },
      { topic: "inventory.stock", sizeBytes: 100 },
      { topic: "__consumer_offsets", sizeBytes: 40 },
    ],
  },
];

export const GROUPS: readonly ConsumerGroup[] = [
  { groupId: "clickstream-etl", state: "STABLE", totalLag: 3861 },
  { groupId: "fraud-detector", state: "STABLE", totalLag: 333 },
  { groupId: "email-dispatcher", state: "STABLE", totalLag: 18 },
  { groupId: "payments-processor", state: "STABLE", totalLag: 0 },
];

/** The screenshot: everything answered, everything fine. */
export const HEALTHY: OverviewData = {
  summary: value(summary()),
  brokers: value(BROKERS),
  logDirs: value(LOG_DIRS),
  groups: value(GROUPS),
  topicCount: value(128),
};

/** Nothing has come back yet. Every figure is a skeleton and none is a dash. */
export const LOADING: OverviewData = {
  summary: pending(),
  brokers: pending(),
  logDirs: pending(),
  groups: pending(),
  topicCount: pending(),
};

/**
 * A cluster in trouble: partitions offline and under-replicated.
 *
 * The point of this fixture is the copy. Every cheerful sentence on the screen has to turn itself
 * off here, and a story that renders it is the fastest way to see whether one of them did not.
 */
export const UNHEALTHY: OverviewData = {
  ...HEALTHY,
  /* `brokerCount: 2` so that this fixture agrees with the drawer's own degraded fixture, which says
   * "2/3". A story whose drawer and whose stat card disagree about how many brokers are up teaches
   * a reviewer to distrust both. */
  summary: value(
    summary({
      brokerCount: 2,
      onlinePartitionCount: 1490,
      offlinePartitionCount: 46,
      underReplicatedPartitionCount: 118,
    }),
  ),
  groups: value([{ groupId: "clickstream-etl", state: "PREPARING_REBALANCE", totalLag: 2_400_910 }, ...GROUPS.slice(1)]),
};

/**
 * Kafka older than 3.3: the brokers answer, but their log directories report no capacity.
 *
 * This is the fixture for "a quantity bar must not draw zero as a full-width track", and for its
 * mirror image — an unknown disk must not draw as an empty one.
 */
export const NO_DISK_SIZES: OverviewData = {
  ...HEALTHY,
  logDirs: value(LOG_DIRS.map((dir) => ({ ...dir, totalBytes: undefined, usableBytes: undefined }))),
};

/**
 * One broker with a second directory that reports replicas and no capacity.
 *
 * This is the fixture the storage card's skip rule is argued with, and it is built so that the wrong
 * answer is *visible*: broker 1's second disk holds 400 bytes of `orders.*`, which is more than the
 * first disk's whole `orders.*` share. Counting it against a capacity that excludes that disk pushes
 * the row past the end of its own track, and `StackedBar` clamps rather than complaining — so the
 * picture would look ordinary and the ratio would be wrong. Broker 1's segments must therefore total
 * exactly what its measured directory holds.
 */
export const PARTIAL_DISKS: OverviewData = {
  ...HEALTHY,
  logDirs: value([
    ...LOG_DIRS,
    {
      brokerId: 1,
      path: "/var/lib/kafka-2",
      totalBytes: undefined,
      usableBytes: undefined,
      replicas: [{ topic: "orders.payments", sizeBytes: 400 }],
    },
  ]),
};

/** One service down, the rest up: the dashboard has to stay useful. */
export const CONSUMERS_UNAVAILABLE: OverviewData = {
  ...HEALTHY,
  groups: unknown("The consumer service is not answering."),
};

/** A broker too old to report partition counts at all. */
export const SPARSE_SUMMARY: OverviewData = {
  ...HEALTHY,
  summary: value(
    summary({
      onlinePartitionCount: undefined,
      offlinePartitionCount: undefined,
      underReplicatedPartitionCount: undefined,
    }),
  ),
};

/**
 * Directories that answered, and answered zero.
 *
 * The state that sits between `HEALTHY` and `NO_DISK_SIZES` and belongs to neither: both figures
 * are present, so the skip rule does not skip anything, and the disk is nevertheless a quantity
 * nothing can be divided by. It is a real answer — a directory read while a volume was being
 * remounted reports it — and it is the fixture the storage card and the broker-health bar have to
 * agree about, because they used to disagree here and nowhere else: the bar refused with a reason
 * while the card printed `0 B of 0 B`.
 */
export const ZERO_BYTE_DISKS: OverviewData = {
  ...HEALTHY,
  logDirs: value(LOG_DIRS.map((dir) => ({ ...dir, totalBytes: 0, usableBytes: 0 }))),
};

/* --- Throughput: the states the card has to be right about ------------------------------------- */

/** The step a 24h series is bucketed at, and the count that follows from it. Mirrors the server. */
const STEP_SECONDS = 300;
const BUCKETS_24H = 288;

/** The instant the fixture series end at. Fixed so a story looks the same on two days. */
const SERIES_END = Date.parse("2026-09-05T12:00:00Z");

/**
 * A day of throughput with a hole in it, and a measured zero right beside the hole.
 *
 * The two facts this fixture exists to keep apart are in it deliberately:
 *
 *  - bucket 0 is a **measured zero** — the exporter answered and the cluster was idle;
 *  - buckets 100 to 119 are **absent** — nothing answered, and the chart must show a gap.
 *
 * Drawn as bars they are the same picture, which is exactly why the card has a coverage strip, a
 * hidden data table and a sentence: three renderings, and only the bars are ambiguous. A fold that
 * turned `null` into `0` would make all three agree with each other and with nothing that happened.
 */
export const THROUGHPUT_WITH_A_GAP: unknown = {
  range: "24h",
  from: new Date(SERIES_END - BUCKETS_24H * STEP_SECONDS * 1000).toISOString(),
  to: new Date(SERIES_END).toISOString(),
  stepSeconds: STEP_SECONDS,
  buckets: Array.from({ length: BUCKETS_24H }, (_unused, index) => {
    const startingAt = new Date(
      SERIES_END - (BUCKETS_24H - index) * STEP_SECONDS * 1000,
    ).toISOString();
    if (index >= 100 && index < 120) {
      return { startingAt, bytesInPerSecond: null, bytesOutPerSecond: null, recordsPerSecond: null };
    }
    if (index === 0) {
      return { startingAt, bytesInPerSecond: 0, bytesOutPerSecond: 0, recordsPerSecond: 0 };
    }
    /* A shape rather than a constant, so the story shows a chart with something to look at and the
       legend's "current" chip is a different number from the first bucket's. */
    const wave = 1 + Math.sin(index / 12);
    return {
      startingAt,
      bytesInPerSecond: Math.round(4_000_000 + wave * 2_500_000),
      bytesOutPerSecond: Math.round(9_000_000 + wave * 4_000_000),
      recordsPerSecond: Math.round(1_200 + wave * 700),
    };
  }),
};

/**
 * A window whose newest measured step is a measured **zero**.
 *
 * The state a quiet cluster is in, and the one that makes the rate cards' switch a rule rather than
 * a convenience: `0 B/s` is a fact about a cluster nobody is writing to, and a card that treated it
 * as "no reading" would report an idle cluster as an unmeasured one — this screen's central mistake
 * made backwards. Short on purpose: 288 buckets are not needed to say one thing.
 */
export const THROUGHPUT_MEASURED_ZERO: unknown = {
  range: "24h",
  stepSeconds: STEP_SECONDS,
  buckets: [
    {
      startingAt: new Date(SERIES_END - 2 * STEP_SECONDS * 1000).toISOString(),
      bytesInPerSecond: 4_000,
      bytesOutPerSecond: 9_000,
    },
    {
      startingAt: new Date(SERIES_END - STEP_SECONDS * 1000).toISOString(),
      bytesInPerSecond: 0,
      bytesOutPerSecond: 0,
    },
  ],
};

/** A source KUI can reach and has never managed to sample: the full axis, and nothing on it. */
export const THROUGHPUT_ALL_ABSENT: unknown = {
  range: "24h",
  from: new Date(SERIES_END - BUCKETS_24H * STEP_SECONDS * 1000).toISOString(),
  to: new Date(SERIES_END).toISOString(),
  stepSeconds: STEP_SECONDS,
  buckets: Array.from({ length: BUCKETS_24H }, (_unused, index) => ({
    startingAt: new Date(SERIES_END - (BUCKETS_24H - index) * STEP_SECONDS * 1000).toISOString(),
    bytesInPerSecond: null,
    bytesOutPerSecond: null,
    recordsPerSecond: null,
  })),
};

/** The endpoint's whole answer, wrapped as the gateway wraps it. */
export const throughputBody = (section: unknown): unknown => ({ throughput: section });

/** `ok`, carrying a series. */
export const throughputOk = (series: unknown): unknown =>
  throughputBody({ status: "ok", data: series, fetchedAt: "2026-09-05T12:00:00Z" });

/** The common answer for a deployment that has configured no exporter. Not a failure. */
export const THROUGHPUT_NOT_CONFIGURED: unknown = throughputBody({ status: "not_configured" });

/**
 * An exporter that stopped answering. A failure, and one a retry might fix.
 *
 * `reason` is the code and `message` is its sibling, which is how `Section.Unavailable` is written
 * on the wire — not a nested `{code, message}`. A fixture that nested them decodes to the reason
 * `"unknown"` with no message, and the card would draw the generic fallback while the case looked
 * like it was asserting the registry's own words.
 */
export const THROUGHPUT_UNAVAILABLE: unknown = throughputBody({
  status: "unavailable",
  reason: "KUI-UPSTREAM-UNAVAILABLE",
  message: "The metrics exporter did not answer.",
});

/**
 * The principal may not read this cluster's metrics.
 *
 * `MetricsMapping.sectionOf` cannot produce this today — it maps a reading to `ok`,
 * `not_configured` or `unavailable` and nothing else — but `Section` has five statuses and the
 * gateway's capability fold is entitled to any of them, so the browser has to be able to draw all
 * five. A status this build refuses to draw is a blank card on the day a service starts sending it.
 */
export const THROUGHPUT_FORBIDDEN: unknown = throughputBody({ status: "forbidden" });

/**
 * The exporter has stopped answering and the buffer still holds the last window.
 *
 * The state nothing on this screen rendered before this wave — no fixture, no story, no render case
 * — which is how `ThroughputCard.captionOf`'s stale branch came to be deletable with 162 cases
 * green. Last-known-good data drawn as though it were current, with no badge (deliberately: the
 * badge needs an `asOf` this state does not carry) and no sentence either, is the same defect class
 * the brokers screen was repaired for in wave 4.
 */
export const THROUGHPUT_STALE: unknown = throughputBody({
  status: "stale",
  data: THROUGHPUT_WITH_A_GAP,
  fetchedAt: "2026-09-05T11:58:00Z",
  reason: "KUI-UPSTREAM-UNAVAILABLE",
  message: "The exporter has not answered since 11:58.",
});

/* --- Latency: the states the p99 card has to be right about ------------------------------------ */

/**
 * A day of p99 latency with the same hole the throughput fixture has, in the same place.
 *
 * Deliberately the same buckets: an operator's first move on seeing a latency spike is to look at
 * the throughput chart above it for the same hour, and two fixtures with holes in different places
 * would make every story on this tab quietly incomparable. Bucket 0 is a **measured** 0.4 ms rather
 * than a zero, because a broker answering inside its own clock resolution is a fact and not an
 * absence.
 */
export const LATENCY_WITH_A_GAP: unknown = {
  range: "24h",
  stepSeconds: STEP_SECONDS,
  buckets: Array.from({ length: BUCKETS_24H }, (_unused, index) => {
    const startingAt = new Date(SERIES_END - (BUCKETS_24H - index) * STEP_SECONDS * 1000).toISOString();
    if (index >= 100 && index < 120) {
      return { startingAt, produceP99Millis: null, fetchP99Millis: null };
    }
    const wave = 1 + Math.sin(index / 12);
    return {
      startingAt,
      produceP99Millis: Math.round((6 + wave * 4) * 10) / 10,
      fetchP99Millis: Math.round((11 + wave * 7) * 10) / 10,
    };
  }),
};

/** A reachable exporter that has never served a percentile: the whole axis, and nothing on it. */
export const LATENCY_ALL_ABSENT: unknown = {
  range: "24h",
  stepSeconds: STEP_SECONDS,
  buckets: Array.from({ length: BUCKETS_24H }, (_unused, index) => ({
    startingAt: new Date(SERIES_END - (BUCKETS_24H - index) * STEP_SECONDS * 1000).toISOString(),
    produceP99Millis: null,
    fetchP99Millis: null,
  })),
};

export const latencyBody = (section: unknown): unknown => ({ latency: section });
export const latencyOk = (series: unknown): unknown =>
  latencyBody({ status: "ok", data: series, fetchedAt: "2026-09-05T12:00:00Z" });
export const LATENCY_NOT_CONFIGURED: unknown = latencyBody({ status: "not_configured" });

/* --- Request handlers -------------------------------------------------------------------------- */

/**
 * The design's three sub-tiles (§3.4), as a broker can actually answer them.
 *
 * Two ratios and a **count**, which is the whole point of the fixture. §3.4 draws "38% PURGATORY";
 * `DelayedOperationPurgatory` publishes a queue length with no ceiling, so the third reading arrives
 * as `count` with its own unit and the card must not draw it as a ring. The two ratios are `0..1`
 * and not pre-formatted percentages, so a fold that forgot to multiply would draw 0.71% idle.
 */
export const HANDLER_READINGS: unknown = {
  readings: [
    { id: "network-idle", label: "NETWORK IDLE", ratio: 0.71 },
    { id: "io-idle", label: "IO IDLE", ratio: 0.64 },
    { id: "purgatory", label: "PURGATORY", count: 38, unit: "operations" },
  ],
};

/** A ratio the exporter served the name of and not the value. Draws the track and an em dash. */
export const HANDLER_ONE_ABSENT: unknown = {
  readings: [
    { id: "network-idle", label: "NETWORK IDLE", ratio: 0.71 },
    { id: "io-idle", label: "IO IDLE", ratio: null },
  ],
};

export const handlersBody = (section: unknown): unknown => ({ requestHandlers: section });
export const handlersOk = (document: unknown): unknown =>
  handlersBody({ status: "ok", data: document, fetchedAt: "2026-09-05T12:00:00Z" });
export const HANDLERS_NOT_CONFIGURED: unknown = handlersBody({ status: "not_configured" });

/* --- Top producers ------------------------------------------------------------------------------ */

/**
 * What an exporter without client quotas can answer: a per-**topic** byte rate.
 *
 * §4 draws "Top producers · client.id" and a broker publishes no per-`client.id` rate unless quotas
 * are configured. This is the fixture for the card's title following the data rather than the
 * design, which is wave 5's rule 7 on the screen.
 */
export const PRODUCERS_BY_TOPIC: unknown = {
  entries: [
    { topic: "orders.payments", bytesPerSecond: 5_400_000 },
    { topic: "analytics.clicks", bytesPerSecond: 3_100_000 },
    { topic: "inventory.stock", bytesPerSecond: 820_000 },
    { topic: "orders.refunds", bytesPerSecond: 240_000 },
    { topic: "audit.trail", bytesPerSecond: null },
  ],
};

/** The other half of the same rule: a deployment that *does* configure quotas answers client ids. */
export const PRODUCERS_BY_CLIENT: unknown = {
  entries: [
    { clientId: "checkout-svc", bytesPerSecond: 4_800_000 },
    { clientId: "payments", bytesPerSecond: 2_200_000 },
  ],
};

export const producersBody = (section: unknown): unknown => ({ producers: section });
export const producersOk = (document: unknown): unknown =>
  producersBody({ status: "ok", data: document, fetchedAt: "2026-09-05T12:00:00Z" });
export const PRODUCERS_NOT_CONFIGURED: unknown = producersBody({ status: "not_configured" });

/* --- Record size --------------------------------------------------------------------------------- */

/** The one figure a broker publishes about record size: a mean, as bytes in over messages in. */
export const RECORD_SIZE_MEAN: unknown = { meanBytes: 1_180, window: "Averaged over the last hour." };

/** A source that answered without a mean. Words rather than a dash — see `RecordSizeCard`. */
export const RECORD_SIZE_ABSENT: unknown = { meanBytes: null };

export const recordSizeBody = (section: unknown): unknown => ({ recordSize: section });
export const recordSizeOk = (document: unknown): unknown =>
  recordSizeBody({ status: "ok", data: document, fetchedAt: "2026-09-05T12:00:00Z" });
export const RECORD_SIZE_NOT_CONFIGURED: unknown = recordSizeBody({ status: "not_configured" });
