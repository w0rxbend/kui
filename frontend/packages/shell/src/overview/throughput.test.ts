/**
 * The throughput fold, and the one distinction it exists to keep.
 *
 * The rendering is asserted in `overview.render.test.tsx`, at the seam where a person meets it —
 * the hidden data table, the coverage strip and the sentence under the chart. This file is about
 * the arithmetic underneath: a rate the server did not send stays `null` all the way through, a
 * measured zero stays `0`, and the axis is always the buckets that arrived rather than the ones
 * the card would have liked.
 */

import { describe, expect, it } from "vitest";
import type { KuiApiClient } from "@kui/api";

import {
  BUCKET_COUNT,
  DEFAULT_THROUGHPUT_RANGE,
  RANGE_PARAM,
  THROUGHPUT_RANGES,
  axisTicks,
  coverageRuns,
  fetchThroughput,
  hasMeasuredBucket,
  throughputChart,
  throughputKey,
  throughputRange,
  type ThroughputBucket,
  type ThroughputSeries,
} from "./throughput.js";
import { THROUGHPUT_NOT_CONFIGURED, THROUGHPUT_UNAVAILABLE, throughputOk } from "./fixtures.js";

const at = (minutes: number): string => new Date(Date.UTC(2026, 8, 5, 0, minutes)).toISOString();

const measured = (minutes: number, into: number, out: number): ThroughputBucket => ({
  startingAt: at(minutes),
  bytesInPerSecond: into,
  bytesOutPerSecond: out,
});

const absent = (minutes: number): ThroughputBucket => ({
  startingAt: at(minutes),
  bytesInPerSecond: null,
  bytesOutPerSecond: null,
});

const series = (buckets: readonly ThroughputBucket[], stepSeconds = 300): ThroughputSeries => ({
  range: "24h",
  stepSeconds,
  buckets,
});

describe("which window an address means", () => {
  it("reads the three the endpoint offers", () => {
    for (const range of THROUGHPUT_RANGES) expect(throughputRange(range)).toBe(range);
  });

  it("resolves an absent or unrecognised window to the default rather than refusing", () => {
    // `?range=` is user-editable in exactly the way the tab segment is. Answering `90d` with a day
    // of data under a label the caller chose is the one failure a chart cannot show its reader, and
    // the fallback is what stops it: the request falls back with the label, so the two agree.
    for (const nonsense of [undefined, "", "90d", "1h", "24H "]) {
      expect(throughputRange(nonsense)).toBe(DEFAULT_THROUGHPUT_RANGE);
    }
  });

  it("carries the window into the cache key, so the selector cannot serve the last window's bars", () => {
    expect(throughputKey("prod", "24h")).not.toBe(throughputKey("prod", "7d"));
    expect(throughputKey("prod", "24h")).not.toBe(throughputKey("staging", "24h"));
  });

  it("names the search parameter once, so the reader and the writer cannot spell it two ways", () => {
    expect(RANGE_PARAM).toBe("range");
  });
});

describe("a rate the server did not send", () => {
  it("stays absent, and a measured zero stays a zero", () => {
    // The whole file in one case. `0` is a fact about a quiet cluster; `null` is KUI admitting it
    // was not looking, and an operator shown the first when the second is true goes and asks their
    // producers why they stopped.
    const chart = throughputChart(series([measured(0, 0, 0), absent(5), measured(10, 12, 34)]), "24h");
    expect(chart.produce).toEqual([0, null, 12]);
    expect(chart.consume).toEqual([0, null, 34]);
  });

  it("counts as absent only when neither rate was measured", () => {
    // A JMX exporter is configured with a whitelist, and one publishing bytes in without bytes out
    // is an ordinary configuration. Calling that step unmeasured would report a gap that is not
    // there — and the server's own `ThroughputSample` makes each rate separately optional for the
    // same reason.
    const chart = throughputChart(
      series([{ startingAt: at(0), bytesInPerSecond: 7, bytesOutPerSecond: null }]),
      "24h",
    );
    expect(chart.absentBuckets).toBe(0);
    expect(chart.coverage).toEqual([{ measured: true, length: 1 }]);
  });

  it("takes the newest measured bucket as the legend's current value, not the last bucket", () => {
    // The last bucket of a live series is very often the one still being filled. Reading it blindly
    // would print the current rate as absent on a cluster that is producing perfectly well.
    const chart = throughputChart(series([measured(0, 1, 2), measured(5, 3, 4), absent(10)]), "24h");
    expect(chart.latest).toEqual({ produce: 3, consume: 4 });
  });

  it("has no current value at all when nothing in the window was measured", () => {
    const chart = throughputChart(series([absent(0), absent(5)]), "24h");
    expect(chart.latest).toBeUndefined();
    expect(hasMeasuredBucket(series([absent(0)]))).toBe(false);
    expect(hasMeasuredBucket(series([measured(0, 0, 0)]))).toBe(true);
  });
});

