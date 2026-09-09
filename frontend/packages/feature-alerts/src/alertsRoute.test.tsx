/**
 * The alerts route: the wiring, which is the part a component test cannot see.
 *
 * ## Why the route is mounted rather than the card
 *
 * `AlertsFeed` refuses correctly when it is handed no `onAcknowledge` — `alerts.test.tsx` pins that
 * — and refusing correctly is worth nothing if nothing decides when to hand it one. Wave 5 shipped a
 * destructive consumer-group control in exactly that state: the component was tested, the story was
 * drawn, and `GroupRoute.tsx`'s `mayReset() ?` could be replaced with `true ?` with all 1449 cases
 * in the workspace green, because no case mounted the route without the permission. So the first
 * case below mounts this route with a principal who holds every permission except
 * `ALERTS:ACKNOWLEDGE`, and asserts through the rendered control and through the requests the client
 * received.
 *
 * ## The router is real
 *
 * The default export reads `clusterId` from the address, so the cases go through a router with a
 * memory history rather than calling `AlertsScreen` directly. The path is the one the shell will
 * mount this feature at; a route that only works when it is handed its parameters by a test is a
 * route nobody has checked.
 */
import { describe, expect, it } from "vitest";
import { flush, onCleanup } from "solid-js";
import { createRouter, memoryHistory } from "@solidjs/router";
import {
  AlertsProvider,
  KuiProvider,
  createAlerts,
  type SseHandle,
} from "@kui/kernel";
import { Actions, ErrorCodes, type KuiApiClient } from "@kui/api";

import { mount, findViolations, describeViolations, testContext } from "./testing.js";
import Alerts, { ACKNOWLEDGEMENT_PATH, EVENTS_PATH } from "./index.jsx";
import openDocument from "./documents/events-open.json" with { type: "json" };
import notConfiguredDocument from "./documents/events-not-configured.json" with { type: "json" };

const BASE = "/ui";

/** Solid batches writes onto a microtask; a router navigation takes a couple of turns to settle. */
async function settle(times = 8): Promise<void> {
  for (let index = 0; index < times; index += 1) await flush();
}

interface Call {
  readonly method: "get" | "post";
  readonly path: string;
  readonly params: Record<string, string>;
  /** Recorded so that a query parameter nobody meant to send is visible to a case. */
  readonly query: Record<string, unknown> | undefined;
}

interface Stub {
  readonly api: KuiApiClient;
  readonly calls: Call[];
  /** Replaces what the next `GET …/events` answers with. */
  answerWith: (document: unknown) => void;
}

/**
 * A client that answers the two alerts paths and records what it was asked.
 *
 * Cast at one boundary, like every stub client in this workspace: `KuiApiClient`'s methods are typed
 * from the OpenAPI document, and a fake satisfying all of that would be a second copy of the schema.
 * The paths it matches are the literal constants the product passes, so a renamed endpoint fails
 * here rather than drawing an empty feed.
 */
function stub(
  options: {
    readonly acknowledgement?: unknown;
    readonly hold?: boolean;
    readonly holdRead?: boolean;
  } = {},
): Stub {
  const calls: Call[] = [];
  let document: unknown = openDocument;

  const answer =
    (method: "get" | "post") =>
    async (
      path: string,
      init: { params: { path: Record<string, string>; query?: Record<string, unknown> } },
    ) => {
      calls.push({ method, path, params: init.params.path, query: init.params.query });
      if (method === "get") {
        // A read that never answers, for the one case about what the screen says before anything
        // has been established.
        if (options.holdRead === true) return new Promise<never>(() => {});
        return { ok: true, value: document };
      }
      // A request that never answers, for the one case about what the screen does *while* a write
      // is out. `never` rather than a slow timer: a case that waits on a clock is a case that is
      // slow when it passes and flaky when the machine is busy.
      if (options.hold === true) return new Promise<never>(() => {});
      const refusal = options.acknowledgement;
      return refusal === undefined ? { ok: true, value: {} } : refusal;
    };

  return {
    api: { get: answer("get"), post: answer("post") } as unknown as KuiApiClient,
    calls,
    answerWith: (next) => {
      document = next;
    },
  };
}

