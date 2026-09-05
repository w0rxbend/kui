/**
 * The topic feature's write paths: create, delete, purge, and edit one configuration key.
 *
 * ## Two shapes, and the reason they differ
 *
 * **Create** is one call. It either makes a topic or it does not, and nothing existing is at risk,
 * so asking the server what it is about to do would tell the operator nothing they did not type.
 *
 * **Delete and purge** are two calls: `POST …/plan` answers with what would be destroyed plus a
 * token, and the mutation takes that token *and nothing else*. That is the server's design and it
 * is worth being precise about why, because a UI that treats it as a formality throws away the
 * property it buys. A purge deletes up to the offsets the plan resolved, not to the topic's end as
 * it stands at the moment of confirmation — so records produced while the operator was reading the
 * confirmation survive, because they are not what the operator agreed to lose. The token is the
 * agreement. Re-planning silently when it expires would destroy exactly that guarantee, which is
 * why {@link confirmPurge} does not, and why a token that has run out surfaces as a plain failure
 * the operator answers by looking at a fresh plan.
 *
 * ## Field names
 *
 * Unlike the read paths in `data.ts`, these responses are *typed*: they are documented DTOs rather
 * than `Schema.any` section payloads, so the generated types are real and a misspelling here is a
 * compile error rather than a silent `undefined`. The mapping below is still explicit, because the
 * screens want a shape that says what it means ("records" rather than "records?: number | undefined
 * that is null when at least one partition could not be counted").
 */
import type { KuiApiClient } from "@kui/api";
import type { ApiResult } from "@kui/api";

/** What a create asks for. `undefined` means "the broker's own default", which is not the same as 1. */
export interface NewTopic {
  readonly name: string;
  readonly partitions: number | undefined;
  readonly replicationFactor: number | undefined;
  /** Extra `key=value` configuration. Empty is the ordinary case. */
  readonly config: Readonly<Record<string, string>>;
}

/** The topic as the cluster reports it *after* the create — not as it was requested. */
export interface CreatedTopic {
  readonly name: string;
  readonly partitions: number | null;
  readonly replicationFactor: number | null;
}

export async function createTopic(
  api: KuiApiClient,
  clusterId: string,
  topic: NewTopic,
): Promise<ApiResult<CreatedTopic>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/topics", {
    params: { path: { clusterId } },
    body: {
      name: topic.name,
      // Omitted rather than sent as null: the contract says an absent field means the broker's
      // default, and `null` is a different statement that the validator rejects.
      ...(topic.partitions === undefined ? {} : { partitions: topic.partitions }),
      ...(topic.replicationFactor === undefined
        ? {}
        : { replicationFactor: topic.replicationFactor }),
      config: topic.config,
    },
  });
  if (!answer.ok) return answer;
  return {
    ok: true,
    value: {
      name: answer.value.name,
      partitions: answer.value.partitions ?? null,
      replicationFactor: answer.value.replicationFactor ?? null,
    },
  };
}

/** One thing worth knowing before a destructive operation is confirmed. */
export interface PlanWarning {
  readonly code: string;
  readonly message: string;
}

/** What emptying a topic would destroy, and the token that agrees to it. */
export interface PurgePlan {
  readonly topic: string;
  /** How many records would go, or `null` when at least one partition could not be read. */
  readonly records: number | null;
  readonly partitions: number;
  readonly warnings: readonly PlanWarning[];
  /**
   * The agreement. Absent when the server computed a plan it will not let this caller apply — a
   * read-only cluster answers a plan with no token rather than an error, so the screen can show
   * what *would* happen and disable the button.
   */
  readonly token: string | null;
  readonly expiresAt: string | null;
}

function warningsOf(
  raw: readonly { readonly code?: string; readonly message?: string }[] | undefined,
): readonly PlanWarning[] {
  return (raw ?? []).map((warning) => ({
    code: warning.code ?? "WARNING",
    // A warning with no text is worse than no warning: it draws an alarming row that says nothing.
    message: warning.message ?? "This operation carries a warning the server did not describe.",
  }));
}