describe("the runs the coverage strip paints", () => {
  it("collapses consecutive buckets of the same kind, and keeps their order", () => {
    const runs = coverageRuns([1, 1, null, null, null, 2], [1, 1, null, null, null, 2]);
    expect(runs).toEqual([
      { measured: true, length: 2 },
      { measured: false, length: 3 },
      { measured: true, length: 1 },
    ]);
  });

  it("sums to the bucket count, which is what makes the strip line up with the bars", () => {
    // Each run is drawn with `flex-grow: length` over the same width the chart divides equally
    // between buckets. If the lengths did not sum to the count, the strip would drift across the
    // axis and point at the wrong columns — the failure a measurement-based alignment would have.
    const points = [null, 1, 1, null, 2, null, null, 3];
    const runs = coverageRuns(points, points);
    expect(runs.reduce((sum, run) => sum + run.length, 0)).toBe(points.length);
  });

  it("is empty for an empty series rather than one run of nothing", () => {
    expect(coverageRuns([], [])).toEqual([]);
  });
});

describe("the axis", () => {
  it("is the buckets that arrived, and never a target", () => {
    // The browser draws what it was sent. The rule that a range's bucket count is constant whatever
    // was sampled belongs to the server, and the browser's half of it is to *notice* — see the
    // caption case below — rather than to pad an answer into the shape it wanted.
    const chart = throughputChart(series([measured(0, 1, 1), absent(5), absent(10)]), "24h");
    expect(chart.categories).toHaveLength(3);
  });

  it("prints five ticks on a long axis and one per bucket on a short one", () => {
    // §4.1 labels this axis `00:00 · 06:00 · 12:00 · 18:00 · now`, which is four quarters and an
    // end. The kernel's own default is three, which is right for a narrow card and wrong for the
    // widest one on the page.
    expect(axisTicks(288)).toEqual([0, 72, 144, 215, 287]);
    expect(axisTicks(3)).toEqual([0, 1, 2]);
    expect(axisTicks(0)).toEqual([]);
  });

  it("labels every bucket, including the ones nothing was measured in", () => {
    const chart = throughputChart(series([absent(0), absent(5)]), "24h");
    expect(chart.categories.every((label) => label.length > 0)).toBe(true);
  });

  it("survives a bucket with no timestamp rather than dropping the column", () => {
    // A column with no label is still a column: dropping it would shorten the axis by one and move
    // every bucket after it, which is a worse answer than a tick with no words on it.
    const chart = throughputChart(series([{ bytesInPerSecond: 5, bytesOutPerSecond: 5 }]), "24h");
    expect(chart.categories).toEqual([""]);
    expect(chart.produce).toEqual([5]);
  });
});

