/**
 * The Connect screen, mounted — which is where the packet's owned rule lives.
 *
 * ## The owned rule, and why it is asserted here rather than in a helper
 *
 * **A connector's controls are gated on `kui.permits(Actions.ConnectOperate, operateSubject(c))` —
 * the question with the Connect cluster's name in it — and not on the subjectless
 * `kui.permits(Actions.ConnectOperate)`.** `ConnectRoute.tsx` is where the product asks it, so that
 * is what these cases mount. Nothing here composes the rule by hand: the harness holds grants in
 * the wire's own shape and evaluates them through `@kui/kernel`'s `grantsAllow`, the same function
 * `state/session.ts` calls in the product.
 *
 * That last part is the whole reason this file can fail at all. Wave 6 shipped two consumer-group
 * controls asking the weaker question with 273 green cases, because that package's `permits` helper
 * compared `{resource, action}` and threw the name away — so the two questions gave identical
 * answers in every case that existed, and the mutation was invisible. Drop the second argument in
 * `ConnectRoute.tsx` and *"a principal granted OPERATE on one Connect cluster may not operate a
 * connector on another"* goes red, because `grantsAllowAny` answers `true` for a principal holding
 * the action on anything of that kind.
 *
 * ## The documents are the service's own
 *
 * `src/documents/connectors-*.json` are copies of
 * `services/connect/contract/test/resources/golden/`, and `wire.golden.test.ts` reads the originals
 * off disk so the copies cannot drift. Two of them are hand-made — an answered-and-empty listing
 * and a payload this build cannot read — because the goldens do not cover those states.
 *
 * ## The two states nobody draws
 *
 * A worker that answered and named no connectors, and a connector the worker named and would not
 * describe. Both have cases here and stories in `connect.stories.tsx`, and neither may render a
 * zero — which is asserted rather than reviewed, because "0/0 tasks" is exactly what a screen draws
 * when somebody defaults a missing list to an empty one.
 */
import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { KuiProvider, createQueryRegistry, type PermissionGrant } from "@kui/kernel";

import { ConnectScreen } from "./ConnectRoute.jsx";
import { NO_CONNECTORS } from "./model.js";
import {
  describeViolations,
  findViolations,
  grant,
  mount,
  serving,
  testContext,
  TEST_CLUSTER,
} from "./testing.js";
import responseDocument from "./documents/connectors-response.json" with { type: "json" };
import partialDocument from "./documents/connectors-partial.json" with { type: "json" };
import emptyDocument from "./documents/connectors-empty.json" with { type: "json" };
import unknownShapeDocument from "./documents/connectors-unknown-shape.json" with { type: "json" };
import notConfiguredDocument from "./documents/connectors-not-configured.json" with {
  type: "json",
};

/** Solid batches writes onto a microtask; a query settles a couple of turns after it is read. */
async function settle(times = 8): Promise<void> {
  for (let index = 0; index < times; index += 1) await flush();
}

interface Open {
  readonly container: HTMLElement;
  readonly dispose: () => void;
  readonly stub: ReturnType<typeof serving>;
}

function open(
  document: unknown,
  options: {
    readonly grants?: readonly PermissionGrant[];
    readonly write?: () => Promise<unknown>;
  } = {},
): Open {
  const stub = serving(document, options.write);
  const queries = createQueryRegistry();
  const mounted = mount(() => (
    <KuiProvider value={testContext(stub.api, options.grants)}>
      <ConnectScreen clusterId={TEST_CLUSTER} queries={queries} />
    </KuiProvider>
  ));
  return { ...mounted, stub };
}

function panels(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="connector"]')];
}

function panel(container: HTMLElement, subject: string): HTMLElement | undefined {
  return panels(container).find((one) => one.dataset["connector"] === subject);
}

function control(host: HTMLElement | undefined, label: string): HTMLButtonElement | undefined {
  return [...(host?.querySelectorAll("button") ?? [])].find((button) =>
    (button.textContent ?? "").includes(label),
  );
}

/** Whether a control can actually be used. `Button` marks refusal with `aria-disabled`. */
function usable(button: HTMLButtonElement | undefined): boolean {
  return button !== undefined && button.getAttribute("aria-disabled") !== "true";
}

/* ------------------------------------------------------------------------------------------------
 * The owned rule
 * ---------------------------------------------------------------------------------------------- */

