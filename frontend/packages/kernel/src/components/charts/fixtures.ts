/**
 * Data the stories and the tests share.
 *
 * Fixtures live in their own module so that a story and the test that asserts on the same shape
 * cannot drift apart: when the extreme case grows a longer label, both see the longer label.
 */
import type { Series } from "./plot.js";

/** Twenty-four hourly buckets, `00:00` … `23:00`, with the last one labelled `now` as the design does. */
export const HOURS: readonly string[] = Array.from({ length: 24 }, (_, i) =>
  i === 23 ? "now" : `${String(i).padStart(2, "0")}:00`
);

const PRODUCE = [
  62, 58, 71, 66, 54, 49, 41, 33, 29, 31, 38, 47, 62, 70, 78, 74, 76, 71, 83, 92, 88, 84, 79, 86,
];
const CONSUME = [
  55, 51, 66, 59, 47, 43, 36, 28, 25, 27, 33, 41, 55, 63, 70, 67, 68, 64, 75, 84, 80, 76, 71, 78,
];

/** The dashboard's throughput chart, two series over a day. */
export const THROUGHPUT: readonly Series[] = [
  { label: "produce", tone: "series-1", points: PRODUCE },
  { label: "consume", tone: "series-2", points: CONSUME },
];

/** Ticks at 00:00, 06:00, 12:00, 18:00 and now, which is what the design prints. */
export const HOUR_TICKS: readonly number[] = [0, 6, 12, 18, 23];

const LATENCY_PRODUCE = [16, 15, 17, 16, 14, 15, 18, 17, 15, 14, 16, 15, 13, 14];
const LATENCY_FETCH = [11, 10, 12, 11, 9, 10, 12, 11, 10, 9, 11, 10, 8, 9];

export const LATENCY_MINUTES: readonly string[] = [
  "-60 min",
  "-55 min",
  "-50 min",
  "-45 min",
  "-40 min",
  "-35 min",
  "-30 min",
  "-25 min",
  "-20 min",
  "-15 min",
  "-10 min",
  "-5 min",
  "-1 min",
  "now",
];

export const LATENCY: readonly Series[] = [
  { label: "produce", tone: "series-1", points: LATENCY_PRODUCE },
  { label: "fetch", tone: "series-2", points: LATENCY_FETCH },
];

/** The same latency series with the metrics exporter down for a quarter of an hour. */
export const LATENCY_WITH_GAP: readonly Series[] = [
  { label: "produce", tone: "series-1", points: [16, 15, 17, null, null, null, 18, 17, 15, 14, 16, 15, 13, 14] },
  { label: "fetch", tone: "series-2", points: [11, 10, 12, null, null, null, 12, 11, 10, 9, 11, 10, 8, 9] },
];

/**
 * The longest string that will realistically appear in a chart label. Kafka's own limit on a
 * topic name is 249 characters and a consumer group id has no limit at all, so "the longest
 * string that will ever appear" is not a hypothetical — somebody's CI names groups after a branch.
 */
export const LONG_LABEL =
  "orders.payments.reconciliation.eu-central-1.replay-2026-09-05T11:02:44Z.attempt-3.shadow-consumer";
