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
  HANDLER_ONE_ABSENT,
  HANDLER_READINGS,
  HEALTHY,
  LATENCY_ALL_ABSENT,
  LATENCY_WITH_A_GAP,
  LOADING,
  NO_DISK_SIZES,
  PARTIAL_DISKS,
  HANDLERS_NOTHING_READ,
  HANDLERS_STALE,
  PRODUCERS_BY_CLIENT,
  PRODUCERS_BY_TOPIC,
  PRODUCERS_STALE,
  PRODUCERS_WITH_INTERNAL_EXCLUDED,
  RECORD_SIZE_ABSENT,
  RECORD_SIZE_MEAN,
  RECORD_SIZE_STALE,
  SPARSE_SUMMARY,
  THROUGHPUT_ALL_ABSENT,
  THROUGHPUT_FORBIDDEN,
  THROUGHPUT_MEASURED_ZERO,
  THROUGHPUT_NOT_CONFIGURED,
  THROUGHPUT_STALE,
  THROUGHPUT_UNAVAILABLE,
  THROUGHPUT_WITH_A_GAP,
  UNHEALTHY,
  ZERO_BYTE_DISKS,
  handlersOk,
  latencyOk,
  producersOk,
  recordSizeOk,
  throughputOk,
} from "./fixtures.js";
import {
  AddressProbe,
  HANDLERS_PATH,
  LATENCY_PATH,
  PRODUCERS_PATH,
  RECORD_SIZE_PATH,
  THROUGHPUT_PATH,
  UNCONFIGURED_METRICS,
  dashboardHost,
  staticAlerts,
  stubApi,
} from "./harness.jsx";
import { FORBIDDEN_SENTENCE, NOT_CONFIGURED_SENTENCE, NO_SAMPLES_SENTENCE } from "./ThroughputCard.jsx";
import { notConfiguredSentence } from "./NotMeasured.jsx";
import {
  HANDLERS_NOUN,
  NO_DISTRIBUTION,
  NO_HANDLER_READINGS,
  NO_PRODUCERS,
  PRODUCERS_NOUN,
  RECORD_SIZE_NOUN,
  producersTitle,
} from "./TrafficCards.jsx";
import { LATENCY_NOUN, NO_LATENCY_SENTENCE } from "./LatencyCard.jsx";
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
const showTraffic = async (body: unknown, at: string = `${DASHBOARD}/traffic`) =>
  showMetrics({ [THROUGHPUT_PATH]: body }, at);

/**
 * The same, with any of the five metrics endpoints answered.
 *
 * Whatever is not named answers `not_configured`, which is the honest state for a fixture cluster
 * with no exporter — and is what stops a case about one card from being surrounded by four red
 * failure panels it did not mean to assert against.
 */
