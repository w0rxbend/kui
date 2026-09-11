/**
 * The shell's own behaviour, all of it learned from defects this product shipped.
 *
 * Everything here is driven with no server, no clock and no `EventSource`: the stores take their
 * stream, their poller and their timer as parameters, which is what makes a bounded wait testable at
 * all. A bound nobody tests is a bound nobody has, and the unbounded one shipped.
 */
import { describe, expect, it } from "vitest";
import { render } from "@solidjs/web";
import { flush, type Accessor } from "solid-js";
import { createSignal } from "solid-js";
import { Actions, ReasonCodes } from "@kui/api";
import { deriveFeatureState, type FeatureRegistration, type FeatureState } from "@kui/kernel";

import { FeatureGate } from "./features/FeatureGate.jsx";
import { createHealth, FailuresBeforeGivingUp, backoffAfter, MaxBackoffMs } from "./health.js";
import { destinationFor, navigationGroups, stillWorking, type FeatureStatus } from "./nav/navigation.js";
import { clusterInUrl, createShellRouter, landingFor } from "./routing/routes.jsx";
import {
  clusterSummaries,
  countLookup,
  currentFeatureId,
  environmentSwitch,
  topCrumbs,
} from "./App.jsx";
import type { NavCounts } from "./chrome/types.js";

const topics: FeatureRegistration = {
  id: "topics",
  serviceId: "topic",
  viewAction: Actions.TopicView,
  label: "Topics",
  icon: "topics",
  group: "Cluster",
  order: 200,
  requiresCluster: true,
  sidebar: true,
  load: async () => ({}),
};

const clusters: FeatureRegistration = {
  ...topics,
  id: "clusters",
  serviceId: "cluster",
  viewAction: Actions.ClusterConfigView,
  label: "Clusters",
  icon: "brokers",
  order: 100,
  requiresCluster: false,
};

/** Renders into a detached element and hands back the root plus its disposer. */
function mount(view: () => unknown) {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const dispose = render(view as never, host);
  flush();
  return {
    host,
    dispose: () => {
      dispose();
      host.remove();
    },
  };
}

const ready: FeatureState = { kind: "ready" };
const down: FeatureState = {
  kind: "unavailable",
  code: ReasonCodes.UpstreamUnavailable,
  message: "",
  since: undefined,
};

describe("the route table", () => {
  /* The property the whole arrangement exists for: the patterns are registered before any feature
   * chunk has been fetched, so a bookmarked deep link resolves on the very first pass. If the router
   * only learned a URL once its feature had been imported, the first address it saw would be one it
   * could not match and the user would get a 404 for a page that exists. */
  it("resolves a deep link to a feature route before that feature has been downloaded", () => {
    let asked = 0;
    const router = createShellRouter("", {
      home: () => null,
      settings: () => null,
      forbidden: () => null,
      notFound: () => null,
      feature: (id) => () => {
        asked += 1;
        return id;
      },
    });

    const matched = router.match("/ui/clusters/prod/topics/orders");
    expect(matched.length).toBeGreaterThan(0);
    /* Matching a route must not have imported anything: the component is not even called. */
    expect(asked).toBe(0);
  });

  /* A hard-coded root link broke this product behind a reverse proxy once already. */
  it("carries the deployment's base path on every address it builds", () => {
    const views = {
      home: () => null,
      settings: () => null,
      forbidden: () => null,
      notFound: () => null,
      feature: () => () => null,
    };

    const atRoot = createShellRouter("", views);
    expect(atRoot.paths()).toBe("/ui");
    expect(landingFor(atRoot, "clusters", undefined)).toBe("/ui/clusters");
    expect(landingFor(atRoot, "topics", "prod")).toBe("/ui/clusters/prod/topics");

    const behindProxy = createShellRouter("/kui", views);
    expect(behindProxy.paths()).toBe("/kui/ui");
    expect(landingFor(behindProxy, "topics", "prod")).toBe("/kui/ui/clusters/prod/topics");
    expect(behindProxy.match("/kui/ui/clusters/prod/topics").length).toBeGreaterThan(0);
  });

  it("reads the cluster out of a pasted link, so the recipient sees what the sender saw", () => {
    expect(clusterInUrl("/ui/clusters/prod/topics/orders", "/ui")).toBe("prod");
    expect(clusterInUrl("https://kafka.example/kui/ui/clusters/prod", "/kui/ui")).toBe("prod");
    expect(clusterInUrl("/ui/clusters", "/ui")).toBeUndefined();
    /* `manage` is a page, not a cluster id. */
    expect(clusterInUrl("/ui/clusters/manage", "/ui")).toBeUndefined();
    expect(clusterInUrl("/ui/settings", "/ui")).toBeUndefined();
  });
});

