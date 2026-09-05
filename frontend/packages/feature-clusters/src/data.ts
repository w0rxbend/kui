/**
 * The cluster feature's data layer: what it asks the gateway for, and how the answers become the
 * view models the screens already take.
 *
 * ## Why the mapping lives here and not in the screens
 *
 * `ClusterList` and `BrokerList` take finished view models and fetch nothing. That is what lets
 * every state of those screens — a cluster that is not answering, a broker whose disk could not be
 * read, a principal who may look but not change — be rendered in a story and asserted in a test
 * with no server anywhere. Moving a single `await` into a screen would take all of that away, so
 * the boundary is kept sharp: this file talks to the gateway, the screens draw.
 *
 * ## Sections, and the two failures they distinguish
 *
 * Every aggregated response arrives in *sections* (ADR-039): the request succeeded, and each part
 * of the answer separately says whether it did. A cluster row always carries its identity — the id,
 * the name, the bootstrap servers, whether it is read-only — because that came from configuration
 * and cannot fail. Its `summary` is a section, because that came from a live scrape and can.
 *
 * So a cluster whose brokers did not answer still appears in the list, named, with its figures
 * absent rather than zero. The alternative — dropping the row, or filling it with zeroes — is the
 * one an operator cannot recover from: a cluster that has vanished from the list looks like a
 * cluster that was deleted, and a cluster reporting 0 brokers looks like a cluster that is down.
 * They are three different situations and the product must not merge them.
 *
 * ## Health is derived here, once
 *
 * `Health` is the screens' vocabulary, not the wire's. The wire says how many partitions are
 * offline and how many are under-replicated; deciding that "any offline partition is unhealthy" is
 * a product judgement, and it is made in one place so that the list, the broker page and the
 * environment rail cannot disagree about what a healthy cluster is.
 */
import { decodeSection, type KuiApiClient } from "@kui/api";
import { apiFailure, figure, fromSection, type Fetched } from "@kui/kernel";
import type { Broker, ClusterSummary, Health } from "./model.js";

/**
 * What the gateway reports about one cluster once a scrape has succeeded.
 *
 * Declared here because the generated types stop at `unknown` for every section payload: the server
 * documents `Section[A]` with `Schema.any`, so the browser's generated type cannot see inside it
 * (`packages/api/src/section.ts` explains why, and records the server-side fix). This is the shape
 * `ClusterSummaryDto` really has, and `decodeSection` is the one boundary at which it is asserted.
 */
interface ClusterSummaryPayload {
  readonly kafkaClusterId?: string | null;
  readonly version?: string | null;
  readonly controllerId?: number | null;
  readonly controllerKind?: string;
  readonly brokerCount?: number;
  readonly onlinePartitionCount?: number | null;
  readonly offlinePartitionCount?: number | null;
  readonly underReplicatedPartitionCount?: number | null;
  readonly totalDiskUsageBytes?: number | null;
  readonly scrapedAt?: string;
}

interface ClusterRowPayload {
  readonly id: string;
  readonly name: string;
  readonly readOnly: boolean;
  readonly bootstrapServers: string;
  readonly summary: unknown;
}

/** What the brokers endpoint reports, once decoded. */
interface BrokerPayload {
  readonly id: number;
  readonly host: string;
  readonly port: number;
  readonly rack?: string | null;
  readonly isController?: boolean;
  readonly leaderPartitions?: number | null;
  readonly replicaPartitions?: number | null;
  readonly outOfSyncReplicas?: number | null;
  readonly diskUsedBytes?: number | null;
  readonly diskTotalBytes?: number | null;
}

/**
 * How healthy a cluster is, from its scrape.
 *
 * The order matters and is a product judgement: an offline partition is not readable and not
 * writable, so it outranks an under-replicated one, which is readable and at risk. A cluster whose
 * summary never arrived is `unknown` — not `unhealthy`, because we did not establish that.
 */
export function healthOf(summary: ClusterSummaryPayload | undefined): Health {
  if (summary === undefined) return "unknown";
  if ((summary.offlinePartitionCount ?? 0) > 0) return "offline";
  if ((summary.underReplicatedPartitionCount ?? 0) > 0) return "degraded";
  return "healthy";
}

function toClusterSummary(row: ClusterRowPayload): ClusterSummary {
  const section = decodeSection<ClusterSummaryPayload>(row.summary);
  const summary = section.status === "ok" || section.status === "stale" ? section.data : undefined;

  return {
    id: row.id,
    name: row.name,
    health: healthOf(summary),
    version: summary?.version ?? null,
    // The wire reports how many brokers answered, not how many are configured, so "3 of 3" is the
    // only honest reading when a scrape succeeded — and both are absent when it did not.
    brokersOnline: figure(summary?.brokerCount),
    brokersTotal: figure(summary?.brokerCount),
    topics: null,
    partitions: figure(summary?.onlinePartitionCount),
    underReplicatedPartitions: figure(summary?.underReplicatedPartitionCount),
    readOnly: row.readOnly,
    observedAt: summary?.scrapedAt === undefined ? null : new Date(summary.scrapedAt),
  };
}

function toBroker(payload: BrokerPayload): Broker {
  return {
    id: payload.id,
    host: payload.host,
    port: payload.port,
    rack: payload.rack ?? null,
    isController: payload.isController === true,
    // A broker in the list answered `describeCluster`; whether its own metrics answered is a
    // separate question, which is why the health is derived from what is missing rather than
    // assumed from its presence.
    health: payload.leaderPartitions === null || payload.leaderPartitions === undefined ? "unknown" : "healthy",
    leaderPartitions: figure(payload.leaderPartitions),
    replicaPartitions: figure(payload.replicaPartitions),
    outOfSyncReplicas: figure(payload.outOfSyncReplicas),
    diskUsedBytes: figure(payload.diskUsedBytes),
    diskTotalBytes: figure(payload.diskTotalBytes),
  };
}

/** Every configured cluster. Rows whose scrape failed are kept, with their figures absent. */
export async function fetchClusters(api: KuiApiClient): Promise<Fetched<readonly ClusterSummary[]>> {
  const answer = await api.get("/api/v1/clusters", {});
  if (!answer.ok) return apiFailure(answer.error);
  const section = decodeSection<readonly ClusterRowPayload[]>(answer.value.clusters);
  return fromSection(section, (rows) => rows.map(toClusterSummary));
}

/** One cluster's brokers. */
export async function fetchBrokers(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<readonly Broker[]>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/brokers", {
    params: { path: { clusterId } },
  });
  if (!answer.ok) return apiFailure(answer.error);
  const section = decodeSection<readonly BrokerPayload[]>(answer.value.brokers);
  return fromSection(section, (rows) => rows.map(toBroker));
}
