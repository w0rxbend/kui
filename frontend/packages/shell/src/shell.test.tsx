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
import { clusterSummaries, currentFeatureId } from "./App.jsx";

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
});

describe("which navigation entry is current", () => {
  it("follows the address, under any mount prefix", () => {
    expect(currentFeatureId("/ui/", "/ui")).toBe("overview");
    expect(currentFeatureId("/ui/settings", "/ui")).toBe("settings");
    expect(currentFeatureId("/ui/clusters", "/ui")).toBe("clusters");
    expect(currentFeatureId("/ui/clusters/prod/brokers", "/ui")).toBe("clusters");
    expect(currentFeatureId("/kui/ui/clusters/prod/topics/orders", "/kui/ui")).toBe("topics");
    expect(currentFeatureId("/ui/clusters/prod/consumer-groups", "/ui")).toBe("consumers");
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
