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

import { afterEach, describe, expect, it } from "vitest";
import { createSignal, flush } from "solid-js";

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
  ZERO_BYTE_DISKS,
} from "./fixtures.js";
import { dashboardHost } from "./harness.jsx";
import { findViolations, mount, type Mounted } from "../chrome/testing.js";
import type { OverviewData } from "./load.js";

const DASHBOARD = "/ui/clusters/prod-kyiv-01/dashboard";

/**
 * Every container this file has mounted, torn down after the case whatever the case did.
 *
 * It used to be a `dispose()` at the end of each `it` body, which works exactly until something
 * fails: an assertion throws, the line is never reached, and the mounted tree stays attached to
 * `document.body` for the rest of the run. The next case that runs axe over its own container then
 * sees two `<header>` landmarks and fails `landmark-no-duplicate-banner` — so one real failure
 * became a cascade of unrelated ones and the first genuine one was the hardest to find. `afterEach`
 * runs after a throw; a trailing statement does not.
 */
const mounted: Mounted[] = [];

afterEach(() => {
  for (const each of mounted.splice(0)) each.dispose();
});

/** Mounts and registers for teardown. Nothing in this file disposes by hand. */
const keep = (m: Mounted): Mounted => {
  mounted.push(m);
  return m;
};

const show = (data: OverviewData, at: string = DASHBOARD) =>
  keep(mount(dashboardHost(at, () => <Overview model={toOverviewModel(data)} />)));

/** Which segment the strip has marked, read the way a screen reader reads it. */
const currentTab = (container: HTMLElement): string | null =>
  container.querySelector('[data-testid="tab-strip"] [aria-current="page"]')?.textContent?.trim() ?? null;

describe("the tab comes from the route and from nowhere else", () => {
  it("opens the storage tab for the address that names it", () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    expect(currentTab(container)).toBe("Storage");
    expect(container.querySelector('[data-testid="panel-storage"]')).not.toBeNull();
    // §4.3: the Storage tab replaces the whole body, so rows 2 and 3 are gone rather than repeated.
    expect(container.querySelector('[data-testid="panel-broker-health"]')).toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')).toBeNull();
  });

  it("opens the overview tab for the address that omits one", () => {
    const { container } = show(HEALTHY);
    expect(currentTab(container)).toBe("Overview");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="panel-message-sizes"]')).toBeNull();
  });

  it("opens the overview tab for a tab nobody has, rather than a blank body", () => {
    // The segment is user-editable. A typo is not an error state, and a strip with nothing marked
    // over an empty body is the worst of the three available answers.
    const { container } = show(HEALTHY, `${DASHBOARD}/nonsense`);
    expect(currentTab(container)).toBe("Overview");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.textContent).toContain("Cluster overview");
  });

  it("builds every href through `paths.dashboard`, so both spellings are one page", () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    const hrefs = [...container.querySelectorAll('[data-testid="tab-strip"] a')].map((a) =>
      a.getAttribute("href"),
    );
    expect(hrefs).toEqual([
      "/ui/clusters/prod-kyiv-01/dashboard/overview",
      "/ui/clusters/prod-kyiv-01/dashboard/storage",
    ]);
  });

  it("draws no strip at all on the address that names no cluster and nothing is selected", () => {
    // `/ui` resolves to this same component and has no cluster in it. A strip whose segments cannot
    // be given an href would be a row of links that go nowhere.
    const { container } = keep(
      mount(dashboardHost("/ui", () => <Overview model={toOverviewModel(HEALTHY)} />)),
    );
    expect(container.querySelector('[data-testid="tab-strip"]')).toBeNull();
    expect(container.querySelector('[data-testid="overview"]')).not.toBeNull();
  });

  it("falls back to the stored selection only on that one address", () => {
    // The root address is the single case where the route names no cluster and one is nevertheless
    // being fetched for. Everywhere else the parameter wins, which is what makes a pasted link show
    // the recipient what the sender saw.
    const { container } = keep(
      mount(dashboardHost("/ui", () => <Overview model={toOverviewModel(HEALTHY)} />, "prod-kyiv-01")),
    );
    expect(container.querySelector('[data-testid="tab-strip"] a')?.getAttribute("href")).toBe(
      "/ui/clusters/prod-kyiv-01/dashboard/overview",
    );
  });

  it("changes the voice line with the tab, and only the voice line", () => {
    const overview = show(HEALTHY);
    const storage = show(HEALTHY, `${DASHBOARD}/storage`);
    expect(overview.container.textContent).toContain("You may sip your coffee");
    expect(storage.container.textContent).toContain("eating your budget");
    // §3.1: the title and the stat cards are identical on every tab.
    expect(storage.container.textContent).toContain("Cluster overview");
    expect(storage.container.querySelector('[data-testid="stat-brokers"]')).not.toBeNull();
  });
});

