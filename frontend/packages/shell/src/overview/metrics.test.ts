/**
 * The four folds behind the Traffic tab's other cards.
 *
 * The rendering is asserted in `overview.render.test.tsx`, at the seam where a person meets it. This
 * file is about the arithmetic underneath, and every case in it is one of three rules:
 *
 *  - a reading the server did not send stays absent all the way through, and never becomes a zero;
 *  - a ratio is a fraction and a count is a count, and neither borrows the other's picture;
 *  - the word the card prints is the word the *server* used, not the word the design drew.
 */

import { describe, expect, it } from "vitest";
import type { KuiApiClient } from "@kui/api";

import {
  fetchLatency,
  fetchRecordSize,
  fetchRequestHandlers,
  fetchTopProducers,
  formatMillis,
  handlerPanel,
  hasLatencyReading,
  latencyChart,
  latencyKey,
  producerBoard,
  producersKey,
  recordSizeReadout,
  requestHandlersKey,
  type LatencyBucket,
  type LatencySeries,
} from "./metrics.js";
import {
  HANDLERS_NOT_CONFIGURED,
  LATENCY_NOT_CONFIGURED,
  PRODUCERS_BY_CLIENT,
  PRODUCERS_BY_TOPIC,
  RECORD_SIZE_MEAN,
  handlersOk,
  latencyOk,
  producersOk,
  recordSizeOk,
} from "./fixtures.js";

const at = (minutes: number): string => new Date(Date.UTC(2026, 8, 5, 0, minutes)).toISOString();

const sampled = (minutes: number, produce: number, fetched: number): LatencyBucket => ({
  startingAt: at(minutes),
  produceP99Millis: produce,
  fetchP99Millis: fetched,
});

const blank = (minutes: number): LatencyBucket => ({
  startingAt: at(minutes),
  produceP99Millis: null,
  fetchP99Millis: null,
});

const series = (buckets: readonly LatencyBucket[], stepSeconds = 300): LatencySeries => ({
  range: "24h",
  stepSeconds,
  buckets,
});

describe("a latency the server did not send", () => {
  it("stays absent, and a measured zero stays a zero", () => {
    // The whole file in one case, and the same one `throughput.test.ts` opens with. A broker that
    // answered inside its own clock resolution reported `0`; a broker nothing scraped reported
    // nothing, and drawing the second as the first says the cluster is answering instantly.
    const chart = latencyChart(series([sampled(0, 0, 0), blank(5), sampled(10, 12, 34)]), "24h");
    expect(chart.produce).toEqual([0, null, 12]);
    expect(chart.fetch).toEqual([0, null, 34]);
  });

  it("counts a step absent only when neither percentile was measured", () => {
    // An exporter's ruleset can whitelist `Produce` and not `FetchConsumer`. That is an ordinary
    // configuration, and calling the step unmeasured would report a gap that is not there.
    const chart = latencyChart(
      series([{ startingAt: at(0), produceP99Millis: 7, fetchP99Millis: null }]),
      "24h",
    );
    expect(chart.absentBuckets).toBe(0);
    expect(chart.fetch).toEqual([null]);
  });

  it("reads the other spelling of each field, because the contract writes it twice", () => {
    const chart = latencyChart(
      series([{ at: at(0), produceP99: 4, fetchP99: 9 }] as readonly LatencyBucket[]),
      "24h",
    );
    expect(chart.produce).toEqual([4]);
    expect(chart.fetch).toEqual([9]);
    expect(chart.categories[0]).not.toBe("");
  });

  it("takes the newest measured bucket as the legend's current value, not the last bucket", () => {
    // The last bucket of a live series is very often the one still being filled.
    const chart = latencyChart(series([sampled(0, 1, 2), sampled(5, 3, 4), blank(10)]), "24h");
    expect(chart.latest).toEqual({ produce: 3, fetch: 4 });
  });

  it("has no current value at all when nothing in the window was measured", () => {
    const chart = latencyChart(series([blank(0), blank(5)]), "24h");
    expect(chart.latest).toBeUndefined();
    expect(hasLatencyReading(series([blank(0)]))).toBe(false);
    expect(hasLatencyReading(series([sampled(0, 0, 0)]))).toBe(true);
  });

  it("draws every bucket the server sent, and labels the blank ones too", () => {
    const chart = latencyChart(series([sampled(0, 1, 1), blank(5), blank(10)]), "24h");
    expect(chart.categories).toHaveLength(3);
    expect(chart.categories.every((label) => label.length > 0)).toBe(true);
  });
});

