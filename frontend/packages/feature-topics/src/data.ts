/**
 * The topic feature's data layer: what it asks the gateway for, and how the answers become the view
 * models the screens already take.
 *
 * Every field name below came from a response a running gateway produced (`src/recorded/*.json`),
 * not from reading the DTOs. That is not belt-and-braces: the server documents each section with
 * `Schema.any`, so the payloads are `unknown` on this side and a misspelled field is a type-correct
 * `undefined`. It then renders as an em dash, because every figure in this product has a defined
 * reading for "absent" — so a mapping can be wrong in every field and look exactly like a cluster
 * that is not answering. The clusters feature shipped precisely that bug, and only running the
 * quickstart stack found it.
 */
import { decodeSection, type KuiApiClient } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";
import type { TopicHealth, TopicRow } from "./types.js";

/** One row of the topic list, as the gateway sends it. */
interface TopicRowPayload {
  readonly name: string;
  readonly internal: boolean;
  readonly partitionCount: number;
  readonly replicationFactor: number;
  readonly outOfSyncReplicas?: number | null;
  readonly offlinePartitions?: number | null;
  readonly messageCount?: number | null;
  readonly sizeBytes?: number | null;
  readonly cleanupPolicy?: string | null;
}

/** The list page: the rows, and where they sit in the whole list. */
interface TopicListPayload {
  readonly items: readonly TopicRowPayload[];
  readonly page?: {
    readonly page?: number;
    readonly pageSize?: number;
    readonly totalItems?: number;
    readonly pageCount?: number;
  } | null;
}

/** Where a page of rows sits in the list the server holds. */
export interface PageInfo {
  readonly page: number;
  readonly pageSize: number;
  /** `undefined` when the server did not count. Not zero — see `Pagination` in the kernel. */
  readonly totalItems: number | undefined;
}

/**
 * What the topic list is asked for.
 *
 * Every one of these is the *server's* to apply, not the browser's, and that is the point. The
 * screen filters and searches what it already has, which is honest for one page and wrong for a
 * cluster with four thousand topics: a search that only looks at the twenty-five rows it was given
 * is a search that lies. Threading them through means the list stays correct at any size.
 */
export interface TopicQuery {
  /**
   * Kafka's own bookkeeping topics — `__consumer_offsets` and friends — and KUI's metadata topics.
   *
   * The server excludes them by default, which made the list page's "Show internal topics"
   * checkbox unable to work at all: the data it was filtering had never contained one. Requesting
   * them and letting the screen decide what to show is what makes that control mean something.
   */
  readonly showInternal?: boolean | undefined;
  /** Substring match on the name, applied by the server across the whole list. */
  readonly q?: string | undefined;
  /** `<field>:<asc|desc>` — `name`, `partitions`, `replicationFactor`, `outOfSyncReplicas`, `size`. */
  readonly sort?: string | undefined;
  readonly page?: number | undefined;
  readonly pageSize?: number | undefined;
}

/** One partition, on the topic overview. */
export interface PartitionRow {
  readonly partition: number;
  readonly leader: number | null;
  readonly replicas: readonly number[];
  readonly inSync: readonly number[];
  readonly earliestOffset: number | null;
  readonly latestOffset: number | null;
  readonly messageCount: number | null;
  readonly sizeBytes: number | null;
}

interface PartitionPayload {
  readonly partition: number;
  readonly leader?: number | null;
  readonly replicas?: readonly { readonly broker: number; readonly leader?: boolean; readonly inSync?: boolean }[];
  readonly earliestOffset?: number | null;
  readonly latestOffset?: number | null;
  readonly messageCount?: number | null;
  readonly sizeBytes?: number | null;
}

interface TopicDetailPayload {
  readonly row: TopicRowPayload;
  readonly partitions?: readonly PartitionPayload[];
}

/** The whole topic overview, as the screen needs it. */
export interface TopicOverview {
  readonly topic: TopicRow;
  readonly partitions: readonly PartitionRow[];
}