describe("the healthy dashboard", () => {
  it("draws the five figures and the six panels", () => {
    const { container } = show(HEALTHY);
    const text = container.textContent ?? "";
    expect(text).toContain("Cluster overview");
    expect(text).toContain("128"); // topics
    expect(text).toContain("1,536 partitions");
    expect(text).toContain("4,212"); // total consumer lag
    expect(text).toContain("all in sync");
    expect(container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')).not.toBeNull();
  });

  it("keeps the design's voice when the cluster deserves it", () => {
    const { container } = show(HEALTHY);
    expect(container.textContent).toContain("You may sip your coffee");
    expect(container.textContent).toContain("won the election fair and square");
  });

  it("draws the in-sync share as a ring, and says which way is good", () => {
    const { container } = show(HEALTHY);
    const card = container.querySelector('[data-testid="stat-in-sync"]');
    expect(card?.textContent).toContain("100.0%");
    // `data-tone` is the gauge's own published judgement, which is what a test should read rather
    // than a fill colour a theme is allowed to change.
    expect(card?.querySelector(".kui-gauge")?.getAttribute("data-tone")).toBe("success");
  });

  it("has no accessibility violations", async () => {
    const { container } = show(HEALTHY);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });

  it("has no accessibility violations on the storage tab either", async () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });
});

describe("the panels this backend cannot fill", () => {
  it("says what is not measured, rather than drawing an empty chart", () => {
    const { container } = show(HEALTHY);

    const throughput = container.querySelector('[data-testid="panel-throughput"]');
    expect(throughput?.textContent).toContain("does not record throughput");
    // No axis, no bars, no range selector. An empty plot with a labelled time axis claims the data
    // is merely missing right now and sends somebody to find a broken exporter. (The card's own
    // title glyph is an svg too, so the check is for a *plot*, not for the absence of all svg.)
    expect(throughput?.querySelector(".kui-chart, .kui-bar-chart, [role=\"img\"]")).toBeNull();

    const latency = container.querySelector('[data-testid="panel-latency"]');
    expect(latency?.textContent).toContain("does not record request latency");
    expect(latency?.querySelector(".kui-chart, .kui-line-chart, [role=\"img\"]")).toBeNull();

  });

  it("says the same of the record-size distribution on the storage tab", () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    const sizes = container.querySelector('[data-testid="panel-message-sizes"]');
    expect(sizes?.textContent).toContain("does not record message sizes");
    // The design's twelve buckets and five axis labels are exactly what must not be drawn: an axis
    // is a claim that the quantity is measured and merely absent right now.
    expect(sizes?.querySelector(".kui-chart, .kui-histogram, [role=\"img\"]")).toBeNull();
    expect(sizes?.textContent).not.toContain("—");
  });

  it("offers no retry, because no retry could ever succeed", () => {
    const { container } = show(HEALTHY);
    const throughput = container.querySelector('[data-testid="panel-throughput"]');
    expect(throughput?.textContent?.toLowerCase()).not.toContain("retry");
    expect(throughput?.querySelector("button")).toBeNull();
  });

  it("does not print a dash where there is no measurement, which would read as a failed read", () => {
    const { container } = show(HEALTHY);
    const production = container.querySelector('[data-testid="stat-production"]');
    expect(production?.textContent).toContain("does not sample");
    expect(production?.textContent).not.toContain("—");
  });
});

