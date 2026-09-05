/**
 * The data the stories and the tests draw.
 *
 * The six groups are the ones in screenshot `04`, with their real numbers, so a story can be put
 * beside the PNG and compared. The extremes are here too, because the states that break a layout
 * are the ones nobody can produce on a healthy cluster: a group id long enough to push the actions
 * off the page, a lag at `Number.MAX_SAFE_INTEGER`, and every value that can be absent, absent.
 */

import type { GroupSummary } from "./model.js";
import type { GroupDetail, PlannedPartition, ResetPlan } from "./detail.js";

function group(row: Partial<GroupSummary> & Pick<GroupSummary, "groupId">): GroupSummary {
  return {
    state: "STABLE",
    members: 0,
    topics: 1,
    coordinator: "broker-1:9092",
    totalLag: 0,
    excludedPartitions: 0,
    incomplete: null,
    ...row,
  };
}

/** Screenshot `04`, row for row. */
export const SAMPLE_GROUPS: readonly GroupSummary[] = [
  group({ groupId: "payments-processor", members: 8, topics: 2, coordinator: "broker-1:9092", totalLag: 0 }),
  group({ groupId: "email-dispatcher", members: 4, topics: 1, coordinator: "broker-2:9092", totalLag: 18 }),
  group({ groupId: "clickstream-etl", state: "PREPARING_REBALANCE", members: 12, topics: 3, coordinator: "broker-3:9092", totalLag: 3_861 }),
  group({ groupId: "fraud-detector", members: 6, topics: 2, coordinator: "broker-1:9092", totalLag: 333 }),
  group({ groupId: "stock-sync", members: 3, topics: 1, coordinator: "broker-2:9092", totalLag: 0 }),
  group({ groupId: "audit-archiver", state: "EMPTY", members: 0, topics: 1, coordinator: "broker-3:9092", totalLag: 0 }),
];

/** Every way a row can be missing something, in one table. */
export const DEGRADED_GROUPS: readonly GroupSummary[] = [
  group({
    groupId: "orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing",
    state: "COMPLETING_REBALANCE",
    members: 24,
    topics: 7,
    totalLag: 9_007_199_254_740_991,
  }),
  group({ groupId: "unreadable-lag", members: 2, totalLag: null, incomplete: { note: "The coordinator did not answer, so this row is incomplete.", offsetsKnown: false, membersKnown: true, endOffsetsKnown: false } }),
  group({ groupId: "unreadable-members", members: null, totalLag: 412 }),
  group({ groupId: "no-coordinator", state: null, coordinator: null, members: 1, totalLag: 120_004 }),
  group({ groupId: "partly-counted", members: 3, totalLag: 780, excludedPartitions: 4 }),
  group({ groupId: "dead-group", state: "DEAD", members: 0, totalLag: 0 }),
];

export const SAMPLE_GROUP_DETAIL: GroupDetail = {
  groupId: "clickstream-etl",
  state: "PREPARING_REBALANCE",
  coordinator: "broker-3:9092",
  partitionAssignor: "cooperative-sticky",
  protocol: "CONSUMER",
  isSimple: false,
  totalLag: 3_861,
  pace: 412.5,
  excludedPartitions: 0,
  observedAt: new Date("2026-09-05T09:14:00Z"),
  members: [
    { memberId: "consumer-1-8f2a", clientId: "etl-worker-a", host: "/10.4.1.17", groupInstanceId: null, partitions: ["clickstream-0", "clickstream-1"], rebalancing: false },
    { memberId: "consumer-1-4c19", clientId: "etl-worker-b", host: "/10.4.1.18", groupInstanceId: null, partitions: ["clickstream-2"], rebalancing: true },
    { memberId: "consumer-1-77bd", clientId: "etl-worker-c", host: "/10.4.2.3", groupInstanceId: null, partitions: [], rebalancing: false },
  ],
  offsets: [
    { topic: "clickstream", partition: 0, committed: 1_204_998, endOffset: 1_205_112, memberId: "consumer-1-8f2a" },
    { topic: "clickstream", partition: 1, committed: 1_198_440, endOffset: 1_202_001, memberId: "consumer-1-8f2a" },
    { topic: "clickstream", partition: 2, committed: 1_201_300, endOffset: 1_201_486, memberId: "consumer-1-4c19" },
    { topic: "clickstream", partition: 3, committed: null, endOffset: 990_112, memberId: null },
    { topic: "sessions", partition: 0, committed: 44_120, endOffset: null, memberId: null },
  ],
};

function planned(partition: number, current: number | null, proposed: number): PlannedPartition {
  return { partition, current, proposed, delta: current === null ? null : proposed - current };
}

export const SAMPLE_PLAN: ResetPlan = {
  token: "plan_01JB6Q2C7K8M",
  topic: "clickstream",
  noOp: false,
  expiresAt: new Date("2026-09-05T09:19:00Z"),
  warnings: [
    { kind: "clamped", message: "Partition 3 has no record at or after that time, so it would move to its end offset — forwards, not backwards. KIP-122." },
    { kind: "retention", message: "Partition 1's earliest record is newer than the time you asked for; it would move to the oldest record it still has." },
  ],
  partitions: [planned(0, 1_204_998, 1_200_000), planned(1, 1_198_440, 1_198_440), planned(2, 1_201_300, 1_190_000), planned(3, null, 990_112)],
};

export const NO_OP_PLAN: ResetPlan = {
  ...SAMPLE_PLAN,
  noOp: true,
  warnings: [],
  partitions: [planned(0, 1_204_998, 1_204_998), planned(1, 1_198_440, 1_198_440)],
};
