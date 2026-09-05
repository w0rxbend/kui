/**
 * The Partitions tab, the Consumers tab, and the form that grows a topic.
 *
 * The cases here are the ones that are easy to get wrong in a way nobody notices, which on these
 * three screens means one thing above all others: a *fact* and an *absence* rendering alike. A
 * partition with no leader and a partition KUI could not read look identical if both draw a dash; a
 * lag of zero and a lag nobody could compute look identical if both draw `0`; and a total summed
 * over the partitions that answered looks exactly like a total, only smaller.
 *
 * The rest is the two rules this product does not bend: a control the operator may not use is
 * disabled with a reason rather than hidden, and an irreversible change states its consequence
 * before it is agreed to and not after.
 */

import { describe, expect, test } from "vitest";
import { flush } from "solid-js";
import { mount } from "./testing.js";
import {
  TopicPartitions,
  offlineCount,
  partitionSummary,
  underReplicatedCount,
} from "./TopicPartitions.jsx";
import { TopicConsumers, groupStateChip, lagLevel } from "./TopicConsumers.jsx";
import { AddPartitionsDialog, targetProblem } from "./AddPartitionsDialog.jsx";
import { describePartitionIncrease } from "./TopicsRoute.jsx";
import type { PartitionRow, TopicConsumerRow } from "./data.js";

/** A healthy partition, so a case can say what it changes and nothing else. */
function partition(overrides: Partial<PartitionRow> = {}): PartitionRow {
  return {
    partition: 0,
    leader: 1,
    replicas: [1, 2, 3],
    inSync: [1, 2, 3],
    earliestOffset: 0,
    latestOffset: 40,
    messageCount: 40,
    sizeBytes: 4096,
    ...overrides,
  };
}

function group(overrides: Partial<TopicConsumerRow> = {}): TopicConsumerRow {
  return {
    groupId: "order-fulfilment",
    state: "STABLE",
    members: 3,
    topicLag: 12,
    partitions: 6,
    dormant: false,
    totalLag: 12,
    topics: 1,
    ...overrides,
  };
}

describe("counting what is wrong with a partition table", () => {
  test("a partition with no leader is offline and not merely under-replicated", () => {
    const rows = [partition({ leader: null, inSync: [], messageCount: null })];
    expect(offlineCount(rows)).toBe(1);
    /*
     * And it is *not* counted as under-replicated. The two sentences the screen draws are mutually
     * exclusive on purpose: an offline partition is not a milder problem that also happens to be
     * short of replicas, it is the stronger fact, and reporting both would give one partition two
     * different severities in two adjacent lines.
     */
    expect(underReplicatedCount(rows)).toBe(0);
  });

  test("a partition short of a replica is under-replicated and still has a leader", () => {
    const rows = [partition({ partition: 3, replicas: [1, 2, 3], inSync: [1, 2] })];
    expect(offlineCount(rows)).toBe(0);
    expect(underReplicatedCount(rows)).toBe(1);
  });

  test("a healthy table counts neither", () => {
    expect(offlineCount([partition(), partition({ partition: 1 })])).toBe(0);
    expect(underReplicatedCount([partition(), partition({ partition: 1 })])).toBe(0);
  });
});

describe("the sentence above the partition table", () => {
  test("adds the records up when every partition answered", () => {
    const summary = partitionSummary([
      partition({ partition: 0, messageCount: 40 }),
      partition({ partition: 1, messageCount: 2 }),
    ]);
    expect(summary).toBe("2 partitions, holding 42 records.");
  });

  test("a partition that genuinely holds nothing still contributes its zero", () => {
    // `0` here is a fact and belongs in the total. It is only `null` that invalidates one.
    expect(partitionSummary([partition({ messageCount: 0 })])).toBe(
      "1 partition, holding 0 records.",
    );
  });

  test("refuses to print a total when any partition withheld its count", () => {
    /*
     * The whole reason this function is not a `reduce`. A sum over the partitions that answered,
     * printed as the topic's total, is a smaller number wearing a complete one's confidence — and
     * nothing on the screen would say it was short.
     */
    const summary = partitionSummary([
      partition({ partition: 0, messageCount: 40 }),
      partition({ partition: 1, leader: null, messageCount: null }),
    ]);
    expect(summary).not.toContain("40");
    expect(summary).toContain("1 of them did not report a record count");
    expect(summary).toContain("no total to show");
  });

  test("says nothing at all about a table with no rows", () => {
    // The empty state under the table already says what happened, in the right one of four ways.
    expect(partitionSummary([])).toBe("");
  });
});

