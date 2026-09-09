/**
 * The documents the **server's own encoder** renders, decoded by the browser that reads them.
 *
 * ## Why this file exists
 *
 * Wave 5 shipped four new metrics wires. Two of them did not match the browser: the service sent
 * `requestHandlers.data.{requestHandlerIdleRatio, networkProcessorIdleRatio, purgatory[]}` and this
 * package read `data.readings[]`; the service sent `producers.data.{measuredBy, topics[]}` and this
 * package read `data.entries[]`. Every gate was green. Both sides had unit cases against their own
 * hand-written literal, the top-level `Section` key matched on both sides so the decode *succeeded*
 * and answered an empty array, and two cards then drew a confident false sentence — "the metrics
 * source answered and named no producers" — over a source that had named five. The browser cases
 * written to catch exactly that drift read the same wrong names, so their loops ran zero times.
 *
 * A hand-written literal on each side is what produced that, so this file has none. It reads
 * `services/metrics/contract/test/resources/golden/*.json` off disk — the files
 * `MetricsResponsesSuite` asserts are exactly what `ThroughputResponse.asJson` and friends produce —
 * and pushes them through the same fetchers the screen calls. A shape that drifts from the service
 * now fails here rather than answering an empty array in production.
 *
 * ## Why the files and not a copy of them
 *
 * A copy is a third literal. `vitest` runs in node and can read the repository, so the artefact
 * these cases assert against is the artefact the Scala suite asserts against — one document, two
 * languages. `controls.test.tsx` reads a stylesheet off disk for the same reason.
 *
 * If this file cannot find a golden it fails loudly rather than skipping: a suite that quietly
 * passes when its fixture has moved is the failure mode the whole file is about.
 */

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { KuiApiClient } from "@kui/api";

import {
  fetchLatency,
  fetchRecordSize,
  fetchRequestHandlers,
  fetchTopProducers,
  handlerPanel,
  latencyChart,
  producerBoard,
  recordSizeReadout,
} from "./metrics.js";
import { fetchThroughput } from "./throughput.js";

/** The metrics contract module's committed documents, five directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "..",
  "..",
  "services",
  "metrics",
  "contract",
  "test",
  "resources",
  "golden",
);

/** One committed document, parsed. A missing file is a failure and never a skip. */
function golden(name: string): unknown {
  const path = join(GOLDEN, name);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (cause) {
    throw new Error(
      `${name} is not where this suite expects the metrics goldens to be (${path}). ` +
        `They are committed by services/metrics/contract; if they moved, this suite moves with them.`,
      { cause },
    );
  }
  return JSON.parse(text) as unknown;
}

/**
 * A gateway that answers one document, and records nothing else.
 *
 * Deliberately the same erasure the real client goes through: `readMetric` casts `api.get`, so a
 * stub that were typed differently would exercise a path the product does not take.
 */
function serving(body: unknown): KuiApiClient {
  const answer = async () => ({ ok: true, value: body });
  return { get: answer, post: answer, put: answer, delete: answer, patch: answer, raw: {} } as unknown as KuiApiClient;
}