describe("the sentence under the chart", () => {
  it("says nothing when a full window was fully sampled", () => {
    const full = Array.from({ length: BUCKET_COUNT["24h"] }, (_unused, index) =>
      measured(index * 5, 1, 1),
    );
    expect(throughputChart(series(full), "24h").caption).toBeUndefined();
  });

  it("counts the gaps and names the step, so a blank column is legible as unmeasured", () => {
    const buckets = Array.from({ length: BUCKET_COUNT["7d"] }, (_unused, index) =>
      index < 3 ? absent(index * 60) : measured(index * 60, 1, 1),
    );
    const caption = throughputChart(series(buckets, 3600), "7d").caption ?? "";
    expect(caption).toContain("3 of the 168 one-hour steps");
    expect(caption).toContain("rather than as a rate of zero");
  });

  it("says the axis is short when the server answered fewer steps than the range holds", () => {
    // The browser's half of the constant-bucket-count rule. A quiet hour that came back as three
    // buckets would draw a three-column chart labelled "24h", and nothing on the screen would say
    // so — which is exactly the defect the server's own bucket arithmetic exists to prevent.
    const caption = throughputChart(series([measured(0, 1, 1), measured(5, 1, 1)]), "24h").caption ?? "";
    expect(caption).toContain("answered 2 steps where a 24h range holds 288");
  });

  it("falls back to plain 'steps' when the server sent no step width", () => {
    const caption =
      throughputChart({ range: "30d", buckets: [absent(0)] }, "30d").caption ?? "";
    expect(caption).toContain("1 of the 1 steps");
    expect(caption).not.toContain("undefined");
  });
});

describe("asking the endpoint", () => {
  const answering = (body: unknown, ok = true): KuiApiClient => {
    const get = async () =>
      ok ? { ok: true, value: body } : { ok: false, error: { kind: "unreachable", cause: "no gateway" } };
    return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
  };

  it("carries the range into the query, because nothing else decides the window", async () => {
    const asked: unknown[] = [];
    const get = async (path: string, init: unknown) => {
      asked.push([path, init]);
      return { ok: true, value: throughputOk({ buckets: [] }) };
    };
    const api = { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;

    await fetchThroughput(api, "prod-kyiv-01", "30d");

    expect(asked).toEqual([
      [
        "/api/v1/clusters/{clusterId}/metrics/throughput",
        { params: { path: { clusterId: "prod-kyiv-01" }, query: { range: "30d" } } },
      ],
    ]);
  });

  it("keeps a not-configured cluster its own case, rather than a failure with a retry", async () => {
    // The common answer for a deployment that configured no exporter, and the whole reason
    // `Fetched` has six cases. Folded into `failed`, it would put a red panel and a Retry button in
    // front of every operator who never asked for metrics, which is how people learn to ignore red
    // panels.
    const state = await fetchThroughput(answering(THROUGHPUT_NOT_CONFIGURED), "prod", "24h");
    expect(state.kind).toBe("not-configured");
  });

  it("keeps an exporter that stopped answering a failure, with the reason it gave", async () => {
    const state = await fetchThroughput(answering(THROUGHPUT_UNAVAILABLE), "prod", "24h");
    expect(state).toEqual({
      kind: "failed",
      message: "The metrics exporter did not answer.",
      code: "KUI-UPSTREAM-UNAVAILABLE",
    });
  });

  it("draws a stale series rather than blanking the card", async () => {
    // Data that is real and out of date is still the best answer anybody has. The section's own
    // staleness is surfaced beside the chart rather than by throwing the chart away.
    const state = await fetchThroughput(
      answering({
        throughput: {
          status: "stale",
          data: { buckets: [measured(0, 1, 2)] },
          fetchedAt: "2026-09-05T12:00:00Z",
          reason: "KUI-UPSTREAM-UNAVAILABLE",
          message: "The exporter has not answered since 11:58.",
        },
      }),
      "prod",
      "24h",
    );
    expect(state.kind).toBe("stale");
    expect(state.kind === "stale" && state.value.buckets).toHaveLength(1);
  });

  it("answers a sentence rather than throwing when the 200 is not a throughput envelope", async () => {
    // A reverse proxy's own 200, a gateway that matched a different route, a build whose envelope
    // moved. Reading `.throughput` off the wrong body and handing `undefined` on would surface as
    // a `TypeError` inside a computation, which Solid 2 answers by halting the graph — so the cost
    // is not one blank card but every skeleton on the page never resolving.
    const state = await fetchThroughput(answering({ detail: "not a series" }), "prod", "24h");
    expect(state.kind).toBe("failed");
    expect(state.kind === "failed" && state.message).toContain("something other than a series");
  });

  it("folds a transport failure into a sentence instead of rejecting", async () => {
    const state = await fetchThroughput(answering(undefined, false), "prod", "24h");
    expect(state.kind).toBe("failed");
  });
});