describe("waiting is not the same as absent", () => {
  it("draws placeholders, not dashes, before anything has answered", () => {
    const { container } = show(LOADING);
    const brokers = container.querySelector('[data-testid="stat-brokers"]');
    expect(brokers?.querySelector(".kui-skeleton")).not.toBeNull();
    expect(brokers?.textContent).not.toContain("—");
  });

  it("shows no pill while the counts are still in flight, rather than a reassuring one", () => {
    const { container } = show(LOADING);
    expect(container.textContent).not.toContain("all in sync");
  });

  it("leaves the in-sync card's ring out entirely rather than drawing an empty one", () => {
    const { container } = show(LOADING);
    const card = container.querySelector('[data-testid="stat-in-sync"]');
    // §3.2: a card with no series draws no visual. An unmeasured ring beside a skeleton says the
    // same absence twice and reserves a box to say it in.
    expect(card?.querySelector(".kui-gauge")).toBeNull();
    expect(card?.querySelector(".kui-stat__visual")).toBeNull();
  });
});

describe("a cluster in trouble", () => {
  it("turns the jokes off", () => {
    const { container } = show(UNHEALTHY);
    const text = container.textContent ?? "";
    expect(text).not.toContain("coffee");
    expect(text).not.toContain("fashionably late");
    expect(text).toContain("46 partitions are offline");
  });

  it("marks the state on the figures that carry it", () => {
    const { container } = show(UNHEALTHY);
    expect(container.textContent).toContain("46 partitions offline");
    expect(container.textContent).toContain("seriously behind");
  });
});

describe("partial availability", () => {
  it("blanks only the panel whose service is down", () => {
    const { container } = show(CONSUMERS_UNAVAILABLE);
    expect(container.querySelector('[data-testid="panel-top-lag"]')?.textContent).toContain("not answering");
    // The rest of the dashboard is still reporting. A page that fails whole because one of five
    // services is down is the failure mode ADR-039 exists to prevent.
    expect(container.querySelector('[data-testid="panel-broker-health"]')?.textContent).toContain("broker-1.kyiv");
    expect(container.textContent).toContain("128");
  });
});

describe("a cluster that reports no partition counts", () => {
  it("draws no ring and no donut, and does not fill either with a zero", () => {
    const { container } = show(SPARSE_SUMMARY);
    expect(container.querySelector('[data-testid="stat-in-sync"] .kui-gauge')).toBeNull();
    expect(container.querySelector('[data-testid="panel-partitions"]')?.textContent).toContain(
      "does not report partition health counts",
    );
  });
});

describe("a broker that cannot report its disk", () => {
  it("draws no fill and says why, rather than an empty bar that reads as an empty disk", () => {
    const { container } = show(NO_DISK_SIZES);
    const panel = container.querySelector('[data-testid="panel-broker-health"]');

    expect(panel?.textContent).toContain("do not report a disk size");
    // The specific defect: an unknown quantity rendered as a zero-width fill on a full-width track
    // is indistinguishable from a disk that is genuinely empty.
    for (const meter of panel?.querySelectorAll('[role="progressbar"]') ?? []) {
      expect(meter.getAttribute("aria-valuenow")).toBeNull();
    }
  });

  it("still names the brokers and their leader counts", () => {
    const { container } = show(NO_DISK_SIZES);
    expect(container.textContent).toContain("id 1 · 512 leaders");
  });

  it("gives the storage card a bare track and a sentence, and no legend to read it by", () => {
    const { container } = show(NO_DISK_SIZES, `${DASHBOARD}/storage`);
    const panel = container.querySelector('[data-testid="panel-storage"]');
    expect(panel?.textContent).toContain("no disk size reported");
    expect(panel?.querySelector(".kui-stacked__segment")).toBeNull();
    // A key naming four prefixes the reader cannot see anywhere reads as a rendering fault.
    expect(panel?.querySelector(".kui-chart-legend")).toBeNull();
  });
});

