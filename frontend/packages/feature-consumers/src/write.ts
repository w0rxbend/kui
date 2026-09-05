/**
 * The consumer feature's write paths: reset a group's offsets, delete them, delete the group.
 *
 * ## Two of these three are the same shape as a topic's purge, and one is not
 *
 * Reset goes through a plan token, for the reason every plan token in this product exists: the
 * agreement is what the operator *read*, not what the cluster happens to look like a minute later.
 * A reset plan resolves against the group's live offsets, so re-planning silently on an expired
 * token would write different offsets from the ones on screen — which is the exact surprise the
 * preview exists to prevent. `ResetWizard` therefore returns to the plan it is showing rather than
 * quietly making a new one.
 *
 * Deleting the offsets and deleting the group do not, because there is nothing to arithmetic about:
 * the operator names a group and it goes. The server refuses to delete a group that still has
 * members, with `KUI-GROUP-NOT-EMPTY` — the one refusal on this screen an operator can act on
 * directly, by stopping the consumers, so the message is passed through rather than reworded.
 *
 * ## Why the request mapping is not a pass-through
 *
 * `ResetRequest` is the *form's* vocabulary and `ResetPlanRequest` is the server's, and they differ
 * in two places that matter. The form holds a duration in minutes because that is what somebody
 * types; the wire wants milliseconds. And the form's optional parameters are per-target — only one
 * of `offset`, `timestamp`, `shiftBy`, `durationMinutes` is meaningful at a time — so sending all
 * four would have the server validate fields the operator never filled in.
 */
import { userMessage, type ApiResult, type components, type KuiApiClient } from "@kui/api";
import type { PlannedPartition, PlanWarning, ResetPlan, ResetRequest } from "./detail.js";

/** Minutes to milliseconds, for the one field whose units differ between the form and the wire. */
const MS_PER_MINUTE = 60_000;

/**
 * The form's request, in the server's vocabulary.
 *
 * Typed as the generated `ResetPlanRequest` rather than a loose object, so a field this mapping
 * misspells is a compile error. That matters more here than in most places: the server validates
 * per target, and an unrecognised extra field or a missing expected one comes back as a validation
 * error about a field the operator cannot see.
 */
function toPlanRequest(request: ResetRequest): components["schemas"]["ResetPlanRequest"] {
  // Bound once so the map below carries a `number` rather than `number | undefined`: the guard on
  // the spread narrows the condition, not the closure inside it.
  const offset = request.offset;
  return {
    topic: request.topic,
    target: request.target,
    // Omitted rather than sent empty: an empty list means "no partitions" to a validator, where the
    // form means "all of them". They are opposite instructions.
    ...(request.partitions.length === 0 ? {} : { partitions: request.partitions }),
    /*
     * Exactly the one parameter this target needs. `OFFSET` is sent as a map from partition to
     * offset because the server takes it that way — the form offers one offset applied to every
     * partition, which is what the hint on that field says and what the plan then clamps per
     * partition.
     */
    ...(request.target === "OFFSET" && offset !== undefined
      ? {
          offsets: Object.fromEntries(
            request.partitions.map((partition) => [String(partition), offset] as const),
          ),
        }
      : {}),
    ...(request.target === "TIMESTAMP" && request.timestamp !== undefined
      ? { timestamp: request.timestamp }
      : {}),
    ...(request.target === "SHIFT_BY" && request.shiftBy !== undefined
      ? { shiftBy: request.shiftBy }
      : {}),
    ...(request.target === "DURATION" && request.durationMinutes !== undefined
      ? { durationMs: request.durationMinutes * MS_PER_MINUTE }
      : {}),
  };
}

interface PlannedPartitionPayload {
  readonly partition: number;
  readonly current?: number;
  readonly proposed: number;
  readonly delta?: number;
}