describe("the navigation's five states", () => {
  const landing = (registration: FeatureRegistration, cluster: string | undefined) =>
    registration.requiresCluster
      ? cluster === undefined
        ? undefined
        : `/ui/clusters/${cluster}/topics`
      : "/ui/clusters";

  const entryFor = (state: FeatureState, hideForbidden = false) =>
    destinationFor({ registration: topics, state }, { landingFor: landing, cluster: "prod", hideForbidden });

  it("keeps an unavailable feature clickable and makes it explain itself", () => {
    const entry = entryFor(down);
    /* Not disabled, and with a real href: the page behind it is the fallback panel, which is the
     * only place the reason, the since, the retry and "what still works" exist. */
    expect(entry?.disabled).toBeUndefined();
    expect(entry?.href).toBe("/ui/clusters/prod/topics");
    expect(entry?.state).toBe("unavailable");
    expect(entry?.badge?.description).toBe("The cluster is not answering.");
  });

  it("hides a feature this deployment has not configured", () => {
    expect(entryFor({ kind: "not_configured" })).toBeUndefined();
  });

  it("shows a forbidden feature disabled, and hides it only when the deployment asks", () => {
    const entry = entryFor({ kind: "forbidden" });
    expect(entry?.disabled).toBe(true);
    expect(entry?.disabledReason).toBe("You do not have permission to view Topics.");
    expect(entryFor({ kind: "forbidden" }, true)).toBeUndefined();
  });

  it("leaves a degraded feature usable and marks it", () => {
    const entry = entryFor({
      kind: "degraded",
      code: ReasonCodes.UpstreamTimeout,
      message: "reading the cluster is taking 4s",
      suggestedPollIntervalMs: undefined,
    });
    expect(entry?.disabled).toBeUndefined();
    expect(entry?.badge?.tone).toBe("warning");
    /* The gateway's own message wins over the code's sentence: it is the more specific of the two. */
    expect(entry?.badge?.description).toBe("reading the cluster is taking 4s");
  });

  it("leaves a cluster-scoped entry out until a cluster is chosen", () => {
    /* An empty path segment collapses, so `/ui/clusters//topics` is `/ui/clusters/topics`, which
     * matches no route. Every cluster-scoped entry in this product was once a dead link. */
    const entry = destinationFor(
      { registration: topics, state: ready },
      { landingFor: landing, cluster: undefined },
    );
    expect(entry).toBeUndefined();
  });

  /* A navigation whose entries reshuffle when a service goes down is one where the user clicks the
   * wrong thing: they aim at the position their muscle memory learned. */
  it("does not move an entry when its state changes", () => {
    const order = (state: FeatureState) =>
      navigationGroups({
        features: [
          { registration: clusters, state: ready },
          { registration: topics, state },
        ],
        landingFor: landing,
        cluster: "prod",
      })
        .flatMap((group) => group.destinations)
        .map((destination) => destination.id);

    expect(order(ready)).toEqual(order(down));
  });

  it("names the other features that still work, and only the other ones", () => {
    const features: readonly FeatureStatus[] = [
      { registration: clusters, state: ready },
      { registration: topics, state: down },
    ];
    expect(stillWorking(features, "topics")).toEqual(["Clusters"]);
    expect(stillWorking(features, "clusters")).toEqual([]);
  });
});

/**
 * The seam between the frame's store and the drawer's badges.
 *
 * `navigationGroups` owns every rule about what a badge says, and `nav/navigation.test.ts` pins
 * them. What is asserted here is the other half: that the shell actually hands the fold the numbers
 * its store learned, keyed the way the fold expects, and that plugging a real source in does not
 * quietly defeat the one rule that matters most when a service is down. Wave 1 shipped the fold and
 * the store and connected neither to the other, and every rule below was green throughout.
 */