describe("the storage card", () => {
  it("draws one bar per broker with the fold's own group names", () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    const panel = container.querySelector('[data-testid="panel-storage"]');
    expect(panel?.querySelectorAll('[data-testid="storage-rows"] > li')).toHaveLength(3);

    const legend = [...(panel?.querySelectorAll(".kui-chart-legend__label") ?? [])].map((el) =>
      el.textContent?.trim(),
    );
    expect(legend).toEqual(["orders.*", "analytics.*", "inventory.*", "internal"]);
  });

  it("prints each broker's used bytes against its own capacity", () => {
    const { container } = show(HEALTHY, `${DASHBOARD}/storage`);
    const rows = container.querySelectorAll('[data-testid="storage-rows"] .kui-broker-health__detail');
    const details = [...rows].map((el) => el.textContent?.trim());
    // 61%, 58% and 83% — the same three figures the broker-health card draws, from the same skip.
    expect(details).toEqual(["610 B of 1.0 kB · 61%", "580 B of 1.0 kB · 58%", "830 B of 1.0 kB · 83%"]);
  });

  it("leaves an unmeasured directory's bytes out of the bar it does not belong on", () => {
    const { container } = show(PARTIAL_DISKS, `${DASHBOARD}/storage`);
    const rows = container.querySelectorAll('[data-testid="storage-rows"] > li');
    // Broker 1's second disk holds 400 B of `orders.*` and reported no size. Its hidden data table
    // is the honest reading of the bar, so the figure that would be wrong is the one asserted.
    expect(rows[0]?.querySelector("table")?.textContent).toContain("250 B");
    expect(rows[0]?.querySelector("table")?.textContent).not.toContain("650 B");
  });

  it("is on the overview tab too, because it is the same card", () => {
    // §4.1 row 4 draws it beside the alerts feed. Two implementations of one card would be two
    // places for the attribution to disagree with itself.
    const { container } = show(HEALTHY);
    expect(container.querySelector('[data-testid="panel-storage"]')).not.toBeNull();
  });
});

describe("the body updates in place rather than being rebuilt", () => {
  /**
   * The regression this describe block exists for, and the reason it asserts DOM identity.
   *
   * `Overview` used to dispatch its body with `{bodyFor(tab(), props.model)}`. The JSX compiler
   * treats an expression container holding a call as dynamic and wraps it in a tracked computation,
   * so reading the model *at the dispatch* made every model change re-run the switch and replace
   * the whole subtree. Nothing in this file could see it: the body still ends up carrying the right
   * text either way, so every existing case here passes over a body that was rebuilt from scratch.
   *
   * What a rebuilt body costs is not visible in text and is why the assertion is on nodes. Focus
   * inside a panel is lost on every poll, `For`'s keying cannot hold anything, and a transition
   * restarts from empty each time. So the case moves the model the way a first answer does —
   * `LOADING` to `HEALTHY` — and asks whether the nodes survived it.
   */
  it("keeps the body's own DOM nodes when the model moves from loading to healthy", () => {
    const [data, setData] = createSignal<OverviewData>(LOADING);
    const { container } = keep(
      mount(dashboardHost(DASHBOARD, () => <Overview model={toOverviewModel(data())} />)),
    );

    const statBefore = container.querySelector('[data-testid="stat-brokers"]');
    const panelBefore = container.querySelector('[data-testid="panel-broker-health"]');
    const rowBefore = container.querySelector('[data-testid="panel-partitions"]');
    expect(panelBefore).not.toBeNull();

    setData(HEALTHY);
    flush();

    // First: the data really did land. Without this the identity checks below would also pass over
    // a screen that never updated at all, which is the other way to keep a DOM node.
    expect(container.querySelector('[data-testid="panel-broker-health"]')?.textContent).toContain(
      "broker-1.kyiv",
    );

    // The tab-invariant stat row was never in question — it is the control that says the two
    // renderings are comparable, and it kept its node under the defect too.
    expect(container.querySelector('[data-testid="stat-brokers"]')).toBe(statBefore);
    // These two are the regression. Under the captured-model dispatch both were replaced.
    expect(container.querySelector('[data-testid="panel-broker-health"]')).toBe(panelBefore);
    expect(container.querySelector('[data-testid="panel-partitions"]')).toBe(rowBefore);
  });

  it("keeps a broker's row across a poll, which is what the keying comment claims", () => {
    // `model.ts` builds a fresh `BrokerBar` on every read, so `For`'s default identity keying
    // replaced all three rows even once the body stopped being rebuilt. The `For` is keyed by the
    // broker's id for exactly this, and this is the assertion that says so.
    const [data, setData] = createSignal<OverviewData>(HEALTHY);
    const { container } = keep(
      mount(dashboardHost(DASHBOARD, () => <Overview model={toOverviewModel(data())} />)),
    );
    const rows = (): readonly Element[] => [
      ...container.querySelectorAll('[data-testid="panel-broker-health"] li'),
    ];
    const before = rows();
    expect(before).toHaveLength(3);

    setData(UNHEALTHY);
    flush();

    // The poll landed — the same guard as above, so the identity check cannot pass on a screen that
    // simply never changed.
    expect(container.textContent).toContain("46 partitions offline");
    // Node by node, and by identity: `toEqual` over two element lists compares their structure and
    // passes happily on a row that was rebuilt into the same shape, which is precisely the state
    // this case is here to reject.
    const after = rows();
    expect(after).toHaveLength(before.length);
    for (const [index, row] of after.entries()) expect(row).toBe(before[index]);
  });
});

