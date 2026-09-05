import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { TopicPartitions } from "./TopicPartitions.jsx";
import { TopicConsumers } from "./TopicConsumers.jsx";
import { AddPartitionsDialog } from "./AddPartitionsDialog.jsx";
import { PlannedActionDialog } from "./PlannedActionDialog.jsx";
import { describePartitionIncrease } from "./TopicsRoute.jsx";
import type { PartitionRow, TopicConsumerRow } from "./data.js";
import type { PartitionPlan } from "./write.js";

/**
 * The Partitions tab.
 *
 * Half of these stories are of something being wrong, and that is the point of having them: a
 * healthy partition table is six identical rows and needs no design review. What needs reviewing is
 * whether a reader can tell an offline partition from an unread one, a sum from a partial sum, and
 * a service that is down from a topic that has nothing in it — because every one of those pairs
 * renders identically if a rule is missed, and each is a different decision by the operator.
 */

/** Three replicas, all in sync, on a topic somebody is actually using. */
function healthy(partition: number, records: number): PartitionRow {
  return {
    partition,
    leader: 1 + (partition % 3),
    replicas: [1, 2, 3],
    inSync: [1, 2, 3],
    earliestOffset: 0,
    latestOffset: records,
    messageCount: records,
    sizeBytes: records * 512,
  };
}

const HEALTHY: readonly PartitionRow[] = [
  healthy(0, 18_442),
  healthy(1, 17_990),
  healthy(2, 18_104),
  healthy(3, 18_331),
  healthy(4, 17_886),
  healthy(5, 18_207),
];

