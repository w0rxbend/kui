/**
 * Records to draw the browser with, in every shape a record can be in.
 *
 * The awkward ones are here deliberately: a tombstone, a payload that would not deserialize, one
 * too large to preview, a key that is a big-endian long the service read as four characters of
 * nonsense, and the longest real Kafka topic name. A story set that only holds well-formed JSON is
 * a story set that never shows the states this screen exists for.
 */

import type { KafkaRecord } from "@kui/kernel";

/** A fixed instant, so every story renders the same relative times on every run. */
export const NOW = Date.parse("2026-09-05T10:00:10Z");

function at(secondsAgo: number): string {
  return new Date(NOW - secondsAgo * 1000).toISOString();
}

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
      text: '{"orderId":"ord_9f21ac","amount":1249.00,"currency":"UAH","status":"CAPTURED"}',
    },
  },
  {
    offset: "18442900",
    partition: 7,
    key: "ord_b8e044",
    timestamp: at(2),
    headers: [],
    value: {
      kind: "json",
      text: '{"orderId":"ord_b8e044","amount":89.99,"currency":"EUR","status":"AUTHORIZED"}',
    },
  },
  {
    // A tombstone: a null key *and* a null value. This is how a compacted topic records a
    // deletion. It is a Kafka concept the operator needs to see, not an error.
    offset: "18442899",
    partition: 1,
    key: null,
    timestamp: at(3),
    headers: [{ name: "reason", value: "" }],
    value: { kind: "tombstone" },
  },
  {
    // The failure travels with the record; the record is still delivered, through the fallback.
    offset: "18442898",
    partition: 3,
    key: "ord_0ac913",
    timestamp: at(5),
    headers: [{ name: "x-raw", value: "0x0a1b2c3d", binary: true }],
    value: {
      kind: "undecodable",
      reason: "Avro schema 42 not found in the registry",
      hex: "0x0a1b2c3d4e5f60718293a4b5c6d7e8f9",
    },
  },
  {
    offset: "18442897",
    partition: 11,
    key: "ord_5d2f7b",
    timestamp: at(5),
    headers: [],
    value: { kind: "large", bytes: 4_200_000 },
  },
  {
    // Not JSON, and that is fine: plain text, CSV, a protobuf rendered as text.
    offset: "18442896",
    partition: 0,
    key: "ord_e19c22",
    timestamp: at(8),
    timestampType: "LogAppendTime",
    headers: [],
    value: { kind: "text", text: "ord_e19c22,74.20,PLN,DECLINED,insufficient_funds" },
  },
  {
    offset: "18442895",
    partition: 5,
    key: "ord_31b09e",
    timestamp: at(9),
    headers: [],
    value: {
      kind: "json",
      text: '{"orderId":"ord_31b09e","amount":560.00,"currency":"UAH","status":"CAPTURED"}',
    },
    schema: { subject: "orders.payments.v2-value", version: 4 },
  },
];

/** The longest topic name this product has met. Every story that draws a name uses it somewhere. */
export const LONG_TOPIC = "orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing";
