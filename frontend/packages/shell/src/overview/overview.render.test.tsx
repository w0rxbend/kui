/**
 * What the dashboard actually puts on the screen, in each of the states it has to survive.
 *
 * The judgements are tested in `overview.test.ts`; this file checks that the right judgement reaches
 * the right pixel — in particular that the unmeasured panels say so in words, that a waiting figure
 * and an absent one look different, and that a blank bar is never reachable as a zero.
 *
 * ## Why every case mounts a router
 *
 * `Overview` reads its cluster and its tab from the address, so the address is an input to almost
 * every case here and the router is the thing that turns one into the other. `harness.tsx` mounts
 * the product's own route table over a memory history, which means these cases resolve the same URL
 * the same way a pasted link does — and a test that stubbed `useParams` would have asserted that the
 * component reads a stub.
 */

import { describe, expect, it } from "vitest";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  CONSUMERS_UNAVAILABLE,
  HEALTHY,
  LOADING,
  NO_DISK_SIZES,
  PARTIAL_DISKS,
  SPARSE_SUMMARY,
  UNHEALTHY,
} from "./fixtures.js";
import { dashboardHost } from "./harness.jsx";
import { findViolations, mount } from "../chrome/testing.js";
import type { OverviewData } from "./load.js";

const DASHBOARD = "/ui/clusters/prod-kyiv-01/dashboard";

const show = (data: OverviewData, at: string = DASHBOARD) =>
  mount(dashboardHost(at, () => <Overview model={toOverviewModel(data)} />));

/** Which segment the strip has marked, read the way a screen reader reads it. */
const currentTab = (container: HTMLElement): string | null =>
  container.querySelector('[data-testid="tab-strip"] [aria-current="page"]')?.textContent?.trim() ?? null;