const meta: Meta<typeof TopicPartitions> = {
  title: "Screens/Topic partitions",
  component: TopicPartitions,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof meta>;

/** The ordinary case: six partitions, evenly loaded, nothing to say about any of them. */
export const Healthy: Story = {
  args: { partitions: HEALTHY, onAdd: () => undefined },
};

/**
 * One partition has lost a replica.
 *
 * Still readable and still writable — the leader is there — so the sentence says so rather than
 * raising an alarm. `2 of 3` is amber; the five rows that are complete are not, because a colour
 * that appears on every row is a colour that means nothing.
 */
export const UnderReplicated: Story = {
  args: {
    partitions: [
      ...HEALTHY.slice(0, 3),
      { ...healthy(3, 18_331), inSync: [1, 2] },
      ...HEALTHY.slice(4),
    ],
    onAdd: () => undefined,
  },
};

/**
 * One partition has no leader at all.
 *
 * The row that this whole screen's rendering rules exist for. `leader: null` is the cluster saying
 * there **is** no leader — the partition is neither readable nor writable — so the cell says "none"
 * in danger colours, while the counts beside it, which need a leader to answer for them, say "not
 * known" as a dash. If those two were drawn the same way an operator could not tell an outage from
 * a gap in KUI's knowledge, and the topic's total below would be a sum over five partitions
 * presented as a sum over six.
 */
export const OneOffline: Story = {
  args: {
    partitions: [
      ...HEALTHY.slice(0, 4),
      {
        partition: 4,
        leader: null,
        replicas: [1, 2, 3],
        inSync: [],
        earliestOffset: null,
        latestOffset: null,
        messageCount: null,
        sizeBytes: null,
      },
      ...HEALTHY.slice(5),
    ],
    onAdd: () => undefined,
  },
};

/**
 * A single-broker cluster, which is what a quickstart and most laptops have.
 *
 * Every partition has one replica and no per-partition size, because a broker reports size only
 * where there is a metrics source for it. Six dashes down the Size column, and not six zeroes: a
 * partition holding eighteen thousand records does not occupy no disk.
 */
export const NoSizesReported: Story = {
  args: {
    partitions: HEALTHY.map((row) => ({
      ...row,
      leader: 1,
      replicas: [1],
      inSync: [1],
      sizeBytes: null,
    })),
    onAdd: () => undefined,
  },
};

/**
 * A topic with nothing in it.
 *
 * Every offset is zero and every count is zero, and every one of those zeroes is a **fact** — the
 * topic exists, has six partitions and holds nothing. This is the story to compare against
 * `OneOffline`: the two must not look alike.
 */
export const Empty: Story = {
  args: {
    partitions: [0, 1, 2, 3, 4, 5].map((partition) => ({
      partition,
      leader: 1,
      replicas: [1],
      inSync: [1],
      earliestOffset: 0,
      latestOffset: 0,
      messageCount: 0,
      sizeBytes: 0,
    })),
    onAdd: () => undefined,
  },
};

/** While the request is out. The table keeps its header, so the columns still say what is coming. */
export const Loading: Story = {
  args: { partitions: [], loading: true, onAdd: () => undefined },
};

/**
 * The principal may look at the topic but not change it.
 *
 * Disabled with the reason on it, never hidden. A hidden button makes an operator believe the
 * product cannot do the thing at all, and they go looking for a command line rather than for
 * somebody who can grant them the role.
 */
export const CannotAddPartitions: Story = {
  args: {
    partitions: HEALTHY,
    onAdd: () => undefined,
    addDisabledReason:
      "You do not hold a role that permits adding partitions to this topic. Ask an administrator for the topic edit permission on this cluster.",
  },
};

/**
 * The cluster service did not answer.
 *
 * The error code is on screen. It means nothing to the operator and everything to whoever they
 * paste it to, and swallowing it turns a five-minute support conversation into an hour.
 */
export const Unavailable: Story = {
  args: {
    partitions: [],
    failure: {
      kind: "unavailable",
      message: "The cluster service did not answer within the gateway's timeout.",
      code: "KUI-UPSTREAM-UNAVAILABLE",
      onRetry: () => undefined,
    },
  },
};

/** A 403 on the partitions alone. Not a failure with a retry — there is nothing to retry. */
export const Forbidden: Story = {
  args: {
    partitions: [],
    failure: {
      kind: "forbidden",
      message:
        "Ask an administrator for a role that includes this cluster's topic read permissions.",
      code: "FORBIDDEN",
    },
  },
};

/**
 * A great many partitions.
 *
 * The reason this tab exists. The overview's own partition list stops at 500, so on a topic like
 * this one it would be short by more than half with only an envelope flag to say so. This table is
 * the whole topic.
 */
export const Thousands: Story = {
  args: {
    partitions: Array.from({ length: 1024 }, (_, partition) => healthy(partition, 400 + partition)),
    onAdd: () => undefined,
  },
};

/* ---------------------------------------------------------------------------------------------- */
/* The Consumers tab                                                                                */
/* ---------------------------------------------------------------------------------------------- */

function reader(overrides: Partial<TopicConsumerRow> = {}): TopicConsumerRow {
  return {
    groupId: "order-fulfilment",
    state: "STABLE",
    members: 6,
    topicLag: 42,
    partitions: 6,
    dormant: false,
    totalLag: 42,
    topics: 1,
    ...overrides,
  };
}

/*
 * One `meta` per file is CSF's rule, and the file's meta is the partition table above. The stories
 * below render the other two components directly, which is what `write.stories.tsx` does for the
 * settings tab in the same situation.
 */
type ConsumerStory = StoryObj<typeof TopicConsumers>;

const hrefFor = (groupId: string): string =>
  `/clusters/quickstart/consumer-groups/${groupId}`;

/** Three groups reading the topic, all of them keeping up. */
export const ConsumersHealthy: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: {
    hrefFor,
    rows: [
      reader(),
      reader({ groupId: "orders-search-indexer", members: 3, topicLag: 0, totalLag: 0 }),
      reader({ groupId: "orders-audit", members: 1, topicLag: 12, totalLag: 12 }),
    ],
  },
};

/**
 * Nothing reads this topic.
 *
 * Completely ordinary, and the sentence says so — a topic written by one service and read by a sink
 * connector has no consumer group at all. It also names the case that otherwise generates a support
 * question: anything consuming *without* a group never appears here.
 */
export const NoGroups: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: { hrefFor, rows: [] },
};

/**
 * A group that is far behind here, and a group that is far behind somewhere else.
 *
 * `orders-archive` is 4.2 million records behind on this topic. `analytics-fanout` is 12 records
 * behind here and four million behind across the five topics it reads — and without the Elsewhere
 * column its `12` would read as a group that is fine, which it is, *on this topic*, which is the
 * only claim this screen is entitled to make.
 */
export const Behind: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: {
    hrefFor,
    rows: [
      reader({ groupId: "orders-archive", topicLag: 4_211_004, totalLag: 4_211_004 }),
      reader({ groupId: "orders-reporting", topicLag: 3_861, totalLag: 3_861 }),
      reader({
        groupId: "analytics-fanout",
        topicLag: 12,
        totalLag: 4_003_918,
        topics: 5,
        members: 12,
      }),
    ],
  },
};

/**
 * A dormant group and one whose lag could not be computed.
 *
 * `nightly-reconciliation` has offsets and no members — which is what a scheduled job looks like
 * between runs and what an abandoned consumer looks like, and KUI does not pretend to tell them
 * apart. Its zero members is a *fact* and prints as `0`. `orders-legacy`'s lag is a dash: its
 * coordinator did not answer, and a group whose lag KUI could not compute has not caught up.
 */