describe("the badges the frame hands the drawer", () => {
  const landing = (registration: FeatureRegistration, cluster: string | undefined) =>
    registration.requiresCluster
      ? cluster === undefined
        ? undefined
        : `/ui/clusters/${cluster}/topics`
      : "/ui/clusters";

  const badgeFor = (state: FeatureState, counts: NavCounts | undefined) =>
    navigationGroups({
      features: [{ registration: topics, state }],
      landingFor: landing,
      cluster: "prod",
      countFor: countLookup(counts),
    })
      .flatMap((group) => group.destinations)
      .find((destination) => destination.id === "topics")?.badge;

  it("draws the count the store learned", () => {
    const badge = badgeFor(ready, { topics: { kind: "total", value: 128 } });
    expect(badge?.text).toBe("128");
    expect(badge?.tone).toBe("neutral");
  });

  it("draws no badge at all for a count the store has not learned", () => {
    /* Not a `0`. "Topics 0" on a cluster whose topic service did not answer is a statement about
       the cluster, and a false one — and the store has three separate ways of having no number. */
    expect(badgeFor(ready, {})).toBeUndefined();
    expect(badgeFor(ready, undefined)).toBeUndefined();
  });

  it("gives the row to the capability badge when the service is down, count or no count", () => {
    /* `128` beside a dead topic service is a reassuring picture of an outage: the reader sees a
       figure, concludes the topics are fine, and the one marker that would have said otherwise is
       the one dropped to make room for it. */
    const badge = badgeFor(down, { topics: { kind: "total", value: 128 } });
    expect(badge?.text).toBe("down");
    expect(badge?.tone).toBe("danger");
  });
});

describe("the feature gate", () => {
  function gate(state: Accessor<FeatureState>, load: () => Promise<unknown>, schedule?: (ms: number, run: () => void) => void) {
    const timers: { ms: number; run: () => void }[] = [];
    const view = mount(() => (
      <FeatureGate
        registration={{ ...topics, load: load as FeatureRegistration["load"] }}
        state={state}
        onProbe={() => undefined}
        stillWorking={() => []}
        loadOptions={{
          timeoutMs: 20_000,
          schedule: schedule ?? ((ms, run) => timers.push({ ms, run })),
        }}
      />
    ));
    return { ...view, timers };
  }

  /* ADR-012's central claim: an unavailable feature is never downloaded. Clicking a dimmed entry
   * lands here and renders the panel without a byte being fetched. */
  it("does not download a feature whose service is unavailable", () => {
    let imports = 0;
    const view = gate(
      () => down,
      async () => {
        imports += 1;
        return {};
      },
    );
    expect(imports).toBe(0);
    expect(view.host.querySelector("[data-testid='feature-fallback']")).not.toBeNull();
    view.dispose();
  });

  it("does not download a feature the user may not see, and offers no retry for it", () => {
    let imports = 0;
    const view = gate(
      () => ({ kind: "forbidden" }),
      async () => {
        imports += 1;
        return {};
      },
    );
    expect(imports).toBe(0);
    /* A permission decision does not change because the user pressed a button, and a button that
     * cannot help is worse than no button. */
    expect(view.host.querySelector("[data-testid='fallback-retry']")).toBeNull();
    expect(view.host.querySelector("[data-testid='feature-notice']")?.textContent).toContain(
      "You do not have permission",
    );
    view.dispose();
  });

  it("never leaves the content area blank while the chunk is in flight, and names what is loading", () => {
    const view = gate(() => ready, () => new Promise(() => {}));
    const spinner = view.host.querySelector("[data-testid='feature-loading']");
    expect(spinner).not.toBeNull();
    /* Not "loading…": a user on a slow connection cannot otherwise tell whether the thing they
     * clicked is the thing that is loading. */
    expect(spinner?.textContent).toContain("Loading Topics…");
    expect(spinner?.getAttribute("role")).toBe("status");
    view.dispose();
  });

  /* The defect: a permanent "Loading Messages…". A dynamic import can hang — a captive portal, a
   * proxy holding the connection open — and without a deadline the route spins for the life of the
   * tab. */
  it("gives up on a hanging download within the deadline and offers a retry", () => {
    const view = gate(() => ready, () => new Promise(() => {}));
    expect(view.timers.length).toBe(1);
    expect(view.timers[0]!.ms).toBe(20_000);

    view.timers[0]!.run();
    flush();

    const panel = view.host.querySelector("[data-testid='feature-fallback']");
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain("did not arrive within 20 seconds");
    expect(view.host.querySelector("[data-testid='fallback-retry']")).not.toBeNull();
    view.dispose();
  });

  it("retries the download, and says a failed download is a network problem", async () => {
    let attempts = 0;
    const view = gate(() => ready, async () => {
      attempts += 1;
      if (attempts === 1) throw new Error("chunk 404");
      return { default: () => "the topics screen" };
    });

    await Promise.resolve();
    await Promise.resolve();
    flush();
    expect(view.host.textContent).toContain("usually a network problem");
    expect(view.host.textContent).toContain("chunk 404");

    (view.host.querySelector("[data-testid='fallback-retry']") as HTMLButtonElement).click();
    flush();
    await Promise.resolve();
    await Promise.resolve();
    flush();

    expect(attempts).toBe(2);
    expect(view.host.textContent).toContain("the topics screen");
    view.dispose();
  });

  /* An import that finally arrives after we gave up on it must not overwrite the failure the user is
   * now looking at with a retry button: the component was never rendered and the panel would vanish
   * into nothing. */
  it("ignores a download that arrives after the deadline has passed", async () => {
    let settle: ((value: unknown) => void) | undefined;
    const view = gate(() => ready, () => new Promise((resolve) => (settle = resolve)));

    view.timers[0]!.run();
    flush();
    expect(view.host.querySelector("[data-testid='feature-fallback']")).not.toBeNull();

    settle?.({ default: () => "too late" });
    await Promise.resolve();
    await Promise.resolve();
    flush();

    expect(view.host.textContent).not.toContain("too late");
    expect(view.host.querySelector("[data-testid='feature-fallback']")).not.toBeNull();
    view.dispose();
  });

  it("says so when a feature's chunk arrives with no screen in it", async () => {
    const view = gate(() => ready, async () => ({ TopicListPage: () => "a page" }));
    await Promise.resolve();
    await Promise.resolve();
    flush();
    expect(view.host.textContent).toContain("does not yet provide a screen");
    view.dispose();
  });
});