describe("the sentence under the latency chart", () => {
  it("says nothing when a full window was fully sampled", () => {
    const full = Array.from({ length: 288 }, (_unused, index) => sampled(index * 5, 1, 1));
    expect(latencyChart(series(full), "24h").caption).toBeUndefined();
  });

  it("counts the gaps and refuses the words a rate would use", () => {
    // "as a rate of zero" is the throughput card's sentence. A blank latency step drawn as zero
    // would claim the broker answered instantly, which is a different and more flattering lie.
    const caption = latencyChart(series([blank(0), sampled(5, 1, 1)], 3600), "24h").caption ?? "";
    expect(caption).toContain("1 of the 2 one-hour steps");
    expect(caption).toContain("rather than as zero latency");
  });

  it("says the axis is short when the server answered fewer steps than the range holds", () => {
    const caption = latencyChart(series([sampled(0, 1, 1)]), "7d").caption ?? "";
    expect(caption).toContain("answered 1 steps where a 7d range holds 168");
  });
});

describe("a request-handler reading", () => {
  it("turns a ratio into a percentage, because the endpoint answers fractions", () => {
    // The endpoint answers ratios rather than pre-formatted percentages precisely so this decision
    // is made once, here, where it is testable: a server sending `64` and one sending `0.64` would
    // otherwise both draw something plausible and only one of them would be right.
    const panel = handlerPanel({ readings: [{ id: "io", label: "IO IDLE", ratio: 0.64 }] });
    expect(panel.gauges[0]?.kind).toBe("ratio");
    expect(panel.gauges[0]?.percent).toBeCloseTo(64);
  });

  it("keeps a count a count, with its unit and no percentage", () => {
    // §3.4 draws "38% PURGATORY" and `DelayedOperationPurgatory` publishes a queue *length*. There
    // is no ceiling to divide it by, and dividing it by an invented one is a fabricated percentage
    // — the defect wave 5's rule 7 exists to stop, in the one card that would have drawn it.
    const panel = handlerPanel({
      readings: [{ id: "purgatory", label: "PURGATORY", count: 38, unit: "operations" }],
    });
    expect(panel.gauges[0]?.kind).toBe("count");
    expect(panel.gauges[0]?.percent).toBeUndefined();
    expect(panel.gauges[0]?.count).toBe(38);
    expect(panel.gauges[0]?.unit).toBe("operations");
    expect(panel.caption).toContain("queue length");
  });

  it("leaves a ratio the exporter did not serve unmeasured rather than at zero", () => {
    const panel = handlerPanel({ readings: [{ id: "io", label: "IO IDLE", ratio: null }] });
    expect(panel.gauges[0]?.kind).toBe("ratio");
    expect(panel.gauges[0]?.percent).toBeUndefined();
  });

  it("takes the good direction from the wire, and only defaults it for the idle readings", () => {
    // `RingGauge` refuses a default of its own because a wrong guess paints an incident green. The
    // default here is a fact about *this* document — every reading it publishes is an idle ratio,
    // where more idle is more headroom — and a reading that says otherwise wins.
    const panel = handlerPanel({
      readings: [
        { id: "io", ratio: 0.64 },
        { id: "saturation", ratio: 0.9, goodDirection: "low" },
      ],
    });
    expect(panel.gauges[0]?.goodDirection).toBe("high");
    expect(panel.gauges[1]?.goodDirection).toBe("low");
  });

  it("says nothing extra when every reading is a ring", () => {
    const panel = handlerPanel({ readings: [{ id: "io", ratio: 0.64 }] });
    expect(panel.caption).toBeUndefined();
  });

  it("is empty rather than invented when the document carries no readings", () => {
    expect(handlerPanel({}).gauges).toEqual([]);
  });
});