function open(
  cluster: string,
  api: KuiApiClient,
  permits: (action: { readonly resource: string; readonly action: string }) => boolean = () => true,
): { readonly container: HTMLElement; readonly dispose: () => void } {
  const history = memoryHistory(`${BASE}/clusters/${cluster}/alerts`);
  const Router = createRouter({
    routes: [{ path: "/clusters/:clusterId/alerts", component: Alerts }],
    base: BASE,
    history,
    scrollRestoration: false,
  });
  const Host = () => {
    const alerts = createAlerts({
      load: async (markRead) => {
        const get = api.get as unknown as (
          path: string,
          init: { params: { path: Record<string, string>; query: Record<string, unknown> } },
        ) => ReturnType<KuiApiClient["get"]>;
        return get(EVENTS_PATH, {
          params: { path: { clusterId: cluster }, query: { markRead } },
        });
      },
      openStream: () =>
        ({
          connection: () => ({ phase: "closed", reason: "test stream" }),
          close: () => {},
          endMarker: () => undefined,
        }) as SseHandle,
      cluster: () => cluster,
    });
    alerts.start();
    onCleanup(() => alerts.stop());
    return (
      <AlertsProvider value={alerts}>
        <KuiProvider value={testContext(api, permits)}>
          <Router />
        </KuiProvider>
      </AlertsProvider>
    );
  };
  return mount(Host);
}

function rows(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="alert-row"]')];
}

/** The acknowledge control on one row, whether it is enabled or disabled with its reason. */
function acknowledgeButton(container: HTMLElement, event: string): HTMLButtonElement | undefined {
  const row = rows(container).find((candidate) => candidate.dataset["event"] === event);
  return [...(row?.querySelectorAll("button") ?? [])].find((button) =>
    (button.textContent ?? "").includes("Acknowledge"),
  );
}