describe("the connectivity tracker", () => {
  function tracker() {
    const timers: (() => void)[] = [];
    let retries = 0;
    const health = createHealth({
      now: () => new Date("2026-09-05T09:14:00Z"),
      schedule: (_delay, run) => timers.push(run),
      onRetry: () => {
        retries += 1;
      },
    });
    return { health, timers, retries: () => retries, runTimer: () => timers.shift()?.() };
  }

  /* One failed request is not an outage: a laptop's wifi hiccups and a proxy drops one connection.
   * Taking the whole application away for that would put the full-screen state on screen several
   * times a day for people whose network is merely ordinary. */
  it("does not take the application away for one failed request", () => {
    const { health } = tracker();
    health.report("shell", "transport-failure");
    flush();
    expect(health.connectivity().kind).toBe("connected");

    for (let i = 1; i < FailuresBeforeGivingUp; i += 1) health.report("shell", "transport-failure");
    flush();
    expect(health.connectivity().kind).toBe("lost");
  });

  it("does not count a gateway that answered, however it answered", () => {
    const { health } = tracker();
    /* A 403 or a 404 is the gateway answering, and answering is the opposite of unreachable. */
    for (let i = 0; i < 10; i += 1) health.report("shell", "answered");
    flush();
    expect(health.connectivity().kind).toBe("connected");
  });

  it("does not count a feature's own failed request", () => {
    const { health } = tracker();
    for (let i = 0; i < 10; i += 1) health.report("feature", "transport-failure");
    flush();
    expect(health.connectivity().kind).toBe("connected");
  });

  it("counts down visibly and backs off, and a manual retry starts again from the first wait", () => {
    const { health, runTimer, retries } = tracker();
    for (let i = 0; i < FailuresBeforeGivingUp; i += 1) health.report("shell", "transport-failure");
    flush();
    expect(health.connectivity()).toMatchObject({ nextRetryInSeconds: 2 });

    runTimer();
    flush();
    expect(health.connectivity()).toMatchObject({ nextRetryInSeconds: 1 });

    runTimer();
    flush();
    /* Reaching zero attempts contact and doubles the wait. */
    expect(retries()).toBe(1);
    expect(health.connectivity()).toMatchObject({ nextRetryInSeconds: 4 });

    health.retryNow();
    flush();
    expect(retries()).toBe(2);
    expect(health.connectivity()).toMatchObject({ nextRetryInSeconds: 2 });
  });

  it("caps the wait rather than doubling for ever", () => {
    let wait = 2_000;
    for (let i = 0; i < 20; i += 1) wait = backoffAfter(wait);
    expect(wait).toBe(MaxBackoffMs);
  });

  it("comes back with no reload when anything answers", () => {
    const { health } = tracker();
    for (let i = 0; i < FailuresBeforeGivingUp; i += 1) health.report("shell", "transport-failure");
    flush();
    expect(health.connectivity().kind).toBe("lost");

    /* A feature's success is evidence too: if its request came back, the gateway is reachable. */
    health.report("feature", "ok");
    flush();
    expect(health.connectivity().kind).toBe("connected");
  });
});