function toPlannedPartition(payload: PlannedPartitionPayload): PlannedPartition {
  return {
    partition: payload.partition,
    /*
     * `null`, never `0`. A group that has never committed on this partition and a group sitting at
     * offset zero are different facts, and the table draws the first as a dash. Rendering both as
     * "0" tells an operator a group has consumed the first record when it has consumed nothing.
     */
    current: typeof payload.current === "number" ? payload.current : null,
    proposed: payload.proposed,
    // Absent when there is no `current` to subtract from, which is not the same as no movement.
    delta: typeof payload.delta === "number" ? payload.delta : null,
  };
}

function toResetPlan(payload: {
  readonly topic: string;
  readonly token: string;
  readonly expiresAt: string;
  readonly noOp: boolean;
  readonly partitions?: readonly PlannedPartitionPayload[];
  readonly warnings?: readonly { readonly kind: string; readonly message: string }[];
}): ResetPlan {
  return {
    token: payload.token,
    topic: payload.topic,
    partitions: (payload.partitions ?? []).map(toPlannedPartition),
    warnings: (payload.warnings ?? []).map((warning): PlanWarning => ({
      kind: warning.kind,
      message: warning.message,
    })),
    noOp: payload.noOp,
    expiresAt: new Date(payload.expiresAt),
  };
}

/** What the wizard's `plan` and `apply` callbacks answer with. */
export type PlanOutcome =
  | { readonly ok: true; readonly plan: ResetPlan }
  | { readonly ok: false; readonly problem: string };

export type ApplyOutcome =
  | { readonly ok: true; readonly receipt: ResetPlan }
  | { readonly ok: false; readonly problem: string };

export async function planReset(
  api: KuiApiClient,
  clusterId: string,
  groupId: string,
  request: ResetRequest,
): Promise<PlanOutcome> {
  const answer = await api.post(
    "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets/plan",
    {
      params: { path: { clusterId, groupId } },
      body: toPlanRequest(request),
    },
  );
  return answer.ok
    ? { ok: true, plan: toResetPlan(answer.value) }
    : { ok: false, problem: userMessage(answer.error) };
}

export async function applyReset(
  api: KuiApiClient,
  clusterId: string,
  groupId: string,
  token: string,
): Promise<ApplyOutcome> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets", {
    params: { path: { clusterId, groupId } },
    // The token and nothing else. The plan already fixed which offsets get written, so there is no
    // second place for the screen and the cluster to disagree about what was agreed.
    body: { token },
  });
  return answer.ok
    ? // The answer is the plan that was *applied*, which is what lets the wizard show what happened
      // without asking the cluster to describe a group whose offsets have just moved underneath it.
      { ok: true, receipt: toResetPlan(answer.value) }
    : { ok: false, problem: userMessage(answer.error) };
}

/** Which partitions' committed offsets were removed. */
export interface DeletedOffsets {
  readonly topic: string;
  readonly partitions: readonly number[];
}

/**
 * Deletes a group's committed offsets for one topic.
 *
 * The answer names the partitions rather than being an empty `204`, deliberately on the server's
 * part: "the group had no offsets here" and "they were deleted" are different outcomes, and a status
 * code with no body cannot tell them apart.
 */
export async function deleteOffsets(
  api: KuiApiClient,
  clusterId: string,
  groupId: string,
  topic: string,
): Promise<ApiResult<DeletedOffsets>> {
  const answer = await api.delete(
    "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets",
    {
      params: { path: { clusterId, groupId }, query: { topic } },
    },
  );
  if (!answer.ok) return answer;
  return {
    ok: true,
    value: { topic: answer.value.topic, partitions: answer.value.partitions ?? [] },
  };
}

/**
 * Deletes the group.
 *
 * Refused with `KUI-GROUP-NOT-EMPTY` while it still has members. That refusal is passed through as
 * the server words it rather than being reworded here, because it is the one failure on this screen
 * the operator can fix themselves — by stopping the consumers — and a generic "could not delete the
 * group" would hide the instruction.
 */
export async function deleteGroup(
  api: KuiApiClient,
  clusterId: string,
  groupId: string,
): Promise<ApiResult<unknown>> {
  return api.delete("/api/v1/clusters/{clusterId}/consumer-groups/{groupId}", {
    params: { path: { clusterId, groupId } },
  });
}