describe("the top producers, and what the card is allowed to call them", () => {
  it("says `topic` when the server named topics", () => {
    // §4 draws "Top producers · client.id" and a broker publishes no per-`client.id` byte rate
    // unless quotas are configured. The heading follows the answer, which is the whole of rule 7:
    // the design's word may not be printed over a different measurement.
    const board = producerBoard(PRODUCERS_BY_TOPIC as { entries: readonly { topic: string }[] });
    expect(board.subject).toBe("topic");
    expect(board.rows[0]?.id).toBe("orders.payments");
    expect(board.rows[0]?.bytesPerSecond).toBe(5_400_000);
  });

  it("says `client.id` when the server named client ids", () => {
    const board = producerBoard(PRODUCERS_BY_CLIENT as { entries: readonly { clientId: string }[] });
    expect(board.subject).toBe("client.id");
    expect(board.rows.map((row) => row.id)).toEqual(["checkout-svc", "payments"]);
  });

  it("claims nothing about rows it does not recognise", () => {
    expect(producerBoard({ entries: [{ bytesPerSecond: 1 }] }).subject).toBe("producer");
    expect(producerBoard({}).subject).toBe("producer");
  });

  it("keeps a named producer whose rate did not arrive, with no rate", () => {
    // Dropping the row would silently shorten a top-five to a top-four; drawing a zero would rank
    // it last on a measurement nobody made. It keeps its place and says nothing about its rate.
    const board = producerBoard({ entries: [{ topic: "audit.trail", bytesPerSecond: null }] });
    expect(board.rows).toEqual([{ id: "audit.trail", bytesPerSecond: undefined }]);
  });

  it("drops a row that names nobody, because a bar with no label is not a row", () => {
    expect(producerBoard({ entries: [{ topic: "", bytesPerSecond: 5 }] }).rows).toEqual([]);
  });
});

describe("the record size", () => {
  it("reads the mean and nothing else, because a mean is all a broker publishes", () => {
    const readout = recordSizeReadout({ meanBytes: 1180, window: "the last hour" });
    expect(readout.meanBytes).toBe(1180);
    expect(readout.window).toBe("the last hour");
  });

  it("leaves an unserved mean absent rather than at zero", () => {
    expect(recordSizeReadout({ meanBytes: null }).meanBytes).toBeUndefined();
    expect(recordSizeReadout({}).meanBytes).toBeUndefined();
  });
});

describe("printing a latency", () => {
  it("drops precision as the magnitude rises, and never prints a number nobody reads", () => {
    expect(formatMillis(0.42)).toBe("0.42 ms");
    expect(formatMillis(6.5)).toBe("6.5 ms");
    expect(formatMillis(1234.5678)).toBe("1.23 s");
    expect(formatMillis(240)).toBe("240 ms");
  });

  it("keeps a measured zero a zero", () => {
    expect(formatMillis(0)).toBe("0.00 ms");
  });
});