describe("the cluster switcher's rows", () => {
  const entry = (cluster: string, status: string, name?: string) => ({
    key: { cluster },
    state: { status },
    ...(name === undefined ? {} : { name }),
  });

  /* It shipped as a featureless dot. The row shows the operator's name for the cluster, and the
   * chevron on the trigger is the chrome's own — see `ClusterSelector`. */
  it("shows the display name the gateway reported, not the identifier", () => {
    const rows = clusterSummaries(
      new Map([["cluster/prod", entry("prod", "available", "Production EU")]]),
    );
    expect(rows).toEqual([{ id: "prod", name: "Production EU", health: "healthy" }]);
  });

  it("falls back to the identifier rather than drawing a blank row", () => {
    const rows = clusterSummaries(new Map([["cluster/prod", entry("prod", "available")]]));
    expect(rows[0]?.name).toBe("prod");
  });

  /* A cluster whose topic service is fine and whose cluster service is unreachable is not a healthy
   * cluster: a dot reporting the best of its services would be reassuring and wrong. */
  it("folds a cluster's services to the worst of them", () => {
    const rows = clusterSummaries(
      new Map([
        ["cluster/prod", entry("prod", "available", "Production")],
        ["topic/prod", entry("prod", "unavailable")],
      ]),
    );
    expect(rows[0]?.health).toBe("unreachable");
  });

  /**
   * The order the environment rail's tiles are in, and the tie-break under it.
   *
   * Rows are sorted by name; the `|| a.id.localeCompare(b.id)` after it could be deleted with all
   * 498 cases `pnpm -C frontend test packages/shell` runs still green, measured here. Two clusters
   * carrying the same *name* is a real state — the name is whatever the operator put in their
   * configuration and nothing makes it unique, and a cluster nobody named falls back to its id —
   * and `Array.prototype.sort` is stable, so without the tie-break the two keep the order the
   * **capability map** happened to hold them in. That map is rebuilt from every frame the gateway
   * streams, which on a struggling cluster is every few seconds: the rail's tiles would then swap
   * places under the pointer of somebody reaching for one, and switching environment is the one
   * click in this product that changes what a later destructive action will destroy.
   *
   * It is the same rule as the drawer's declared order and the topic tree's alphabetical tie-break,
   * at the third of the three places this frame sorts something.
   */
  it("orders by name and breaks a tie on the identifier, whatever order the frame held them in", () => {
    const rows = (pairs: readonly (readonly [string, string])[]) =>
      clusterSummaries(
        new Map(pairs.map(([id]) => [`cluster/${id}`, entry(id, "available", "Production")])),
      ).map((row) => row.id);

    // One name, two clusters, offered in each order. Both must draw `eu` before `us`.
    expect(rows([["us", "Production"], ["eu", "Production"]])).toEqual(["eu", "us"]);
    expect(rows([["eu", "Production"], ["us", "Production"]])).toEqual(["eu", "us"]);
  });
});

