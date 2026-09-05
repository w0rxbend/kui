/**
 * What the overview actually puts on the screen, in each of the states it has to survive.
 *
 * The judgements are tested in `overview.test.ts`; this file checks that the right judgement reaches
 * the right pixel — in particular that the three unmeasured panels say so in words, that a waiting
 * figure and an absent one look different, and that a blank bar is never reachable as a zero.
 */

import { describe, expect, it } from "vitest";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import { CONSUMERS_UNAVAILABLE, HEALTHY, LOADING, NO_DISK_SIZES, UNHEALTHY } from "./fixtures.js";
import { findViolations, mount } from "../chrome/testing.js";
import type { OverviewData } from "./load.js";

const show = (data: OverviewData) => mount(() => <Overview model={toOverviewModel(data)} />);

describe("the healthy dashboard", () => {
  it("draws the four figures and the six panels", () => {
    const { container, dispose } = show(HEALTHY);
    const text = container.textContent ?? "";
    expect(text).toContain("Cluster overview");
    expect(text).toContain("128"); // topics
    expect(text).toContain("1,536 partitions");
    expect(text).toContain("4,212"); // total consumer lag
    expect(text).toContain("all in sync");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')).not.toBeNull();
    dispose();
  });

  it("keeps the design's voice when the cluster deserves it", () => {
    const { container, dispose } = show(HEALTHY);
    expect(container.textContent).toContain("You may sip your coffee");
    expect(container.textContent).toContain("won the election fair and square");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = show(HEALTHY);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
    dispose();
  });
});

describe("the panels this backend cannot fill", () => {
  it("says what is not measured, rather than drawing an empty chart", () => {
    const { container, dispose } = show(HEALTHY);

    const throughput = container.querySelector('[data-testid="panel-throughput"]');
    expect(throughput?.textContent).toContain("does not record throughput");
    // No axis, no bars, no range selector. An empty plot with a labelled time axis claims the data
    // is merely missing right now and sends somebody to find a broken exporter. (The card's own
    // title glyph is an svg too, so the check is for a *plot*, not for the absence of all svg.)
    expect(throughput?.querySelector(".kui-chart, .kui-bar-chart, [role=\"img\"]")).toBeNull();

    const latency = container.querySelector('[data-testid="panel-latency"]');
    expect(latency?.textContent).toContain("does not record request latency");
    expect(latency?.querySelector(".kui-chart, .kui-line-chart, [role=\"img\"]")).toBeNull();

    dispose();
  });

  it("offers no retry, because no retry could ever succeed", () => {
    const { container, dispose } = show(HEALTHY);
    const throughput = container.querySelector('[data-testid="panel-throughput"]');
    expect(throughput?.textContent?.toLowerCase()).not.toContain("retry");
    expect(throughput?.querySelector("button")).toBeNull();
    dispose();
  });

  it("does not print a dash where there is no measurement, which would read as a failed read", () => {
    const { container, dispose } = show(HEALTHY);
    const production = container.querySelector('[data-testid="stat-production"]');
    expect(production?.textContent).toContain("does not sample");
    expect(production?.textContent).not.toContain("—");
    dispose();
  });
});

describe("waiting is not the same as absent", () => {
  it("draws placeholders, not dashes, before anything has answered", () => {
    const { container, dispose } = show(LOADING);
    const brokers = container.querySelector('[data-testid="stat-brokers"]');
    expect(brokers?.querySelector(".kui-skeleton")).not.toBeNull();
    expect(brokers?.textContent).not.toContain("—");
    dispose();
  });

  it("shows no pill while the counts are still in flight, rather than a reassuring one", () => {
    const { container, dispose } = show(LOADING);
    expect(container.textContent).not.toContain("all in sync");
    dispose();
  });
});

describe("a cluster in trouble", () => {
  it("turns the jokes off", () => {
    const { container, dispose } = show(UNHEALTHY);
    const text = container.textContent ?? "";
    expect(text).not.toContain("coffee");
    expect(text).not.toContain("fashionably late");
    expect(text).toContain("46 partitions are offline");
    dispose();
  });

  it("marks the state on the figures that carry it", () => {
    const { container, dispose } = show(UNHEALTHY);
    expect(container.textContent).toContain("46 partitions offline");
    expect(container.textContent).toContain("seriously behind");
    dispose();
  });
});

describe("partial availability", () => {
  it("blanks only the panel whose service is down", () => {
    const { container, dispose } = show(CONSUMERS_UNAVAILABLE);
    expect(container.querySelector('[data-testid="panel-top-lag"]')?.textContent).toContain("not answering");
    // The rest of the dashboard is still reporting. A page that fails whole because one of five
    // services is down is the failure mode ADR-039 exists to prevent.
    expect(container.querySelector('[data-testid="panel-broker-health"]')?.textContent).toContain("broker-1.kyiv");
    expect(container.textContent).toContain("128");
    dispose();
  });
});

describe("a broker that cannot report its disk", () => {
  it("draws no fill and says why, rather than an empty bar that reads as an empty disk", () => {
    const { container, dispose } = show(NO_DISK_SIZES);
    const panel = container.querySelector('[data-testid="panel-broker-health"]');

    expect(panel?.textContent).toContain("do not report a disk size");
    // The specific defect: an unknown quantity rendered as a zero-width fill on a full-width track
    // is indistinguishable from a disk that is genuinely empty.
    for (const meter of panel?.querySelectorAll('[role="progressbar"]') ?? []) {
      expect(meter.getAttribute("aria-valuenow")).toBeNull();
    }
    dispose();
  });

  it("still names the brokers and their leader counts", () => {
    const { container, dispose } = show(NO_DISK_SIZES);
    expect(container.textContent).toContain("id 1 · 512 leaders");
    dispose();
  });
});
