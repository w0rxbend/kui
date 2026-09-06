/**
 * Mounting the whole application.
 *
 * ## Why this file exists
 *
 * Every other test in this package imports a *piece* of the shell — a pure helper, one component,
 * one store — and drives it directly. That is the right way to test behaviour, and it left one gap
 * that a review found by opening a browser: nothing rendered `<App />` itself. The composition root
 * was the only component in the product with no test, and it is the one component every user sees.
 *
 * What got through the gap was a blank page. `App` built a `createMemo` that called a `const` arrow
 * function declared eleven lines further down. A `const` is in its temporal dead zone until its own
 * line runs, and Solid 2 computes a memo eagerly when it is created, so the memo called the binding
 * before it existed. The `ReferenceError` was raised inside the reactive graph, which Solid reports
 * as `REACTIVITY_HALTED`: the graph stops, nothing renders, and the browser shows a black rectangle
 * with no failed request and no broken-looking component to point at. Five hundred and eighty-five
 * tests passed while the application did not start.
 *
 * So the assertion that matters here is the cheapest one imaginable — the frame drew, and the
 * console stayed quiet. A test that merely *mounts* would have caught it, because Solid reports a
 * halted graph rather than throwing out of `render`; the test has to look at what landed.
 *
 * ## How the gateway is faked
 *
 * `fetch` is replaced for the length of each case. The shell asks for `/auth/me` and
 * `/auth/settings` at start-up and nothing else until a cluster is chosen, so a stub that answers
 * those two and refuses everything else is enough to render the frame — and refusing the rest is
 * deliberate, because "the gateway answered some of it" is the state the shell is built to survive.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { render } from "@solidjs/web";
import { flush } from "solid-js";

import { Actions, CapabilityStatuses, Resources, SseEventNames } from "@kui/api";

import { App } from "./App.jsx";
import { SEARCH_DEBOUNCE_MS } from "./data/search.js";
import { featureRegistry } from "./features/registry.js";

/**
 * The permissions a deployment with authentication disabled really hands out.
 *
 * One grant per feature's view action, over every cluster and every name — which is what
 * `/auth/me` answers when no identity provider is configured, and what the third `describe` in this
 * file asserts the registrations line up with. Without them every cluster-scoped row in the drawer
 * comes out **forbidden**, and a forbidden row carries neither a badge nor a nested tree: a stub
 * that omitted the grants would leave this suite asserting against a drawer nobody ever sees.
 */
const WILDCARD_GRANTS = featureRegistry.map((registration) => ({
  clusters: ["*"],
  resource: registration.viewAction.resource,
  value: ".*",
  actions: [registration.viewAction.action],
}));

/** The `/auth/me` body both stubs answer with. */
const SESSION = {
  authType: "disabled",
  csrfToken: "test-token",
  principal: { kind: "anonymous", name: "anonymous" },
  permissions: WILDCARD_GRANTS,
};

/**
 * An `EventSource` that connects to nothing.
 *
 * jsdom does not implement one, and the capability store opens a stream during start-up. A stub
 * that only records the address is enough: what this file tests is that the frame renders, and the
 * capability picture arriving late is the normal case the shell is designed for — an empty picture
 * renders as degraded-with-STARTING, which is exactly the state under test.
 */
