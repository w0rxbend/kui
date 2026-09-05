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
import { decodeSection, type ApiResult, type KuiApiClient } from "@kui/api";
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

/** One entry of the cluster list. The row itself is nested under `cluster`; see `toClusterSummary`. */
interface ClusterEntryPayload {
  readonly cluster: {
    readonly id: string;
    readonly name: string;
    readonly readOnly: boolean;
    readonly bootstrapServers: string;
    readonly summary: unknown;
  };
  /** The topic service's answer for this cluster: counts, and the largest few. Its own section. */
  readonly topics?: unknown;
}

/** The topic counts the list endpoint returns per cluster. */
interface ClusterTopicsPayload {
  readonly topicCount?: number | null;
  readonly partitionCount?: number | null;
}

/**
 * What the brokers endpoint reports, once decoded.
 *
 * These names are the server's, taken from a recorded response
 * (`src/recorded/brokers.json`) rather than guessed. They are worth reading carefully, because a
 * plausible guess is wrong for almost every one of them: it is `leaderCount`, not
 * `leaderPartitions`; `replicaCount`, not `replicaPartitions`; `diskUsageBytes`, not
 * `diskUsedBytes`. A mismatch does not fail — every field falls back and the whole card renders as
 * em dashes, which reads as "this broker did not answer".
 */