export async function planPurge(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<ApiResult<PurgePlan>> {
  const answer = await api.post(
    "/api/v1/clusters/{clusterId}/topics/{topicName}/messages/purge/plan",
    {
      params: { path: { clusterId, topicName } },
    },
  );
  if (!answer.ok) return answer;

  const partitions = answer.value.partitions ?? [];
  return {
    ok: true,
    value: {
      topic: answer.value.topic,
      // Summed here rather than on the wire, from each partition's offset window. A partition whose
      // watermarks are not both known contributes nothing *and* makes the whole figure unknown,
      // because a sum over the partitions that could be read is a smaller number presented with the
      // confidence of a complete one — the operator would agree to lose more than the screen said.
      records: partitions.some(
        (partition) =>
          typeof partition.highWatermark !== "number" || typeof partition.lowWatermark !== "number",
      )
        ? null
        : partitions.reduce(
            (total, partition) => total + (partition.highWatermark - partition.lowWatermark),
            0,
          ),
      partitions: partitions.length,
      warnings: warningsOf(answer.value.warnings),
      token: answer.value.token ?? null,
      expiresAt: answer.value.expiresAt ?? null,
    },
  };
}

export async function confirmPurge(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
  token: string,
): Promise<ApiResult<PurgeOutcome>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/topics/{topicName}/messages/purge", {
    params: { path: { clusterId, topicName } },
    // The token and nothing else. The plan already fixed what will be deleted, so there is no second
    // place for the two halves to disagree.
    body: { token },
  });
  if (!answer.ok) return answer;

  const failed = answer.value.result?.failed ?? [];
  return {
    ok: true,
    value: {
      purgedPartitions: answer.value.result?.purged?.length ?? 0,
      // Named individually rather than counted: "3 partitions refused" is not something an operator
      // can act on, and each refusal carries the broker's own reason.
      refused: failed.map((failure) => ({
        partition: failure.partition,
        reason: failure.reason,
      })),
    },
  };
}

/** What a purge actually did, as opposed to what it was agreed to do. */
export interface PurgeOutcome {
  readonly purgedPartitions: number;
  readonly refused: readonly {
    readonly partition: number;
    readonly reason: string;
  }[];
}

/** What deleting a topic would destroy. */
export interface DeletionPlan {
  readonly topic: string;
  readonly partitions: number;
  /** `null` when at least one partition could not be counted. Never a sum over the ones that could. */
  readonly records: number | null;
  /**
   * Whether this cluster will recreate the topic the moment anything names it.
   *
   * The single most useful thing on this dialog. With `auto.create.topics.enable` on, deleting a
   * topic that a producer is still writing to does not remove it — it removes its configuration and
   * its data and leaves a fresh one with the broker's defaults, which is usually the opposite of
   * what the operator wanted. `null` when the server could not read the setting.
   */
  readonly autoCreateEnabled: boolean | null;
  readonly warnings: readonly PlanWarning[];
  readonly token: string | null;
  readonly expiresAt: string | null;
}

export async function planDeletion(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<ApiResult<DeletionPlan>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/topics/{topicName}/deletion/plan", {
    params: { path: { clusterId, topicName } },
  });
  if (!answer.ok) return answer;
  return { ok: true, value: toDeletionPlan(answer.value) };
}

function toDeletionPlan(raw: {
  readonly topic: string;
  readonly partitions: number;
  readonly records?: number;
  readonly autoCreateEnabled?: boolean;
  readonly warnings?: readonly {
    readonly code?: string;
    readonly message?: string;
  }[];
  readonly token?: string;
  readonly expiresAt?: string;
}): DeletionPlan {
  return {
    topic: raw.topic,
    partitions: raw.partitions,
    records: typeof raw.records === "number" ? raw.records : null,
    autoCreateEnabled: typeof raw.autoCreateEnabled === "boolean" ? raw.autoCreateEnabled : null,
    warnings: warningsOf(raw.warnings),
    token: raw.token ?? null,
    expiresAt: raw.expiresAt ?? null,
  };
}

/**
 * Deletes the topic the plan named.
 *
 * The answer repeats the plan that was applied, which is what lets the screen say what was destroyed
 * without asking the cluster about a topic that no longer exists.
 *
 * Kafka's `deleteTopics` is asynchronous: it returns once the controller has *accepted* the
 * deletion, and the topic can still appear in a listing for a moment afterwards. A caller must not
 * read "still listed" as a failure, which is why the screens re-fetch and say nothing rather than
 * re-checking and complaining.
 */
export async function deleteTopic(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
  token: string,
): Promise<ApiResult<DeletionPlan>> {
  const answer = await api.delete("/api/v1/clusters/{clusterId}/topics/{topicName}", {
    params: { path: { clusterId, topicName }, query: { token } },
  });
  if (!answer.ok) return answer;
  return { ok: true, value: toDeletionPlan(answer.value) };
}

/**
 * Sets and resets entries of a topic's configuration.
 *
 * Incremental: a key named in neither list is untouched. `remove` puts a key back to the broker's
 * default *for it*, which is not the same as setting it to that default's current value — the
 * difference shows up the next time somebody changes the broker default.
 */
export async function updateTopicConfig(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
  change: {
    readonly set?: Readonly<Record<string, string>>;
    readonly remove?: readonly string[];
  },
): Promise<ApiResult<unknown>> {
  return api.patch("/api/v1/clusters/{clusterId}/topics/{topicName}/config", {
    params: { path: { clusterId, topicName } },
    body: {
      set: change.set ?? {},
      remove: change.remove ?? [],
    },
  });
}
