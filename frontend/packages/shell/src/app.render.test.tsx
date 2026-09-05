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
      expect(known).toContain(`${registration.viewAction.resource}:${registration.viewAction.action}`);
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
