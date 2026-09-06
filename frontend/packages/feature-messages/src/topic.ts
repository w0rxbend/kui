/**
 * The one thing the message browser needs to know about the topic it is browsing.
 *
 * ## Why this exists at all
 *
 * `MessagesRoute` held `const [partitionCount] = createSignal(0)` and handed that zero to three
 * children. Two of them merely offered a shorter menu than they should have; the third —
 * `ResendDialog` — spends it in a sentence, and rendered "`orders.payments.v2` has 0 partitions, and
 * there is a range for each of them" on every screen it has ever appeared on. That is a false
 * statement about somebody's cluster, printed beside a control that copies records between topics,
 * and it disabled the control that adds a range as a side effect of being false.
 *
 * The figure was never missing from the wire. `TopicRowDto.partitionCount` is a required `Int`, and
 * `GET …/clusters/{id}/topics/{name}` answers with it inside a section. So this is one request the
 * route was not making.
 *
 * ## Why the count is optional here when the wire says it is not
 *
 * Because the *section* is not. ADR-039's envelope has five statuses and three of them carry no
 * data at all: a topic the gateway could not describe, one this principal may not see, and one the
 * deployment has nothing configured for. In each of those the honest answer is that KUI does not
 * know how many partitions this topic has — which is a different fact from "it has none", and the
 * whole reason {@link TopicFacts.partitionCount} is `number | undefined` rather than a number with
 * a default. Every consumer of it draws a sentence for the `undefined` case and none of them draws
 * a zero.
 */

import { apiFailure, fromSection, type Fetched } from "@kui/kernel";
import { decodeSection, type KuiApiClient } from "@kui/api";

/** What the browser needs about the topic, as opposed to about the records in it. */
export interface TopicFacts {
  /**
   * How many partitions the topic has, or `undefined` when the answer did not carry it.
   *
   * Never `0` for "unknown". A topic cannot have zero partitions, so a zero here would be a value
   * that is both impossible and reassuring, which is the pairing this product treats as expensive.
   */
  readonly partitionCount: number | undefined;
}

/** The wire's topic row, as much of it as this screen reads. */
interface TopicRowPayload {
  readonly partitionCount?: number | null;
}

interface TopicDetailPayload {
  readonly row?: TopicRowPayload | null;
}

/**
 * The cache key for one topic's facts.
 *
 * Everything that changes the request is in the string, which is what `useQuery` promises and what
 * lets the browser and anything else asking about this topic share one answer. The prefix is this
 * package's, so a key collision with another feature asking a different question about the same
 * topic is not possible.
 */
export function topicFactsKey(clusterId: string, topicName: string): string {
  return `messages:topic:${clusterId}:${topicName}`;
}

/**
 * Reads the topic, and keeps only what this screen needs.
 *
 * Deliberately the detail endpoint rather than `/overview`: the overview is five sections from five
 * services and this screen needs one field from one of them, and asking for the schema registry's
 * opinion in order to size a partition menu is a request that can fail for reasons that have nothing
 * to do with the question.
 */
export async function fetchTopicFacts(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<Fetched<TopicFacts>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics/{topicName}", {
    params: { path: { clusterId, topicName } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  const section = decodeSection<TopicDetailPayload>(answer.value.topic);
  return fromSection(section, (detail) => ({
    // `typeof`, not `??`: a `null` on the wire is the server saying it could not read the figure,
    // and `?? 0` would turn that sentence into the claim the whole screen exists to avoid.
    partitionCount:
      typeof detail.row?.partitionCount === "number" ? detail.row.partitionCount : undefined,
  }));
}