export const DormantAndUnreadable: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: {
    hrefFor,
    rows: [
      reader({
        groupId: "nightly-reconciliation",
        state: "EMPTY",
        members: 0,
        dormant: true,
        topicLag: 18_004,
        totalLag: 18_004,
      }),
      reader({ groupId: "orders-legacy", state: "UNKNOWN", topicLag: null, totalLag: null }),
      reader({ groupId: "orders-rebalancing", state: "PREPARING_REBALANCE", members: 4 }),
      reader({ groupId: "orders-dead", state: "DEAD", members: 0, topicLag: 0, totalLag: 0 }),
    ],
  },
};

/** While the request is out. */
export const ConsumersLoading: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: { hrefFor, rows: [], loading: true },
};

/**
 * The consumer service is down.
 *
 * Compare with `NoGroups`, which is the story this one must never be mistaken for: "nobody reads
 * this topic" and "we could not find out who reads this topic" send an operator in opposite
 * directions.
 */
export const ConsumersUnavailable: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: {
    hrefFor,
    rows: [],
    failure: {
      kind: "unavailable",
      message: "The consumer service did not answer within the gateway's timeout.",
      code: "KUI-UPSTREAM-UNAVAILABLE",
      onRetry: () => undefined,
    },
  },
};

/** The principal may see the topic and not who reads it. */
export const ConsumersForbidden: ConsumerStory = {
  render: (args) => <TopicConsumers {...args} />,
  args: {
    hrefFor,
    rows: [],
    failure: {
      kind: "forbidden",
      message:
        "Ask an administrator for a role that includes this cluster's consumer group read permissions.",
      code: "FORBIDDEN",
    },
  },
};

/* ---------------------------------------------------------------------------------------------- */
/* Choosing a partition count                                                                       */
/* ---------------------------------------------------------------------------------------------- */

type AddStory = StoryObj<typeof AddPartitionsDialog>;

const addBase = {
  open: true,
  onClose: () => undefined,
  onContinue: () => undefined,
  topicName: "orders.payments.v2",
};

/**
 * The form as it opens.
 *
 * Pre-filled with one more than the current count — the smallest legal answer, and therefore the
 * least opinionated. Doubling is the folklore and this form is in no position to recommend it: the
 * right number depends on the consumer group's size and the throughput, and neither is on screen.
 *
 * The warning is here, at the point of choice, and not only on the confirmation that follows.
 * Learning that per-key ordering breaks *after* picking a number is learning it too late to change
 * the number.
 */
export const AddPartitionsOpen: AddStory = {
  render: (args) => <AddPartitionsDialog {...args} />,
  args: { ...addBase, current: 6 },
};

/**
 * A topic KUI could not describe.
 *
 * No current count, so the form declines to check the number at all and says why, rather than
 * pre-filling a plausible one. A `1` on a topic that has twelve partitions is a wrong answer
 * wearing a right answer's shape, and the server would refuse it a screen later.
 */
export const AddPartitionsWithoutACount: AddStory = {
  render: (args) => <AddPartitionsDialog {...args} />,
  args: { ...addBase, current: undefined },
};

/**
 * A topic with a great many partitions already.
 *
 * Nothing special happens, which is the point: there is no upper limit in this form, because the
 * limit is the broker's and belongs to the broker's refusal rather than to a number invented here.
 */
export const AddPartitionsToALargeTopic: AddStory = {
  render: (args) => <AddPartitionsDialog {...args} />,
  args: { ...addBase, topicName: "analytics.pageviews", current: 1_024 },
};

/* ---------------------------------------------------------------------------------------------- */
/* Confirming the increase                                                                          */
/* ---------------------------------------------------------------------------------------------- */

/*
 * The second half of the flow, and the half that is worth reviewing.
 *
 * `PlannedActionDialog` asks the server what the change would do the moment it opens, so each story
 * below is really a story about *what the server said* — and the three that matter are a plan that
 * arrives, a plan that never arrives, and a plan the server computed but will not let this caller
 * apply. All three are reachable in production and only the first is reachable from a healthy
 * cluster with a permitted operator.
 *
 * The sentences on these dialogs are the **server's own**, not composed here. See `consequenceOf`:
 * the server knows the counts, writes the better sentence, and a client that composed one alongside
 * gave the operator the same fact twice, in two phrasings, with two different numbers.
 */
type ConfirmStory = StoryObj<typeof PlannedActionDialog<PartitionPlan>>;