describe("a broker whose disk answered zero", () => {
  /**
   * The two sentences that used to disagree, asserted where the screen draws them.
   *
   * `isMeasured` admits `totalBytes: 0` — both figures are present, so nothing is skipped — and the
   * two halves of the card then took different views of it. The broker-health bar drew no fill and
   * said why; the storage row printed `0 B of 0 B` with no percentage and no explanation, which is
   * the product's own rule against rendering an unmeasurable figure as a zero, broken by the one
   * function whose comment claimed it could not disagree with the other. Both now come out of
   * `diskShare`.
   */
  it("says so in the storage row, rather than printing a pair of zeroes", () => {
    const { container } = show(ZERO_BYTE_DISKS, `${DASHBOARD}/storage`);
    const details = [
      ...container.querySelectorAll('[data-testid="storage-rows"] .kui-broker-health__detail'),
    ].map((el) => el.textContent?.trim());

    expect(details).toHaveLength(3);
    for (const detail of details) {
      expect(detail).toBe("this broker reported a zero-byte disk");
    }
    expect(container.textContent).not.toContain("0 B of 0 B");
  });

  it("says the same thing under the broker-health bar, in the same words", () => {
    const { container } = show(ZERO_BYTE_DISKS);
    const why = [
      ...container.querySelectorAll('[data-testid="panel-broker-health"] .kui-broker-health__why'),
    ].map((el) => el.textContent?.trim());

    expect(why).toEqual([
      "this broker reported a zero-byte disk",
      "this broker reported a zero-byte disk",
      "this broker reported a zero-byte disk",
    ]);
    // And no fill: an unmeasurable share is not 0%.
    for (const meter of container.querySelectorAll(
      '[data-testid="panel-broker-health"] [role="progressbar"]',
    )) {
      expect(meter.getAttribute("aria-valuenow")).toBeNull();
    }
  });

  // One mount per case, deliberately. `landmark-no-duplicate-banner` is a rule about the whole
  // document, so two dashboards mounted at once fail it however correct each one is — which is the
  // same fact that makes the `afterEach` above worth having.
  it("has no accessibility violations on the overview tab", async () => {
    const { container } = show(ZERO_BYTE_DISKS);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });

  it("has no accessibility violations on the storage tab", async () => {
    const { container } = show(ZERO_BYTE_DISKS, `${DASHBOARD}/storage`);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });
});