describe("the alerts route", () => {
  it("reads this cluster's events, at the path the service publishes", async () => {
    const client = stub();
    const { container, dispose } = open("quickstart", client.api);
    await settle();

    expect(client.calls).toHaveLength(1);
    /*
     * The address as a literal, not as `EVENTS_PATH`.
     *
     * The stub answers whatever path the product passes, so a case comparing the request against
     * the same constant the product built it from asserts that a constant equals itself — the
     * self-referential shape an adversary looks for first. `AlertsEndpoints.events` composes
     * `clusters / clusterId / alerts / events` under the gateway's public `/api/v1` prefix, and that
     * is what is written out here.
     */
    expect(client.calls[0]).toEqual({
      method: "get",
      path: "/api/v1/clusters/{clusterId}/alerts/events",
      params: { clusterId: "quickstart" },
      /*
       * And no `markRead`. The endpoint defaults it to false and its description says why: *"so a
       * card polling the feed does not clear somebody's bell"*. This screen polls, so a `true` here
       * would empty the bell of anybody who left a tab open on it — and marking read is the bell's
       * own control, in the shell's chrome.
       */
      query: { markRead: false },
    });
    expect(EVENTS_PATH).toBe("/api/v1/clusters/{clusterId}/alerts/events");
    expect(rows(container)).toHaveLength(5);
    dispose();
  });

  it("writes no voice line until something has answered", async () => {
    const client = stub({ holdRead: true });
    const { container, dispose } = open("silent-cluster", client.api);
    await settle();

    /*
     * A sentence about how much is open, written over a document nobody has read yet, is the
     * product asserting something it does not know — and "Nothing is open" is the one it would
     * write, because an unread feed and an empty one are the same object until the read lands.
     */
    expect(container.querySelector(".kui-page-head__voice")).toBeNull();
    expect(container.textContent).not.toContain("Nothing is open");
    expect(container.textContent).not.toContain("open alerts");
    dispose();
  });

  it("says the count the service answered, in the voice line and in the pill", async () => {
    const client = stub();
    const { container, dispose } = open("voice-cluster", client.api);
    await settle();

    // Three open of five rows: the two figures come from different fields and neither is derived
    // from the other. The design's second sentence — "One is the usual suspect" — is a joke about
    // which one, and the browser does not know that, so it is not written.
    expect(container.textContent).toContain("3 open alerts.");
    expect(container.textContent).not.toContain("usual suspect");
    expect(
      container.querySelector('[data-testid="alerts-open-count"]')?.textContent,
    ).toContain("3 open");
    dispose();
  });

  it("a principal without ALERTS:ACKNOWLEDGE is never handed an enabled acknowledge control", async () => {
    const client = stub();
    const { container, dispose } = open(
      "unpermitted-cluster",
      client.api,
      (action) =>
        !(
          action.resource === Actions.AlertsAcknowledge.resource &&
          action.action === Actions.AlertsAcknowledge.action
        ),
    );
    await settle();

    const button = acknowledgeButton(container, "evt-1");
    expect(button).toBeDefined();
    // Disabled and *readable*: `aria-disabled` rather than the attribute, so the reason stays
    // reachable by keyboard and by hover. A hidden control would tell the operator the product
    // cannot do this at all.
    expect(button?.getAttribute("aria-disabled")).toBe("true");
    /*
     * And the reason is reachable, which is the half of the rule that makes the other half humane:
     * `Tooltip` renders its bubble on focus and hover, so the sentence is asserted by focusing the
     * control the way a keyboard user reaches it, not by reading a title attribute nobody hears.
     */
    expect(button?.getAttribute("aria-describedby")).not.toBeNull();
    button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
    await settle();
    expect(document.body.textContent).toContain(
      "You do not have permission to acknowledge alerts on this cluster.",
    );

    button?.click();
    await settle();
    // The gate is the wiring, not the button's own opinion: no request left the browser.
    expect(client.calls.filter((call) => call.method === "post")).toHaveLength(0);
    dispose();
  });

  it("acknowledging an event the API refuses leaves the row unacknowledged and says why", async () => {
    const client = stub({
      acknowledgement: {
        ok: false,
        error: {
          kind: "envelope",
          // The event closed between the feed being drawn and the button being pressed.
          code: ErrorCodes.InvalidState,
          message: "This event is already closed, so it cannot be acknowledged.",
          details: [],
          correlationId: "c-1",
          retryable: false,
        },
      },
    });
    const { container, dispose } = open("refused-cluster", client.api);
    await settle();

    acknowledgeButton(container, "evt-1")?.click();
    await settle();

    expect(container.textContent).toContain("This event is already closed");
    // The code, because it is what an operator quotes to whoever they escalate to.
    expect(container.textContent).toContain(ErrorCodes.InvalidState);
    // The row is exactly as it was: still open, still offering the control, and carrying nobody's
    // name. A refusal that quietly marked the row acknowledged would be the screen lying about a
    // request the server declined.
    const row = rows(container).find((candidate) => candidate.dataset["event"] === "evt-1");
    expect(row?.textContent).not.toContain("Acknowledged");
    expect(acknowledgeButton(container, "evt-1")).toBeDefined();
    // And the feed was not re-read: refetching would replace the reason with the same row and read
    // as though nothing had been pressed.
    expect(client.calls.filter((call) => call.method === "get")).toHaveLength(1);
    dispose();
  });

  it("acknowledges through the event's own sub-resource and re-reads the feed", async () => {
    const client = stub();
    const { container, dispose } = open("accepting-cluster", client.api);
    await settle();

    acknowledgeButton(container, "evt-1")?.click();
    await settle();

    const posts = client.calls.filter((call) => call.method === "post");
    expect(posts).toHaveLength(1);
    // The literal again, for the reason the read's case gives: `AlertsEndpoints.acknowledge` posts
    // to the event's own `acknowledgement` sub-resource, and no request body goes with it.
    expect(posts[0]).toEqual({
      method: "post",
      path: "/api/v1/clusters/{clusterId}/alerts/events/{eventId}/acknowledgement",
      params: { clusterId: "accepting-cluster", eventId: "evt-1" },
      query: undefined,
    });
    expect(ACKNOWLEDGEMENT_PATH).toBe(
      "/api/v1/clusters/{clusterId}/alerts/events/{eventId}/acknowledgement",
    );
    // The row's new state comes from re-reading the feed rather than from a body this build would
    // have to guess the shape of.
    expect(client.calls.filter((call) => call.method === "get").length).toBeGreaterThan(1);
    dispose();
  });

  it("sends one acknowledgement however many times the control is pressed", async () => {
    const client = stub();
    const { container, dispose } = open("double-press-cluster", client.api);
    await settle();

    const button = acknowledgeButton(container, "evt-1");
    button?.click();
    button?.click();
    await settle();

    // Two presses in one turn is the shape a real double click has, and `createMutation`'s guard is
    // a plain variable for exactly that reason: a signal read would still say `idle`.
    expect(client.calls.filter((call) => call.method === "post")).toHaveLength(1);
    dispose();
  });

  it("marks the row whose acknowledgement is in flight, and only that row", async () => {
    const client = stub({ hold: true });
    const { container, dispose } = open("in-flight-cluster", client.api);
    await settle();

    acknowledgeButton(container, "evt-1")?.click();
    await settle();

    /*
     * The busy mark is on the row that was pressed and on no other. It is what stops a second press
     * — `Button` swallows a click while `busy` — and it is the only thing on screen that says the
     * request is out; without it the operator presses again, which is the duplicate-mutation shape
     * `createMutation`'s own guard exists for one layer down.
     */
    expect(acknowledgeButton(container, "evt-1")?.getAttribute("aria-busy")).toBe("true");
    expect(acknowledgeButton(container, "evt-3")?.getAttribute("aria-busy")).toBeNull();
    dispose();
  });

  it("carries a not-configured feed to the screen as hidden, and not as a failure", async () => {
    const client = stub();
    client.answerWith(notConfiguredDocument);
    const { container, dispose } = open("no-alerts-cluster", client.api);
    await settle();

    /*
     * The seam between the section's status and the screen's six-case state, which the component's
     * own case cannot see: `fromSection` is what keeps `not_configured` from arriving as `failed`,
     * and a screen that collapsed the two would put a red panel and a Retry button in front of
     * every operator whose deployment simply runs no alerts service (ADR-032).
     */
    expect(container.querySelector('[data-testid="alerts-feed"]')).toBeNull();
    expect(container.textContent).not.toContain("Retry");
    expect(container.textContent).not.toContain("did not answer");
    // The heading stays: a bookmark has to land somewhere, and the row that vanishes is the
    // navigation's, which is the shell's to draw.
    expect(container.querySelector("h1")?.textContent).toBe("Alerts");
    dispose();
  });

  it("draws only the rows the reader's filter names, and keeps the service's count", async () => {
    const client = stub();
    const { container, dispose } = open("filter-cluster", client.api);
    await settle();

    const critical = [...container.querySelectorAll("button")].find(
      (button) => (button.textContent ?? "").trim() === "Critical",
    );
    critical?.click();
    await settle();

    // Two of the five rows opened `critical`; one of those two is resolved, which the severity
    // filter has no opinion about — that is the other chip bar's question.
    expect(rows(container)).toHaveLength(2);
    expect(
      container.querySelector('[data-testid="alerts-open-count"]')?.textContent,
    ).toContain("3 open");
    expect(container.textContent).toContain("2 of the 5 events on this page match.");
    dispose();
  });

  it("has no accessibility violations with a feed, its filters and its controls", async () => {
    const client = stub();
    const { container, dispose } = open("a11y-cluster", client.api);
    await settle();

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});