describe("the tab comes from the route and from nowhere else", () => {
  it("opens the storage tab for the address that names it", () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
    expect(currentTab(container)).toBe("Storage");
    expect(container.querySelector('[data-testid="panel-storage"]')).not.toBeNull();
    // §4.3: the Storage tab replaces the whole body, so rows 2 and 3 are gone rather than repeated.
    expect(container.querySelector('[data-testid="panel-broker-health"]')).toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')).toBeNull();
    dispose();
  });

  it("opens the overview tab for the address that omits one", () => {
    const { container, dispose } = show(HEALTHY);
    expect(currentTab(container)).toBe("Overview");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="panel-message-sizes"]')).toBeNull();
    dispose();
  });

  it("opens the overview tab for a tab nobody has, rather than a blank body", () => {
    // The segment is user-editable. A typo is not an error state, and a strip with nothing marked
    // over an empty body is the worst of the three available answers.
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/nonsense`);
    expect(currentTab(container)).toBe("Overview");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.textContent).toContain("Cluster overview");
    dispose();
  });

  it("builds every href through `paths.dashboard`, so both spellings are one page", () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
    const hrefs = [...container.querySelectorAll('[data-testid="tab-strip"] a')].map((a) =>
      a.getAttribute("href"),
    );
    expect(hrefs).toEqual([
      "/ui/clusters/prod-kyiv-01/dashboard/overview",
      "/ui/clusters/prod-kyiv-01/dashboard/storage",
    ]);
    dispose();
  });

  it("draws no strip at all on the address that names no cluster and nothing is selected", () => {
    // `/ui` resolves to this same component and has no cluster in it. A strip whose segments cannot
    // be given an href would be a row of links that go nowhere.
    const { container, dispose } = mount(
      dashboardHost("/ui", () => <Overview model={toOverviewModel(HEALTHY)} />),
    );
    expect(container.querySelector('[data-testid="tab-strip"]')).toBeNull();
    expect(container.querySelector('[data-testid="overview"]')).not.toBeNull();
    dispose();
  });

  it("falls back to the stored selection only on that one address", () => {
    // The root address is the single case where the route names no cluster and one is nevertheless
    // being fetched for. Everywhere else the parameter wins, which is what makes a pasted link show
    // the recipient what the sender saw.
    const { container, dispose } = mount(
      dashboardHost("/ui", () => <Overview model={toOverviewModel(HEALTHY)} />, "prod-kyiv-01"),
    );
    expect(container.querySelector('[data-testid="tab-strip"] a')?.getAttribute("href")).toBe(
      "/ui/clusters/prod-kyiv-01/dashboard/overview",
    );
    dispose();
  });

  it("changes the voice line with the tab, and only the voice line", () => {
    const overview = show(HEALTHY);
    const storage = show(HEALTHY, `${DASHBOARD}/storage`);
    expect(overview.container.textContent).toContain("You may sip your coffee");
    expect(storage.container.textContent).toContain("eating your budget");
    // §3.1: the title and the stat cards are identical on every tab.
    expect(storage.container.textContent).toContain("Cluster overview");
    expect(storage.container.querySelector('[data-testid="stat-brokers"]')).not.toBeNull();
    overview.dispose();
    storage.dispose();
  });
});

describe("the healthy dashboard", () => {
  it("draws the five figures and the six panels", () => {
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

  it("draws the in-sync share as a ring, and says which way is good", () => {
    const { container, dispose } = show(HEALTHY);
    const card = container.querySelector('[data-testid="stat-in-sync"]');
    expect(card?.textContent).toContain("100.0%");
    // `data-tone` is the gauge's own published judgement, which is what a test should read rather
    // than a fill colour a theme is allowed to change.
    expect(card?.querySelector(".kui-gauge")?.getAttribute("data-tone")).toBe("success");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = show(HEALTHY);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
    dispose();
  });

  it("has no accessibility violations on the storage tab either", async () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
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

  it("says the same of the record-size distribution on the storage tab", () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
    const sizes = container.querySelector('[data-testid="panel-message-sizes"]');
    expect(sizes?.textContent).toContain("does not record message sizes");
    // The design's twelve buckets and five axis labels are exactly what must not be drawn: an axis
    // is a claim that the quantity is measured and merely absent right now.
    expect(sizes?.querySelector(".kui-chart, .kui-histogram, [role=\"img\"]")).toBeNull();
    expect(sizes?.textContent).not.toContain("—");
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

  it("leaves the in-sync card's ring out entirely rather than drawing an empty one", () => {
    const { container, dispose } = show(LOADING);
    const card = container.querySelector('[data-testid="stat-in-sync"]');
    // §3.2: a card with no series draws no visual. An unmeasured ring beside a skeleton says the
    // same absence twice and reserves a box to say it in.
    expect(card?.querySelector(".kui-gauge")).toBeNull();
    expect(card?.querySelector(".kui-stat__visual")).toBeNull();
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

describe("a cluster that reports no partition counts", () => {
  it("draws no ring and no donut, and does not fill either with a zero", () => {
    const { container, dispose } = show(SPARSE_SUMMARY);
    expect(container.querySelector('[data-testid="stat-in-sync"] .kui-gauge')).toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')?.textContent).toContain(
      "does not report partition health counts",
    );
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

  it("gives the storage card a bare track and a sentence, and no legend to read it by", () => {
    const { container, dispose } = show(NO_DISK_SIZES, `${DASHBOARD}/storage`);
    const panel = container.querySelector('[data-testid="panel-storage"]');
    expect(panel?.textContent).toContain("no disk size reported");
    expect(panel?.querySelector(".kui-stacked__segment")).toBeNull();
    // A key naming four prefixes the reader cannot see anywhere reads as a rendering fault.
    expect(panel?.querySelector(".kui-chart-legend")).toBeNull();
    dispose();
  });
});

describe("the storage card", () => {
  it("draws one bar per broker with the fold's own group names", () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
    const panel = container.querySelector('[data-testid="panel-storage"]');
    expect(panel?.querySelectorAll('[data-testid="storage-rows"] > li')).toHaveLength(3);

    const legend = [...(panel?.querySelectorAll(".kui-chart-legend__label") ?? [])].map((el) =>
      el.textContent?.trim(),
    );
    expect(legend).toEqual(["orders.*", "analytics.*", "inventory.*", "internal"]);
    dispose();
  });

  it("prints each broker's used bytes against its own capacity", () => {
    const { container, dispose } = show(HEALTHY, `${DASHBOARD}/storage`);
    const rows = container.querySelectorAll('[data-testid="storage-rows"] .kui-broker-health__detail');
    const details = [...rows].map((el) => el.textContent?.trim());
    // 61%, 58% and 83% — the same three figures the broker-health card draws, from the same skip.
    expect(details).toEqual(["610 B of 1.0 kB · 61%", "580 B of 1.0 kB · 58%", "830 B of 1.0 kB · 83%"]);
    dispose();
  });

  it("leaves an unmeasured directory's bytes out of the bar it does not belong on", () => {
    const { container, dispose } = show(PARTIAL_DISKS, `${DASHBOARD}/storage`);
    const rows = container.querySelectorAll('[data-testid="storage-rows"] > li');
    // Broker 1's second disk holds 400 B of `orders.*` and reported no size. Its hidden data table
    // is the honest reading of the bar, so the figure that would be wrong is the one asserted.
    expect(rows[0]?.querySelector("table")?.textContent).toContain("250 B");
    expect(rows[0]?.querySelector("table")?.textContent).not.toContain("650 B");
    dispose();
  });

  it("is on the overview tab too, because it is the same card", () => {
    // §4.1 row 4 draws it beside the alerts feed. Two implementations of one card would be two
    // places for the attribution to disagree with itself.
    const { container, dispose } = show(HEALTHY);
    expect(container.querySelector('[data-testid="panel-storage"]')).not.toBeNull();
    dispose();
  });
});
