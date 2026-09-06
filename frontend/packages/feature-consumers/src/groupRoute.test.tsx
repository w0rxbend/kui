/**
 * The group page's two destructive successes, and the toast each of them owes the operator.
 *
 * ## Why this file exists rather than another case in `consumers.test.tsx`
 *
 * "A toast on every destructive success" is one of M6's bullets, and until this file both of
 * `GroupRoute`'s `notify(…)` calls could be deleted with all 68 of this package's tests still
 * green. Nothing mounted the route: the list screen has a suite, the wizard has one, the detail
 * page has one, and the file that joins them to a client — the only place a mutation's *result* is
 * observed — had none. So the rule was written down twice, in two comments arguing for it, and
 * asserted nowhere.
 *
 * The seam is therefore the route with a router around it and a recording underneath, and the
 * assertion is on `toasts()`, the kernel's own store, which is what `ToastRegion` draws from in the
 * shell. Asserting against markup this package renders would prove nothing: this package renders no
 * toast, and a case that mounted `ToastRegion` beside the route would be asserting that the kernel
 * can draw a toast it was handed.
 *
 * ## Why the detail answer is the recorded document, edited
 *
 * `recorded/group.json` came off a running gateway, so the shape the mapping reads is the shape a
 * coordinator sends. The two cases below need states the quickstart cannot be put into — a group
 * with no members, because a group with members cannot be deleted and the button says so before the
 * click — so the recording is copied and the one field under test is changed. Everything else,
 * including every field name, stays the server's.
 */

import { beforeEach, describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { createRouter, memoryHistory } from "@solidjs/router";
import { KuiProvider, clearToasts, toasts } from "@kui/kernel";
import type { KuiApiClient } from "@kui/api";

import { mount, testContext } from "./testing.js";
import { GroupRoute } from "./GroupRoute.jsx";
import groupDocument from "./recorded/group.json" with { type: "json" };

const CLUSTER = "quickstart";
const GROUP = "analytics-indexer";
/** Where the product is mounted. The route composes addresses against it, as it does in a browser. */
const BASE = "/ui";

/** Solid batches writes onto a microtask; a router navigation takes a couple of turns to settle. */
async function settle(times = 8): Promise<void> {
  for (let index = 0; index < times; index += 1) await flush();
}

/** The recorded group, with no members: the state in which the group may be deleted at all. */
function memberlessGroup(): unknown {
  const copy = JSON.parse(JSON.stringify(groupDocument)) as Record<string, unknown>;
  copy["members"] = [];
  return copy;
}

/** What `POST …/offsets/plan` and `POST …/offsets` both answer with: a plan, and its token. */
function plan(noOp = false): unknown {
  return {
    topic: "analytics.pageviews",
    token: "plan-token-1",
    expiresAt: "2099-01-01T00:00:00Z",
    noOp,
    partitions: [{ partition: 0, current: 40, proposed: 0, delta: -40 }],
    warnings: [],
  };
}

interface Stub {
  readonly api: KuiApiClient;
  readonly calls: string[];
}

/**
 * A client that answers this group's four endpoints from a table and records what it was asked.
 *
 * Cast at one boundary, like every stub client in this workspace: `KuiApiClient`'s methods are typed
 * from the OpenAPI document, and a fake satisfying all of that would be a second copy of the schema.
 * The paths it matches are the literal strings the product passes, so a renamed endpoint fails here.
 */
function stub(options: {
  readonly detail?: unknown;
  readonly applyFails?: string;
  readonly deleteFails?: string;
}): Stub {
  const calls: string[] = [];
  const refuse = (message: string) => ({
    ok: false,
    error: {
      kind: "envelope",
      code: "KUI-CONFLICT",
      message,
      details: [],
      correlationId: "test",
      retryable: false,
    },
  });

  const get = async (path: string) => {
    calls.push(`GET ${path}`);
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}") {
      return { ok: true, value: options.detail ?? memberlessGroup() };
    }
    return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
  };
  const post = async (path: string) => {
    calls.push(`POST ${path}`);
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets/plan") {
      return { ok: true, value: plan() };
    }
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets") {
      return options.applyFails === undefined
        ? { ok: true, value: plan() }
        : refuse(options.applyFails);
    }
    return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
  };
  const remove = async (path: string) => {
    calls.push(`DELETE ${path}`);
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}") {
      return options.deleteFails === undefined
        ? { ok: true, value: {} }
        : refuse(options.deleteFails);
    }
    return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
  };

  return {
    calls,
    api: {
      get,
      post,
      put: post,
      patch: post,
      delete: remove,
      raw: {},
    } as unknown as KuiApiClient,
  };
}

/**
 * Mounts the route at this group's own address, inside a real router.
 *
 * `memoryHistory` rather than the browser's, so nothing writes into the runner's own address bar —
 * but it is the same adapter contract, so the delete's navigation is observable here exactly as it
 * is in a browser. That is the point of `url()`: the route used to leave with
 * `window.location.assign`, which is a *document* navigation, and a document navigation destroys
 * the module-level toast store the line above it had just written to.
 */
function openGroup(api: KuiApiClient): {
  readonly container: HTMLElement;
  readonly dispose: () => void;
  readonly url: () => string;
} {
  const history = memoryHistory(`${BASE}/clusters/${CLUSTER}/consumer-groups/${GROUP}`);
  const Router = createRouter({
    routes: [
      { path: "/clusters/:clusterId/consumer-groups/:groupId", component: GroupRoute },
      /* The list's address has to be a route this router knows, or the navigation the delete makes
         resolves to nothing and the case would pass on a screen showing a 404. */
      { path: "/clusters/:clusterId/consumer-groups", component: () => <p>The list</p> },
    ],
    base: BASE,
    history,
    scrollRestoration: false,
  });
  const mounted = mount(() => (
    <KuiProvider value={testContext(api)}>
      <Router />
    </KuiProvider>
  ));
  return { ...mounted, url: () => history.get() };
}

