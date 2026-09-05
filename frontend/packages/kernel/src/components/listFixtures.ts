import type { KafkaRecord } from "./record.js";

/**
 * The data the list stories are drawn from.
 *
 * ## Why the fixtures are shared and named
 *
 * A story that builds its own row inline tends to build a *comfortable* row: a short topic name, a
 * round number, a key that fits. The defects this project has paid for were all in the rows nobody
 * would have typed by hand — the group id longer than its column, the lag with five digits, the
 * payload that would not deserialize, the header with an empty value. Naming them here means every
 * story gets the awkward ones for free, and a new story has to opt *out* of them rather than
 * remember to opt in.
 *
 * The values are shaped like the screenshots so a story can be compared against them directly.
 */

/** One consumer group, as the consumer table draws it. */
export interface ConsumerGroup {
  readonly groupId: string;
  readonly state: "Stable" | "Rebalancing" | "Empty" | "Dead" | "Unknown" | "unreadable";
  readonly members: number;
  readonly topics: number;
  readonly coordinator: string | null;
  /** `null` is "we could not read it" and is drawn as a dash — never as zero. */
  readonly lag: number | null;
}

/** The four groups from screenshot `04`, with their real lag figures. */
export const CONSUMER_GROUPS: readonly ConsumerGroup[] = [
  {
    groupId: "payments-processor",
    state: "Stable",
    members: 6,
    topics: 3,
    coordinator: "broker-1:9092",
    lag: 0,
  },
  {
    groupId: "clickstream-etl",
    state: "Rebalancing",
    members: 4,
    topics: 7,
    coordinator: "broker-2:9092",
    lag: 3861,
  },
  {
    groupId: "fraud-detector",
    state: "Stable",
    members: 2,
    topics: 1,
    coordinator: "broker-3:9092",
    lag: 333,
  },
  {
    groupId: "email-dispatcher",
    state: "Stable",
    members: 1,
    topics: 2,
    coordinator: "broker-1:9092",
    lag: 18,
  },
  {
    groupId: "nightly-reconciliation",
    state: "Empty",
    members: 0,
    topics: 1,
    coordinator: "broker-2:9092",
    lag: 0,
  },
];

/**
 * The row that breaks a layout, in every way at once.
 *
 * A group id at the length Kafka actually permits (255 characters), a member count with four
 * digits, a lag past a billion, and a coordinator that could not be read. Every story that draws a
 * table draws this one too: a table that is only ever shown comfortable data is a table whose
 * overflow behaviour has never been looked at, and "the column stretched and the page scrolled
 * sideways" is a defect this project has already shipped once.
 */
export const EXTREME_GROUP: ConsumerGroup = {
  groupId:
    "connect-s3-sink-eu-west-1-partitioned-by-hour-with-a-deliberately-and-unreasonably-long-name-that-somebody-really-did-configure-in-production-and-then-asked-why-the-console-looked-wrong-about-it",
  state: "Unknown",
  members: 1024,
  topics: 512,
  coordinator: null,
  lag: 2_147_483_647,
};

/** A group whose state could not be read. Not `Unknown`: Kafka *reports* Unknown, and conflating
 * "the broker said unknown" with "we could not ask" hides an outage (design spec §4.17). */
export const UNREADABLE_GROUP: ConsumerGroup = {
  groupId: "orders-archiver",
  state: "unreadable",
  members: 3,
  topics: 2,
  coordinator: null,
  lag: null,
};

/** A fixed instant, so every relative time in a story is the same on every run and in every
 * screenshot. Stories that render "2s ago" from `Date.now()` produce a different image every time
 * and cannot be compared against the design. */
export const NOW = Date.parse("2026-03-14T12:00:00.000Z");

function at(secondsAgo: number): string {
  return new Date(NOW - secondsAgo * 1000).toISOString();
}

