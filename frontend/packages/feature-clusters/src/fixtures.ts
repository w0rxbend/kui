/**
 * The data the cluster and broker stories and tests draw.
 *
 * The three brokers are the dashboard's (`01`): 61%, 58% and 83% disk, with broker 1 the controller,
 * so a story can be put beside the PNG. Everything else here exists because it is a state nobody can
 * produce on a healthy cluster — a broker that is down, a disk past the critical threshold, a log
 * directory that did not answer, a sensitive setting, and every value that can be absent, absent.
 */

import type { Broker, ClusterSummary, ConfigEntry, LogDir } from "./model.js";

const GB = 1_000_000_000;

function broker(row: Partial<Broker> & Pick<Broker, "id" | "host">): Broker {
  return {
    port: 9092,
    rack: null,
    isController: false,
    health: "healthy",
    leaderPartitions: 512,
    replicaPartitions: 1_536,
    outOfSyncReplicas: 0,
    diskUsedBytes: 610 * GB,
    diskTotalBytes: 1_000 * GB,
    ...row,
  };
}

/** The dashboard panel's three brokers, with their measured disk percentages. */
export const SAMPLE_BROKERS: readonly Broker[] = [
  broker({ id: 1, host: "broker-1.kyiv", isController: true, diskUsedBytes: 610 * GB, leaderPartitions: 512 }),
  broker({ id: 2, host: "broker-2.kyiv", diskUsedBytes: 580 * GB, leaderPartitions: 498 }),
  broker({ id: 3, host: "broker-3.kyiv", diskUsedBytes: 830 * GB, leaderPartitions: 526 }),
];

/** A rack-aware cluster, so the story that keeps the RACK column has something to keep it for. */
export const RACKED_BROKERS: readonly Broker[] = SAMPLE_BROKERS.map((one, index) => ({
  ...one,
  rack: `eu-central-1${"abc"[index] ?? "a"}`,
}));

/** Everything that can go wrong on one screen: down, unreachable, full, and unreadable. */
export const DEGRADED_BROKERS: readonly Broker[] = [
  broker({ id: 1, host: "broker-1.kyiv", isController: true, diskUsedBytes: 610 * GB }),
  broker({ id: 2, host: "broker-2.kyiv", health: "offline", leaderPartitions: 0, replicaPartitions: 0, outOfSyncReplicas: null, diskUsedBytes: null, diskTotalBytes: null }),
  broker({ id: 3, host: "broker-3.kyiv", health: "unknown", leaderPartitions: null, replicaPartitions: null, outOfSyncReplicas: null, diskUsedBytes: null, diskTotalBytes: null }),
  broker({ id: 4, host: "broker-4.kyiv", health: "degraded", outOfSyncReplicas: 47, diskUsedBytes: 940 * GB, replicaPartitions: 3_140 }),
];

export const SAMPLE_CLUSTERS: readonly ClusterSummary[] = [
  {
    id: "prod-kyiv-01",
    name: "prod-kyiv-01",
    health: "healthy",
    version: "v3.7.0",
    brokersOnline: 3,
    brokersTotal: 3,
    topics: 128,
    partitions: 1_536,
    underReplicatedPartitions: 0,
    readOnly: false,
    observedAt: new Date("2026-09-05T09:14:00Z"),
  },
  {
    id: "staging-fra",
    name: "staging-fra",
    health: "degraded",
    version: "v3.6.1",
    brokersOnline: 2,
    brokersTotal: 3,
    topics: 74,
    partitions: 611,
    underReplicatedPartitions: 47,
    readOnly: true,
    observedAt: new Date("2026-09-05T09:10:00Z"),
  },
  {
    id: "archive-eu",
    name: "archive-eu",
    health: "unknown",
    version: null,
    brokersOnline: null,
    brokersTotal: null,
    topics: null,
    partitions: null,
    underReplicatedPartitions: null,
    readOnly: true,
    observedAt: null,
  },
];

export const SAMPLE_LOG_DIRS: readonly LogDir[] = [
  { path: "/var/lib/kafka/data-0", sizeBytes: 412 * GB, partitions: 780, error: null },
  { path: "/var/lib/kafka/data-1", sizeBytes: 198 * GB, partitions: 512, error: null },
  { path: "/mnt/nvme1/kafka", sizeBytes: 0, partitions: 0, error: null },
  { path: "/mnt/nvme2/kafka", sizeBytes: null, partitions: null, error: "KafkaStorageException: the directory is offline." },
];

function config(row: Partial<ConfigEntry> & Pick<ConfigEntry, "name">): ConfigEntry {
  return { value: "", sensitive: false, source: "DEFAULT", overridden: false, ...row };
}

export const SAMPLE_CONFIGS: readonly ConfigEntry[] = [
  config({ name: "log.retention.hours", value: "72", source: "DYNAMIC_BROKER", overridden: true }),
  config({ name: "num.replica.fetchers", value: "4", source: "DYNAMIC_DEFAULT", overridden: true }),
  config({ name: "listeners", value: "PLAINTEXT://broker-1.kyiv:9092", source: "STATIC", overridden: true }),
  config({ name: "ssl.keystore.password", value: null, sensitive: true, source: "STATIC", overridden: true }),
  config({ name: "log.segment.bytes", value: "1073741824", source: "DEFAULT" }),
  config({ name: "compression.type", value: "producer", source: "DEFAULT" }),
  config({ name: "message.max.bytes", value: "1048588", source: "DEFAULT" }),
  config({ name: "auto.create.topics.enable", value: "false", source: "STATIC", overridden: true }),
  config({ name: "unclean.leader.election.enable", value: "", source: "DEFAULT" }),
];
