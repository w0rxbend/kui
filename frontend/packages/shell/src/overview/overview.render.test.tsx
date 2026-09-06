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
import { createQueryRegistry } from "@kui/kernel";

import { Overview, UNMEASURED_DISK } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  CONSUMERS_UNAVAILABLE,
  HEALTHY,
  LOADING,
  NO_DISK_SIZES,
  PARTIAL_DISKS,
  SPARSE_SUMMARY,
  THROUGHPUT_ALL_ABSENT,
  THROUGHPUT_FORBIDDEN,
  THROUGHPUT_NOT_CONFIGURED,
  THROUGHPUT_UNAVAILABLE,
  THROUGHPUT_WITH_A_GAP,
  UNHEALTHY,
  ZERO_BYTE_DISKS,
  throughputOk,
} from "./fixtures.js";
import { AddressProbe, THROUGHPUT_PATH, dashboardHost, stubApi } from "./harness.jsx";
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

/**
 * Lets the throughput request land, then lets the DOM catch up.
 *
 * `flush()` drains Solid's queue and does not resolve a promise. The card asks on mount, maps the
 * answer and then draws, which is three microtask hops; a case that flushed once would assert
 * against the waiting box and read as a card drawing nothing.
 */
const settle = async (rounds = 6): Promise<void> => {
  for (let round = 0; round < rounds; round += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();
  }
};

/**
 * The dashboard at an address, over a gateway that answers the throughput endpoint with `body`.
 *
 * Each case gets its own query registry. The shared one is module state — a browser tab's view of
 * one server, which is right in the product — so two cases naming the same cluster would otherwise
 * share an answer and the second would assert against the first one's stub.
 */
const showTraffic = async (body: unknown, at: string = `${DASHBOARD}/traffic`) => {
  const stub = stubApi({ [THROUGHPUT_PATH]: body });
  const mounted = keep(
    mount(
      dashboardHost(
        at,
        () => (
          <>
            <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />
            <AddressProbe />
          </>
        ),
        { api: stub.api },
      ),
    ),
  );
  await settle();
  return { ...mounted, stub };
};

/** The card's hidden data table, which is the honest reading of the bars beside it. */
const throughputTable = (container: HTMLElement): HTMLTableElement | null =>
  container.querySelector('[data-testid="panel-throughput"] table');