describe("drawing a partition", () => {
  test('a partition with no leader says "none", not a dash', () => {
    const { container, dispose } = mount(() => (
      <TopicPartitions partitions={[partition({ leader: null, messageCount: null })]} />
    ));
    const text = container.textContent ?? "";
    // The cluster told us there is no leader. That is an outage on this partition, and drawing it
    // as an em dash would report it as a gap in KUI's knowledge instead.
    expect(text).toContain("none");
    expect(text).toContain("no leader");
    dispose();
  });

  test("a count nobody could take is a dash with the words beside it", () => {
    const { container, dispose } = mount(() => (
      <TopicPartitions
        partitions={[
          partition({ leader: null, messageCount: null, latestOffset: null, sizeBytes: null }),
        ]}
      />
    ));
    // Both halves. The dash is for the eye; "not known" is what a screen reader hears, and without
    // it "not known" and "zero" are the same cell to the audience least able to check.
    expect(container.textContent).toContain("—");
    expect(container.textContent).toContain("not known");
    dispose();
  });

  test("a size of zero is never invented for a partition that reported none", () => {
    const { container, dispose } = mount(() => (
      <TopicPartitions partitions={[partition({ sizeBytes: null })]} />
    ));
    // `formatBytes(0)` would render "0 B", which claims a partition holding forty records occupies
    // no disk. The assertion is on the absence of that claim.
    expect(container.textContent).not.toContain("0 B");
    dispose();
  });

  test("the add-partitions button is disabled with a reason, never hidden", () => {
    const { container, dispose } = mount(() => (
      <TopicPartitions
        partitions={[partition()]}
        onAdd={() => undefined}
        addDisabledReason="You do not hold a role that permits adding partitions to this topic."
      />
    ));
    const button = container.querySelector("button[disabled], button[aria-disabled='true']");
    // Present. A hidden button makes an operator believe the product cannot do the thing at all,
    // and they go looking for a command line instead of for an administrator.
    expect(button).not.toBeNull();
    expect(container.textContent).toContain("Add partitions");
    dispose();
  });

  test("an unavailable table is not an empty one", () => {
    const { container, dispose } = mount(() => (
      <TopicPartitions
        partitions={[]}
        failure={{
          kind: "unavailable",
          message: "The cluster service did not answer.",
          code: "KUI-UPSTREAM-UNAVAILABLE",
          onRetry: () => undefined,
        }}
      />
    ));
    const text = container.textContent ?? "";
    expect(text).toContain("did not come back");
    // The code is on screen because it means nothing to the operator and everything to whoever they
    // paste it to.
    expect(text).toContain("KUI-UPSTREAM-UNAVAILABLE");
    dispose();
  });

  test("an empty table says the cluster answered with none, not that the topic has none", () => {
    const { container, dispose } = mount(() => <TopicPartitions partitions={[]} />);
    // "This topic has no partitions" is the one sentence that is never true of a Kafka topic.
    expect(container.textContent).toContain("Every Kafka topic has at least one partition");
    dispose();
  });
});

