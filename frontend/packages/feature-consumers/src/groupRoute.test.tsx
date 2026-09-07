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
  /** The `topic` query each `DELETE …/offsets` carried, in order. */
  readonly forgotten: string[];
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
  readonly plan?: unknown;
  readonly applyFails?: string;
  readonly deleteFails?: string;
  /** What `DELETE …/offsets` answers with. Absent means it removed every partition it was asked. */
  readonly forgetAnswer?: { readonly topic: string; readonly partitions: readonly number[] };
  readonly forgetFails?: string;
}): Stub {
  const calls: string[] = [];
  const forgotten: string[] = [];
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
      return { ok: true, value: options.plan ?? plan() };
    }
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets") {
      return options.applyFails === undefined
        ? { ok: true, value: plan() }
        : refuse(options.applyFails);
    }
    return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
  };
  const remove = async (
    path: string,
    init?: { readonly params?: { readonly query?: { readonly topic?: string } } },
  ) => {
    calls.push(`DELETE ${path}`);
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}") {
      return options.deleteFails === undefined
        ? { ok: true, value: {} }
        : refuse(options.deleteFails);
    }
    if (path === "/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets") {
      /* The topic is a *query* parameter, not a path segment, because the resource is "this
         group's offsets" narrowed by topic. Recorded rather than ignored: a request that reached
         the endpoint without it would forget nothing and answer 400, and a stub that dropped it
         would let this suite pass over that. */
      const topic = init?.params?.query?.topic ?? "";
      forgotten.push(topic);
      if (options.forgetFails !== undefined) return refuse(options.forgetFails);
      return {
        ok: true,
        value: options.forgetAnswer ?? { topic, partitions: [0, 1, 2] },
      };
    }
    return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
  };

  return {
    calls,
    forgotten,
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

  it("shows a partition the group never committed on as a dash, never as offset zero", async () => {
    /*
     * `write.ts`'s most argued-for line, asserted where the product applies it rather than where a
     * test arranges it. `toPlannedPartition` is not exported, so the only honest seam is the
     * server's own payload going in and the plan table coming out — and the payload here is the
     * one shape the recorded plan cannot be: a partition whose `current` field is **absent**,
     * which is how the server says this group has never committed a position there.
     *
     * `current: payload.current ?? 0` satisfies every type on that path and draws a `0` in the
     * From column, which tells an operator the group has consumed the first record when it has
     * consumed nothing — and then the reset they are about to approve reads as a rewind of one
     * record instead of a first commit.
     */
    const { api } = stub({
      plan: {
        topic: "analytics.pageviews",
        token: "plan-token-1",
        expiresAt: "2099-01-01T00:00:00Z",
        noOp: false,
        partitions: [
          { partition: 0, current: 40, proposed: 0, delta: -40 },
          // No `current` and no `delta`: nothing has ever been committed here.
          { partition: 1, proposed: 0 },
        ],
        warnings: [],
      },
    });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Reset offsets$/);
    await settle();
    press(container, /^Preview the plan$/);
    await settle();

    const rows = [...container.querySelectorAll('[data-testid="group-reset-plan"] tbody tr')];
    expect(rows).toHaveLength(2);
    const cells = (row: Element | undefined): readonly string[] =>
      [...(row?.querySelectorAll("td") ?? [])].map((cell) => (cell.textContent ?? "").trim());

    // The partition that has a position keeps its number, so this is not a table of dashes.
    expect(cells(rows[0])[1]).toBe("40");
    // And the one that has none draws the em dash the column's own comment promises.
    expect(cells(rows[1])[1]).toBe("—");
    expect(cells(rows[1])[1]).not.toBe("0");

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

  it("forgets one topic's offsets and reports how many partitions the server removed", async () => {
    /*
     * `CG-005`'s exit, in one case: the control is on the page per topic, and the receipt is
     * asserted at the route.
     *
     * Three things are checked and none of them can be met by the others. The request carries the
     * topic as a **query** parameter, which is where the endpoint puts it — a request without it
     * forgets nothing and comes back 400. The toast quotes the *server's* partition count and not
     * the figure the page was drawing, because the two are the same only until somebody else has
     * reset the group between the page load and the click. And the group is still here afterwards:
     * this removes committed positions, not the group, and a screen that navigated away would be
     * telling the operator it had done the other thing.
     */
    const { api, calls, forgotten } = stub({
      forgetAnswer: { topic: "analytics.pageviews", partitions: [0, 1, 2, 3, 4] },
    });
    const { container, dispose, url } = openGroup(api);
    await settle();

    press(container, /^Forget offsets$/);
    await settle();

    /* The consequence, before the click, in this group's own figures. The recorded group holds
       twelve partitions of `analytics.pageviews`, and the number is what makes this a consequence
       rather than an adjective — the dialog's own contract, and the sentence beside it is the one
       that decides whether an operator should press the button at all. */
    const asking = confirmation().textContent ?? "";
    expect(asking).toContain("12 partitions of analytics.pageviews");
    expect(asking).toContain("No records are deleted.");
    expect(asking).toContain("auto.offset.reset");

    press(confirmation(), /^Forget offsets$/);
    await settle();

    expect(forgotten).toEqual(["analytics.pageviews"]);

    expect(titles()).toContain("Committed offsets forgotten");
    const raised = toasts().find((toast) => toast.title === "Committed offsets forgotten");
    expect(raised?.tone).toBe("success");
    // The server's five, not the twelve the recorded group holds — so the sentence cannot be
    // composed from anything the browser already knew.
    expect(raised?.message).toContain("5 partitions");
    expect(raised?.message).toContain("analytics.pageviews");
    // The sentence that decides whether this was safe, and the one an operator forgets.
    expect(raised?.message).toContain("No records were deleted.");

    expect(url()).toBe(`${BASE}/clusters/${CLUSTER}/consumer-groups/${GROUP}`);

    /* And the page behind the dialog was re-read. The assignments table is drawn from offsets that
       have just changed, so without this it keeps showing committed positions Kafka no longer
       holds — figures that were true a second ago, which is the most convincing kind of wrong. */
    const reads = calls.filter(
      (call) => call === "GET /api/v1/clusters/{clusterId}/consumer-groups/{groupId}",
    );
    expect(reads).toHaveLength(2);

    dispose();
  });

  it("says one partition rather than 1 partitions when the server removed exactly one", async () => {
    // The singular, which the plural template gets wrong on every single-partition topic — most of
    // what a scratch cluster holds. Its twin on the way in (`1 partition held`) has a case in
    // `consumers.test.tsx`; this is the receipt's.
    const { api } = stub({ forgetAnswer: { topic: "analytics.pageviews", partitions: [3] } });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Forget offsets$/);
    await settle();
    press(confirmation(), /^Forget offsets$/);
    await settle();

    const raised = toasts().find((toast) => toast.title === "Committed offsets forgotten");
    expect(raised?.message).toContain("1 partition of analytics.pageviews");
    expect(raised?.message).not.toContain("1 partitions");

    dispose();
  });

  it("does not show the last refusal over a confirmation that has just been reopened", async () => {
    /*
     * A refusal belongs to the attempt that earned it. The mutation's state outlives the dialog —
     * the dialog is a `Show`, the mutation is not — so without a reset on the way in, reopening
     * this confirmation puts "cluster is read-only" over a fresh question about a topic the
     * operator has just chosen, which reads as a refusal of the thing they have not asked yet.
     */
    const { api } = stub({ forgetFails: "cluster 'quickstart' is read-only" });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Forget offsets$/);
    await settle();
    press(confirmation(), /^Forget offsets$/);
    await settle();
    expect(confirmation().textContent).toContain("read-only");

    // Closed, and asked again.
    press(confirmation(), /^Cancel$/);
    await settle();
    press(container, /^Forget offsets$/);
    await settle();

    expect(confirmation().textContent).not.toContain("read-only");

    dispose();
  });

  it("says nothing was forgotten, in a warning, when the group held no offsets there", async () => {
    /*
     * The shape this whole product keeps meeting: a 200 whose entire meaning is in a figure.
     *
     * `DELETE …/offsets` answers 200 with an **empty** partition list when the group held no
     * committed position on that topic — the endpoint's own description says the body exists so
     * that "the group had none" and "they were deleted" stay distinguishable, which a bare status
     * code cannot do. A green tick over the empty case sends an operator away believing a position
     * they can still see elsewhere was removed.
     */
    const { api } = stub({ forgetAnswer: { topic: "analytics.pageviews", partitions: [] } });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Forget offsets$/);
    await settle();
    press(confirmation(), /^Forget offsets$/);
    await settle();

    expect(titles()).toContain("Nothing was forgotten");
    const raised = toasts().find((toast) => toast.title === "Nothing was forgotten");
    // The tone is the assertion. The wording is what a reader skims; the colour is what they see
    // from across the room.
    expect(raised?.tone).toBe("warning");
    expect(raised?.message).toContain("held no committed offset");
    expect(titles()).not.toContain("Committed offsets forgotten");
    // And above all not a zero dressed as a result: `0 partitions` reads as an action that ran.
    expect(raised?.message).not.toContain("0 partitions");

    dispose();
  });

  it("raises no toast when the cluster refuses to forget the offsets", async () => {
    const { api } = stub({ forgetFails: "cluster 'quickstart' is read-only" });
    const { container, dispose } = openGroup(api);
    await settle();

    press(container, /^Forget offsets$/);
    await settle();
    press(confirmation(), /^Forget offsets$/);
    await settle();

    expect(titles()).toEqual([]);
    // The confirmation stays open carrying the server's own words, where the operator is looking.
    expect(confirmation().textContent).toContain("read-only");

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