describe("changing environment", () => {
  it("says nothing at all when the chosen cluster is the one already shown", () => {
    /* The rail marks the current environment, so this is a misclick — and a confirmation for a
       misclick is what teaches an operator that the toasts in this product are noise. */
    expect(environmentSwitch("prod", "prod", "Production EU", true)).toBeUndefined();
  });

  it("names the cluster the way the operator named it, and falls back to the identifier", () => {
    expect(environmentSwitch("prod", "staging", "Production EU", false)?.title).toBe(
      "Switched to Production EU",
    );
    /* A blank where a name goes reads as a bug in the toast rather than as a cluster nobody has
       named, which is the same degradation `clusterSummaries` makes for the same reason. */
    expect(environmentSwitch("prod", "staging", undefined, false)?.title).toBe("Switched to prod");
  });

  it("rewrites the address only when the address disagrees with the new selection", () => {
    /* On `/ui/clusters/staging/topics`, leaving the address alone puts a URL saying `staging` in
       front of a frame describing `prod` — and the address is the half of that pair people copy. */
    expect(environmentSwitch("prod", "staging", undefined, true)?.rewriteAddress).toBe(true);
    /* On `/ui/settings` there is nothing to contradict, and moving somebody off a page they
       deliberately opened is the rudeness `soleClusterChoice` is careful to avoid. */
    expect(environmentSwitch("prod", "staging", undefined, false)?.rewriteAddress).toBe(false);
  });
});

describe("which navigation entry is current", () => {
  it("follows the address, under any mount prefix", () => {
    expect(currentFeatureId("/ui/", "/ui")).toBe("overview");
    expect(currentFeatureId("/ui/settings", "/ui")).toBe("settings");
    expect(currentFeatureId("/ui/clusters", "/ui")).toBe("clusters");
    expect(currentFeatureId("/ui/clusters/manage", "/ui")).toBe("clusters");
    expect(currentFeatureId("/ui/clusters/prod/brokers", "/ui")).toBe("clusters");
    expect(currentFeatureId("/kui/ui/clusters/prod/topics/orders", "/kui/ui")).toBe("topics");
    expect(currentFeatureId("/ui/clusters/prod/consumer-groups", "/ui")).toBe("consumers");
    expect(currentFeatureId("/ui/clusters/prod/alerts", "/ui")).toBe("alerts");
  });

  /**
   * Alerts, and the address that is not it.
   *
   * The screen is a route of its own — `/clusters/<id>/alerts` — and there is no dashboard tab
   * called alerts. `/dashboard/alerts` therefore has to read as the **dashboard**, because that is
   * what the route table matches it to; reading it as the alerts screen would highlight a drawer
   * row for a page the reader is not on, which is the defect the dashboard and the registry both
   * had until wave 5. The two spellings are one segment apart and the fall-through is a
   * `segments.includes`, so this is the pair worth writing down.
   */
  it("marks the alerts screen as itself and the dashboard's tabs as the dashboard", () => {
    expect(currentFeatureId("/ui/clusters/prod/alerts", "/ui")).toBe("alerts");
    expect(currentFeatureId("/kui/ui/clusters/prod/alerts", "/kui/ui")).toBe("alerts");
    expect(currentFeatureId("/ui/clusters/prod/dashboard/alerts", "/ui")).toBe("overview");
  });

  /**
   * The tenth service's screen, which arrives with the same trap the ninth's did.
   *
   * Every test under the dashboard line is a `segments.includes`, so a section with no line of its
   * own does not fail loudly: it falls through to the `clusters` fall-through below and comes back
   * `"overview"`. The drawer would then highlight the dashboard while `@kui/feature-connect` drew
   * the connector list, and the trail would read `prod-kyiv-01` alone — the trail for a different
   * page. That is the exact defect the case above this one exists to record, and it is written here
   * rather than discovered because the fall-through is silent.
   */
  it("marks the Connect screen as itself and not as the dashboard it falls through to", () => {
    expect(currentFeatureId("/ui/clusters/prod/connect", "/ui")).toBe("connect");
    expect(currentFeatureId("/kui/ui/clusters/prod/connect", "/kui/ui")).toBe("connect");
    // And a dashboard tab spelled the same way is still the dashboard, as it is for alerts.
    expect(currentFeatureId("/ui/clusters/prod/dashboard/connect", "/ui")).toBe("overview");
  });

  /** The eleventh service's screen, with the same silent fall-through the ninth and tenth had. */
  it("marks the ksqlDB screen as itself and not as the dashboard it falls through to", () => {
    expect(currentFeatureId("/ui/clusters/prod/ksql", "/ui")).toBe("ksql");
    expect(currentFeatureId("/kui/ui/clusters/prod/ksql", "/kui/ui")).toBe("ksql");
    expect(currentFeatureId("/ui/clusters/prod/dashboard/ksql", "/ui")).toBe("overview");
  });

  /**
   * The address the product opens on, which was marking the wrong entry.
   *
   * `/clusters/<id>/dashboard/overview` fell through to the `clusters` fall-through, so the drawer
   * highlighted **Brokers** and the top band's trail read "Brokers" over a page headed "Cluster
   * overview" — three signals about where you are, two of them wrong, on the first screen anybody
   * sees. The registry's screen had the same defect one row down.
   */
  it("marks the dashboard and the registry as themselves rather than as Brokers", () => {
    expect(currentFeatureId("/ui/clusters/prod/dashboard/overview", "/ui")).toBe("overview");
    expect(currentFeatureId("/ui/clusters/prod/dashboard", "/ui")).toBe("overview");
    // The shortest thing anybody types, which resolves to the same page.
    expect(currentFeatureId("/ui/clusters/prod", "/ui")).toBe("overview");
    expect(currentFeatureId("/ui/clusters/prod/schemas", "/ui")).toBe("schemas");
    const deep = currentFeatureId("/kui/ui/clusters/prod/schemas/orders-value", "/kui/ui");
    expect(deep).toBe("schemas");
  });
});

