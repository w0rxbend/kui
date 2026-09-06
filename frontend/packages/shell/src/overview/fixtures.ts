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