const showMetrics = async (
  answers: Readonly<Record<string, unknown>>,
  at: string = `${DASHBOARD}/traffic`,
) => {
  const stub = stubApi({ ...UNCONFIGURED_METRICS, ...answers });
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

/**
 * The dashboard over a gateway that cannot be reached for exactly one of the five endpoints.
 *
 * `stubApi` refuses any path it was not given, so the failure is produced by leaving one out rather
 * than by a second kind of stub — which keeps "the gateway did not answer" one situation on this
 * screen instead of two.
 */
const showWithOneFailing = async (path: string) => {
  const answers = Object.fromEntries(
    Object.entries(UNCONFIGURED_METRICS).filter(([each]) => each !== path),
  );
  const stub = stubApi(answers);
  const mounted = keep(
    mount(
      dashboardHost(`${DASHBOARD}/traffic`, () => (
        <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />
      ), { api: stub.api }),
    ),
  );
  await settle();
  return { ...mounted, stub };
};

/**
 * Only the throughput requests, in order.
 *
 * The Traffic tab asks five endpoints, so a case about the range selector has to say which requests
 * it is counting or it is asserting how many other cards this tab happens to have.
 */
const throughputCalls = (calls: readonly string[]): readonly string[] =>
  calls.filter((call) => call.startsWith(THROUGHPUT_PATH));

/** The card's hidden data table, which is the honest reading of the bars beside it. */
const throughputTable = (container: HTMLElement): HTMLTableElement | null =>
  container.querySelector('[data-testid="panel-throughput"] table');

/** One bucket's two cells, by index, as a screen reader would read them. */
const bucketCells = (container: HTMLElement, index: number): readonly string[] => {
  const row = throughputTable(container)?.querySelectorAll("tbody tr")[index];
  return [...(row?.querySelectorAll("td") ?? [])].map((cell) => cell.textContent ?? "");
};

/**
 * The voice line under the page title, and only that.
 *
 * Read from the header's own element rather than from the container's text, because two of the
 * sentences this tab can print also appear inside the throughput card — so a case reading the whole
 * page would pass on the card's copy while the header said the opposite above it.
 */
const voiceOf = (container: HTMLElement): string =>
  container.querySelector('[data-testid="overview-header"] .kui-page-head__voice')?.textContent ?? "";

/** Which segment the strip has marked, read the way a screen reader reads it. */
const currentTab = (container: HTMLElement): string | null =>
  container.querySelector('[data-testid="tab-strip"] [aria-current="page"]')?.textContent?.trim() ?? null;

describe("the shared alerts card", () => {
  it("draws the store's cluster-wide count rather than counting the page rows", async () => {
    const alerts = staticAlerts({
      kind: "ready",
      value: {
        items: [
          {
            id: "evt-one",
            severity: "critical",
            tone: "danger",
            category: "partition",
            glyph: "partition",
            openedAt: "2026-09-03T09:11:12Z",
            lastSeenAt: "2026-09-03T10:11:12Z",
            title: "2 partitions offline",
            detail: "no leader",
            resolution: undefined,
          },
        ],
        total: 7,
        openCount: 7,
        unreadCount: 2,
        lastReadAt: undefined,
        evaluatedAt: "2026-09-03T10:11:12Z",
        rules: [],
      },
    });
    const screen = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts },
        ),
      ),
    );
    await settle();

    const card = screen.container.querySelector('[data-testid="panel-alerts"]');
    expect(card?.textContent).toContain("7 open");
    expect(card?.textContent).toContain("2 partitions offline");
    expect(card?.querySelectorAll("li")).toHaveLength(1);
  });

  /**
   * The compact card is compact, and the pill says how much it is not showing.
   *
   * `events().slice(0, 3)` could be written `events()` with every case in this package green: the
   * case above it hands the store one event, so the cap has never had more rows than it caps. A
   * cluster mid-incident with forty open events would then push `Storage by broker` off the bottom
   * of a dashboard that exists to be read in one glance — and every row on it would be true, which
   * is why nothing would report it.
   *
   * The pill is the other half: it draws the **server's** count, so the card is never claiming the
   * three rows are all there are.
   */
  it("draws three rows however many the feed holds, over the real count", async () => {
    const event = (id: string, title: string) => ({
      id,
      severity: "warning" as const,
      tone: "warning",
      category: "rebalance",
      glyph: "rebalance",
      openedAt: "2026-09-03T09:11:12Z",
      lastSeenAt: "2026-09-03T10:11:12Z",
      title,
      detail: undefined,
      resolution: undefined,
    });
    const alerts = staticAlerts({
      kind: "ready",
      value: {
        items: [
          event("evt-1", "first"),
          event("evt-2", "second"),
          event("evt-3", "third"),
          event("evt-4", "fourth"),
          event("evt-5", "fifth"),
        ],
        total: 12,
        openCount: 12,
        unreadCount: 5,
        lastReadAt: undefined,
        evaluatedAt: "2026-09-03T10:11:12Z",
        rules: [],
      },
    });
    const screen = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts },
        ),
      ),
    );
    await settle();

    const card = screen.container.querySelector('[data-testid="panel-alerts"]')!;
    expect(card.querySelectorAll("li")).toHaveLength(3);
    expect(card.textContent).toContain("third");
    expect(card.textContent).not.toContain("fourth");
    /* Twelve, not three and not five: the count is the service's, over the whole cluster, and the
       card would otherwise be a smaller number wearing the same pill. */
    expect(card.querySelector(".kui-pill")?.textContent).toContain("12 open");
  });

  /**
   * A feed KUI knows is out of date, on the one card on this page that is not drawn from the model.
   *
   * The stale arm is the same rule the traffic cards keep and it was gated by nothing here:
   * deleting the note left every case in this package green, and the card then drew a stream that
   * had dropped as though it were live. `Fetched.stale` carries no `asOf`, so `Card`'s stale badge
   * is not available and this sentence is the only thing on the card that says the rows are old.
   *
   * The rows are still drawn beside it, which is the rest of the rule: last-known-good alerts at
   * the moment the stream drops are worth more than a blank card.
   */
  it("says an alert feed is out of date, and still draws what it holds", async () => {
    const alerts = staticAlerts({
      kind: "stale",
      reason: "The alert stream closed and KUI has not been able to re-open it.",
      value: {
        items: [
          {
            id: "evt-one",
            severity: "critical",
            tone: "danger",
            category: "partition",
            glyph: "partition",
            openedAt: "2026-09-03T09:11:12Z",
            lastSeenAt: "2026-09-03T10:11:12Z",
            title: "2 partitions offline",
            detail: "no leader",
            resolution: undefined,
          },
        ],
        total: 3,
        openCount: 3,
        unreadCount: 1,
        lastReadAt: undefined,
        evaluatedAt: "2026-09-03T10:11:12Z",
        rules: [],
      },
    });
    const screen = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts },
        ),
      ),
    );
    await settle();

    const card = screen.container.querySelector('[data-testid="panel-alerts"]')!;
    expect(card.querySelector(".kui-alerts-summary__note")?.textContent).toBe(
      "The alert stream closed and KUI has not been able to re-open it.",
    );
    expect(card.textContent).toContain("2 partitions offline");
    expect(card.querySelector(".kui-pill")?.textContent).toContain("3 open");
  });

  it("mounts no alerts card for an unconfigured deployment", () => {
    const screen = show(HEALTHY);
    expect(screen.container.querySelector('[data-testid="panel-alerts"]')).toBeNull();
  });

  /**
   * The pill, over a cluster the service's rules have never run on.
   *
   * The card's `headerEnd` is `<Show when={openCount() !== null}>`, and the store answers `null`
   * for exactly this feed: `openCount: 0` beside an absent `evaluatedAt` is not a measured zero, it
   * is a cluster nobody has swept. Drawn as *"None open"* in a green pill, on the dashboard, beside
   * the storage card, it is the most reassuring thing this screen can say and it would be about
   * nothing at all — the same defect as the storage meter's em dash over a disk it had read,
   * inverted.
   *
   * Nothing had ever mounted the card in this state, so the guard was free to be deleted; and the
   * card's body sentence is the only thing left saying what is actually true, which is why it is
   * asserted here beside the absence of the pill rather than instead of it.
   */
  it("draws no pill for a feed with nothing behind its zero, and says why", async () => {
    const alerts = staticAlerts({
      kind: "ready",
      value: {
        items: [],
        total: 0,
        openCount: 0,
        unreadCount: 0,
        lastReadAt: undefined,
        evaluatedAt: undefined,
        rules: [],
      },
    });
    const screen = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts },
        ),
      ),
    );
    await settle();

    const card = screen.container.querySelector('[data-testid="panel-alerts"]');
    expect(card).not.toBeNull();
    expect(card?.querySelector(".kui-pill")).toBeNull();
    expect(card?.textContent).toContain("KUI has not evaluated this cluster's alert rules yet.");
    // And emphatically not the sentence for a cluster the rules *have* swept.
    expect(card?.textContent).not.toContain("None open");
    expect(card?.textContent).not.toContain("holding no events");
  });

  it("draws a measured zero as a pill: a cluster the rules swept clean is a fact", async () => {
    /* The other half of the same rule, and the reason the guard is `!== null` rather than
       `> 0`: once the rules have run, "nothing is open" is a measurement and the card says so. */
    const alerts = staticAlerts({
      kind: "ready",
      value: {
        items: [],
        total: 0,
        openCount: 0,
        unreadCount: 0,
        lastReadAt: undefined,
        evaluatedAt: "2026-09-03T10:11:12Z",
        rules: [],
      },
    });
    const screen = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts },
        ),
      ),
    );
    await settle();

    const card = screen.container.querySelector('[data-testid="panel-alerts"]');
    /* The same selector the case above asserts is absent, so that neither of the two is passing on
       a class name this card stopped using. */
    expect(card?.querySelector(".kui-pill")?.textContent).toContain("None open");
    expect(card?.textContent).toContain("holding no events for this cluster");
  });

  /**
   * A refusal is not a failure, on the card as on the bell.
   *
   * `stateAction`'s `Show` is on `kind === "failed"`, so a principal who may not read this
   * cluster's alerts gets the sentence and no Retry — pressing it would be refused every time.
   */
  it("offers no Retry on a refusal, and one on a read that did not answer", async () => {
    const refused = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          { alerts: staticAlerts({ kind: "forbidden" }) },
        ),
      ),
    );
    await settle();
    const refusedCard = refused.container.querySelector('[data-testid="panel-alerts"]');
    expect(refusedCard?.textContent).toContain("You do not have permission");
    expect(refusedCard?.querySelector("button")).toBeNull();

    const broken = keep(
      mount(
        dashboardHost(
          DASHBOARD,
          () => <Overview model={toOverviewModel(HEALTHY)} queries={createQueryRegistry()} />,
          {
            alerts: staticAlerts({
              kind: "failed",
              message: "The alerts service did not answer.",
              code: "KUI-UPSTREAM-UNAVAILABLE",
            }),
          },
        ),
      ),
    );
    await settle();
    const brokenCard = broken.container.querySelector('[data-testid="panel-alerts"]');
    expect(brokenCard?.textContent).toContain("The alerts service did not answer.");
    // The code beside the sentence: it is the part of a failure that survives being pasted into a
    // ticket.
    expect(brokenCard?.textContent).toContain("KUI-UPSTREAM-UNAVAILABLE");
    expect(brokenCard?.querySelector("button")?.textContent).toContain("Retry");
  });
});

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