function figure(value: number | null | undefined): number | undefined {
  // `undefined` rather than `null` here, because `TopicRow`'s optional fields are `?: number` — the
  // screens distinguish "absent" by the property being missing. The rule is the same either way and
  // it is the only rule that matters: never `0`.
  return typeof value === "number" ? value : undefined;
}

/**
 * How a topic's replication is doing.
 *
 * Three facts, in the order that decides which one wins. An offline partition has no leader — it is
 * neither readable nor writable — so it outranks a replica that is merely behind. A topic KUI could
 * not describe is `unknown`, which is deliberately *not* `offline`: one says the topic is broken,
 * the other says we do not know, and drawing the second as the first invents a fact about somebody's
 * cluster.
 */
export function healthOf(row: TopicRowPayload): TopicHealth {
  if (row.offlinePartitions === null || row.offlinePartitions === undefined) {
    // The broker did not describe it. Not a claim about the topic.
    if (row.outOfSyncReplicas === null || row.outOfSyncReplicas === undefined) return "unknown";
  }
  if ((row.offlinePartitions ?? 0) > 0) return "offline";
  if ((row.outOfSyncReplicas ?? 0) > 0) return "under-replicated";
  return "in-sync";
}

function toTopicRow(payload: TopicRowPayload): TopicRow {
  return {
    name: payload.name,
    internal: payload.internal,
    partitions: payload.partitionCount,
    replicationFactor: payload.replicationFactor,
    health: healthOf(payload),
    ...(figure(payload.messageCount) === undefined ? {} : { records: figure(payload.messageCount) }),
    ...(figure(payload.sizeBytes) === undefined ? {} : { bytes: figure(payload.sizeBytes) }),
    ...(payload.cleanupPolicy === null || payload.cleanupPolicy === undefined
      ? {}
      : { cleanupPolicy: payload.cleanupPolicy }),
  };
}

/**
 * One partition row.
 *
 * The wire sends `replicas` as objects carrying `broker`, `leader` and `inSync`; the screen wants
 * two lists of broker ids. Deriving `inSync` from the flag rather than from a separate field is
 * what keeps the two consistent — a partition cannot be in the in-sync list and not marked in sync.
 */
function toPartition(payload: PartitionPayload): PartitionRow {
  const replicas = payload.replicas ?? [];
  return {
    partition: payload.partition,
    leader: payload.leader ?? null,
    replicas: replicas.map((replica) => replica.broker),
    inSync: replicas.filter((replica) => replica.inSync === true).map((replica) => replica.broker),
    earliestOffset: payload.earliestOffset ?? null,
    latestOffset: payload.latestOffset ?? null,
    messageCount: payload.messageCount ?? null,
    sizeBytes: payload.sizeBytes ?? null,
  };
}

/**
 * One page of the topic list, and how many topics could not be described.
 *
 * The incomplete count is carried beside the data rather than folded into it, because a list that
 * is quietly four topics short is a list somebody makes decisions from without knowing it is
 * incomplete. `TopicListPage` renders it as a sentence.
 */
export interface TopicListResult {
  readonly topics: readonly TopicRow[];
  readonly incomplete: number;
  readonly page: PageInfo;
}

export async function fetchTopics(
  api: KuiApiClient,
  clusterId: string,
  query: TopicQuery = {},
): Promise<Fetched<TopicListResult>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics", {
    params: {
      path: { clusterId },
      // Only what the caller asked for. Sending `q: ""` is not the same as sending nothing — the
      // server would match every name against the empty string, which happens to be harmless here
      // and is the sort of accident that stops being harmless when a parameter gains a meaning.
      query: {
        ...(query.showInternal === undefined ? {} : { showInternal: query.showInternal }),
        ...(query.q === undefined || query.q === "" ? {} : { q: query.q }),
        ...(query.sort === undefined ? {} : { sort: query.sort }),
        ...(query.page === undefined ? {} : { page: query.page }),
        ...(query.pageSize === undefined ? {} : { pageSize: query.pageSize }),
      },
    },
  });
  if (!answer.ok) return apiFailure(answer.error);

  // `incompleteTopics` sits *outside* the section: the count of topics that could not be described
  // is known even when the ones that could be are not, so the server puts it on the envelope.
  const incomplete = typeof answer.value.incompleteTopics === "number" ? answer.value.incompleteTopics : 0;

  const section = decodeSection<TopicListPayload>(answer.value.topics);
  return fromSection(section, (listing) => ({
    topics: listing.items.map(toTopicRow),
    incomplete,
    page: {
      page: listing.page?.page ?? 1,
      pageSize: listing.page?.pageSize ?? listing.items.length,
      // `undefined`, not `0`, when the server did not count: the paginator draws numbered buttons
      // only where there is a known last page, because a guessed one sends the reader nowhere.
      totalItems: typeof listing.page?.totalItems === "number" ? listing.page.totalItems : undefined,
    },
  }));
}