describe("the consumer tab's vocabulary", () => {
  test("an empty group is neutral, because that is a batch job between runs", () => {
    expect(groupStateChip("EMPTY").tone).toBe("neutral");
  });

  test("a dead group is the only danger state", () => {
    expect(groupStateChip("DEAD").tone).toBe("danger");
    expect(groupStateChip("STABLE").tone).toBe("success");
  });

  test("both rebalancing states read as one word", () => {
    expect(groupStateChip("PREPARING_REBALANCE").label).toBe("Rebalancing");
    expect(groupStateChip("COMPLETING_REBALANCE").label).toBe("Rebalancing");
  });

  test("a state this build has never heard of is neutral and named, not dropped", () => {
    // A future Kafka state must not be coloured as a failure, and must not vanish from the column.
    expect(groupStateChip("SOMETHING_NEW")).toEqual({ label: "Unknown", tone: "neutral" });
  });

  test("lag has three levels and a healthy zero is not one of them", () => {
    expect(lagLevel(0)).toBe("normal");
    expect(lagLevel(1_000)).toBe("normal");
    expect(lagLevel(1_001)).toBe("warning");
    expect(lagLevel(100_001)).toBe("critical");
  });
});

describe("drawing the consumer tab", () => {
  test("a topic nothing reads says so, and does not read as a failure", () => {
    const { container, dispose } = mount(() => (
      <TopicConsumers rows={[]} hrefFor={(id) => `/groups/${id}`} />
    ));
    const text = container.textContent ?? "";
    expect(text).toContain("No consumer group reads this topic.");
    // And it explains the case that otherwise generates a support question: a console consumer or a
    // connector reading without a group never appears here, and that is not a bug.
    expect(text).toContain("without a group");
    dispose();
  });

  test("a consumer service that is down is a different screen from a topic nobody reads", () => {
    const { container, dispose } = mount(() => (
      <TopicConsumers
        rows={[]}
        hrefFor={(id) => `/groups/${id}`}
        failure={{
          kind: "unavailable",
          message: "The consumer service did not answer.",
          code: "KUI-UPSTREAM-UNAVAILABLE",
          onRetry: () => undefined,
        }}
      />
    ));
    const text = container.textContent ?? "";
    expect(text).toContain("did not come back");
    expect(text).not.toContain("No consumer group reads this topic.");
    dispose();
  });

  test("lag on this topic is labelled as such, and a group's reach beside it", () => {
    const { container, dispose } = mount(() => (
      <TopicConsumers
        rows={[group({ topics: 5, topicLag: 12, totalLag: 4_000_000 })]}
        hrefFor={(id) => `/groups/${id}`}
      />
    ));
    const text = container.textContent ?? "";
    expect(text).toContain("Lag on this topic");
    // The whole point of the Elsewhere column: without it, 12 beside a group that is four million
    // records behind across five topics reads as a group that is fine.
    expect(text).toContain("reads 5 topics");
    expect(text).toContain("4,000,000 lag in total");
    dispose();
  });

  test("a lag that could not be computed is a dash, and zero is a zero", () => {
    const { container, dispose } = mount(() => (
      <TopicConsumers
        rows={[group({ groupId: "unknown-lag", topicLag: null })]}
        hrefFor={(id) => `/groups/${id}`}
      />
    ));
    expect(container.textContent).toContain("not known");
    dispose();

    const caught = mount(() => (
      <TopicConsumers
        rows={[group({ groupId: "caught-up", topicLag: 0 })]}
        hrefFor={(id) => `/groups/${id}`}
      />
    ));
    // A group that has caught up has caught up. That is a fact and it prints as `0`.
    expect(caught.container.textContent).not.toContain("not known");
    caught.dispose();
  });

  test("a dormant group is marked without being alarmed about", () => {
    const { container, dispose } = mount(() => (
      <TopicConsumers
        rows={[group({ state: "EMPTY", members: 0, dormant: true })]}
        hrefFor={(id) => `/${id}`}
      />
    ));
    expect(container.textContent).toContain("dormant");
    // Zero members is what dormant *means*, so it prints rather than dashing.
    expect(container.textContent).toContain("0");
    dispose();
  });
});