describe("asking the four endpoints", () => {
  const recording = (body: unknown) => {
    const asked: { path: string; init: unknown }[] = [];
    const get = async (path: string, init: unknown) => {
      asked.push({ path, init });
      return { ok: true, value: body };
    };
    const api = {
      get, post: get, put: get, delete: get, patch: get, raw: {},
    } as unknown as KuiApiClient;
    return { api, asked };
  };

  const failing = (): KuiApiClient => {
    const get = async () => ({ ok: false, error: { kind: "unreachable", cause: "no gateway" } });
    return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
  };

  it("carries the window into the latency query under the name the wire uses", async () => {
    // The address spells it `?range=` and the wire spells it `window=`. Both are resolved before
    // the request goes out, so an unrecognised `?range=` cannot reach the server at all.
    const { api, asked } = recording(latencyOk({ buckets: [] }));
    await fetchLatency(api, "prod-kyiv-01", "30d");
    expect(asked).toEqual([
      {
        path: "/api/v1/clusters/{clusterId}/metrics/latency",
        init: { params: { path: { clusterId: "prod-kyiv-01" }, query: { window: "30d" } } },
      },
    ]);
  });

  it("asks for a bounded number of producers, because a card draws a handful", async () => {
    const { api, asked } = recording(producersOk({ entries: [] }));
    await fetchTopProducers(api, "prod-kyiv-01");
    expect(asked[0]?.init).toEqual({
      params: { path: { clusterId: "prod-kyiv-01" }, query: { top: "5" } },
    });
  });

  it("sends no query at all for the two readings that have no window", async () => {
    const handlers = recording(handlersOk({ readings: [] }));
    await fetchRequestHandlers(handlers.api, "prod");
    expect(handlers.asked[0]?.init).toEqual({ params: { path: { clusterId: "prod" } } });

    const size = recording(recordSizeOk(RECORD_SIZE_MEAN));
    await fetchRecordSize(size.api, "prod");
    expect(size.asked[0]?.init).toEqual({ params: { path: { clusterId: "prod" } } });
  });

  it("keeps a not-configured cluster its own case rather than a failure with a retry", async () => {
    // The common answer for a deployment that configured no exporter, on all four. Folded into
    // `failed` it would put four red panels and four Retry buttons in front of every operator who
    // never asked for metrics, which is how people learn to ignore red panels.
    const latency = recording(LATENCY_NOT_CONFIGURED);
    expect((await fetchLatency(latency.api, "prod", "24h")).kind).toBe("not-configured");
    const handlers = recording(HANDLERS_NOT_CONFIGURED);
    expect((await fetchRequestHandlers(handlers.api, "prod")).kind).toBe("not-configured");
  });

  it("reads the section under either spelling of a hyphenated key", async () => {
    // `requestHandlers` is how Circe writes it and `request-handlers` is how the path spells it.
    // They are one field written down twice, exactly as `startingAt`/`at` are on a bucket.
    const kebab = recording({ "request-handlers": { status: "ok", data: { readings: [] } } });
    expect((await fetchRequestHandlers(kebab.api, "prod")).kind).toBe("ready");
  });

  it("answers a sentence rather than throwing when the 200 is the wrong document", async () => {
    // A reverse proxy's own 200, a gateway that matched a different route. Handing `undefined` on
    // would surface as a `TypeError` inside a computation, which Solid 2 answers by halting the
    // graph — so the cost is not one blank card but every skeleton on the page never resolving.
    const wrong = recording({ detail: "not a series" });
    const state = await fetchLatency(wrong.api, "prod", "24h");
    expect(state.kind).toBe("failed");
    expect(state.kind === "failed" && state.message).toContain("something other than a");
  });

  it("folds a transport failure into a sentence instead of rejecting", async () => {
    expect((await fetchRecordSize(failing(), "prod")).kind).toBe("failed");
  });

  it("keys each read on what varies, so one card cannot serve another's answer", () => {
    expect(latencyKey("prod", "24h")).not.toBe(latencyKey("prod", "7d"));
    expect(latencyKey("prod", "24h")).not.toBe(latencyKey("staging", "24h"));
    expect(producersKey("prod", 5)).not.toBe(producersKey("prod", 10));
    expect(requestHandlersKey("prod")).not.toBe(requestHandlersKey("staging"));
  });
});
