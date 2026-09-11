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
import { ErrorCodes } from "@kui/api";
import { KuiProvider, createQueryRegistry, type PermissionGrant } from "@kui/kernel";

import { ConnectScreen } from "./ConnectRoute.jsx";
import { NO_CONNECTORS, NO_REASON_REPORTED, THROUGHPUT_NOT_MEASURED } from "./model.js";
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

    /*
     * §3.14's *Absent* paragraph: a literal `0 msg/s` on a paused connector is a measured zero, and
     * an unmeasured one must never look like it. There is no rate on this wire at all.
     *
     * Asserted against `THROUGHPUT_NOT_MEASURED` rather than against a third literal, because the
     * words on the screen come from `@kui/kernel`'s `ConnectorCard` and this package cannot hand
     * them in — the card is given no `throughput` prop and decides the sentence itself. Comparing
     * the two copies here is the only join available across a boundary a feature may not cross.
     */
    expect(container.textContent).toContain(THROUGHPUT_NOT_MEASURED);
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
    connectors: {
      data: { workers: { connectors: { data?: { items: Record<string, unknown>[] } } }[] };
    };
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

  it("says the worker did not say what kind of connector it is, and never guesses", async () => {
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

  it("sends the restart path, which is a third endpoint and not pause with a verb", async () => {
    const { container, stub, dispose } = open(responseDocument);
    await settle();

    control(panel(container, "payments/orders-source"), "Restart")?.click();
    await settle();

    /* The address as a literal, for the same reason the pause case spells it out: comparing it
       against `RESTART_PATH` would assert that a constant equals itself. Pause, resume and restart
       are three endpoints, three audit operation names and three separately refusable mutations,
       so the last segment of each is a contract and not a detail. */
    expect(stub.calls.find((call) => call.method === "post")).toEqual({
      method: "post",
      path: "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/restart",
      params: { clusterId: "quickstart", connectName: "payments", connectorName: "orders-source" },
    });

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
          /* The generated constant, not the string. `ErrorCodes` is rendered from the server's own
             `ErrorCode` enum, so a rename on that side reddens this case instead of leaving a
             browser quietly matching a code nothing sends any more. The product does not branch on
             this code — a refused command shows whoever refused it their own sentence and that is
             the whole of `sentenceOf` — so this case is its only reader, which is why the constant
             belongs here rather than a copy of its text. */
          code: ErrorCodes.ConnectRebalancing,
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

  it("clears the last refusal when the next command is sent: one sentence, one press", async () => {
    /*
     * Also found by mutation here: deleting `setFailure(undefined)` from `onCommand` left all 73
     * cases green, and under it the sentence from a refused pause sits under the card while a
     * restart is in flight — so the operator reads a server's refusal of something they are no
     * longer doing, beside a spinner, and has no way to tell which press it belongs to.
     */
    let attempt = 0;
    const { container, dispose } = open(responseDocument, {
      write: async () => {
        attempt += 1;
        if (attempt === 1) {
          return {
            ok: false,
            error: { kind: "envelope", code: "KUI-FORBIDDEN", message: "Refused.", status: 403 },
          };
        }
        return await new Promise<unknown>(() => {});
      },
    });
    await settle();

    const card = (): HTMLElement | undefined => panel(container, "payments/orders-source");
    control(card(), "Pause")?.click();
    await settle();
    expect(card()?.querySelector('[data-testid="connector-failure"]')?.textContent).toContain(
      "Refused.",
    );

    control(card(), "Restart")?.click();
    await settle();

    expect(card()?.querySelector('[data-testid="connector-failure"]')).toBeNull();

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
 * The voice line, which is the page's heading and was carried by nothing
 * ---------------------------------------------------------------------------------------------- */

describe("the sentence under the page title", () => {
  it("counts the rows this browser is holding, and says how many are failing", async () => {
    /*
     * Found by mutation after the eleven filed rules were closed, and it is the twelfth: replacing
     * `voiceOf(connectors.state())` with `undefined` in `ConnectRoute` deleted `SCREENS-V4` §4.14's
     * whole heading — `4 connectors · 1 failed and sulking` — and left all 73 cases green.
     * `connectVoice` itself is gated five ways in `model.test.ts`; nothing asserted that the route
     * puts its answer on the page, which is the shape this package's own header warns about in the
     * other direction.
     */
    const { container, dispose } = open(responseDocument);
    await settle();

    const header = container.querySelector('[data-testid="connect-header"]');
    expect(header?.textContent).toContain("3 connectors");
    // Counted from the wire's `failed` flag and not from the state word: `elastic-sink`'s own state
    // is RUNNING and its task 1 has failed, and a heading counting state words calls that healthy.
    expect(header?.textContent).toContain("1 failed and sulking");
    // And the count is marked partial, because one of the two workers is rebalancing and a
    // confident "3 connectors" over a cluster nobody has finished reading is a smaller number in
    // the reassuring direction.
    expect(header?.textContent).toContain("from the workers that answered");

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * A command in flight, which is the only thing stopping a duplicate mutation
 * ---------------------------------------------------------------------------------------------- */

/** A server that never answers, so the page stays in the state a click puts it in. */
function neverAnswers(): () => Promise<unknown> {
  return () => new Promise<unknown>(() => {});
}

/** Whether a control is marked busy. `Button` marks it with `aria-busy` and swallows the click. */
function isBusy(button: HTMLButtonElement | undefined): boolean {
  return button?.getAttribute("aria-busy") === "true";
}

describe("a command in flight", () => {
  it("marks the connector it was sent against, and no other card on the page", async () => {
    /*
     * `pendingFor` keys on `connectorLabel`, and its twin `failureFor` — which is gated — keys the
     * same way. Without the subject comparison one pause marks every card on the page busy, and a
     * page of cards is exactly what this screen is: an operator watching a restart on one connector
     * finds the controls of every other one dead for as long as it takes.
     */
    const { container, dispose } = open(TWO_CLUSTERS, { write: neverAnswers() });
    await settle();

    control(panel(container, "payments/orders-source"), "Pause")?.click();
    await settle();

    expect(isBusy(control(panel(container, "payments/orders-source"), "Pause"))).toBe(true);
    expect(isBusy(control(panel(container, "analytics/es-sink"), "Pause"))).toBe(false);
    expect(isBusy(control(panel(container, "analytics/es-sink"), "Restart"))).toBe(false);

    dispose();
  });

  it("makes that connector's controls swallow a second press: one click, one POST", async () => {
    /*
     * This is not cosmetic and it is the packet's mutation line. `busy` reaches the kernel
     * `Button`, whose `inert()` is what swallows the click — and nothing else between the pointer
     * and `POST /pause` stops a second one. Connect answers all three commands `202 Accepted` with
     * an empty body, so the card cannot show the outcome and a second press looks reasonable.
     */
    const { container, stub, dispose } = open(TWO_CLUSTERS, { write: neverAnswers() });
    await settle();

    const card = (): HTMLElement | undefined => panel(container, "payments/orders-source");
    control(card(), "Pause")?.click();
    await settle();
    control(card(), "Pause")?.click();
    control(card(), "Restart")?.click();
    await settle();

    expect(isBusy(control(card(), "Pause"))).toBe(true);
    expect(isBusy(control(card(), "Restart"))).toBe(true);
    expect(stub.calls.filter((call) => call.method === "post")).toHaveLength(1);

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * The figures and sentences a missing field would turn into a false statement
 * ---------------------------------------------------------------------------------------------- */

describe("a field the service did not send", () => {
  it("counts the tasks the worker described rather than saying there are none", async () => {
    /*
     * `taskCount` is the service's own figure and `tasks.length` is the fallback for a document
     * that omits it. Defaulting to zero instead draws "This connector has no tasks." over a
     * described task list — a sentence an operator acts on, by going to look for a connector
     * somebody deleted, on a connector that is running three tasks.
     */
    const { container, dispose } = open(
      withConnector((one) => {
        delete one["taskCount"];
      }),
    );
    await settle();

    const sentence = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-tasks"]',
    );
    expect(sentence?.textContent).toContain("3 of 3 tasks running");
    expect(sentence?.textContent).not.toContain("no tasks");

    dispose();
  });

  it("counts no task as running when the service sent no figure, never all of them", async () => {
    /*
     * Filed by W8-04's verification pass as F1, and the exact mirror of the `taskCount` case above:
     * the fallback `?? 0` on `runningTasks` was measured by nothing, so `?? tasks.length` was one
     * edit away and left all 75 cases green.
     *
     * The two fallbacks are not symmetric and that is the whole finding. `taskCount` falling
     * back to `tasks.length` is a *count of things the document actually carries* — a true
     * figure about a shorter document. `runningTasks` falling back to `tasks.length` is a
     * *claim about state*, and one nothing in the document supports: this fixture's three tasks
     * are RUNNING, RESTARTING and FAILED, and the domain is explicit that RESTARTING is not
     * running. The panel would read
     * "3 of 3 tasks running" over a connector with a dead task, which is the most convincing
     * kind of wrong number there is — §3.14's *Absent* rule exists because an unmeasured
     * figure invented as a healthy one is worse than no figure at all.
     *
     * Zero is the honest answer here rather than a bare zero the design forbids: the sentence still
     * carries the denominator and the three tasks are still drawn with their own states beside it.
     */
    const { container, dispose } = open(
      withConnector((one) => {
        delete one["runningTasks"];
        one["taskCount"] = 3;
        one["tasks"] = [
          { id: 0, state: "RUNNING", workerId: "10.0.0.1:8083" },
          { id: 1, state: "RESTARTING", workerId: "10.0.0.2:8083" },
          { id: 2, state: "FAILED", workerId: "10.0.0.3:8083" },
        ];
      }),
    );
    await settle();

    const sentence = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-tasks"]',
    );
    expect(sentence?.textContent).toContain("0 of 3 tasks running");
    expect(sentence?.textContent).not.toContain("3 of 3");

    dispose();
  });

  it("says a connector has no tasks rather than drawing a zero over a zero", async () => {
    /*
     * Filed by W8-04's verification pass as F2, and this case is a *restoration*. `model.test.ts`
     * carried `says a connector has no tasks rather than drawing 0/0` before this wave; W8-04's
     * correction item 14 deleted `taskCaption()` and took the case with it, moved the sentence into
     * `ConnectorPanel.taskSentence`, and left the promise gated by nothing. Deleting the
     * `taskCount === 0` line from `taskSentence` left all 75 cases green.
     *
     * What it draws under the mutation is `0 of 0 tasks running.` — the bare zero over a bare zero
     * that this product's own rule forbids, on the one panel where it is also *ambiguous*: a
     * connector that is paused with no tasks assigned and a connector whose worker described
     * none of its tasks produce the same two zeroes, and the sentence is the only thing that
     * distinguishes "there is nothing to run" from "KUI was told nothing". A paused or freshly
     * created connector genuinely sends no task list, so this is a document the quickstart
     * itself can produce.
     */
    const { container, dispose } = open(
      withConnector((one) => {
        one["taskCount"] = 0;
        one["runningTasks"] = 0;
        one["tasks"] = [];
      }),
    );
    await settle();

    const sentence = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-tasks"]',
    );
    expect(sentence?.textContent).toBe("This connector has no tasks.");
    expect(sentence?.textContent).not.toContain("0 of 0");

    dispose();
  });

  it("says a failure reported with no reason is exactly that, never an empty block", async () => {
    /*
     * The fallback is the whole of the rule: a failed connector's reason area is red, and a red
     * area with nothing in it reads as "KUI knows and will not say". The true fact — a failure the
     * worker reported without a reason — is itself something an operator needs, because it points
     * at the worker's log rather than at KUI.
     */
    const { container, dispose } = open(
      withConnector((one) => {
        one["failed"] = true;
        one["reason"] = null;
      }),
    );
    await settle();

    const block = panel(container, "payments/orders-source")?.querySelector(
      '[data-testid="connector-reason"]',
    );
    expect(block?.textContent).toContain(NO_REASON_REPORTED);

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * The outer section's refusals, which are not interchangeable with each other
 * ---------------------------------------------------------------------------------------------- */

describe("what the whole listing says when the server would not give it", () => {
  it("tells a principal without CONNECT:VIEW it is a permission, not an empty list", async () => {
    /*
     * Three empty screens are three different facts and only one of them is a reason to ask
     * somebody for a grant. A page that drew this as "nothing is deployed" would send an operator
     * to look at a Connect worker that is running perfectly well.
     */
    const { container, dispose } = open({ connectors: { status: "forbidden" } });
    await settle();

    const refusal = container.querySelector('[data-testid="connect-forbidden"]');
    expect(refusal?.textContent).toContain("CONNECT:VIEW");
    expect(container.querySelector('[data-testid="connect-empty"]')).toBeNull();
    expect(container.querySelector('[data-testid="connect-not-configured"]')).toBeNull();
    // No retry: a permission decision does not change because somebody pressed a button.
    const labels = [...container.querySelectorAll("button")].map((one) => one.textContent);
    expect(labels).not.toContain("Retry");

    dispose();
  });

  it("marks a stale listing, so an old answer is not read as this morning's", async () => {
    /*
     * A stale section is an answer with a badge on it: the connectors are still drawn, because
     * hiding them would lose the only list there is, and the badge is what stops the screen being
     * read as current. There is a story for this state and, until now, no case.
     */
    const stale = {
      connectors: {
        status: "stale",
        reason: "UPSTREAM_TIMEOUT",
        message: "The Connect clusters did not answer, so this is the last list KUI received.",
        data: (responseDocument as { connectors: { data: unknown } }).connectors.data,
        fetchedAt: "2026-09-03T10:11:12.000Z",
      },
    };
    const { container, dispose } = open(stale);
    await settle();

    expect(container.querySelector('[data-testid="connect-stale"]')?.textContent).toContain(
      "the last list KUI received",
    );
    expect(panels(container)).toHaveLength(3);

    dispose();
  });

  it("a worker whose last answer named none still says the worker named none", async () => {
    /*
     * W8-04 disclosed this one itself and nobody had closed it: narrowing `answered()` from
     * `kind === "ok" || kind === "stale"` to `kind === "ok"` left all 75 cases green, because the
     * only stale fixture in the file carries three connectors and so never reaches the fallback the
     * predicate guards.
     *
     * The staleness that matters here is the **worker's own** section and not the listing's — the
     * outer one is the banner above, and `answered` reads `worker.page.kind`. A Connect cluster
     * whose connectors were all deleted answers exactly this document and then stops answering, so
     * stale-and-empty is a real sequence rather than a contrived one.
     *
     * The two halves of the stale state pull in opposite directions and both have to hold. The case
     * above proves a stale answer's *rows* are drawn — hiding them would throw away the only list
     * there is. This proves a stale answer's *emptiness* is drawn too, and that is the half the
     * narrowing removes: with `stale` no longer counting as having answered, the sentence
     * disappears and the operator is shown a page with nothing on it at all, where the product
     * knows, and could say, what this worker last told it. §3.14's rule is that an unmeasured
     * figure says so in words; a measured emptiness suppressed is the same failure with more
     * information thrown away.
     */
    const staleAndEmpty = {
      connectors: {
        status: "ok",
        data: {
          workers: [
            {
              connect: "payments",
              connectors: {
                status: "stale",
                reason: "UPSTREAM_TIMEOUT",
                message: "The Connect cluster did not answer, so this is the last list KUI had.",
                data: { items: [], unreadable: [] },
                fetchedAt: "2026-09-03T10:11:12.000Z",
              },
            },
          ],
        },
        fetchedAt: "2026-09-03T10:11:12.000Z",
      },
    };
    const { container, dispose } = open(staleAndEmpty);
    await settle();

    expect(container.querySelector('[data-testid="connect-empty"]')?.textContent).toContain(
      "answered and named no connectors",
    );
    expect(panels(container)).toHaveLength(0);

    dispose();
  });
});

/* ------------------------------------------------------------------------------------------------
 * One worker's row, and the rows there is no honest way to draw
 * ---------------------------------------------------------------------------------------------- */

/** The two-cluster listing with the `analytics` worker's own inner section replaced. */
function withSecondWorker(section: unknown): unknown {
  const copy = structuredClone(TWO_CLUSTERS) as {
    connectors: { data: { workers: { connectors: unknown }[] } };
  };
  const worker = copy.connectors.data.workers[1];
  if (worker === undefined) throw new Error("the two-cluster fixture lost its second worker");
  worker.connectors = section;
  return copy;
}

/** The two-cluster listing with one more worker row appended, exactly as a server could send it. */
function withExtraWorker(entry: unknown): unknown {
  const copy = structuredClone(TWO_CLUSTERS) as {
    connectors: { data: { workers: unknown[] } };
  };
  copy.connectors.data.workers.push(entry);
  return copy;
}

/** The two-cluster listing with one more entry in the `payments` worker's item list. */
function withExtraConnector(entry: unknown): unknown {
  const copy = structuredClone(TWO_CLUSTERS) as {
    connectors: { data: { workers: { connectors: { data: { items: unknown[] } } }[] } };
  };
  const worker = copy.connectors.data.workers[0];
  if (worker === undefined) throw new Error("the two-cluster fixture lost its first worker");
  worker.connectors.data.items.push(entry);
  return copy;
}

describe("one Connect cluster's own row", () => {
  it("names a Connect cluster the principal may not see, and keeps the ones it may", async () => {
    /*
     * One worker refusing costs one row rather than the screen. But the row has to be *said*: a
     * list that silently dropped the refused worker would be a short list drawn as a complete one,
     * and an operator would conclude that the connectors on it had been deleted.
     */
    const { container, dispose } = open(withSecondWorker({ status: "forbidden" }));
    await settle();

    const notice = container.querySelector('[data-testid="connect-worker-forbidden"]');
    expect(notice?.textContent).toContain("analytics");
    expect(notice?.textContent).toContain("missing from this list");
    // And the worker that did answer is drawn in full.
    expect(panels(container).map((one) => one.dataset["connector"])).toEqual([
      "payments/orders-source",
    ]);

    dispose();
  });

  it("drops a worker row that names no Connect cluster rather than drawing a blank", async () => {
    /*
     * A row this build cannot name is a row it cannot ask a permission question about either:
     * `operateSubject()` would hand the empty string to `kui.permits`, and the banner would open
     * with a colon — ": the cluster is rebalancing" — over a worker nobody can identify or act on.
     */
    const { container, dispose } = open(
      withExtraWorker({
        connectors: {
          status: "unavailable",
          reason: "STARTING",
          message: "the Kafka Connect cluster is rebalancing",
        },
      }),
    );
    await settle();

    expect(container.querySelector('[data-testid="connect-worker-rebalancing"]')).toBeNull();
    // The two workers that did name themselves are untouched by the one that did not.
    expect(panels(container)).toHaveLength(2);

    dispose();
  });

  it("drops a connector the worker did not name, not a card nobody can act on", async () => {
    /*
     * Every control on a card names the connector to the server, so a card drawn for a nameless
     * entry posts a name that does not exist — `connectorName: "(unnamed)"` — and reports the
     * server's refusal as though the operator had done something wrong. A row nobody can act on is
     * furniture; the name is what makes it a row.
     */
    const { container, dispose } = open(
      withExtraConnector({
        connect: "payments",
        kind: "source",
        state: "RUNNING",
        failed: false,
        runningTasks: 1,
        taskCount: 1,
        tasks: [{ id: 0, state: "RUNNING", workerId: "10.0.0.1:8083" }],
      }),
    );
    await settle();

    expect(panels(container).map((one) => one.dataset["connector"])).toEqual([
      "payments/orders-source",
      "analytics/es-sink",
    ]);

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