/** One bucket's two cells, by index, as a screen reader would read them. */
const bucketCells = (container: HTMLElement, index: number): readonly string[] => {
  const row = throughputTable(container)?.querySelectorAll("tbody tr")[index];
  return [...(row?.querySelectorAll("td") ?? [])].map((cell) => cell.textContent ?? "");
};

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
      "/ui/clusters/prod-kyiv-01/dashboard/traffic",
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
      mount(dashboardHost("/ui", () => <Overview model={toOverviewModel(HEALTHY)} />, { selected: "prod-kyiv-01" })),
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

  it("prints words where the percentage would be, not the punctuation for one", () => {
    // `disk — this broker reported a zero-byte disk` was the rendering: the em dash is
    // `ProgressBar`'s own default for a value it cannot draw, and it is not *bare* — the sentence is
    // directly beneath it — but between a caption and a sentence it reads as a missing figure
    // rather than as an admission. The product's rule is that a figure that cannot be measured says
    // so in words, and this row was the last place on this screen spelling it with punctuation.
    const { container } = show(ZERO_BYTE_DISKS);
    const bars = [...container.querySelectorAll('[data-testid="panel-broker-health"] .kui-progress')];

    expect(bars).toHaveLength(3);
    for (const bar of bars) {
      expect(bar.querySelector(".kui-progress__value")?.textContent).toBe(UNMEASURED_DISK);
      expect(bar.textContent).not.toContain("—");
      // And the screen-reader rendering says the same thing, rather than announcing a dash.
      expect(bar.querySelector('[role="progressbar"]')?.getAttribute("aria-valuetext")).toBe(
        UNMEASURED_DISK,
      );
    }
  });

  it("still prints the percentage where there is one, so the words are not the only thing it can say", () => {
    const { container } = show(HEALTHY);
    const values = [
      ...container.querySelectorAll('[data-testid="panel-broker-health"] .kui-progress__value'),
    ].map((el) => el.textContent);
    expect(values).toEqual(["61%", "58%", "83%"]);
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

/**
 * The Traffic tab, and the first chart in this product drawn from a broker metric.
 *
 * Every case here mounts the real route over the real router with a stubbed gateway, so the request
 * that goes out, the address the selector writes and the picture the card draws are all the
 * product's own. A case that composed `ThroughputCard` by hand and handed it a state would assert
 * the arrangement the case itself made — which is the shape twelve of last wave's rules had.
 */
describe("the Traffic tab", () => {
  it("draws the same stat cards as Overview, and its own last row", async () => {
    // §4.2's composition rule: the tab changes the voice line, the last row and the address, and
    // nothing else. The stat cards and rows 2 and 3 are identical, which is what makes the strip
    // cheap enough to be worth having.
    const traffic = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    const overview = show(HEALTHY);

    for (const stat of ["stat-brokers", "stat-topics", "stat-in-sync", "stat-production", "stat-lag"]) {
      expect(traffic.container.querySelector(`[data-testid="${stat}"]`)).not.toBeNull();
      expect(overview.container.querySelector(`[data-testid="${stat}"]`)).not.toBeNull();
    }
    // Rows 2 and 3, repeated rather than replaced.
    expect(traffic.container.querySelector('[data-testid="panel-broker-health"]')).not.toBeNull();
    expect(traffic.container.querySelector('[data-testid="panel-partitions"]')).not.toBeNull();
    expect(traffic.container.querySelector('[data-testid="panel-latency"]')).not.toBeNull();

    // And the last row is this tab's own: three cards Overview does not draw.
    for (const panel of ["panel-top-producers", "panel-message-sizes", "panel-request-handlers"]) {
      expect(traffic.container.querySelector(`[data-testid="${panel}"]`)).not.toBeNull();
      expect(overview.container.querySelector(`[data-testid="${panel}"]`)).toBeNull();
    }
    // Storage belongs to the other two tabs (§4.2: `Storage by broker` is gone here).
    expect(traffic.container.querySelector('[data-testid="panel-storage"]')).toBeNull();
  });

  it("keeps the three cards wave 5 will fill saying so, beside a chart that is real", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));

    expect(container.querySelector('[data-testid="panel-top-producers"]')?.textContent).toContain(
      "does not record which clients are producing",
    );
    expect(container.querySelector('[data-testid="panel-request-handlers"]')?.textContent).toContain(
      "does not record request-handler idle time",
    );
    // None of the three may borrow a figure from something the browser happens to hold: a producer
    // rate computed from a message browse is not a broker metric.
    for (const panel of ["panel-top-producers", "panel-message-sizes", "panel-request-handlers"]) {
      const card = container.querySelector(`[data-testid="${panel}"]`);
      expect(card?.querySelector('[role="img"]')).toBeNull();
    }
  });

  it("names the tab in the strip and marks it current at its own address", async () => {
    const { container } = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    expect(currentTab(container)).toBe("Traffic");
  });

  it("promises only what it measures in the voice line", async () => {
    const measured = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(measured.container.textContent).toContain("KUI measures the first of those");

    const unconfigured = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    // The design's sentence names three things and this cluster measures none of them. Printed
    // unqualified over three cards that say so, it is the cheerful-line-over-a-broken-cluster
    // failure in a different hat: a reader who believes the header goes looking for the chart.
    expect(unconfigured.container.textContent).toContain("no metrics source");
    expect(unconfigured.container.textContent).not.toContain("KUI measures the first of those");
  });
});

