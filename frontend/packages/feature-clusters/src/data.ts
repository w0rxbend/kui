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
import { decodeSection, userMessage, type ApiError, type KuiApiClient, type Section } from "@kui/api";
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
 * What a screen is given: the data, and the honest state of it.
 *
 * A union rather than `{ data, loading, error }`, for the reason the rest of this codebase gives:
 * three independent fields describe eight states of which five are nonsense, and the nonsense is
 * exactly what renders when a request fails halfway.
 *
 * Named `Fetched` rather than `Loaded` because `BrokerDetail` already exports a `Loaded` with four
 * cases and a bound `onRetry`. This one has six — it carries `stale` and `not-configured`, which a
 * section can be and a tab cannot — and two types with one name in one package is how a call site
 * ends up satisfying the wrong one. They should be merged; that is a change to `BrokerDetail`'s
 * public shape and belongs in its own commit.
 */
export type Fetched<T> =
  | { readonly kind: "loading" }
  | { readonly kind: "ready"; readonly value: T }
  /** The data is real and out of date. Shown, with the reason — never hidden. */
  | { readonly kind: "stale"; readonly value: T; readonly reason: string }
  | { readonly kind: "failed"; readonly message: string; readonly code: string }
  /** The principal may not see this. Distinct from failed: retrying will never help. */
  | { readonly kind: "forbidden" }
  /** This deployment has not configured the thing. Also distinct: nothing is broken. */
  | { readonly kind: "not-configured" };

/**
 * Turns a decoded section into the screen's state.
 *
 * Exported because it is the part worth testing directly: five statuses in, six states out, and the
 * two that look alike — `forbidden` and `failed` — must never be collapsed, because one has a retry
 * button that would do nothing and the other has one that works.
 */
export function fromSection<A, B>(section: Section<A>, map: (data: A) => B): Fetched<B> {
  switch (section.status) {
    case "ok":
      return { kind: "ready", value: map(section.data) };
    case "stale":
      return {
        kind: "stale",
        value: map(section.data),
        reason: section.reason.message ?? "This is the last answer KUI received.",
      };
    case "forbidden":
      return { kind: "forbidden" };
    case "not_configured":
      return { kind: "not-configured" };
    case "unavailable":
    case "unreadable":
      return {
        kind: "failed",
        message: section.reason.message ?? "The cluster service did not answer.",
        code: section.reason.code,
      };
  }
}

/**
 * A transport or envelope failure, as the screens' state.
 *
 * `userMessage` rather than `error.message`: only the `envelope` case has one. An unreachable
 * gateway, a timeout and a body that would not decode each need a sentence of their own, and
 * reaching for `.message` on those is how a screen ends up rendering "undefined" at the exact
 * moment something is wrong.
 */
function failure(error: ApiError): Fetched<never> {
  return {
    kind: "failed",
    message: userMessage(error),
    // The code is what somebody quotes when they ask for help. Only an envelope carries one; the
    // other three kinds are their own code, which is more use than an empty string.
    code: error.kind === "envelope" ? error.code : error.kind.toUpperCase(),
  };
}

/** `null` rather than `0` for every figure that could not be read. See the header. */
function figure(value: number | null | undefined): number | null {
  return typeof value === "number" ? value : null;
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
  if (!answer.ok) return failure(answer.error);
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
  if (!answer.ok) return failure(answer.error);
  const section = decodeSection<readonly BrokerPayload[]>(answer.value.brokers);
  return fromSection(section, (rows) => rows.map(toBroker));
}