describe("a deployment that has configured no exporter", () => {
  it("says so on every metrics card, in one voice, and draws no axis on any of them", async () => {
    // Five cards reach this state and they must reach it in one sentence: an operator looking at a
    // screen where five panels say the same thing in five different ways reads five problems. The
    // sentence is imported rather than retyped, which is the drift this packet removed — a case
    // that typed its own fragment passes while the screen says something else.
    const { container } = await showMetrics({});

    const nouns: Readonly<Record<string, string>> = {
      "panel-latency": LATENCY_NOUN,
      "panel-top-producers": PRODUCERS_NOUN,
      "panel-request-handlers": HANDLERS_NOUN,
    };
    for (const panel of ["panel-throughput", "panel-latency", "panel-top-producers", "panel-request-handlers"]) {
      const card = container.querySelector(`[data-testid="${panel}"]`);
      const noun = nouns[panel];
      expect(card?.textContent).toContain(
        noun === undefined ? "No metrics source is configured for it" : notConfiguredSentence(noun),
      );
      // An axis or a ring is a claim that the quantity is measured and merely absent right now,
      // and this cluster has nothing measuring it. Either sends somebody to find an exporter that
      // was never configured.
      expect(card?.querySelector("table")).toBeNull();
      expect(card?.querySelector('[role="img"]')).toBeNull();
      // And not a failure: no retry, because no retry could ever succeed.
      expect(card?.querySelector("button")).toBeNull();
    }
    // The card's own sentence, whole and imported rather than a fragment retyped here. Read from
    // the `NotMeasured` note itself, because the card's text also carries its title and its range
    // selector's three segments.
    expect(
      container.querySelector('[data-testid="throughput-not-measured"] .kui-not-measured__why')
        ?.textContent,
    ).toBe(NOT_CONFIGURED_SENTENCE);
  });

  it("says the same of the record-size card on the storage tab", async () => {
    const { container } = await showMetrics({}, `${DASHBOARD}/storage`);
    const sizes = container.querySelector('[data-testid="panel-message-sizes"]');
    expect(sizes?.textContent).toContain(notConfiguredSentence("this cluster's record sizes"));
    // The design's twelve buckets and five axis labels are exactly what must not be drawn.
    expect(sizes?.querySelector('.kui-chart, .kui-histogram, .kui-plot, [role="img"]')).toBeNull();
    // And no figure at all, dash or otherwise. The *sentence* contains an em dash — "— a deployment
    // choice rather than a fault" — so what must be absent is a figure that reads as one, which is
    // the rendering that says the mean is momentarily unreadable.
    expect(sizes?.querySelector('[data-testid="record-size-mean"]')).toBeNull();
  });

  it("does not print a dash where there is no measurement, which would read as a failed read", async () => {
    // A `StatCard` reading `— MB/s` says the rate is momentarily unreadable. The truth is that this
    // deployment configured nothing to read it, and that is a sentence rather than punctuation.
    const { container } = await showMetrics({});
    for (const stat of ["stat-production", "stat-consume"]) {
      const card = container.querySelector(`[data-testid="${stat}"]`);
      expect(card?.textContent).toContain("No metrics source is configured for it");
      /* No figure slot at all, which is stronger than "no dash in the text": the shared sentence
         itself ends "— a deployment choice rather than a fault", so a case that forbade the
         character would be forbidding the punctuation of the sentence it is asserting. What must
         not exist is a `StatCard` figure — `.kui-stat__unknown` is the em dash this rule is about. */
      expect(card?.querySelector(".kui-stat__figure")).toBeNull();
      expect(card?.querySelector(".kui-stat__unknown")).toBeNull();
      expect(card?.querySelector(".kui-sparkline")).toBeNull();
    }
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

  it("draws each card from its own read, so one dead family costs one card and not the tab", async () => {
    // ADR-039's whole shape, on one screen. The throughput and the producers are answered and the
    // other three are not, and the difference has to be visible per card rather than per page —
    // which is what `Section` is for and why these are five queries and not one.
    const { container } = await showMetrics({
      [THROUGHPUT_PATH]: throughputOk(THROUGHPUT_WITH_A_GAP),
      [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC),
    });

    expect(throughputTable(container)).not.toBeNull();
    expect(container.querySelector('[data-testid="panel-top-producers"]')?.textContent).toContain(
      "orders.payments",
    );
    expect(container.querySelector('[data-testid="panel-request-handlers"]')?.textContent).toContain(
      notConfiguredSentence(HANDLERS_NOUN),
    );
    expect(container.querySelector('[data-testid="panel-latency"]')?.textContent).toContain(
      notConfiguredSentence(LATENCY_NOUN),
    );
  });

  it("names the row by what the server measured, not by what the design drew", async () => {
    // §4 draws `Top producers · client.id`. A broker publishes no per-`client.id` byte rate unless
    // quotas are configured; it publishes a per-*topic* one. So the heading follows the answer —
    // a tile labelled `client.id` over a topic name is the defect wave 5's rule 7 exists to stop.
    const topics = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC) });
    const byTopic = topics.container.querySelector('[data-testid="panel-top-producers"]');
    expect(byTopic?.textContent).toContain("Top producers · topic");
    expect(byTopic?.textContent).not.toContain("client.id");

    const clients = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_CLIENT) });
    expect(clients.container.querySelector('[data-testid="panel-top-producers"]')?.textContent).toContain(
      "Top producers · client.id",
    );
  });

  it("gives each producer a monogram, which is what the design leads the row with", async () => {
    const { container } = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC) });
    const tiles = container.querySelectorAll('[data-testid="panel-top-producers"] .kui-monogram');
    expect(tiles).toHaveLength(5);
    // `orders.payments` reads left to right: the first letter of each of the first two segments.
    expect(tiles[0]?.textContent).toBe("OP");
    // Decoration, because the identifier it abbreviates is written beside it.
    expect(tiles[0]?.getAttribute("aria-hidden")).toBe("true");
  });

  it("draws every bar against the largest rate on the card, which is the comparison it exists for", async () => {
    // The figure this card *is*: a magnitude list compares its rows to each other. Nothing else here
    // reads a bar's width, so `Math.max` could be — and was — `Math.min` with the whole suite green,
    // after which every row but the quietest is pegged full and the ranking becomes unreadable.
    const { container } = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC) });
    const fills = container.querySelectorAll<HTMLElement>(
      '[data-testid="panel-top-producers"] .kui-progress__fill',
    );

    // Four bars and not five: the fifth row's rate never arrived, and an unknown value draws no
    // fill at all rather than an empty track that reads as a measured zero.
    expect(fills).toHaveLength(4);
    expect(fills[0]?.style.width).toBe("100%");
    // 3.1 MB/s against the busiest 5.4 MB/s. Rounded here, exact in the DOM.
    expect(Math.round(parseFloat(fills[1]?.style.width ?? "0"))).toBe(57);
    expect(Math.round(parseFloat(fills[2]?.style.width ?? "0"))).toBe(15);
    expect(parseFloat(fills[3]?.style.width ?? "0")).toBeLessThan(10);
  });

  it("says how many of Kafka's own topics were left out, and says nothing when none were", async () => {
    // A ranking that quietly omits rows cannot be reconciled against the exporter it came from. The
    // figure is the server's own — a browser that subtracted two list lengths would be reporting an
    // omission it inferred rather than one that happened.
    const excluded = await showMetrics({
      [PRODUCERS_PATH]: producersOk(PRODUCERS_WITH_INTERNAL_EXCLUDED),
    });
    const note = excluded.container.querySelector('[data-testid="producers-excluded"]');
    expect(note?.textContent).toContain("2 of Kafka's own internal topics are not ranked");

    // And never a zero: a sentence about an omission that did not happen is noise on a full card.
    const none = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC) });
    expect(none.container.querySelector('[data-testid="producers-excluded"]')).toBeNull();
  });

  it("says so in a sentence when the source answered and named nobody", async () => {
    // Real, and not the same as no source: the exporter published the family and no line carrying a
    // topic. A titled card with an empty body is what `fallback={undefined}` leaves here, and it is
    // the panel-renders-nothing failure this screen exists to prevent.
    const { container } = await showMetrics({
      [PRODUCERS_PATH]: producersOk({ measuredBy: "topic", topics: [], internalTopicsExcluded: 0 }),
    });
    const card = container.querySelector('[data-testid="panel-top-producers"]');

    expect(card?.textContent).toContain(NO_PRODUCERS);
    expect(card?.querySelector(".kui-progress__fill")).toBeNull();
    expect(card?.textContent).toContain(producersTitle({ rows: [], subject: "topic", internalTopicsExcluded: 0 }));
  });

  it("draws a failed read as an unavailable card carrying its sentence and its stable code", async () => {
    // Three props that only work together. `state="ready"` with the message and the code dropped
    // leaves a healthy-looking card with an empty body and nothing to quote in a support
    // conversation — and no Retry, for the one state where retrying is the right action.
    const { container } = await showWithOneFailing(PRODUCERS_PATH);
    const card = container.querySelector('[data-testid="panel-top-producers"]');

    // The code is what somebody quotes when they ask for help, and it is drawn in its own element.
    expect(card?.querySelector(".kui-empty-state--unavailable")).not.toBeNull();
    expect(card?.querySelector(".kui-empty-state__code")?.textContent).toContain("UNREACHABLE");
    // The gateway's own sentence and not `Card`'s "There is nothing to show." fallback, which is what
    // dropping `message` leaves behind: a card that says a failure happened and not what failed.
    const title = card?.querySelector(".kui-empty-state__title")?.textContent ?? "";
    expect(title).not.toBe("There is nothing to show.");
    expect(title).toContain("KUI cannot reach the server");
    // Not the not-configured sentence: nothing here is a deployment choice.
    expect(card?.querySelector('[data-testid="producers-not-measured"]')).toBeNull();
    expect(card?.textContent).not.toContain(notConfiguredSentence(PRODUCERS_NOUN));
  });

  it("keeps a producer whose rate did not arrive, and says so in words", async () => {
    // Dropping the row would shorten a top-five to a top-four without saying so; drawing a zero
    // would rank it last on a measurement nobody made.
    const { container } = await showMetrics({ [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC) });
    const card = container.querySelector('[data-testid="panel-top-producers"]');
    expect(card?.textContent).toContain("audit.trail");
    expect(card?.textContent).toContain("not measured");
  });

  it("names the tab in the strip and marks it current at its own address", async () => {
    const { container } = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    expect(currentTab(container)).toBe("Traffic");
  });

  it("promises only what it measures in the voice line", async () => {
    const measured = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(voiceOf(measured.container)).toContain("KUI measures the first of those");

    const unconfigured = await showTraffic(THROUGHPUT_NOT_CONFIGURED);
    // The design's sentence names three things and this cluster measures none of them. Printed
    // unqualified over cards that say so, it is the cheerful-line-over-a-broken-cluster failure in
    // a different hat: a reader who believes the header goes looking for the chart.
    expect(voiceOf(unconfigured.container)).toContain("no metrics source");
    expect(voiceOf(unconfigured.container)).not.toContain("KUI measures the first of those");
  });

  it("does not claim to be measuring when a reachable exporter has sampled nothing", async () => {
    /*
     * The rule this packet owns, and it is the one `trafficLede` was written for and nothing
     * asserted. `hasMeasuredBucket` is the whole difference between the two sentences, and
     * collapsing the branch to the cheerful one left 162 cases green — so a cluster whose exporter
     * is up and has sampled nothing got "KUI measures the first of those" printed over 288 blank
     * steps, which is precisely what the function's own eight-line comment says it exists to
     * prevent. The fixture and the story for this state both existed; neither was ever read.
     *
     * Asserted on the *voice line* rather than on the page's text, because "Nothing has been
     * sampled in this window yet" is also the plot's empty message — a case that read the whole
     * container would pass on the chart's sentence while the header lied above it.
     */
    const { container } = await showTraffic(throughputOk(THROUGHPUT_ALL_ABSENT));
    expect(voiceOf(container)).toContain("Nothing has been sampled in this window yet");
    expect(voiceOf(container)).not.toContain("KUI measures the first of those");
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
    // The card's own sentence, imported rather than retyped: a case that types its own fragment
    // keeps passing while the screen says something else, which is what these three exports were
    // added for and what nothing had ever used them for.
    expect(container.querySelector('[data-testid="panel-throughput"]')?.textContent).toContain(
      NO_SAMPLES_SENTENCE,
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

describe("the p99 latency card", () => {
  it("draws a null bucket as a gap and never as a zero", async () => {
    /*
     * The throughput card's rule, on the second chart drawn from a broker metric. The fixture holds
     * a measured `0` nowhere and twenty unsampled steps at 100..119, and a fold that turned a null
     * into a zero would draw a broker that stopped answering as one answering instantly — the most
     * flattering possible rendering of "we were not looking".
     *
     * Read from the plot's own hidden data table, which is the honest reading of a line that simply
     * has no ink in the hole.
     */
    const { container } = await showMetrics({ [LATENCY_PATH]: latencyOk(LATENCY_WITH_A_GAP) });
    const rows = container.querySelectorAll('[data-testid="panel-latency"] table tbody tr');
    expect(rows).toHaveLength(288);

    const cells = (index: number): readonly string[] =>
      [...(rows[index]?.querySelectorAll("td") ?? [])].map((cell) => cell.textContent ?? "");
    expect(cells(100)).toEqual(["—", "—"]);
    expect(cells(119)).toEqual(["—", "—"]);
    expect(cells(100)).not.toContain("0.00 ms");
    // And a bucket either side still carries its reading, so this is not a case that passes over a
    // chart that lost every value.
    expect(cells(99).join()).toContain("ms");
    expect(cells(120).join()).toContain("ms");
  });

  it("counts the gaps in words, and not in the words a rate would use", async () => {
    const { container } = await showMetrics({ [LATENCY_PATH]: latencyOk(LATENCY_WITH_A_GAP) });
    const caption = container.querySelector('[data-testid="panel-latency"] .kui-panel__caption');
    expect(caption?.textContent).toContain("20 of the 288 5-minute steps");
    // "as a rate of zero" belongs to the throughput card. A blank latency step drawn as zero claims
    // the broker answered instantly.
    expect(caption?.textContent).toContain("rather than as zero latency");
  });

  it("prints the newest measured reading in each legend chip", async () => {
    // §3.1 puts the current value in the chip, which is what lets this plot have no y-axis labels.
    const { container } = await showMetrics({ [LATENCY_PATH]: latencyOk(LATENCY_WITH_A_GAP) });
    const chips = [
      ...container.querySelectorAll('[data-testid="panel-latency"] .kui-chart-legend__item'),
    ].map((item) => item.textContent?.trim());
    expect(chips).toHaveLength(2);
    expect(chips[0]).toMatch(/^produce.*ms$/);
    expect(chips[1]).toMatch(/^fetch.*ms$/);
  });

  it("draws the whole axis for a window nothing was sampled in, and no figure in the chips", async () => {
    const { container } = await showMetrics({ [LATENCY_PATH]: latencyOk(LATENCY_ALL_ABSENT) });
    expect(container.querySelectorAll('[data-testid="panel-latency"] table tbody tr')).toHaveLength(288);
    expect(
      container.querySelectorAll('[data-testid="panel-latency"] .kui-chart-legend__value'),
    ).toHaveLength(0);
    // And the plot says which of the two nothings this is, in the card's own words: the window is
    // real and empty, which is not the same as a deployment that measures no latency at all.
    expect(container.querySelector('[data-testid="panel-latency"]')?.textContent).toContain(
      NO_LATENCY_SENTENCE,
    );
  });
});

describe("the request-handler tiles", () => {
  it("draws a ratio as a ring and a queue length as a count, in the same card", async () => {
    /*
     * §3.4 draws "71% NETWORK IDLE", "64% IO IDLE" and "38% PURGATORY" as three rings.
     * `DelayedOperationPurgatory` publishes a queue *length* with no ceiling, so the third is not a
     * percentage of anything and is not drawn as one — and the card says why in words, because a
     * tile that is not a ring in a row of rings otherwise reads as a ring that failed to draw.
     */
    const { container } = await showMetrics({ [HANDLERS_PATH]: handlersOk(HANDLER_READINGS) });
    const card = container.querySelector('[data-testid="panel-request-handlers"]');

    expect(card?.querySelector('[data-testid="handler-network-idle"] .kui-gauge')).not.toBeNull();
    expect(
      card?.querySelector('[data-testid="handler-io-idle"] [role="img"]')?.getAttribute("aria-label"),
    ).toBe("IO IDLE: 64%");
    // The ratio arrived as `0.64`, so a fold that forgot to multiply would print `1%`.
    expect(card?.textContent).toContain("64%");

    /* One tile per delayed operation rather than one summed tile: `Fetch` is deep by design on any
       cluster with consumers and `Produce` being deep at all means acknowledgements are waiting on
       replicas, so adding them makes the ordinary number hide the interesting one. */
    const purgatory = card?.querySelector('[data-testid="handler-purgatory-fetch"]');
    expect(purgatory?.querySelector(".kui-gauge")).toBeNull();
    expect(purgatory?.textContent).toContain("481");
    expect(purgatory?.textContent).toContain("requests");
    expect(purgatory?.textContent).not.toContain("481%");
    expect(card?.querySelector('[data-testid="handler-purgatory-produce"]')).not.toBeNull();
    expect(card?.textContent).toContain("queue lengths");
  });

  it("says so in a sentence when the source answered and this build read nothing out of it", async () => {
    // The state the two mismatched wires used to produce on every request: the section decoded, the
    // document carried nothing this build recognises, and the card drew a titled panel. The rule is
    // that it draws the sentence — a card with a heading and an empty body is the failure this
    // screen exists to prevent, and it is what `fallback={undefined}` here leaves behind.
    const { container } = await showMetrics({ [HANDLERS_PATH]: handlersOk(HANDLERS_NOTHING_READ) });
    const card = container.querySelector('[data-testid="panel-request-handlers"]');

    expect(card?.textContent).toContain(NO_HANDLER_READINGS);
    expect(card?.querySelector(".kui-gauge")).toBeNull();
    // And the sentence is the body, not a heading with nothing under it.
    expect((card?.textContent ?? "").length).toBeGreaterThan("Request handlers".length + 40);
  });

  it("draws the plain track and an em dash for a reading with no value", async () => {
    // §3.4's absent rule, and `RingGauge`'s: never a full ring and never an empty one that reads as
    // a measured zero. The arc is a separate element from the track, so counting paths is the
    // honest test for "did this gauge claim a measurement".
    const { container } = await showMetrics({ [HANDLERS_PATH]: handlersOk(HANDLER_ONE_ABSENT) });
    const absent = container.querySelector('[data-testid="handler-io-idle"]');

    expect(absent?.querySelector(".kui-gauge__track")).not.toBeNull();
    expect(absent?.querySelector(".kui-gauge__arc")).toBeNull();
    expect(absent?.querySelector(".kui-gauge__figure")?.textContent).toBe("—");
    // The dash never reaches a screen reader: it is announced as "dash" or as nothing at all.
    expect(absent?.querySelector('[role="img"]')?.getAttribute("aria-label")).toBe(
      "IO IDLE: not measured",
    );
    // Beside a gauge that did measure something, so this is not a card that drew nothing.
    expect(container.querySelector('[data-testid="handler-network-idle"] .kui-gauge__arc')).not.toBeNull();
  });
});

describe("the record-size card, on a cluster that is measured", () => {
  it("draws the mean and refuses the distribution the design asked for", async () => {
    /*
     * The card ADR-052 calls unmeasurable, drawn on a deployment where the other four cards are
     * answering — which is the only way a refusal can be told apart from an endpoint nobody wrote.
     * §3.5 draws twelve buckets and `p50 · 1.1 KB` / `p99 · 18 KB` / `max · 0.9 MB`; a broker
     * publishes a mean and nothing else, so the mean is printed and the distribution is named as
     * absent rather than assembled from it.
     */
    const { container } = await showMetrics({
      [THROUGHPUT_PATH]: throughputOk(THROUGHPUT_WITH_A_GAP),
      [RECORD_SIZE_PATH]: recordSizeOk(RECORD_SIZE_MEAN),
    });
    const card = container.querySelector('[data-testid="panel-message-sizes"]');

    expect(card?.querySelector('[data-testid="record-size-mean"]')?.textContent).toContain("1.2 kB");
    expect(card?.textContent).toContain(NO_DISTRIBUTION);
    /* No histogram, no axis, and none of the three percentile chips the design draws. Selected by
       the chart family's own classes rather than by `svg`, because `Card` draws its title icon as
       one and a case that counted every `svg` would be asserting that the card has no icon. */
    expect(card?.querySelector(".kui-histogram, .kui-plot, .kui-chart")).toBeNull();
    expect(card?.textContent).not.toContain("p50");
    expect(card?.textContent).not.toContain("p99");
    // And the throughput card beside it really is measured, so the refusal is a refusal and not the
    // absence of the code.
    expect(throughputTable(container)).not.toBeNull();
  });

  it("names what it is not measuring when the deployment has no metrics source", async () => {
    // The card's noun, from the card, so a change to it moves this case rather than leaving the
    // screen and the string it exports saying different things.
    const { container } = await showMetrics({});
    expect(container.querySelector('[data-testid="panel-message-sizes"]')?.textContent).toContain(
      notConfiguredSentence(RECORD_SIZE_NOUN),
    );
  });

  it("says a mean that did not arrive in words rather than with a dash", async () => {
    const { container } = await showMetrics({ [RECORD_SIZE_PATH]: recordSizeOk(RECORD_SIZE_ABSENT) });
    const figure = container.querySelector('[data-testid="record-size-mean"]');
    expect(figure?.textContent).toContain("not measured");
    expect(figure?.textContent).not.toContain("—");
  });

  it("is drawn on the storage tab too, because it is the same card", async () => {
    const { container } = await showMetrics(
      { [RECORD_SIZE_PATH]: recordSizeOk(RECORD_SIZE_MEAN) },
      `${DASHBOARD}/storage`,
    );
    expect(container.querySelector('[data-testid="record-size-mean"]')?.textContent).toContain("1.2 kB");
  });
});

describe("the two rate cards, from the series already on the screen", () => {
  it("prints the current rate and a sparkline of the window", async () => {
    // §3.2's `Produce rate 86.4 MB/s` and `Consume rate 71.2 MB/s`, each with the jagged sparkline
    // the same table gives it. `bytesInPerSecond` **is** the produce rate — the same broker metric
    // the chart below is drawn from — rather than a number derived from something else.
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));

    for (const stat of ["stat-production", "stat-consume"]) {
      const card = container.querySelector(`[data-testid="${stat}"]`);
      expect(card?.querySelector(".kui-stat__figure")?.textContent).toMatch(/\/s$/);
      // The mark is decoration on a card that has already printed the figure, so it says nothing
      // to a screen reader — §3.3, in one attribute.
      const spark = card?.querySelector(".kui-sparkline");
      expect(spark).not.toBeNull();
      expect(spark?.getAttribute("aria-hidden")).toBe("true");
    }
    // The two cards are not the same number: one is bytes in and the other bytes out.
    const produce = container.querySelector('[data-testid="stat-production"] .kui-stat__figure')?.textContent;
    const consume = container.querySelector('[data-testid="stat-consume"] .kui-stat__figure')?.textContent;
    expect(produce).not.toBe(consume);

    /* And neither is the *mark*. The figure and the sparkline come from one `pick`, so a swap of
       both shows up in the line above — but a sparkline handed the other card's series while the
       figure stayed right would leave that assertion green, and a reader would compare two cards
       whose shapes were the same picture of one quantity. */
    const line = (stat: string): string | null =>
      container
        .querySelector(`[data-testid="${stat}"] .kui-sparkline__line`)
        ?.getAttribute("points") ?? null;
    expect(line("stat-production")).not.toBeNull();
    expect(line("stat-production")).not.toBe(line("stat-consume"));
  });

  it("says nothing has been sampled rather than printing a rate of zero", async () => {
    // A reachable exporter that has sampled nothing has no *current* rate. A card reading `0 B/s`
    // would be a measured claim about a cluster nobody measured.
    const { container } = await showTraffic(throughputOk(THROUGHPUT_ALL_ABSENT));
    const card = container.querySelector('[data-testid="stat-production"]');
    expect(card?.textContent).toContain("Nothing has been sampled in this window yet");
    expect(card?.textContent).not.toContain("0 B/s");
    expect(card?.querySelector(".kui-sparkline")).toBeNull();
  });

  it("prints a measured zero as a rate, because a quiet cluster is a reading", async () => {
    /*
     * The one case that separates "nobody is writing to this cluster" from "nobody measured it",
     * and it is a case the obvious code gets wrong: written `<Show when={rate()}>`, a rate of
     * exactly `0` is falsy and the card falls through to the sentence — reporting an idle cluster
     * as an unmeasured one, which is this screen's central mistake made backwards. Found by writing
     * it that way first.
     */
    const { container } = await showTraffic(throughputOk(THROUGHPUT_MEASURED_ZERO));
    const card = container.querySelector('[data-testid="stat-production"]');
    expect(card?.querySelector(".kui-stat__figure")?.textContent).toBe("0 B/s");
    expect(card?.textContent).not.toContain("Nothing has been sampled");
    expect(card?.querySelector(".kui-sparkline")).not.toBeNull();
  });

  it("leaves the two cards with no series without a sparkline at all", async () => {
    // §3.2's absent rule: a card with no series has no sparkline, and does not draw a flat line at
    // zero. KUI keeps no history of the topic count or of the in-sync share, so neither card gets
    // one — and a flat mark beside `128 total` would assert a trend nobody measured.
    const { container } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(container.querySelector('[data-testid="stat-topics"] .kui-sparkline')).toBeNull();
    expect(container.querySelector('[data-testid="stat-in-sync"] .kui-sparkline')).toBeNull();
  });
});

describe("an answer KUI knows is out of date", () => {
  it("says so in the caption, beside the data it is still drawing", async () => {
    /*
     * The second rule this packet owns. Nothing anywhere rendered the throughput card in `stale`
     * — no fixture, no story, no render case — so `captionOf`'s stale branch could be replaced by
     * `return chart?.caption` with 162 cases green, and last-known-good data then drew as though it
     * were current: no badge (deliberately, because `Fetched.stale` carries no `asOf` and the badge
     * would mean inventing a timestamp) and, after the mutation, no sentence either. That is the
     * same defect class the brokers screen was repaired for in the same wave.
     *
     * The data is still drawn, which is the other half of the rule: a blank panel at the moment
     * something is wrong is worse than an old figure that says it is old.
     */
    const { container } = await showTraffic(THROUGHPUT_STALE);
    const caption = container.querySelector('[data-testid="panel-throughput"] .kui-panel__caption');

    expect(caption?.textContent).toContain("This is the last answer KUI received:");
    expect(caption?.textContent).toContain("The exporter has not answered since 11:58.");
    // And the gap count is still there: the stale reason is added to what the card had to say, not
    // instead of it.
    expect(caption?.textContent).toContain("20 of the 288 5-minute steps");
    expect(throughputTable(container)?.querySelectorAll("tbody tr")).toHaveLength(288);
  });

  /**
   * The same rule on the three cards that share one helper, and this is W7-02's third metrics rule
   * — it lives in this tree rather than in `services/metrics`, which is why it is written here.
   *
   * `TrafficCards.captionOf` opens `if (state.kind !== "stale") return own;`, and widening that to
   * `if (state.kind !== "stale" || true)` was green across the whole frontend suite. Request
   * handlers, Top producers and Message size would then draw hour-old figures with **no badge and
   * no sentence** — the badge deliberately, because `Fetched.stale` carries no `asOf` and drawing
   * one would mean inventing a timestamp, which leaves the caption as the only thing on the card
   * that says the numbers are old. The only assertion of that caption in the repository covered
   * `ThroughputCard`, which has a *separate* copy of the helper one file over, so the three cards
   * this file is named for were gated by nothing at all.
   *
   * All three in one case on purpose: they share the function, and a case naming one of them would
   * leave a "fix" that special-cases that card and drops the sentence from the other two.
   */
  it("says so on all three of the cards that share the Traffic tab's caption", async () => {
    const { container } = await showMetrics({
      [HANDLERS_PATH]: HANDLERS_STALE,
      [PRODUCERS_PATH]: PRODUCERS_STALE,
      [RECORD_SIZE_PATH]: RECORD_SIZE_STALE,
    });
    const caption = (panel: string): string =>
      container.querySelector(`[data-testid="${panel}"] .kui-panel__caption`)?.textContent ?? "";

    for (const panel of ["panel-request-handlers", "panel-top-producers", "panel-message-sizes"]) {
      expect(caption(panel)).toContain("This is the last answer KUI received:");
      expect(caption(panel)).toContain("The exporter has not answered since 11:58.");
    }

    /* The card's own sentence survives behind the staleness: the record-size card had a window to
       state, and the stale reason is added to it rather than instead of it. */
    expect(caption("panel-message-sizes")).toContain("Averaged over the last hour.");

    /* And the figures are still on screen, which is the other half of the rule. A blank panel at
       the moment something is wrong is worse than an old number that says it is old. */
    // Four tiles: the two idle ratios, and one per purgatory queue the exporter named.
    expect(
      container.querySelectorAll('[data-testid="panel-request-handlers"] .kui-handlers__tile'),
    ).toHaveLength(4);
    expect(
      container.querySelector('[data-testid="panel-top-producers"]')?.textContent,
    ).toContain("orders.payments");
    expect(
      container.querySelector('[data-testid="record-size-mean"]')?.textContent,
    ).toContain("1.2 kB");
    /* Not drawn as a failure either: a stale read is data, so there is no unavailable state and no
       Retry — which is what separates this case from the one about a read that did not answer. */
    expect(
      container.querySelector('[data-testid="panel-top-producers"] button'),
    ).toBeNull();
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

  /* Fifteen seconds rather than the default five. This case mounts the whole Traffic tab, which is
     five metrics reads and five cards, and the default budget is one this file's own a11y case
     already had to raise for a smaller tree — a red here under a loaded machine is a stopwatch and
     not a defect. */
  it("still offers the range control, because the card is the same card", { timeout: 15_000 }, async () => {
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

    expect(card?.textContent).toContain(FORBIDDEN_SENTENCE);
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

    expect(throughputCalls(stub.calls)).toEqual([`${THROUGHPUT_PATH}?range=30d`]);
    expect(container.querySelector('[role="radiogroup"] input[value="30d"]')).toHaveProperty(
      "checked",
      true,
    );
  });

  it("reaches the request and not only the label", async () => {
    const { container, stub } = await showTraffic(throughputOk(THROUGHPUT_WITH_A_GAP));
    expect(throughputCalls(stub.calls)).toEqual([`${THROUGHPUT_PATH}?range=24h`]);

    choose7d(container);
    await settle();

    expect(throughputCalls(stub.calls)).toEqual([
      `${THROUGHPUT_PATH}?range=24h`,
      `${THROUGHPUT_PATH}?range=7d`,
    ]);
  });

  it("resolves a window nobody has to the default rather than refusing the page", async () => {
    // `?range=` is user-editable in the same way the tab segment is, and a typo is not an error
    // state. Answering `90d` with a day of data under a label the caller chose is the one failure a
    // chart cannot show its reader, which is why the *request* falls back too.
    const { container, stub } = await showTraffic(
      throughputOk(THROUGHPUT_WITH_A_GAP),
      `${DASHBOARD}/traffic?range=90d`,
    );
    expect(throughputCalls(stub.calls)).toEqual([`${THROUGHPUT_PATH}?range=24h`]);
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

  /* The same thirty seconds its neighbour above takes, and for a related reason. This tab is five
     metrics cards now rather than one chart and three sentences, and axe walks the whole subtree —
     the not-configured rendering is cheap to *draw* and is still five cards, two stat notes and a
     tab strip to audit. Measured here at 15.8s on a machine running eleven other suites; the five
     the default allows is a stopwatch rather than a finding. */
  it("has no accessibility violations when there is nothing to measure", { timeout: 30_000 }, async () => {
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