/**
 * The trail in the top band, which is the *installation* trail: which deployment, which cluster,
 * which section.
 *
 * The section crumb comes from the same reading as the drawer's highlight, so the two cannot
 * disagree — and "overview" deliberately adds no crumb, because the cluster crumb already links
 * there and a trail that repeats itself is a trail nobody reads.
 */
describe("the top band's trail", () => {
  const clusters = [{ id: "prod", name: "prod-kyiv-01", health: "healthy" as const }];
  const router = createShellRouter("", {
    home: () => null,
    settings: () => null,
    forbidden: () => null,
    notFound: () => null,
    feature: () => () => null,
  });

  it("names the cluster and the section, and says nothing twice", () => {
    const at = "/ui/clusters/prod/dashboard/overview";
    const dashboard = topCrumbs(clusters, "prod", at, "/ui", router);
    expect(dashboard.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01"]);

    const schemas = topCrumbs(clusters, "prod", "/ui/clusters/prod/schemas", "/ui", router);
    expect(schemas.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01", "Schema Registry"]);

    const brokers = topCrumbs(clusters, "prod", "/ui/clusters/prod/brokers", "/ui", router);
    expect(brokers.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01", "Brokers"]);

    /* The ninth service's screen. Its label comes from the same table as the others, so a section
       reachable from the drawer with no row in that table would drop its crumb silently — the trail
       would read `prod-kyiv-01` alone, which is the trail for the dashboard, over a different
       page. */
    const alerts = topCrumbs(clusters, "prod", "/ui/clusters/prod/alerts", "/ui", router);
    expect(alerts.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01", "Alerts"]);

    /* And the tenth's. The failure this asserts against is silent in both directions: a section
       missing from the label table drops its crumb, and a section missing from `currentFeatureId`
       draws the *dashboard's* trail over somebody else's page. */
    const connect = topCrumbs(clusters, "prod", "/ui/clusters/prod/connect", "/ui", router);
    expect(connect.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01", "Connect"]);

    /* And the eleventh's, where the *spelling* is the assertion as well as the presence. The id is
       `ksql` because an id is a path segment; the product's word is `ksqlDB` (§4.15), with one
       capital in the middle. A crumb built from the id rather than from the table would read
       `Ksql`, which is a word this product does not use anywhere a person can see. */
    const ksql = topCrumbs(clusters, "prod", "/ui/clusters/prod/ksql", "/ui", router);
    expect(ksql.map((crumb) => crumb.label)).toEqual(["prod-kyiv-01", "ksqlDB"]);
  });
});

describe("what the shell renders for an unreported capability", () => {
  /* Between the gateway starting and its first readiness poll it has no information, so reporting an
   * outage would be a claim it cannot support. Every operator restarting the gateway would otherwise
   * watch the whole navigation go red for one polling interval. */
  it("is degraded-with-STARTING and never unavailable", () => {
    const state = deriveFeatureState(undefined, true);
    expect(state.kind).toBe("degraded");
    const [signal] = createSignal(state);
    expect(signal().kind).not.toBe("unavailable");
  });
});