/** Two Connect clusters that both answered, so a grant on one has something to miss. */
const TWO_CLUSTERS = {
  connectors: {
    status: "ok",
    data: {
      workers: [
        {
          connect: "payments",
          connectors: {
            status: "ok",
            data: {
              items: [
                {
                  connect: "payments",
                  name: "orders-source",
                  kind: "source",
                  state: "RUNNING",
                  workerId: "10.0.0.1:8083",
                  reason: null,
                  failed: false,
                  runningTasks: 1,
                  taskCount: 1,
                  tasks: [{ id: 0, state: "RUNNING", workerId: "10.0.0.1:8083" }],
                },
              ],
              unreadable: [],
            },
            fetchedAt: "2026-09-03T10:11:12.000Z",
          },
        },
        {
          connect: "analytics",
          connectors: {
            status: "ok",
            data: {
              items: [
                {
                  connect: "analytics",
                  name: "es-sink",
                  kind: "sink",
                  state: "RUNNING",
                  workerId: "10.0.0.3:8083",
                  reason: null,
                  failed: false,
                  runningTasks: 1,
                  taskCount: 1,
                  tasks: [{ id: 0, state: "RUNNING", workerId: "10.0.0.3:8083" }],
                },
              ],
              unreadable: [],
            },
            fetchedAt: "2026-09-03T10:11:12.000Z",
          },
        },
      ],
    },
    fetchedAt: "2026-09-03T10:11:12.000Z",
  },
};

