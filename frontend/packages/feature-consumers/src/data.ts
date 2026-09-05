/**
 * The consumer feature's data layer.
 *
 * Field names are taken from a response a running gateway produced (`src/recorded/groups.json`),
 * not from reading the DTOs — the server documents every section with `Schema.any`, so the payloads
 * are `unknown` here and a misspelled field is a type-correct `undefined` that renders as an em
 * dash. A mapping can therefore be wrong in every field and look exactly like a coordinator that
 * did not answer. The clusters feature shipped that bug; these documents are what stop it recurring.
 */
import { decodeSection, type KuiApiClient } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";
import type { GroupState, GroupSummary } from "./model.js";

interface GroupRowPayload {
  readonly groupId: string;
  readonly state?: string | null;
  readonly members?: number | null;
  readonly topics?: number | null;
  readonly partitions?: number | null;
  readonly coordinatorId?: number | null;
  readonly totalLag?: number | null;
  readonly excludedPartitions?: number | null;
  /**
   * Present when part of this group's picture could not be read; `null` when it is complete.
   *
   * The wire's shape here is not pinned by the recorded document — the quickstart's groups are all
   * complete, so every one of them sends `null` and there is no example of the populated case. The
   * three booleans below are the screen's vocabulary (`Incomplete` in `model.ts`) and are read
   * defensively: anything the server sends that is not `false` is treated as known, because
   * claiming a figure is missing when it is present is the milder of the two errors here.
   */
  readonly incomplete?: {
    readonly note?: string;
    readonly offsetsKnown?: boolean;
    readonly membersKnown?: boolean;
    readonly endOffsetsKnown?: boolean;
  } | null;
}

interface GroupListPayload {
  readonly items: readonly GroupRowPayload[];
  readonly page?: { readonly page?: number; readonly pageSize?: number; readonly totalItems?: number } | null;
}

/** The states Kafka reports. Anything else is `null` — an unknown state is not a state. */
const STATES: readonly string[] = ["STABLE", "EMPTY", "PREPARING_REBALANCE", "COMPLETING_REBALANCE", "DEAD", "UNKNOWN"];

/**
 * The group's state, or `null`.
 *
 * A string the browser does not recognise becomes `null` rather than being passed through: the
 * screen's chip is a closed set, and rendering an unknown word in it would style it as whatever the
 * default happens to be — which on this screen is the healthy colour.
 */
export function stateOf(raw: string | null | undefined): GroupState | null {
  if (typeof raw !== "string") return null;
  const upper = raw.toUpperCase();
  return STATES.includes(upper) ? (upper as GroupState) : null;
}

function figure(value: number | null | undefined): number | null {
  return typeof value === "number" ? value : null;
}

function toGroupSummary(payload: GroupRowPayload): GroupSummary {
  return {
    groupId: payload.groupId,
    state: stateOf(payload.state),
    members: figure(payload.members),
    // `topics` is not nullable on the row: a group with no subscriptions genuinely has zero, and
    // that is a fact worth printing rather than a gap.
    topics: payload.topics ?? 0,
    // The wire gives a broker *id*; the screen wants `host:port`, which this endpoint does not
    // carry. `null` says the coordinator is not named here rather than printing a bare number that
    // reads like a count.
    coordinator: payload.coordinatorId === null || payload.coordinatorId === undefined
      ? null
      : `broker ${payload.coordinatorId}`,
    // The most expensive `0` on this screen: a group with no lag is caught up, and a group whose
    // lag could not be computed is a group nobody knows about. They must not look alike.
    totalLag: figure(payload.totalLag),
    excludedPartitions: payload.excludedPartitions ?? 0,
    incomplete:
      payload.incomplete === null || payload.incomplete === undefined
        ? null
        : {
            note: payload.incomplete.note ?? "Part of this group could not be read.",
            offsetsKnown: payload.incomplete.offsetsKnown !== false,
            membersKnown: payload.incomplete.membersKnown !== false,
            endOffsetsKnown: payload.incomplete.endOffsetsKnown !== false,
          },
  };
}

export interface GroupListResult {
  readonly groups: readonly GroupSummary[];
  /** How many coordinators did not answer. Drives the voice line and the incomplete chips. */
  readonly coordinatorsMissing: number;
}

export async function fetchGroups(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<GroupListResult>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/consumer-groups", {
    params: { path: { clusterId } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  // Outside the section, like the topic list's `incompleteTopics`: how many coordinators failed is
  // known even when the groups they hold are not.
  const missing =
    typeof answer.value.incompleteCoordinators === "number" ? answer.value.incompleteCoordinators : 0;

  const section = decodeSection<GroupListPayload>(answer.value.groups);
  return fromSection(section, (listing) => ({
    groups: listing.items.map(toGroupSummary),
    coordinatorsMissing: missing,
  }));
}
