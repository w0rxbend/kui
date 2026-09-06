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