describe("a null bucket is a gap and never a zero", () => {
  it("draws the gap as a gap, beside a measured zero drawn as a zero", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));

    // The fixture is built so the two cases sit in one series: bucket 0 was measured and was zero,
    // buckets 100..119 were never sampled. As bars they are the same picture — no ink — which is
    // exactly why the reading that matters is the one a screen reader gets.
    expect(bucketCells(container, 0)).toEqual(["0 B/s", "0 B/s"]);
    expect(bucketCells(container, 100)).toEqual(["—", "—"]);
    expect(bucketCells(container, 119)).toEqual(["—", "—"]);
    // And a bucket either side of the hole still carries its rate, so this is not a case that
    // passes over a chart that lost every value.
    expect(bucketCells(container, 99)).not.toContain("—");
    expect(bucketCells(container, 120)).not.toContain("—");
  });

  it("paints the unsampled run on the coverage strip, so the gap is visible and not merely absent", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    const runs = [
      ...container.querySelectorAll('[data-testid="panel-throughput"] .kui-throughput__coverage-run'),
    ];

    const absent = runs.filter((run) => run.classList.contains("kui-throughput__coverage-run--absent"));
    expect(absent).toHaveLength(1);
    // Twenty buckets wide, which is the flex-grow that makes it line up with the twenty columns the
    // chart drew nothing in. A `0`-filled fold would produce no absent run at all.
    expect(absent[0]?.getAttribute("style")).toContain("20 0 0%");
    expect(runs.filter((run) => run.classList.contains("kui-throughput__coverage-run--measured"))).toHaveLength(2);
  });

  it("counts the gaps in words under the chart", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    const caption = container.querySelector('[data-testid="panel-throughput"] .kui-panel__caption');
    expect(caption?.textContent).toContain("20 of the 288 5-minute steps");
    expect(caption?.textContent).toContain("rather than as a rate of zero");
  });

  it("draws the whole axis for a window nothing was sampled in, and says so", async () => {
    // The other half of the constant-bucket-count rule, seen from the browser: a window with no
    // samples draws the same 288 steps a busy one does, so a quiet day and a busy day are the same
    // shape. A card that drew only the buckets it had would draw a short axis for a quiet window.
    const { container } = await showTraffic(throughputOk(THROUGHPUT_ALL_ABSENT));

    expect(throughputTable(container)?.querySelectorAll("tbody tr")).toHaveLength(288);
    expect(container.querySelector('[data-testid="panel-throughput"]')?.textContent).toContain(
      "Nothing has been sampled in this window yet",
    );
    // No legend figure either: there is no current rate, and an em dash in a chip reads as a
    // rendering fault where the sentence in the plot has already said what is missing.
    const legend = container.querySelectorAll(
      '[data-testid="panel-throughput"] .kui-chart-legend__value',
    );
    expect(legend).toHaveLength(0);
  });

  it("prints the newest measured rate in each legend chip", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    const chips = [
      ...container.querySelectorAll('[data-testid="panel-throughput"] .kui-chart-legend__item'),
    ].map((item) => item.textContent?.trim());
    expect(chips).toHaveLength(2);
    expect(chips[0]).toMatch(/^produce.*\/s$/);
    expect(chips[1]).toMatch(/^consume.*\/s$/);
  });
});

describe("a cluster with no metrics source", () => {
  it("draws the sentence and no axis", async () => {
    const { container } = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    const card = container.querySelector('[data-testid="panel-throughput"]');

    expect(card?.textContent).toContain("No metrics source is configured for it");
    // An axis is a claim that the quantity is measured and merely absent right now; this cluster
    // has nothing measuring it, and a labelled time axis over that sends somebody to go and find a
    // broken exporter that was never configured.
    expect(card?.querySelector('[role="img"]')).toBeNull();
    expect(card?.querySelector("table")).toBeNull();
    expect(card?.querySelector(".kui-plot__axis")).toBeNull();
    // And it is not drawn as a failure: no retry, because no retry could ever succeed.
    expect(card?.querySelector("button")).toBeNull();
  });

  it("still offers the range control, because the card is the same card", async () => {
    // `Card` keeps `headerEnd` in every state on purpose. A selector that vanished with the data
    // would remove the only way out of a window with nothing in it.
    const { container } = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    expect(
      container.querySelector('[data-testid="panel-throughput"] [role="radiogroup"]'),
    ).not.toBeNull();
  });

  it("says which permission is missing rather than drawing an empty card", async () => {
    // Found by mutation: replacing this branch's sentence with nothing left the whole suite green,
    // because no case fed the card a `forbidden` section. `MetricsMapping` cannot produce one
    // today, but `Section` has five statuses and the gateway's capability fold is entitled to any
    // of them — a status the browser refuses to draw is a blank card on the day a service starts
    // sending it, which is the failure `Card`'s "the frame never disappears" rule exists to stop.
    const { container } = await showTraffic(THROUGHPUT_FORBIDDEN);
    const card = container.querySelector('[data-testid="panel-throughput"]');

    expect(card?.textContent).toContain("You do not have permission");
    // Not a failure and not a retry: retrying will never help, and offering one teaches an operator
    // that the button does nothing.
    expect(card?.querySelector("button")).toBeNull();
    expect(card?.querySelector('[role="img"]')).toBeNull();
  });

  it("draws a failed exporter as a failure with a code and a retry, which is a different card", async () => {
    const { container } = await showTraffic(THROUGHPUT_UNAVAILABLE);
    const card = container.querySelector('[data-testid="panel-throughput"]');
    expect(card?.textContent).toContain("The metrics exporter did not answer.");
    expect(card?.textContent).toContain("KUI-UPSTREAM-UNAVAILABLE");
    expect(card?.querySelector("button")?.textContent).toContain("Retry");
  });
});