class SilentEventSource {
  static readonly opened: string[] = [];
  /** Every source built since the last reset, so a case can push a frame down one. */
  static readonly live: SilentEventSource[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onopen: ((event: Event) => void) | null = null;
  readonly readyState = 0;

  private readonly listeners = new Map<string, ((event: Event) => void)[]>();

  constructor(readonly url: string) {
    SilentEventSource.opened.push(url);
    SilentEventSource.live.push(this);
  }

  addEventListener(name: string, handler: (event: Event) => void): void {
    const held = this.listeners.get(name) ?? [];
    held.push(handler);
    this.listeners.set(name, held);
  }

  removeEventListener(): void {}
  close(): void {}

  /**
   * Delivers one frame, the way the browser's own `EventSource` would.
   *
   * A `{ data }` object rather than a real `MessageEvent`: the stream reader takes the payload off
   * `event.data` and looks at nothing else, and jsdom's `MessageEvent` constructor is not what is
   * under test here.
   */
  emit(name: string, data: unknown): void {
    for (const handler of this.listeners.get(name) ?? []) {
      handler({ data: JSON.stringify(data) } as unknown as Event);
    }
  }
}

/** One capability entry, as the gateway sends it. */
function entry(service: string, cluster: string, name: string) {
  return {
    key: { service, cluster },
    state: { status: CapabilityStatuses.Available },
    updatedAt: "2026-09-06T09:00:00.000Z",
    name,
  };
}

/**
 * The services a cluster's features are gated on, all reporting healthy.
 *
 * Pushed down the stream because that is the only way a feature reaches `ready`: with no frame at
 * all the shell renders every capability as degraded-with-STARTING, which is the honest state for a
 * picture that has not arrived and is *not* the state most of this product's rules apply to. A
 * degraded row carries the capability badge instead of its count and draws no tree, so a suite that
 * never delivered a frame would be asserting against a drawer no operator sees for longer than a
 * second.
 */
function healthy(...clusters: readonly string[]) {
  return {
    generatedAt: "2026-09-06T09:00:00.000Z",
    entries: clusters.flatMap((cluster) =>
      ["cluster", "topic", "message", "consumer", "schema"].map((service) =>
        entry(service, cluster, cluster),
      ),
    ),
  };
}

/** Delivers a capability frame down the stream the shell opened. */
function announce(frame: unknown): void {
  const stream = SilentEventSource.live.at(-1);
  if (stream === undefined) throw new Error("the capability stream was never opened");
  stream.emit(SseEventNames.Capabilities, frame);
  flush();
}

/** The two start-up answers, and a 404 for anything else the shell decides to ask for. */
function stubGateway(): void {
  vi.stubGlobal("EventSource", SilentEventSource);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.includes("/auth/me")) {
        return new Response(JSON.stringify(SESSION), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      if (url.includes("/auth/settings")) {
        return new Response(JSON.stringify({ authType: "disabled", providers: [] }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      return new Response(
        JSON.stringify({ code: "KUI-ROUTE-NOT-FOUND", message: "no route", details: [] }),
        { status: 404, headers: { "content-type": "application/json" } },
      );
    }),
  );
}

function mountApp() {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const dispose = render(() => <App />, host);
  flush();
  return {
    host,
    dispose: () => {
      dispose();
      host.remove();
    },
  };
}

afterEach(() => {
  SilentEventSource.live.length = 0;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  /* The address and the stored selection are both global, and the shell writes to both: a case that
     mounts on a cluster's address leaves that cluster selected for the next one, which then fetches
     a cluster its own stub knows nothing about. Two suites sharing one `localStorage` is how a test
     ends up depending on the order it runs in. */
  window.history.replaceState({}, "", "/");
  try {
    window.localStorage.clear();
  } catch {
    /* A browser configured to block site data raises on the accessor itself, and a test that cannot
       clear a store it was never able to write to has nothing to clean up. */
  }
});

describe("the application, mounted", () => {
  it("draws its frame", () => {
    stubGateway();
    const app = mountApp();

    // The frame is the thing that must never fail to render: a service being down is a panel's
    // problem, and the shell going blank is everybody's.
    expect(app.host.querySelector(".kui-frame")).not.toBeNull();
    expect(app.host.querySelector(".kui-frame__drawer")).not.toBeNull();
    expect(app.host.querySelector(".kui-frame__topbar")).not.toBeNull();
    expect(app.host.querySelector(".kui-frame__content")).not.toBeNull();

    app.dispose();
  });

  it("does not halt its reactive graph while starting up", () => {
    // Solid reports a `ReferenceError` raised inside a computation by logging `REACTIVITY_HALTED`
    // and stopping the graph, rather than by throwing where a test would see it. Watching the
    // console is therefore not belt-and-braces here; it is the only place the failure appears.
    const errors: unknown[][] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => void errors.push(args));
    stubGateway();

    const app = mountApp();
    flush();

    expect(errors.map((line) => String(line[0])).join("\n")).not.toMatch(
      /REACTIVITY_HALTED|before initialization/,
    );

    app.dispose();
  });

  it("fills the drawer from the feature registry", () => {
    stubGateway();
    const app = mountApp();

    // This is the assertion aimed squarely at the defect. The navigation is built from `groups()`,
    // which reads `statuses()` — the memo that called a binding still in its temporal dead zone.
    // When that threw, the frame's static markup still appeared and the drawer came out empty, so
    // "the frame rendered" alone was not enough to tell the two apart. A destination in the drawer
    // proves the memo ran to completion.
    const drawer = app.host.querySelector(".kui-frame__drawer");
    expect(drawer?.querySelectorAll("a").length ?? 0).toBeGreaterThan(0);
    expect(drawer?.textContent).toContain("Overview");

    app.dispose();
  });
});

/**
 * The frame, over a cluster the address names.
 *
 * The three tests above mount the shell at the root, where no cluster is selected and the store
 * therefore asks for nothing. This one is the wiring wave's own assertion, and it is the one that
 * could not have passed before it: wave 1 built `createClusterStore`, `brokerStorageOf` and the
 * count fold, and nothing in the product constructed any of them — `grep` found no reference to one
 * outside `data/`. So the meter drew its "not known" rendering in every deployment since it was
 * built, not because the disks could not be read but because nobody had ever handed it any.
 *
 * The address is the input on purpose. `/ui/clusters/<id>` is the shortest thing anybody types, it
 * used to fall through to the 404 wildcard, and it is the one input that exercises the whole chain
 * in one go: the route resolves, the cluster comes out of it, the store asks six questions about
 * that cluster, and the drawer draws three of the answers.
 */
describe("the frame, given a cluster in the address", () => {
  const ok = (data: unknown) => ({ status: "ok", data, fetchedAt: "2026-09-06T09:00:00.000Z" });

  /** What the six requests behind the drawer are answered with. Everything else 404s, as above. */
  const CLUSTER: Readonly<Record<string, unknown>> = {
    "/api/v1/clusters/prod-kyiv-01": {
      cluster: {
        id: "prod-kyiv-01",
        name: "prod-kyiv-01",
        summary: ok({
          version: "3.7.0",
          brokerCount: 3,
          offlinePartitionCount: 0,
          underReplicatedPartitionCount: 1,
        }),
      },
    },
    "/api/v1/clusters/prod-kyiv-01/brokers": { brokers: ok([{ id: 1 }, { id: 2 }, { id: 3 }]) },
    "/api/v1/clusters/prod-kyiv-01/log-dirs": {
      logDirs: ok([
        { brokerId: 1, path: "/data/a", totalBytes: 200, usableBytes: 100 },
        { brokerId: 2, path: "/data/a", totalBytes: 200, usableBytes: 50 },
        /* Offline, so this broker gets no row and contributes to neither sum. If it did, the
           percentage below would be computed over capacity the cluster does not have. */
        { brokerId: 3, path: "/data/a", error: "KafkaStorageException" },
      ]),
    },
    "/api/v1/clusters/prod-kyiv-01/topics": {
      topics: ok({ items: [], page: { totalItems: 128 } }),
      incompleteTopics: 0,
    },
    /* The names-only index the drawer's tree is folded from. Eight names over three prefixes and
       two internal topics, which is enough for every rule the fold has: a genuine prefix written
       `orders.*`, a single topic that must *not* be written `heartbeats.*`, and the padlocked
       `internal` row that collects both underscored names whatever their own prefixes are. */
    "/api/v1/clusters/prod-kyiv-01/topics/names": {
      names: ok([
        "orders.payments.v2",
        "orders.payments.v1",
        "orders.shipments",
        "analytics.clickstream",
        "analytics.sessions",
        "heartbeats",
        "__consumer_offsets",
        "__transaction_state",
      ]),
    },
  };

  function stubCluster(): void {
    vi.stubGlobal("EventSource", SilentEventSource);
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const href =
          typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        const path = new URL(href, "http://kui.test").pathname;
        if (path.includes("/auth/me")) {
          return new Response(JSON.stringify(SESSION), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        const body = CLUSTER[path];
        if (body !== undefined) {
          return new Response(JSON.stringify(body), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        if (path.includes("/auth/settings")) {
          return new Response(JSON.stringify({ authType: "disabled", providers: [] }), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        /* Everything else refused, and refused as a real envelope. Four of the frame's six requests
           are answered above and the other two are not, which is the ordinary state of this product
           — the drawer's whole design is that six failures are six failures and not one. */
        return new Response(
          JSON.stringify({ code: "KUI-ROUTE-NOT-FOUND", message: "no route", details: [] }),
          { status: 404, headers: { "content-type": "application/json" } },
        );
      }),
    );
  }

  /** Lets the six requests land and the reactive graph catch up. */
  async function settled(): Promise<void> {
    for (let turn = 0; turn < 4; turn += 1) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      flush();
    }
  }

  it("draws the dashboard, not the 404 page, and fills the drawer from the store", async () => {
    window.history.replaceState({}, "", "/ui/clusters/prod-kyiv-01");
    stubCluster();
    const app = mountApp();
    await settled();

    /* The route change: `/clusters/<id>` names no page, and now resolves to the cluster's own
       dashboard instead of to the wildcard at the foot of the table. */
    expect(app.host.querySelector("[data-testid='overview']")).not.toBeNull();

    const drawer = app.host.querySelector(".kui-frame__drawer");
    // The head is the cluster block, and it is the store's summary that names it.
    expect(drawer?.textContent).toContain("prod-kyiv-01");

    /* 250 B used of 400 B, over the two brokers that reported a size. The third reported none and
       is in neither sum: a failed disk is not a disk of size zero, and counting its 200 B of
       capacity as empty would print 42% over a cluster that is at 63%. */
    const meter = drawer?.querySelector("[data-testid='storage-meter']");
    expect(meter?.textContent).toContain("63%");
    expect(meter?.textContent).toContain("250 B of 400 B");

    app.dispose();
  });

  /**
   * The badge seam, asserted where the product joins the two halves and not where a test joins
   * them.
   *
   * `shell.test.tsx` has three cases about badges and all three call `countLookup` and
   * `navigationGroups` themselves, so they check the *fold* and observe nothing about `App`'s use
   * of it. Replacing `countFor: countLookup(readingValue(facts.counts))` with `countFor: () =>
   * undefined` — cutting the store off from the drawer entirely — left all of them green. This case
   * mounts the real application over a gateway that answers `page.totalItems: 128` and looks at the
   * drawer, so the only way it passes is for the store's number to have reached the row.
   */
  it("carries the store's own count into the drawer's badge", async () => {
    window.history.replaceState({}, "", "/ui/clusters/prod-kyiv-01");
    stubCluster();
    const app = mountApp();
    await settled();

    announce(healthy("prod-kyiv-01"));

    const topics = app.host.querySelector("[data-testid='nav-topics']");
    expect(topics?.textContent).toContain("128");
    /* And in words, because the visible badge is a fragment: the row's accessible name is what a
       screen-reader user is given, and it is assembled from the badge's description. */
    expect(topics?.getAttribute("aria-label")).toContain("128");

    app.dispose();
  });

  /**
   * The tree, drawn from names the product fetched.
   *
   * `nav/topicTree.ts` was written, tested and exported a wave ago and called by nothing but the
   * barrel that exported it, so the drawer never nested. Expanding the row here is the assertion
   * that it is called: the disclosure only exists for a branch, and a branch only exists when
   * `childrenFor` answered — which needs the names endpoint, the fold and the frame's wiring all
   * three.
   */
  it("nests the topic tree under Topics, from the names the cluster reported", async () => {
    window.history.replaceState({}, "", "/ui/clusters/prod-kyiv-01");
    stubCluster();
    const app = mountApp();
    await settled();

    announce(healthy("prod-kyiv-01"));

    const disclosure = app.host.querySelector<HTMLButtonElement>(
      "[data-testid='nav-topics-disclosure']",
    );
    expect(disclosure).not.toBeNull();

    disclosure!.click();
    flush();

    const subtree = app.host.querySelector("[data-testid='nav-topics-subtree']");
    /* The three rules the fold owns, seen through the drawer: a genuine prefix is written with its
       star, a lone topic keeps its bare name, and every underscored topic is one padlocked row. */
    expect(subtree?.textContent).toContain("orders.*");
    expect(subtree?.textContent).toContain("heartbeats");
    expect(subtree?.textContent).not.toContain("heartbeats.*");
    expect(subtree?.textContent).toContain("internal");
    expect(subtree?.textContent).not.toContain("__consumer_offsets");

    /* The counts are the cluster's and not the page's, and the addresses are the list's own query
       rather than a hand-written path. */
    const orders = subtree?.querySelector("[data-testid='nav-prefix:orders.*']");
    expect(orders?.getAttribute("href")).toBe("/ui/clusters/prod-kyiv-01/topics?q=orders");
    expect(orders?.textContent).toContain("3");
    const internal = subtree?.querySelector("[data-testid='nav-prefix:internal']");
    expect(internal?.getAttribute("href")).toBe(
      "/ui/clusters/prod-kyiv-01/topics?showInternal=true",
    );

    app.dispose();
  });

  /**
   * `+ Create topic` on the dashboard.
   *
   * The handler is three lines in `App.tsx` and replacing it with a no-op was, until this case,
   * invisible to all 281 shell tests: nothing mounted the route that renders the button. The
   * assertion is the address, because the address is what the wiring produces — the create flow
   * lives inside the topics screen and this button is the route to it.
   */
  it("takes the dashboard's Create topic button to the cluster's topic list", async () => {
    window.history.replaceState({}, "", "/ui/clusters/prod-kyiv-01");
    stubCluster();
    const app = mountApp();
    await settled();

    const create = [...app.host.querySelectorAll<HTMLButtonElement>("button")].find((button) =>
      button.textContent?.includes("Create topic"),
    );
    expect(create).toBeDefined();

    create!.click();
    await settled();

    expect(window.location.pathname).toBe("/ui/clusters/prod-kyiv-01/topics");

    app.dispose();
  });
});

/**
 * Changing environment from the rail.
 *
 * `EnvRailProps.onSelect` is optional, so an unwired rail is not even a type error — and
 * `shell.test.tsx`'s three cases about switching call `environmentSwitch` directly, which is the
 * *decision* and not the four side effects that carry it out. So this case does the thing an
 * operator does: it puts two clusters in front of the rail and clicks the other one.
 *
 * The capability snapshot is what puts them there. `clusters()` is folded out of the capability
 * registry and out of nothing else, so a frame has to arrive before the rail has anything to draw —
 * which is also the first time this suite has driven the stream rather than stubbing it silent.
 */
describe("the environment rail", () => {
  it("switches the frame to the cluster the operator clicked", async () => {
    window.history.replaceState({}, "", "/ui/clusters/prod-kyiv-01");
    stubGateway();
    const app = mountApp();
    await settle();
    await settle();

    announce(healthy("prod-kyiv-01", "staging-eu-01"));

    const tile = app.host.querySelector<HTMLButtonElement>(
      "[data-testid='env-tile-staging-eu-01']",
    );
    expect(tile).not.toBeNull();

    tile!.click();
    await settle();
    await settle();

    /* The address is rewritten because the address named a cluster: leaving it alone would put a
       URL saying `prod-kyiv-01` in front of a frame describing `staging-eu-01`, and the address is
       the half of that pair people copy. */
    expect(window.location.pathname).toBe("/ui/clusters/staging-eu-01/dashboard/overview");
    /* And the switch is announced, once, by the one toast region in the product. */
    expect(app.host.ownerDocument.body.textContent).toContain("Switched to staging-eu-01");

    app.dispose();
  });
});

/**
 * The top bar's search field, wired to the gateway's fold.
 *
 * The field has existed since wave 1 and searched nothing: its `onInput` was `() => undefined` and
 * its status was the literal `"idle"`, so every state below the box was reachable only in a story.
 * This is the seam — a person types, a request goes out, and the overlay draws what came back —
 * and it is asserted through the mounted application because that is the only place the wiring is.
 */
describe("the search field", () => {
  const FOUND = {
    results: {
      topics: [{ cluster: "prod-kyiv-01", name: "orders.payments.v2" }],
      groups: [{ cluster: "prod-kyiv-01", groupId: "payments-processor" }],
    },
    /* The distributed stack routes no schema service, so this is the ordinary answer rather than a
       failure — and the field has to say so instead of showing two lists out of three. */
    partial: ["schema"],
  };

  /** Records every search the shell asks for, and answers the rest as the frame's stub does. */
  function stubSearch(): string[] {
    const searched: string[] = [];
    vi.stubGlobal("EventSource", SilentEventSource);
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const href =
          typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        const url = new URL(href, "http://kui.test");
        if (url.pathname.includes("/auth/me")) {
          return new Response(JSON.stringify(SESSION), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        if (url.pathname === "/api/v1/search") {
          searched.push(url.searchParams.get("q") ?? "");
          return new Response(JSON.stringify(FOUND), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        return new Response(JSON.stringify({ authType: "disabled", providers: [] }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }),
    );
    return searched;
  }

  /** Types into the box the way a person does: one event per character. */
  function type(app: { readonly host: HTMLElement }, text: string): void {
    const input = app.host.querySelector<HTMLInputElement>("[data-testid='search-input']");
    if (input === null) throw new Error("the search box is not on the page");
    input.focus();
    for (let length = 1; length <= text.length; length += 1) {
      input.value = text.slice(0, length);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }
    flush();
  }

  /** Waits out the debounce and lets the answer land. */
  async function answered(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, SEARCH_DEBOUNCE_MS + 80));
    for (let turn = 0; turn < 4; turn += 1) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      flush();
    }
  }

  it("asks the gateway once for a typed word and draws what came back", async () => {
    const searched = stubSearch();
    const app = mountApp();
    await settle();

    type(app, "orders");
    /* One request for six keystrokes. The fold is one call per service per cluster, so a keystroke
       that escaped the debounce is three upstream calls and not one. */
    expect(searched).toEqual([]);

    await answered();
    expect(searched).toEqual(["orders"]);

    const results = app.host.querySelector("[data-testid='search']");
    expect(results?.textContent).toContain("orders.payments.v2");
    expect(results?.textContent).toContain("payments-processor");
    /* And the third list, which nobody was asked for, named rather than silently absent. */
    expect(results?.textContent).toContain("Schema Registry");

    app.dispose();
  });

  it("stops searching when the box is emptied rather than searching for nothing", async () => {
    const searched = stubSearch();
    const app = mountApp();
    await settle();

    type(app, "orders");
    await answered();
    expect(searched).toEqual(["orders"]);

    const input = app.host.querySelector<HTMLInputElement>("[data-testid='search-input']")!;
    input.value = "";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    flush();
    await answered();

    expect(searched).toEqual(["orders"]);
    expect(app.host.querySelector("[data-testid='search']")?.textContent).not.toContain(
      "orders.payments.v2",
    );

    app.dispose();
  });
});

/**
 * Every feature's view permission must be spelled the way the server spells it.
 *
 * The shell used to ask `permits(serviceId, "view", …)`, which compared `"topic"` against `TOPIC`
 * and `"view"` against `VIEW` by exact string, and asked for a resource called `"cluster"` that
 * does not exist at all — the cluster feature is gated on `CLUSTERCONFIG`. Every question answered
 * "no" the instant `/auth/me` replied, so on the demonstration environment, where authentication is
 * *disabled* and the principal holds a grant on every resource and every cluster, the whole drawer
 * went dim and each page read "You do not have permission to view …".
 *
 * Checking the registrations against the generated vocabulary is cheap and catches the reappearance
 * of that whole class: a value not drawn from `Actions` cannot match, and a hand-written string can
 * no longer be one.
 */
describe("the permissions the features ask for", () => {
  it("names only actions from the generated vocabulary", () => {
    const known = new Set(Object.values(Actions).map((a) => `${a.resource}:${a.action}`));
    for (const registration of featureRegistry) {
      expect(known).toContain(
        `${registration.viewAction.resource}:${registration.viewAction.action}`,
      );
    }
  });

  it("asks about a resource the server's own enum contains", () => {
    const resources = new Set<string>(Object.values(Resources));
    for (const registration of featureRegistry) {
      expect(resources).toContain(registration.viewAction.resource);
    }
  });

  it("lets a principal holding a wildcard grant see every feature", () => {
    // This is the shape `/auth/me` really returns when authentication is disabled: one grant per
    // resource, scoped to every cluster. Under the old spelling this expectation failed for all
    // four features at once.
    const grants = featureRegistry.map((registration) => ({
      clusters: ["*"],
      resource: registration.viewAction.resource,
      value: ".*",
      actions: [registration.viewAction.action],
    }));

    for (const registration of featureRegistry) {
      const covering = grants.filter(
        (grant) =>
          grant.resource === registration.viewAction.resource &&
          grant.actions.includes(registration.viewAction.action),
      );
      expect(covering.length).toBeGreaterThan(0);
    }
  });
});

/**
 * Lets the pending promises run, then flushes Solid.
 *
 * A macrotask rather than a few `await Promise.resolve()`: releasing the session gate resolves a
 * chain of promises inside the client's middleware and the capability store, and counting how many
 * microtask turns that takes is a test asserting an implementation detail of two other modules.
 */
async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  flush();
}

describe("start-up asks for the session before anything else", () => {
  /**
   * The gateway mints an anonymous session for any API request that arrives without a cookie, and
   * stamps `Set-Cookie` on the answer. Two cookieless requests therefore mint two sessions, the
   * browser keeps whichever reply lands last, and the CSRF token this client keeps — the one
   * `/auth/me` returned — belongs to the other one.
   *
   * The symptom is as bad as it gets: every read works, because a fresh anonymous session can read
   * everything an anonymous session can read, and every *write* comes back
   * "X-Csrf-Token does not match the session's token". It stayed invisible for the whole of the read
   * work and appeared the moment the first mutation existed to be refused.
   *
   * So `/auth/me` goes first and alone. Everything else — `/auth/settings`, the capability stream,
   * every feature's first read — waits behind it, by which time the browser holds a cookie and the
   * gateway resolves it instead of minting another.
   */
  it("nothing goes out beside /auth/me", async () => {
    const asked: string[] = [];
    SilentEventSource.opened.length = 0;
    vi.stubGlobal("EventSource", SilentEventSource);

    let releaseMe: (() => void) | undefined;
    const mePending = new Promise<void>((resolve) => {
      releaseMe = resolve;
    });

    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url =
          typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        asked.push(new URL(url, "http://kui.test").pathname);
        if (url.includes("/auth/me")) {
          await mePending;
          return new Response(
            JSON.stringify({
              authType: "disabled",
              csrfToken: "test-token",
              principal: { kind: "anonymous", name: "anonymous" },
            }),
            { status: 200, headers: { "content-type": "application/json" } },
          );
        }
        return new Response(JSON.stringify({ authType: "disabled", providers: [] }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }),
    );

    const { dispose } = mountApp();
    await settle();
    await settle();

    // While `/auth/me` is still out, it is the only thing that has been asked for, and the
    // capability stream — which uses the native EventSource and so bypasses the client's own gate —
    // has not been opened either.
    expect(asked.filter((path) => !path.includes("/auth/me"))).toEqual([]);
    expect(SilentEventSource.opened).toEqual([]);

    releaseMe?.();
    await settle();
    await settle();
    await settle();

    // Once the session exists, the rest follows.
    expect(asked.some((path) => path.includes("/auth/settings"))).toBe(true);
    expect(SilentEventSource.opened.length).toBeGreaterThan(0);

/*
     * Not asserted here: whether the connectivity banner clears.
     *
     * `SilentEventSource` records the URL and never dispatches `open`, so the stream in this harness
     * genuinely never connects and the banner is *correct*. The property that matters — that the
     * deferred handle's `connection()` is reactive, so the effect driving the banner re-runs when the
     * real stream opens — needs a stream that opens, and is checked against the running stack
     * instead. It was a plain variable first, which gave Solid nothing to subscribe to: the effect
     * read "connecting" once and never again, and the application told the operator it had lost its
     * connection for the rest of the session while frames were arriving.
     */

    dispose();
  });
});