/** A button inside one root, by the words on it. */
function press(root: HTMLElement, label: RegExp): void {
  const button = [...root.querySelectorAll("button")].find((candidate) =>
    label.test((candidate.textContent ?? "").trim()),
  );
  if (button === undefined) {
    const seen = [...root.querySelectorAll("button")]
      .map((candidate) => (candidate.textContent ?? "").trim())
      .join(" | ");
    throw new Error(`no button matching ${String(label)}; this root offers: ${seen}`);
  }
  button.click();
}

/**
 * The confirmation on screen.
 *
 * `Dialog` renders through a `Portal` into `document.body`, so it is not inside the container the
 * route was mounted in — and Solid tears a portal down on its own schedule rather than synchronously
 * in `dispose()`, so a query across the whole body can find the *previous* case's dialog. The last
 * match is unambiguously the one this case opened. Scoping matters twice over here: the page's own
 * `Delete group` action and the confirmation's `Delete group` button read identically, and a helper
 * that took the first match would press the action again and confirm nothing.
 */
function confirmation(): HTMLElement {
  const all = document.body.querySelectorAll<HTMLElement>("[role='dialog']");
  const last = all[all.length - 1];
  if (last === undefined) throw new Error("no confirmation is open");
  return last;
}

const titles = (): readonly string[] => toasts().map((toast) => toast.title);

describe("the group page's toasts", () => {
  beforeEach(() => {
    // Module-level state, shared by the whole process: one case's confirmation would otherwise be
    // the next case's evidence.
    clearToasts();
  });

  it("raises a toast when a reset is applied, naming the group the offsets belong to", async () => {
    const { api } = stub({});
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Reset offsets$/);
    await settle();
    press(container, /^Preview the plan$/);
    await settle();
    press(container, /^Apply this plan$/);
    await settle();

    /*
     * The wizard's own receipt is on screen and says what the broker wrote — but the wizard closes,
     * and the page behind it then looks exactly as it did before with different numbers. The toast
     * is what survives that, which is why the rule is "on the destructive success", not "somewhere
     * on the screen".
     */
    expect(titles()).toContain("Offsets reset");
    const raised = toasts().find((toast) => toast.title === "Offsets reset");
    expect(raised?.message).toContain(GROUP);
    // Not `danger`: this succeeded. A `danger` toast never auto-dismisses, and one that stays on
    // screen after a success reads as an error nobody explained.
    expect(raised?.tone).toBe("success");

    dispose();
  });

  it("raises no toast when the cluster refuses the reset", async () => {
    /*
     * The other half of the rule, and the half that makes the first one mean something: a toast is
     * the confirmation of a thing that *happened*. A route that raised one from the click rather
     * than from the answer would pass the case above and be wrong here.
     */
    const { api } = stub({ applyFails: "the plan token has expired" });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Reset offsets$/);
    await settle();
    press(container, /^Preview the plan$/);
    await settle();
    press(container, /^Apply this plan$/);
    await settle();

    expect(titles()).toEqual([]);
    // And the refusal is on the screen, where the operator is looking.
    expect(container.textContent).toContain("the plan token has expired");

    dispose();
  });

  it("raises a toast when the group is deleted, and leaves through the router so it survives", async () => {
    /*
     * `GroupRoute` navigates away on success. The list it arrives at has no trace of what happened —
     * the group is simply not there, which is indistinguishable from having mistyped the address —
     * so the toast is the only account of it.
     *
     * Which is why the address is asserted beside the toast. The route left with
     * `window.location.assign`, a document navigation: the toast was raised into a module-level
     * store and the store was thrown away with the document a moment later. A case that only
     * checked `toasts()` would have gone green over a confirmation nobody could ever see.
     */
    const { api } = stub({});
    const { container, dispose, url } = openGroup(api);
    await settle();

    press(container, /^Delete group$/);
    await settle();
    press(confirmation(), /^Delete group$/);
    await settle();

    expect(titles()).toContain("Consumer group deleted");
    const raised = toasts().find((toast) => toast.title === "Consumer group deleted");
    expect(raised?.message).toContain(GROUP);
    // The sentence an operator needs beside a red button: the offsets went, the records did not.
    expect(raised?.message).toContain("No records were deleted.");
    // The list, through the router — and with the mount point exactly once. A `KuiPaths` address
    // already carries the base, so a navigation that resolved it again would land on `/ui/ui/…`.
    expect(url()).toBe(`${BASE}/clusters/${CLUSTER}/consumer-groups`);

    dispose();
  });

  it("raises no toast, and goes nowhere, when the cluster refuses the delete", async () => {
    const { api } = stub({ deleteFails: "the group still has members" });
    const { container, dispose, url } = openGroup(api);
    await settle();

    press(container, /^Delete group$/);
    await settle();
    press(confirmation(), /^Delete group$/);
    await settle();

    expect(titles()).toEqual([]);
    // The confirmation stays open carrying the server's own words, which name what to do next.
    expect(confirmation().textContent).toContain("the group still has members");
    expect(url()).toBe(`${BASE}/clusters/${CLUSTER}/consumer-groups/${GROUP}`);

    dispose();
  });
});