/** The records from screenshots `02` and `03`. */
export const RECORDS: readonly KafkaRecord[] = [
  {
    offset: "18442901",
    partition: 3,
    key: "ord_9f21ac",
    timestamp: at(2),
    timestampType: "CreateTime",
    headers: [
      { name: "content-type", value: "application/json" },
      { name: "correlation-id", value: "c_18442901" },
    ],
    value: {
      kind: "json",
      text: JSON.stringify({
        orderId: "ord_9f21ac",
        customerId: "cus_4821",
        amount: 149.99,
        currency: "EUR",
        items: [
          { sku: "KB-8821", qty: 1 },
          { sku: "MS-1140", qty: 2 },
        ],
        status: "confirmed",
      }),
    },
  },
  {
    offset: "18442900",
    partition: 1,
    key: "ord_9f21ab",
    timestamp: at(9),
    timestampType: "CreateTime",
    headers: [{ name: "content-type", value: "application/json" }],
    value: {
      kind: "json",
      text: JSON.stringify({ orderId: "ord_9f21ab", amount: 12.5, status: "pending" }),
    },
  },
  {
    offset: "18442899",
    partition: 3,
    key: "ord_9f21aa",
    timestamp: at(44),
    timestampType: "LogAppendTime",
    headers: [],
    value: { kind: "text", text: "plain,csv,row,not,json" },
  },
];

/** A tombstone: a null key and a null value. This is how a compacted topic records a deletion, and
 * it is a thing the operator needs to see rather than an error. */
export const TOMBSTONE: KafkaRecord = {
  offset: "18442898",
  partition: 7,
  key: null,
  timestamp: at(120),
  timestampType: "CreateTime",
  headers: [{ name: "reason", value: "gdpr-erasure" }],
  value: { kind: "tombstone" },
};

/** The deserializer failed. The reason is the whole diagnosis, and the raw bytes are offered
 * because they are the only thing left that is definitely true. */
export const UNDECODABLE: KafkaRecord = {
  offset: "18442897",
  partition: 2,
  key: "evt_5510",
  timestamp: at(300),
  headers: [{ name: "schema-id", value: "42" }],
  value: {
    kind: "undecodable",
    reason: "Avro schema 42 not found in registry https://schema-registry:8081",
    hex: "00 00 00 00 2a 02 06 6f 72 64 04 90 03 1c 4b 42 2d 38 38 32 31\n02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
  },
};

/** Too large to preview inline. The size is shown and the payload is fetched on demand. */
export const TOO_LARGE: KafkaRecord = {
  offset: "18442896",
  partition: 5,
  key: "batch_2026_03_14",
  timestamp: at(3600),
  headers: [{ name: "content-encoding", value: "gzip" }],
  value: { kind: "large", bytes: 4_200_000 },
};

/**
 * The record that breaks the row, in every way at once.
 *
 * An offset past 2^53 — which is why an offset is carried as a string and not a number — a key
 * longer than its column, a header with an empty value, a header that is not valid UTF-8, a deeply
 * nested payload, and a schema attached so the expansion has to draw five boxes in a grid built
 * for four.
 */
export const EXTREME_RECORD: KafkaRecord = {
  offset: "9223372036854775806",
  partition: 511,
  key: "tenant=acme-corporation-international/region=eu-central-1/shard=0000000000000000000000000042/entity=subscription-renewal-attempt",
  timestamp: at(86_400 * 3),
  timestampType: "LogAppendTime",
  schema: { subject: "orders.payments.v2-value", version: 17 },
  headers: [
    { name: "content-type", value: "application/vnd.acme.order.v2+json; charset=utf-8" },
    { name: "x-empty", value: null },
    { name: "x-signature", value: "0x9f2a1c00ffee1234", binary: true },
    {
      name: "x-b3-traceparent",
      value: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-and-then-some-more",
    },
    { name: "x-retry-count", value: "0" },
  ],
  value: {
    kind: "json",
    text: JSON.stringify({
      subscription: {
        id: "sub_00000000000000000000000042",
        tenant: { id: "acme-corporation-international", tier: "enterprise", seats: 12_500 },
        renewal: {
          attempt: 4,
          lastError:
            "card_declined: the issuing bank declined this transaction without giving a reason, which is a thing issuing banks are permitted to do",
          nextAttemptAt: "2026-03-15T09:00:00Z",
        },
        history: Array.from({ length: 12 }, (_, index) => ({
          attempt: index + 1,
          at: `2026-03-${String(index + 1).padStart(2, "0")}T09:00:00Z`,
          outcome: index % 3 === 0 ? "declined" : "retry",
        })),
      },
    }),
  },
};

/** A long list, for the windowed table. Ten thousand is past the point where every row in the
 * document is a stutter, which is the whole reason `VirtualizedTable` exists. */
export function manyTopics(count: number): readonly { name: string; partitions: number; size: number }[] {
  return Array.from({ length: count }, (_, index) => ({
    name: `orders.events.v${(index % 7) + 1}.partition-set-${String(index).padStart(6, "0")}`,
    partitions: (index % 24) + 1,
    size: (index * 7919) % 1_000_000,
  }));
}