describe("the range selector", () => {
  it("puts the range in the address, so a colleague can be sent one", async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(container.querySelector('[data-testid="address"]')?.textContent).toBe(
      "/ui/clusters/prod-kyiv-01/dashboard/traffic",
    );

    choose7d(container);
    await settle();

    expect(container.querySelector('[data-testid="address"]')?.textContent).toBe(
      "/ui/clusters/prod-kyiv-01/dashboard/traffic?range=7d",
    );
  });

  it("opens on the window the address names, and asks for that one", async () => {
    // The other direction, and the one that makes a pasted link worth sending: the address is read
    // as well as written. A selector that only wrote to it would move the label and leave the
    // request on the default window.
    const { container, stub } = await showTraffic(
      throughputOk(THROUGHPUT_WITH_A_GAP),
      `${DASHBOARD}/traffic?range=30d`,
    );

    expect(stub.calls).toEqual([`${THROUGHPUT_PATH}?range=30d`]);
    expect(container.querySelector('[role="radiogroup"] input[value="30d"]')).toHaveProperty(
      "checked",
      true,
    );
  });

  it("reaches the request and not only the label", async () => {
    const { container, stub } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(stub.calls).toEqual([`${THROUGHPUT_PATH}?range=24h`]);

    choose7d(container);
    await settle();

    expect(stub.calls).toEqual([`${THROUGHPUT_PATH}?range=24h`, `${THROUGHPUT_PATH}?range=7d`]);
  });

  it("resolves a window nobody has to the default rather than refusing the page", async () => {
    // `?range=` is user-editable in the same way the tab segment is, and a typo is not an error
    // state. Answering `90d` with a day of data under a label the caller chose is the one failure a
    // chart cannot show its reader, which is why the *request* falls back too.
    const { container, stub } = await showTraffic(
      throughputOk(THROUGHPUT_WITH_A_GAP),
      `${DASHBOARD}/traffic?range=90d`,
    );
    expect(stub.calls).toEqual([`${THROUGHPUT_PATH}?range=24h`]);
    expect(container.querySelector('[role="radiogroup"] input[value="24h"]')).toHaveProperty(
      "checked",
      true,
    );
  });

  /* Thirty seconds rather than the default five. Axe walks the whole subtree, and a 24h series is
     288 rows of hidden data table beside 576 bar paths — the cost of the chart drawing every bucket
     the server sent rather than re-bucketing it, which is the decision `throughput.ts` argues for. */
  it("has no accessibility violations with a chart on the screen", { timeout: 30_000 }, async () => {
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });

  it("has no accessibility violations when there is nothing to measure", async () => {
    const { container } = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    expect((await findViolations(container)).map((v) => v.id)).toEqual([]);
  });
});

/** Picks `7d` the way a pointer does: the label points at the input, so the label is what is hit. */
function choose7d(container: HTMLElement): void {
  const input = container.querySelector<HTMLInputElement>('[role="radiogroup"] input[value="7d"]');
  if (input === null) throw new Error("no 7d segment");
  input.click();
  flush();
}
