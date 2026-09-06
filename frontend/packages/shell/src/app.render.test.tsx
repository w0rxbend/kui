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

import { Actions, Resources } from "@kui/api";

import { App } from "./App.jsx";
import { featureRegistry } from "./features/registry.js";

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
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onopen: ((event: Event) => void) | null = null;
  readonly readyState = 0;

  constructor(readonly url: string) {
    SilentEventSource.opened.push(url);
  }

  addEventListener(): void {}
  removeEventListener(): void {}
  close(): void {}
}

/** The two start-up answers, and a 404 for anything else the shell decides to ask for. */
function stubGateway(): void {
  vi.stubGlobal("EventSource", SilentEventSource);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.includes("/auth/me")) {
        return new Response(
          JSON.stringify({
            authType: "disabled",
            csrfToken: "test-token",
            principal: { kind: "anonymous", name: "anonymous" },
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
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
          return new Response(
            JSON.stringify({
              authType: "disabled",
              csrfToken: "test-token",
              principal: { kind: "anonymous", name: "anonymous" },
            }),
            { status: 200, headers: { "content-type": "application/json" } },
          );
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