const KEY_ROUTING =
  "Records are routed by hash(key) % partitions, so raising the count from 6 to 12 sends most keys to a different partition from the records already stored under them. Per-key ordering is broken across the change, and it cannot be undone: Kafka has no way to remove a partition.";

const confirmBase = {
  open: true,
  onClose: () => undefined,
  onConfirm: () => undefined,
  title: "Add partitions to orders.payments.v2?",
  confirmLabel: "Add partitions",
  confirmIcon: "plus" as const,
  destructive: false,
  typeToConfirm: "orders.payments.v2",
  planningMessage: "Asking the cluster what this would change…",
  describe: describePartitionIncrease,
  state: { kind: "idle" } as const,
};

/**
 * The plan arrived.
 *
 * The confirm button stays unusable until the topic's name has been typed. This action destroys no
 * record, so it is not styled as destruction — but Kafka cannot remove a partition afterwards, and
 * the kernel's test for asking somebody to type a name is undo-ability rather than destruction.
 */
export const ConfirmIncrease: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: {
    ...confirmBase,
    plan: () =>
      Promise.resolve({
        topic: "orders.payments.v2",
        current: 6,
        target: 12,
        added: 6,
        warnings: [{ code: "KEY_ROUTING_CHANGES", message: KEY_ROUTING }],
        token: "a-plan-token",
        expiresAt: "2026-09-06T10:05:00Z",
      }),
  },
};

/**
 * The plan is still out.
 *
 * A separate dialog rather than a spinner inside the confirmation, because a confirmation whose
 * figures arrive after its button does can be agreed to by somebody who has read nothing. The
 * sentence names *change* and not destruction, which is what `planningMessage` exists for: telling
 * an operator KUI is working out what this "would destroy" when it destroys nothing is the product
 * inventing a consequence.
 */
export const PlanningIncrease: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: { ...confirmBase, plan: () => new Promise(() => undefined) },
};

/**
 * The server refused to plan it.
 *
 * This is what asking to *shrink* a topic looks like, and the message is the gateway's own, quoted
 * verbatim from a real refusal. There is no confirm button at all: without a token there is nothing
 * to send, and a button that cannot work is worse than an absent one.
 */
export const IncreaseRefused: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: {
    ...confirmBase,
    plan: () =>
      Promise.resolve({
        failure:
          "'orders.payments.v2' already has 6 partitions, and Kafka cannot remove one; a partition count can only be increased",
      }),
  },
};

/**
 * A read-only cluster.
 *
 * The server computes the plan and withholds the token, so the operator can see exactly what would
 * happen and cannot do it. `consequenceOf` appends the reason; the confirm button is inert.
 */
export const IncreaseOnAReadOnlyCluster: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: {
    ...confirmBase,
    plan: () =>
      Promise.resolve({
        topic: "orders.payments.v2",
        current: 6,
        target: 12,
        added: 6,
        warnings: [{ code: "KEY_ROUTING_CHANGES", message: KEY_ROUTING }],
        token: null,
        expiresAt: null,
      }),
  },
};

/**
 * The token had run out by the time it was confirmed.
 *
 * The dialog stays open with the failure on it. It deliberately does not re-plan: a fresh plan
 * would quietly widen what is being agreed to at the exact moment the operator believes they are
 * confirming what they just read.
 */
export const IncreaseTokenExpired: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: {
    ...confirmBase,
    state: {
      kind: "failed",
      message:
        "this confirmation is no longer valid; ask for the change to be planned again and confirm the plan that comes back",
      code: "KUI-VALIDATION",
    },
    plan: () =>
      Promise.resolve({
        topic: "orders.payments.v2",
        current: 6,
        target: 12,
        added: 6,
        warnings: [{ code: "KEY_ROUTING_CHANGES", message: KEY_ROUTING }],
        token: "a-plan-token",
        expiresAt: "2026-09-06T10:05:00Z",
      }),
  },
};

/**
 * A plan with no warning of its own.
 *
 * The server always sends `KEY_ROUTING_CHANGES` today, so this is the fallback path — and it is
 * here because a dialog with a blank consequence is the failure that would otherwise ship the day a
 * server stops sending one. `describePartitionIncrease` states the change and its irreversibility
 * and nothing else.
 */
export const IncreaseWithNoServerWarning: ConfirmStory = {
  render: (args) => <PlannedActionDialog<PartitionPlan> {...args} />,
  args: {
    ...confirmBase,
    plan: () =>
      Promise.resolve({
        topic: "orders.payments.v2",
        current: 6,
        target: 7,
        added: 1,
        warnings: [],
        token: "a-plan-token",
        expiresAt: "2026-09-06T10:05:00Z",
      }),
  },
};