interface BrokerPayload {
  readonly id: number;
  readonly host: string;
  readonly port: number;
  readonly rack?: string | null;
  readonly isController?: boolean;
  readonly leaderCount?: number | null;
  readonly replicaCount?: number | null;
  readonly partitionCount?: number | null;
  readonly diskUsageBytes?: number | null;
  readonly segmentCount?: number | null;
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

/**
 * One list entry, as the screen's row.
 *
 * The entry is *not* the cluster: it is `{ cluster, capability, topics, consumerGroups }`, where
 * each of the last three is its own section. That shape is the whole design — the cluster's
 * identity comes from configuration and cannot fail, while its scrape, its topic counts and its
 * group counts come from three different services and can each fail on their own. A row therefore
 * draws with whichever of them arrived.
 *
 * Two sections are read here. `cluster.summary` carries the scrape; `topics` carries the counts,
 * and reading it is what puts a topic count on the row at all — taking the partition count from the
 * scrape instead gives `onlinePartitionCount`, which the quickstart's own broker reports as `null`.
 */
function toClusterSummary(entry: ClusterEntryPayload): ClusterSummary {
  const row = entry.cluster;
  const section = decodeSection<ClusterSummaryPayload>(row.summary);
  const summary = section.status === "ok" || section.status === "stale" ? section.data : undefined;

  const topicsSection = decodeSection<ClusterTopicsPayload>(entry.topics);
  const topics =
    topicsSection.status === "ok" || topicsSection.status === "stale"
      ? topicsSection.data
      : undefined;

  return {
    id: row.id,
    name: row.name,
    health: healthOf(summary),
    version: summary?.version ?? null,
    // The wire reports how many brokers answered, not how many are configured, so "3 of 3" is the
    // only honest reading when a scrape succeeded — and both are absent when it did not.
    brokersOnline: figure(summary?.brokerCount),
    brokersTotal: figure(summary?.brokerCount),
    topics: figure(topics?.topicCount),
    // The topic service's count, not the scrape's `onlinePartitionCount` — a single-broker cluster
    // reports the latter as `null`, so taking it from there is a dash on a screen that has the
    // number a few bytes away.
    partitions: figure(topics?.partitionCount),
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
    // separate question, so health is derived from what is missing rather than assumed from its
    // presence. `replicaCount` is the field that is populated on every cluster KUI has seen —
    // `leaderCount` is null on a single-broker cluster — so it is the one that decides.
    health:
      payload.replicaCount === null || payload.replicaCount === undefined ? "unknown" : "healthy",
    leaderPartitions: figure(payload.leaderCount),
    replicaPartitions: figure(payload.replicaCount),
    // Not on the wire. The brokers endpoint reports no out-of-sync count per broker; it is a
    // cluster-level figure on the scrape. `null` says so, rather than `0` claiming everything is in
    // sync on evidence nobody produced.
    outOfSyncReplicas: null,
    diskUsedBytes: figure(payload.diskUsageBytes),
    // Also not on the wire: KUI reports how much a broker's log directories hold, never how large
    // the disk under them is — that is the host's business and the admin protocol does not expose
    // it. `ProgressBar` draws a bar with no total as "we cannot measure this", which is exactly
    // right, and is why this is `null` rather than an invented denominator.
    diskTotalBytes: null,
  };
}

/** Every configured cluster. Rows whose scrape failed are kept, with their figures absent. */
export async function fetchClusters(
  api: KuiApiClient,
): Promise<Fetched<readonly ClusterSummary[]>> {
  const answer = await api.get("/api/v1/clusters", {});
  if (!answer.ok) return apiFailure(answer.error);
  const section = decodeSection<readonly ClusterEntryPayload[]>(answer.value.clusters);
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

/* ---------------------------------------------------------------------------------------------- */
/* Managing the registration itself                                                                 */
/* ---------------------------------------------------------------------------------------------- */

/**
 * One configured cluster, for the screen that adds, edits and removes them.
 *
 * Read from the same `GET /clusters` document the list uses, but for a different purpose: this cares
 * about the *registration* — where it came from and which version of it the server holds — where the
 * list cares about how the cluster is doing.
 */
export interface ManagedClusterRow {
  readonly id: string;
  readonly name: string;
  readonly bootstrapServers: string;
  readonly readOnly: boolean;
  /**
   * Where this cluster's definition comes from, and therefore what may be done to it.
   *
   * `static` — only the file this process was started with names it.
   * `stored` — only a record in KUI's metadata store.
   * `staticThenStored` — both, and the stored record won.
   */
  readonly origin: ClusterOrigin;
  /**
   * The optimistic-concurrency version, echoed back as `If-Match`.
   *
   * `undefined` when the server did not send one, which is not zero: a save then goes without the
   * header and the server decides. Inventing a version would be claiming to have read a record that
   * was never read.
   */
  readonly version: number | undefined;
  readonly security: { readonly protocol: string; readonly mechanism: string | null };
}

/** The three origins the cluster registry distinguishes, plus what an unknown word becomes. */
export type ClusterOrigin = "static" | "stored" | "staticThenStored" | "unknown";

/**
 * Reading the origin, conservatively.
 *
 * A word the browser does not recognise becomes `unknown`, which this screen treats as *not
 * editable*. That is the safe direction and it is worth saying why: offering an edit for a cluster
 * whose definition lives in the deployment's configuration file would make the file a lie — the next
 * deployment would quietly change the cluster back — whereas withholding an edit for one that is in
 * fact editable is an inconvenience the operator can work around, and the server refuses the write
 * anyway.
 */
export function originOf(raw: string | null | undefined): ClusterOrigin {
  if (typeof raw !== "string") return "unknown";
  const lower = raw.toLowerCase();
  if (lower === "stored") return "stored";
  if (lower === "static") return "static";
  if (lower === "staticthenstored") return "staticThenStored";
  return "unknown";
}

/** Whether this cluster's definition can be replaced from the browser. */
export function isEditable(origin: ClusterOrigin): boolean {
  // `staticThenStored` included: a stored record already wins over the file, so replacing it changes
  // what is actually in force.
  return origin === "stored" || origin === "staticThenStored";
}

/**
 * Whether it can be removed.
 *
 * Only a purely stored cluster. Deleting the store record for one the file also names would leave
 * the row on screen — which reads as a delete that silently failed — so the server refuses it, and
 * so does this screen, before the click rather than after.
 */
export function isRemovable(origin: ClusterOrigin): boolean {
  return origin === "stored";
}

interface ManagedRowPayload {
  readonly id: string;
  readonly name: string;
  readonly bootstrapServers: string;
  readonly readOnly: boolean;
  readonly origin?: string | null;
  readonly version?: number | null;
  readonly security?: { readonly protocol?: string; readonly mechanism?: string | null };
}

export async function fetchManagedClusters(
  api: KuiApiClient,
): Promise<Fetched<readonly ManagedClusterRow[]>> {
  const answer = await api.get("/api/v1/clusters", {});
  if (!answer.ok) return apiFailure(answer.error);

  /*
   * A section, not a bare array. `GET /clusters` answers `{"clusters": {"status": "ok", "data": …}}`
   * — the same ADR-039 envelope every aggregated response uses — and reading `.clusters` as a list
   * produced an empty screen that looked exactly like a deployment with no clusters configured.
   */
  const section = decodeSection<readonly { readonly cluster?: ManagedRowPayload }[]>(
    (answer.value as { clusters?: unknown }).clusters,
  );
  return fromSection(section, (entries) =>
    entries
      .map((entry) => entry.cluster)
      .filter((row): row is ManagedRowPayload => row !== undefined)
      .map((row) => ({
        id: row.id,
        name: row.name,
        bootstrapServers: row.bootstrapServers,
        readOnly: row.readOnly,
        origin: originOf(row.origin),
        version: typeof row.version === "number" ? row.version : undefined,
        security: {
          protocol: row.security?.protocol ?? "PLAINTEXT",
          mechanism: row.security?.mechanism ?? null,
        },
      })),
  );
}

/** Registers a cluster, or replaces one. A `PUT` replaces the whole record — there is no patch. */
export async function saveCluster(
  api: KuiApiClient,
  clusterId: string,
  request: Record<string, unknown>,
  version: number | undefined,
): Promise<ApiResult<unknown>> {
  return api.put("/api/v1/clusters/{clusterId}", {
    params: { path: { clusterId }, header: { "If-Match": ifMatch(version) } },
    body: request as never,
  });
}

/**
 * The `If-Match` value: the version being replaced, quoted, or `"0"` to create.
 *
 * Required by the contract rather than optional, and the reasoning there is worth keeping in view
 * from this side too: an unconditional write to a versioned record is a lost update waiting for a
 * second person with the same screen open. `"0"` means "create this, and fail if it already exists",
 * which is what keeps one code path here instead of two endpoints.
 *
 * The quotes are part of the value — it is an HTTP entity tag, not a number in a header.
 */
function ifMatch(version: number | undefined): string {
  return version === undefined ? '"0"' : `"${version}"`;
}

export async function deleteCluster(
  api: KuiApiClient,
  clusterId: string,
  version: number | undefined,
): Promise<ApiResult<unknown>> {
  return api.delete("/api/v1/clusters/{clusterId}", {
    params: { path: { clusterId }, header: { "If-Match": ifMatch(version) } },
  });
}

/**
 * Asks whether KUI could reach a cluster with these settings, without registering anything.
 *
 * Takes the same request the save would, which is what makes it worth having: it exercises the
 * broker addresses, the protocol and the credentials exactly as the save will use them.
 */
export async function testConnection(
  api: KuiApiClient,
  request: Record<string, unknown>,
): Promise<
  ApiResult<{ readonly reachable: boolean; readonly status: string; readonly detail?: string }>
> {
  const answer = await api.post("/api/v1/clusters/connection-test", { body: request as never });
  if (!answer.ok) return answer;
  return {
    ok: true,
    value: {
      reachable: answer.value.reachable,
      status: answer.value.status,
      ...(answer.value.detail === undefined ? {} : { detail: answer.value.detail }),
    },
  };
}