export async function fetchTopicOverview(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<Fetched<TopicOverview>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics/{topicName}/overview", {
    params: { path: { clusterId, topicName } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  // The overview is five sections — topic, consumerGroups, connectors, acls, schemas — and only the
  // first is required to draw the page. The others belong to the tabs, and a page that failed
  // whole because the schema registry is down would be the opposite of ADR-039.
  const section = decodeSection<TopicDetailPayload>(answer.value.topic);
  return fromSection(section, (detail) => ({
    topic: toTopicRow(detail.row),
    partitions: (detail.partitions ?? []).map(toPartition),
  }));
}

/**
 * Every partition of one topic, from the endpoint that answers with all of them.
 *
 * ## Why this exists beside the overview, which also carries partitions
 *
 * `fetchTopicOverview` reads `/overview`, whose topic section holds a partition list that the
 * gateway **stops at 500** — and the envelope's `partitionsTruncated` flag is the only thing that
 * says so. A topic with 1,024 partitions therefore renders an overview whose partition table is
 * missing more than half its rows, with the totals above it counted from all of them. That is a
 * screen an operator reads a wrong conclusion off, so the Partitions tab reads this endpoint
 * instead, which returns the whole table.
 *
 * The payload is the same `PartitionDto` shape the overview nests under `topic.data.partitions`,
 * so it goes through the same {@link toPartition}: one mapping, one place for a renamed field to
 * break, and no chance of the two tabs disagreeing about what `inSync` means.
 */
export async function fetchPartitions(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<Fetched<readonly PartitionRow[]>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics/{topicName}/partitions", {
    params: { path: { clusterId, topicName } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  // Sectioned (ADR-039), like every read in this file: a cluster that cannot describe the
  // partitions answers `unavailable` with a reason rather than failing the request, and the tab
  // draws that instead of an empty table. An empty table would say "this topic has no partitions",
  // which no Kafka topic ever is.
  const section = decodeSection<readonly PartitionPayload[]>(answer.value.partitions);
  return fromSection(section, (partitions) => partitions.map(toPartition));
}

/**
 * One consumer group that reads this topic, with its lag **on this topic**.
 *
 * `topicLag` is not `group.totalLag`, and the difference is the reason this row exists: a group
 * that reads four topics carries the lag of all four in its total, so the consumer-group list's
 * figure answers "is this group behind?" while this one answers "is this group behind *here*?".
 * Those are different questions and the topic page is only ever asking the second.
 */
export interface TopicConsumerRow {
  readonly groupId: string;
  /** Kafka's own word: `STABLE`, `EMPTY`, `DEAD`, `PREPARING_REBALANCE`, … */
  readonly state: string;
  readonly members: number;
  /** Lag on this topic alone. `null` when it could not be computed — never `0`. */
  readonly topicLag: number | null;
  /** How many of this topic's partitions the group holds a committed offset for. */
  readonly partitions: number;
  /** The group has offsets on this topic but no live member reading it. The server decides this. */
  readonly dormant: boolean;
  /** The group's lag across every topic it reads. `null` when not computed. */
  readonly totalLag: number | null;
  /** How many topics the group reads in total, which is what makes `totalLag` readable. */
  readonly topics: number;
}

interface TopicConsumerPayload {
  readonly group?: {
    readonly groupId?: string;
    readonly state?: string;
    readonly members?: number;
    readonly topics?: number;
    readonly totalLag?: number | null;
  } | null;
  readonly topicLag?: number | null;
  readonly partitions?: number;
  readonly dormant?: boolean;
}

/**
 * The groups reading this topic.
 *
 * Unlike every other read here this response is **not** an ADR-039 section — the gateway answers a
 * bare `{ "rows": [...] }`, verified against a running gateway and recorded in
 * `recorded/topic-consumers.json`. So there is no `stale`, no `unavailable` and no `forbidden`
 * status to read: the consumer service being down is an error envelope, which `apiFailure` turns
 * into `failed` with the code on it. Writing a `decodeSection` here would have decoded the object
 * `{rows: […]}` as a section with no `status`, and every row would have vanished silently.
 *
 * The overview carries the same rows in a `consumerGroups` section, and this tab deliberately does
 * not use them: the overview is fetched once when the page opens, and lag is the one figure here
 * that moves while somebody is looking at it. Reading it when the tab is opened costs one request
 * and is the difference between a lag figure and a lag figure from four minutes ago.
 */
export async function fetchTopicConsumers(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<Fetched<readonly TopicConsumerRow[]>> {
  // `{topic}`, not `{topicName}`: this endpoint belongs to the consumer service and spells its own
  // path parameter differently from every topic-service endpoint in this file. The generated types
  // enforce it, which is the only reason it is not a run-time 404 waiting to happen.
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics/{topic}/consumer-groups", {
    params: { path: { clusterId, topic: topicName } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  const rows = (answer.value.rows ?? []) as readonly TopicConsumerPayload[];
  return { kind: "ready", value: rows.map(toTopicConsumer) };
}

function toTopicConsumer(payload: TopicConsumerPayload): TopicConsumerRow {
  const group = payload.group ?? {};
  return {
    // A row whose group has no id is a row nothing can link to. It is kept rather than dropped —
    // dropping it would quietly shorten a list somebody counts — and named so it reads as broken.
    groupId: group.groupId ?? "",
    state: group.state ?? "UNKNOWN",
    members: typeof group.members === "number" ? group.members : 0,
    // `null`, not `0`: a lag that could not be computed is not a group that has caught up, and
    // those two facts must never render alike.
    topicLag: typeof payload.topicLag === "number" ? payload.topicLag : null,
    partitions: typeof payload.partitions === "number" ? payload.partitions : 0,
    dormant: payload.dormant === true,
    totalLag: typeof group.totalLag === "number" ? group.totalLag : null,
    topics: typeof group.topics === "number" ? group.topics : 0,
  };
}

/*
 * ## `GET …/topics/{topicName}` has no call site, and should not get one
 *
 * Curled side by side against the quickstart gateway on 2026-09-06:
 *
 *   GET …/topics/orders.v1           -> { topic: <section>, partitionsTruncated: false }
 *   GET …/topics/orders.v1/overview  -> { topic: <section>, consumerGroups, connectors, acls,
 *                                         schemas, generatedAt }
 *
 * The `topic` section is byte-for-byte the same document in both — the same `row`, the same
 * `partitions`, the same `cleanupPolicy` and `segmentCount`. The overview adds four more sections
 * and the timestamp; the detail endpoint adds exactly one thing the overview does not have, the
 * envelope flag `partitionsTruncated`, which says whether the 500-partition cap was hit.
 *
 * So the overview is a superset in every field the topic page draws, and adopting the detail
 * endpoint would add a second request for one boolean. That boolean is worth naming rather than
 * shrugging at, because it is a real gap: on a topic with more than 500 partitions the overview's
 * table is short and says nothing about it. The Partitions tab is the answer to that — it reads
 * {@link fetchPartitions}, which is not capped — rather than a second call for a flag whose only
 * possible use would be to tell the operator to go and look at that tab.
 *
 * Leave `GET …/topics/{topicName}` unused.
 */