describe("who may operate which connector", () => {
  it("OPERATE on one Connect cluster does not carry to a connector on another", async () => {
    /*
     * The packet's owned rule, asserted through the screen the product renders.
     *
     * The grant names the Connect cluster `payments` and nothing else, which is the grant the
     * server's own requirement reads. Asked *with* the subject the two rows answer differently;
     * asked without it, `grantsAllowAny` sees a CONNECT grant carrying OPERATE on this cluster and
     * answers `true` for both — so the second half of this case is what goes red when the subject
     * is dropped from `ConnectRoute.tsx`.
     */
    const { container, dispose } = open(TWO_CLUSTERS, {
      grants: [grant("CONNECT", ["OPERATE"], "payments")],
    });
    await settle();

    expect(usable(control(panel(container, "payments/orders-source"), "Pause"))).toBe(true);
    expect(usable(control(panel(container, "payments/orders-source"), "Restart"))).toBe(true);

    expect(usable(control(panel(container, "analytics/es-sink"), "Pause"))).toBe(false);
    expect(usable(control(panel(container, "analytics/es-sink"), "Restart"))).toBe(false);

    dispose();
  });

  it("says which Connect cluster it is refusing, in words and not only in a tooltip", async () => {
    const { container, dispose } = open(TWO_CLUSTERS, {
      grants: [grant("CONNECT", ["OPERATE"], "payments")],
    });
    await settle();

    const refusal = panel(container, "analytics/es-sink")?.querySelector(
      '[data-testid="connector-refusal"]',
    );
    // The grant to ask for is on the Connect cluster, so that is the name in the sentence.
    expect(refusal?.textContent).toContain("'analytics'");
    // And the permitted row carries none, so the sentence is a decision and not decoration.
    expect(
      panel(container, "payments/orders-source")
        ?.querySelector('[data-testid="connector-refusal"]'),
    ).toBeNull();

    dispose();
  });

  it("honours a grant whose pattern covers one Connect cluster and not a longer name", async () => {
    /*
     * `grantsAllow` full-matches the pattern, so `payments` does not cover `payments-dlq`. A gate
     * that used a prefix or a `startsWith` would enable a control the server refuses — and a grant
     * spelled the other way round would disable one it allows.
     */
    const { container, dispose } = open(TWO_CLUSTERS, {
      grants: [grant("CONNECT", ["OPERATE"], "payment")],
    });
    await settle();

    expect(usable(control(panel(container, "payments/orders-source"), "Pause"))).toBe(false);

    dispose();
  });

  it("hands an unpermitted principal no working control anywhere on the page", async () => {
    const { container, dispose } = open(TWO_CLUSTERS, { grants: [] });
    await settle();

    const buttons = [...container.querySelectorAll("button")];
    expect(buttons.length).toBeGreaterThan(0);
    expect(buttons.filter((button) => usable(button))).toEqual([]);

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * The states nobody draws
 * ---------------------------------------------------------------------------------------------- */

describe("what the screen says when it has nothing to show", () => {
  it("a worker that answered and named no connectors says so about the worker", async () => {
    const { container, dispose } = open(emptyDocument);
    await settle();

    const empty = container.querySelector('[data-testid="connect-empty"]');
    expect(empty?.textContent).toContain("answered and named no connectors");
    // Never a zero. The count is not the point — what the worker said is.
    expect(container.querySelector('[data-testid="connect-list"]')?.textContent).not.toContain(
      "0 connectors",
    );

    dispose();
  });

  it("a connector the worker would not describe is named, and claims nothing else", async () => {
    const { container, dispose } = open(partialDocument);
    await settle();

    const row = container.querySelector('[data-testid="connector-not-described"]');
    expect(row?.getAttribute("data-connector")).toBe("payments/elastic-sink");
    expect(row?.textContent).toContain("would not describe it");
    expect(row?.textContent).toContain("It has not been deleted");
    /* The strings a defaulted-to-empty list would produce. Either of them over a connector that may
       be running twenty tasks is a confident false statement about a pipeline. */
    expect(row?.textContent).not.toContain("0/0");
    expect(row?.textContent).not.toContain("no tasks");
    // And it is not drawn as running: there is no state to draw.
    expect(row?.textContent).toContain("state not reported");

    dispose();
  });

  it("a cluster with no Connect block is told so, and is offered no retry", async () => {
    const { container, dispose } = open(notConfiguredDocument);
    await settle();

    const notice = container.querySelector('[data-testid="connect-not-configured"]');
    expect(notice?.textContent).toContain("kui.clusters");
    // Nothing is broken, so there is nothing to try again. ADR-032's whole rule.
    expect(container.querySelector('[data-testid="connect-failed"]')).toBeNull();
    const labels = [...container.querySelectorAll("button")].map((b) => b.textContent);
    expect(labels).not.toContain("Retry");

    dispose();
  });

  it("a document this build cannot read is unread, and never an empty list", async () => {
    const { container, dispose } = open(unknownShapeDocument);
    await settle();

    expect(container.querySelector('[data-testid="connect-failed"]')).not.toBeNull();
    expect(container.textContent).not.toContain(NO_CONNECTORS);

    dispose();
  });

  it("does not say 'the workers named nothing' when a worker said nothing at all", async () => {
    /*
     * A listing whose only worker is rebalancing has no connectors on it and no *answer* either.
     * "The Connect workers answered and named no connectors" would be a claim about a worker that
     * answered nothing at all — the reassuring reading of a cluster nobody has finished reading.
     */
    const silent = {
      connectors: {
        status: "ok",
        data: {
          workers: [
            {
              connect: "analytics",
              connectors: {
                status: "unavailable",
                reason: "STARTING",
                message: "the Kafka Connect cluster 'analytics' is rebalancing",
              },
            },
          ],
        },
      },
    };
    const { container, dispose } = open(silent);
    await settle();

    expect(container.querySelector('[data-testid="connect-empty"]')).toBeNull();
    expect(container.querySelector('[data-testid="connect-worker-rebalancing"]')).not.toBeNull();

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * The reason, the rebalance, and the three commands
 * ---------------------------------------------------------------------------------------------- */

describe("what a failed connector says", () => {
  it("carries the worker's own line and never a word KUI chose", async () => {
    const { container, dispose } = open(responseDocument);
    await settle();

    const line = panel(container, "payments/elastic-sink")?.querySelector(
      '[data-testid="connector-reason-line"]',
    );
    expect(line?.textContent).toBe(
      "org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200",
    );

    // And no reason block on the connectors that are not failing.
    expect(
      panel(container, "payments/orders-source")?.querySelector('[data-testid="connector-reason"]'),
    ).toBeNull();

    dispose();
  });

  it("draws the reason on a connector whose state is RUNNING but whose task failed", async () => {
    /* `elastic-sink` is `state: "RUNNING"`, `failed: true`. A panel keyed on the state word would
       hide the reason on exactly the connector somebody has to go and fix. */
    const { container, dispose } = open(responseDocument);
    await settle();

    const card = panel(container, "payments/elastic-sink");
    expect(card?.textContent).toContain("running");
    expect(card?.querySelector('[data-testid="connector-reason"]')).not.toBeNull();

    dispose();
  });

  it("never draws a throughput figure, because the Connect API measures none", async () => {
    const { container, dispose } = open(responseDocument);
    await settle();

    // §3.14's *Absent* paragraph: a literal `0 msg/s` on a paused connector is a measured zero, and
    // an unmeasured one must never look like it. There is no rate on this wire at all.
    expect(container.textContent).toContain("throughput not measured");
    expect(container.textContent).not.toContain("msg/s");

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * Two sentences the panel composes, filed by W7-A3
 * ---------------------------------------------------------------------------------------------- */

/** The golden listing with one connector edited, so the panel is the thing under test. */
function withConnector(edit: (one: Record<string, unknown>) => void): unknown {
  const copy = structuredClone(responseDocument) as {
    connectors: { data: { workers: { connectors: { data?: { items: Record<string, unknown>[] } } }[] } };
  };
  const items = copy.connectors.data.workers[0]?.connectors.data?.items ?? [];
  const target = items.find((one) => one["name"] === "orders-source");
  if (target === undefined) throw new Error("the golden no longer holds orders-source");
  edit(target);
  return copy;
}

describe("what the panel says about the tasks it was told about", () => {
  it("says how many of the counted tasks the worker actually described", async () => {
    /* Filed by W7-A3. The bar has one segment per task **object**, so a connector claiming three
       tasks and describing one draws a one-segment bar that is indistinguishable from a one-task
       connector. The sentence is the only thing that says otherwise, and dropping the whole
       `tasks.length < taskCount` arm left all 58 cases green. */
    const { container, dispose } = open(
      withConnector((one) => {
        one["taskCount"] = 3;
        one["runningTasks"] = 1;
        one["tasks"] = [{ id: 0, state: "RUNNING", workerId: "10.0.0.1:8083" }];
      }),
    );
    await settle();

    const sentence = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-tasks"]',
    );
    expect(sentence?.textContent).toContain("1 of 3 tasks running");
    expect(sentence?.textContent).toContain("The worker described 1 of them");

    dispose();
  });

  it("does not claim a gap on a connector whose tasks were all described", async () => {
    // The other direction, so the sentence reports a real disagreement rather than always warning.
    const { container, dispose } = open(responseDocument);
    await settle();

    const sentence = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-tasks"]',
    );
    expect(sentence?.textContent).toContain("3 of 3 tasks running");
    expect(sentence?.textContent).not.toContain("described");

    dispose();
  });

  it("says the worker did not say what kind of connector it is, and never guesses one", async () => {
    /* Filed by W7-A3, and it is §7.2's rule — do not infer a cart from the word orders. `kind` is
       empty on every Connect release before 2.0; a panel that filled it in from the connector's
       name would print `source · on payments` as though the worker had said so. Replacing the
       empty-kind arm with a guess left all 58 cases green. */
    const { container, dispose } = open(withConnector((one) => (one["kind"] = "   ")));
    await settle();

    const card = panel(container, "payments/orders-source");
    expect(card?.textContent).toContain("the worker did not say what kind of connector this is");
    expect(card?.textContent).toContain("on payments");
    // And the name is not read as the kind.
    expect(card?.textContent).not.toContain("source \u00b7 on payments");

    dispose();
  });
});

describe("a Connect cluster that is rebalancing", () => {
  it("is a notice, not a failure, and the connectors that answered are still drawn", async () => {
    /*
     * `ErrorCode.ConnectRebalancing` is transient. Drawing it as a failure would put a red panel
     * and a Retry in front of an operator over a state that settles by itself in seconds — and it
     * would hide the connectors the other worker did answer for.
     */
    const { container, dispose } = open(responseDocument);
    await settle();

    const notice = container.querySelector('[data-testid="connect-worker-rebalancing"]');
    expect(notice?.textContent).toContain("rebalancing");
    expect(notice?.textContent).toContain("analytics");
    expect(container.querySelector('[data-testid="connect-failed"]')).toBeNull();
    expect(container.querySelector('[data-testid="connect-worker-failed"]')).toBeNull();
    expect(panels(container)).toHaveLength(3);

    dispose();
  });

  it("draws any other unavailable reason as a failure that names its code", async () => {
    const down = {
      connectors: {
        status: "ok",
        data: {
          workers: [
            {
              connect: "payments",
              connectors: {
                status: "unavailable",
                reason: "UPSTREAM_UNAVAILABLE",
                message: "the Connect cluster did not answer within 10 seconds",
              },
            },
          ],
        },
      },
    };
    const { container, dispose } = open(down);
    await settle();

    const notice = container.querySelector('[data-testid="connect-worker-failed"]');
    expect(notice?.textContent).toContain("did not answer");
    expect(notice?.textContent).toContain("missing from this list");
    expect(container.querySelector('[data-testid="connect-worker-rebalancing"]')).toBeNull();

    dispose();
  });
});

describe("the three commands", () => {
  it("sends the pause path for the connector whose button was pressed", async () => {
    const { container, stub, dispose } = open(responseDocument);
    await settle();

    control(panel(container, "payments/orders-source"), "Pause")?.click();
    await settle();

    const write = stub.calls.find((call) => call.method === "post");
    /* The address as a literal. The stub answers whatever path it is given, so comparing it against
       `PAUSE_PATH` would assert that a constant equals itself. */
    expect(write).toEqual({
      method: "post",
      path: "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/pause",
      params: { clusterId: "quickstart", connectName: "payments", connectorName: "orders-source" },
    });

    dispose();
  });

  it("offers Resume to a paused connector and sends the resume path", async () => {
    const { container, stub, dispose } = open(responseDocument);
    await settle();

    const paused = panel(container, "payments/archive-sink");
    expect(control(paused, "Pause")).toBeUndefined();
    control(paused, "Resume")?.click();
    await settle();

    expect(stub.calls.find((call) => call.method === "post")?.path).toBe(
      "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/resume",
    );

    dispose();
  });

  it("re-reads the list after a command the cluster accepted", async () => {
    // Connect answers 202 with an empty body and changes state when its workers agree, so the
    // screen asks again rather than painting an outcome it was not told.
    const { container, stub, dispose } = open(responseDocument);
    await settle();
    const before = stub.calls.filter((call) => call.method === "get").length;

    control(panel(container, "payments/orders-source"), "Restart")?.click();
    await settle();

    expect(stub.calls.filter((call) => call.method === "get").length).toBeGreaterThan(before);

    dispose();
  });

  it("says what the server said when a command is refused, beside that connector", async () => {
    const { container, dispose } = open(responseDocument, {
      write: async () => ({
        ok: false,
        error: {
          kind: "envelope",
          code: "KUI-CONNECT-REBALANCING",
          message: "The Kafka Connect cluster is rebalancing and cannot answer yet.",
          status: 409,
        },
      }),
    });
    await settle();

    control(panel(container, "payments/orders-source"), "Pause")?.click();
    await settle();

    const failure = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-failure"]',
    );
    expect(failure?.textContent).toContain("rebalancing and cannot answer yet");
    // And beside that connector only.
    expect(
      panel(container, "payments/elastic-sink")?.querySelector('[data-testid="connector-failure"]'),
    ).toBeNull();

    dispose();
  });

  it("does not re-read the list after a command that was refused", async () => {
    /* A refused command leaves the card as it was with the reason beneath it. Re-reading would
       replace the reason with a spinner and then with the same card, which reads as though nothing
       had been clicked. */
    const { container, stub, dispose } = open(responseDocument, {
      write: async () => ({
        ok: false,
        error: { kind: "envelope", code: "KUI-FORBIDDEN", message: "Refused.", status: 403 },
      }),
    });
    await settle();
    const before = stub.calls.filter((call) => call.method === "get").length;

    control(panel(container, "payments/orders-source"), "Pause")?.click();
    await settle();

    expect(stub.calls.filter((call) => call.method === "get").length).toBe(before);

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * Accessibility
 * ---------------------------------------------------------------------------------------------- */

describe("the screen's accessibility", () => {
  it("has no axe violations with connectors, a failure, a notice and a refusal on it", async () => {
    const { container, dispose } = open(responseDocument, {
      grants: [grant("CONNECT", ["OPERATE"], "nothing-at-all")],
    });
    await settle();

    const violations = await findViolations(container);
    expect(violations, describeViolations(violations)).toEqual([]);

    dispose();
  });

  it("has no axe violations on the states nobody draws", async () => {
    for (const document of [emptyDocument, partialDocument, notConfiguredDocument]) {
      const { container, dispose } = open(document);
      await settle();
      const violations = await findViolations(container);
      expect(violations, describeViolations(violations)).toEqual([]);
      dispose();
    }
  });
});