describe("a document the metrics service rendered", () => {
  it("decodes into request-handler gauges in the browser", async () => {
    // The required case, and the one the wave exists for. Not "the decode succeeds": the decode
    // succeeded before this wave too, and answered nothing.
    const state = await fetchRequestHandlers(serving(golden("request-handlers-response.json")), "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    const panel = handlerPanel(state.value);
    expect(panel.gauges.map((gauge) => gauge.id)).toEqual([
      "network-idle",
      "io-idle",
      "purgatory-fetch",
      "purgatory-produce",
    ]);
    expect(panel.gauges[0]?.percent).toBeCloseTo(71.04);
    expect(panel.gauges[1]?.percent).toBeCloseTo(89.12);
    expect(panel.gauges[2]?.count).toBe(481);
    expect(panel.gauges[2]?.kind).toBe("count");
    expect(panel.caption).toContain("queue lengths");
  });

  it("decodes into top-producer rows in the browser", async () => {
    const state = await fetchTopProducers(serving(golden("top-producers-response.json")), "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    const board = producerBoard(state.value);
    expect(board.rows.map((row) => row.id)).toEqual([
      "orders.payments",
      "analytics.clicks",
      "audit.trail",
    ]);
    expect(board.rows[0]?.bytesPerSecond).toBe(5_400_000);
    // A measured zero survives as a zero: `audit.trail` is served and quiet, which is not the same
    // as a rate that never arrived.
    expect(board.rows[2]?.bytesPerSecond).toBe(0);
    expect(board.subject).toBe("topic");
    expect(board.internalTopicsExcluded).toBe(2);
  });

  it("decodes into a latency chart whose gaps are gaps", async () => {
    const state = await fetchLatency(serving(golden("latency-response.json")), "quickstart", "24h");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    const chart = latencyChart(state.value, "24h");
    expect(chart.produce).toEqual([9, 7.5, null]);
    expect(chart.fetch).toEqual([502, null, null]);
    // The newest step that measured anything, which is what the legend chip prints.
    expect(chart.latest).toEqual({ produce: 7.5, fetch: null });
  });

  it("decodes into a throughput series whose null and zero stay different facts", async () => {
    const state = await fetchThroughput(serving(golden("throughput-response.json")), "quickstart", "24h");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    expect(state.value.buckets?.map((bucket) => bucket.bytesInPerSecond)).toEqual([124800.5, null, 0]);
  });

  it("decodes into a record-size readout that is a mean and nothing else", async () => {
    const state = await fetchRecordSize(serving(golden("record-size-response.json")), "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    expect(recordSizeReadout(state.value).meanBytes).toBe(128);
  });

  it("reads an hour-old reading as stale rather than as current", async () => {
    // The fourth section state, which `services/metrics` produces for a gauge whose newest scrape is
    // older than one `kui.metrics.scrapeInterval`. The figures are true and are not current, and the
    // browser has to keep both halves: a `failed` here would throw away a real reading and offer a
    // Retry, and a `ready` would draw an hour-old idle ratio as the broker's present state.
    const state = await fetchRequestHandlers(
      serving(golden("request-handlers-response-stale.json")),
      "quickstart",
    );
    expect(state.kind).toBe("stale");
    if (state.kind !== "stale") return;

    expect(handlerPanel(state.value).gauges[0]?.percent).toBeCloseTo(71.04);
    expect(state.reason.length).toBeGreaterThan(0);
  });

  it("reads a not_configured section as its own state and never as a failure", async () => {
    // The answer for every deployment that configured no exporter. Folded into `failed` it puts a
    // red panel and a Retry in front of an operator who never asked for metrics.
    const state = await fetchTopProducers(serving(golden("top-producers-not-configured.json")), "quickstart");
    expect(state.kind).toBe("not-configured");
  });

  it("reads an unavailable section as a failure carrying the service's own sentence", async () => {
    const state = await fetchRecordSize(serving(golden("record-size-unavailable.json")), "quickstart");
    expect(state.kind).toBe("failed");
    if (state.kind !== "failed") return;

    expect(state.message).toContain("carry no bytes-in and records-in rate");
  });

  it("reads a served family with no topic line as an empty ranking, which is an answer", async () => {
    const state = await fetchTopProducers(serving(golden("top-producers-empty.json")), "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    const board = producerBoard(state.value);
    expect(board.rows).toEqual([]);
    expect(board.subject).toBe("topic");
  });

  it("reads a ratio the exporter did not measure as absent, beside one it did", async () => {
    const state = await fetchRequestHandlers(
      serving(golden("request-handlers-one-absent.json")),
      "quickstart",
    );
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;

    const gauges = handlerPanel(state.value).gauges;
    expect(gauges.find((gauge) => gauge.id === "io-idle")?.percent).toBeUndefined();
    expect(gauges.find((gauge) => gauge.id === "network-idle")?.percent).toBeCloseTo(71.04);
  });
});