describe("choosing a partition count", () => {
  test("refuses a target that is not greater, in Kafka's terms and not KUI's", () => {
    const problem = targetProblem("6", 6);
    expect(problem).toBeDefined();
    // An operator who reads "KUI does not allow this" goes looking for a command line that does.
    // There isn't one, so the sentence names Kafka.
    expect(problem).toContain("Kafka cannot remove one");
  });

  test("refuses a lower target for the same reason", () => {
    expect(targetProblem("2", 6)).toContain("can only be raised");
  });

  test("accepts one more than the current count, the smallest legal answer", () => {
    expect(targetProblem("7", 6)).toBeUndefined();
  });

  test("refuses anything that is not a whole number", () => {
    expect(targetProblem("6.5", 6)).toBe("Partitions are a whole number.");
    expect(targetProblem("lots", 6)).toBe("Partitions are a whole number.");
    expect(targetProblem("", 6)).toContain("Enter the number");
  });

  test("declines to judge at all when the current count is not known", () => {
    /*
     * A topic KUI could not describe. Guessing here would refuse a legal target — or, worse, accept
     * an illegal one and let the operator find out from a validation error. The server checks the
     * same thing against a count it has just read, and its answer is the authority.
     */
    expect(targetProblem("4", undefined)).toBeUndefined();
    expect(targetProblem("400", undefined)).toBeUndefined();
  });
});

/*
 * `Dialog` renders through a `Portal` into `document.body`, so its content is not inside the
 * container `mount` created. The assertions below read the document rather than the container,
 * which is what the messages feature does for the same reason.
 */
describe("the add-partitions form", () => {
  test("starts one above the current count and says what that adds", async () => {
    const { dispose } = mount(() => (
      <AddPartitionsDialog
        open
        onClose={() => undefined}
        topicName="orders.v1"
        current={6}
        onContinue={() => undefined}
      />
    ));
    await flush();
    const field = document.body.querySelector<HTMLInputElement>("input[type='number']");
    expect(field?.value).toBe("7");
    expect(document.body.textContent).toContain("This topic has 6 partitions.");
    // The field is a *total*, not an increment, and the help text spells both out — the single most
    // likely misreading on this form is typing 6 meaning "add six".
    expect(document.body.textContent).toContain("adds 1 partition");
    dispose();
  });

  test("warns about key routing before the number is chosen, not after", async () => {
    const { dispose } = mount(() => (
      <AddPartitionsDialog
        open
        onClose={() => undefined}
        topicName="orders.v1"
        current={6}
        onContinue={() => undefined}
      />
    ));
    await flush();
    const text = document.body.textContent ?? "";
    // Said at the point of choice, because that is the only point at which it can change the choice.
    expect(text).toContain("hash(key) % partitions");
    expect(text).toContain("cannot be undone");
    dispose();
  });

  test("says it cannot check the number when the topic could not be described", async () => {
    const { dispose } = mount(() => (
      <AddPartitionsDialog
        open
        onClose={() => undefined}
        topicName="orders.v1"
        current={undefined}
        onContinue={() => undefined}
      />
    ));
    await flush();
    // Rather than pre-filling a plausible number. A `1` on a topic with twelve partitions is a
    // wrong answer wearing a right answer's shape.
    expect(document.body.querySelector<HTMLInputElement>("input[type='number']")?.value).toBe("");
    expect(document.body.textContent).toContain(
      "could not read this topic's current partition count",
    );
    dispose();
  });
});

describe("what the confirmation says when the server has nothing to add", () => {
  test("states the change and its irreversibility, and no more", () => {
    const sentence = describePartitionIncrease({
      topic: "orders.v1",
      current: 6,
      target: 12,
      added: 6,
      warnings: [],
      token: "t",
      expiresAt: null,
    });
    expect(sentence).toContain("from 6 to 12 partitions");
    expect(sentence).toContain("adding 6 partitions");
    expect(sentence).toContain("cannot be undone");
  });

  test("counts one partition as one", () => {
    const sentence = describePartitionIncrease({
      topic: "orders.v1",
      current: 6,
      target: 7,
      added: 1,
      warnings: [],
      token: "t",
      expiresAt: null,
    });
    expect(sentence).toContain("adding 1 partition.");
  });
});
