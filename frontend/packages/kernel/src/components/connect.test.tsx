import { describe, expect, test } from "vitest";
import { flush } from "solid-js";
import { render } from "@solidjs/web";
import { TaskBar, describeTasks } from "./TaskBar.jsx";
import { ConnectorCard, connectorChip } from "./ConnectorCard.jsx";

function mount(component: () => unknown) {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const dispose = render(component as never, host);
  flush();
  return {
    host,
    dispose: () => {
      dispose();
      host.remove();
    },
  };
}

describe("a connector's task bar", () => {
  test("a connector with no tasks does not look like one whose tasks all failed", async () => {
    /*
     * The distinction the component exists to keep. "This connector has no tasks" and "every one of
     * this connector's tasks has failed" are different problems with different causes, and a bar
     * that drew them alike would send somebody to look at the wrong one — at the connector's
     * configuration instead of at the worker, or the other way round.
     */
    const none = mount(() => <TaskBar tasks={[]} />);
    await flush();
    expect(none.host.querySelector(".kui-taskbar__track--empty")).not.toBeNull();
    expect(none.host.querySelectorAll(".kui-taskbar__task--failed")).toHaveLength(0);
    none.dispose();

    const failed = mount(() => <TaskBar tasks={["failed", "failed"]} />);
    await flush();
    expect(failed.host.querySelector(".kui-taskbar__track--empty")).toBeNull();
    expect(failed.host.querySelectorAll(".kui-taskbar__task--failed")).toHaveLength(2);
    failed.dispose();
  });

  test("the bar carries its meaning in words as well as in colour", async () => {
    // A row of coloured rectangles carries nothing at all to somebody who cannot see it, and nothing
    // useful to somebody who cannot tell red from green.
    const { host, dispose } = mount(() => <TaskBar tasks={["running", "running", "failed"]} />);
    await flush();
    expect(host.textContent).toContain("3 tasks");
    expect(host.textContent).toContain("2 running");
    expect(host.textContent).toContain("1 failed");
    dispose();
  });

  test("the sentence names only the states that are present", () => {
    expect(describeTasks(["running", "running"])).toBe("2 tasks: 2 running.");
    expect(describeTasks(["running"])).toBe("1 task: 1 running.");
    expect(describeTasks([])).toBe("This connector has no tasks.");
  });
});

describe("a connector's state chip", () => {
  test("an unreported state is never drawn as running", () => {
    // Guessing healthy is this product telling somebody their pipeline is fine on no evidence, which
    // is the single worst thing a monitoring screen can do.
    const unknown = connectorChip("UNKNOWN");
    expect(unknown.label).not.toMatch(/running/i);
    expect(unknown.tone).not.toBe("success");
  });

  test("unassigned is not a failure", () => {
    /*
     * It means the connector exists and Connect has not handed it to a worker yet. That resolves on
     * its own and is not something to page anybody about — drawing it in danger colours produces
     * exactly that page.
     */
    expect(connectorChip("UNASSIGNED").tone).not.toBe("danger");
    expect(connectorChip("FAILED").tone).toBe("danger");
  });

  test("every state has the word this product shows, pinned where the word is chosen", () => {
    /*
     * Filed by W8-04's verification pass as F5. `frontend/e2e/connect.spec.ts`'s `wordFor` helper
     * transcribes all five of these labels into a browser spec, and the spec's own comment concedes
     * it ("written out rather than imported"). The transcription is correct today — the verifier
     * compared all five — and the shape is the one house rule 12 exists to end: that file now reads
     * the *wire* off disk and still hand-copies the *words*.
     *
     * Nothing here can stop a copy being made. What it can do is decide which language a rename
     * reddens in first, and this is the right one: a rename to `connectorChip`'s labels reddens
     * here, in the package that owns them, in about a second — rather than in a Playwright run
     * against a live stack, where the failure arrives as a locator that found no text and points at
     * the spec rather than at the edit that caused it.
     *
     * Literals rather than a loop over a roster, deliberately. A case that derived the expected
     * words from `connectorChip` itself would assert that the function equals the function. These
     * five strings are the product's vocabulary and `UNKNOWN`'s is the one carrying an argument:
     * "state not reported" rather than "unknown", because the reader is being told KUI has no
     * information and not that the connector is in a state called unknown.
     */
    expect(connectorChip("RUNNING").label).toBe("running");
    expect(connectorChip("FAILED").label).toBe("failed");
    expect(connectorChip("PAUSED").label).toBe("paused");
    expect(connectorChip("UNASSIGNED").label).toBe("unassigned");
    expect(connectorChip("UNKNOWN").label).toBe("state not reported");
  });

  test("the pill publishes the state word beside the sentence, for every state", async () => {
    /*
     * The structural half of the finding the case above holds the cheap half of. Pinning the five
     * labels decides which language a rename reddens in; it cannot stop the copy being made, and
     * `frontend/e2e/connect.spec.ts` had made it. `data-state` is what lets that spec stop: it
     * carries the drawn state verbatim, so a browser case compares this attribute to the state the
     * Connect worker reported and never writes a label down at all.
     *
     * Asserted here rather than only in the browser for the reason every kernel case exists: this
     * is the package that owns the attribute, and a Playwright run against a live stack reports its
     * loss as a locator that matched nothing.
     *
     * The pair is the assertion, not the attribute alone. `data-state` and the pill's own text have
     * to describe the same connector — an attribute wired to a different prop, or left on a stale
     * value while the words moved, is worse than no attribute, because the spec that trusts it
     * would then pass on a screen showing something else. So every state is mounted and both
     * channels are read off the one element.
     */
    for (const state of ["RUNNING", "FAILED", "PAUSED", "UNASSIGNED", "UNKNOWN"] as const) {
      const { host, dispose } = mount(() => (
        <ConnectorCard name="orders-sink" kind="sink" state={state} tasks={["running"]} />
      ));
      await flush();

      const pill = host.querySelector(".kui-connector__head .kui-pill");
      expect(pill, `no pill drawn for ${state}`).not.toBeNull();
      expect(pill?.getAttribute("data-state")).toBe(state);
      expect(pill?.textContent).toBe(connectorChip(state).label);
      // AND THE COLOUR, WHICH IS THE THIRD CHANNEL AND THE ONE AN OPERATOR READS FIRST. Hard-coding
      // the call site's `tone` — `tone="danger"` for every state — draws a RUNNING connector with a
      // red pill and left both this loop and `e2e/connect.spec.ts` green, because the word and the
      // attribute were still right. A card whose colour says failing and whose word says running is
      // read as failing. Filed by W9-04 as its own green mutation and reproduced by W9-03/F5.
      expect(pill?.className).toContain(`kui-pill--${connectorChip(state).tone}`);
      dispose();
    }
  });

  test("a pill drawn from no reported state says so in both channels", async () => {
    /*
     * The one case where the two channels could be made to disagree without anybody noticing, and
     * the one where it would matter most. `UNKNOWN` is the state this card's header calls the worst
     * thing to get wrong: a regression drawing it as `RUNNING` in either channel tells an operator
     * their pipeline is fine on no evidence. The attribute is checked against the *word* rather
     * than only against the prop, because a `data-state` hard-coded to "RUNNING" would satisfy the
     * loop above on four of its five passes and this is the fifth.
     */
    const { host, dispose } = mount(() => (
      <ConnectorCard name="orders-sink" kind="sink" state="UNKNOWN" tasks={[]} />
    ));
    await flush();

    const pill = host.querySelector(".kui-connector__head .kui-pill");
    expect(pill?.getAttribute("data-state")).not.toMatch(/RUNNING/i);
    expect(pill?.textContent).not.toMatch(/running/i);
    dispose();
  });
});

describe("a connector card's facts line", () => {
  test("counts the tasks that are running and not the tasks that exist", async () => {
    /*
     * Found by mutation while this packet was looking for a green one, and it was green: replacing
     * `taskSummary`'s filter with `tasks.length` — so a connector with one running task and two
     * failed ones draws "3/3 tasks" — left all 1,900 cases in all 82 frontend test files passing.
     * It is the same class of defect as the header's `UNKNOWN` rule, one line lower down: a
     * monitoring screen reporting that everything is fine on evidence that says otherwise.
     *
     * It is worse here than a wrong number usually is, because the number has a neighbour.
     * `feature-connect`'s `ConnectorPanel` prints the *cluster's* own figures beside this one and
     * its comment says, correctly, that where the two disagree the difference is a fact — KUI was
     * told about fewer tasks than the connector has. A card that miscounts its own segments
     * manufactures that disagreement, and the reader is then told a true-sounding thing about the
     * wrong subject.
     *
     * The bar above the line already draws the distinction and `TaskBar`'s own cases gate it. This
     * is the sentence beside it, which had no case at all.
     */
    const { host, dispose } = mount(() => (
      <ConnectorCard
        name="elastic-sink"
        kind="sink"
        state="FAILED"
        tasks={["running", "failed", "failed"]}
      />
    ));
    await flush();

    const facts = host.querySelector(".kui-connector__facts");
    expect(facts?.textContent).toContain("1/3 tasks");
    dispose();
  });

  test("a connector with no tasks says so rather than drawing a ratio of nothing", async () => {
    // "0/0 tasks" is arithmetic, not an answer, and it reads as a connector whose tasks all died.
    const { host, dispose } = mount(() => (
      <ConnectorCard name="orders-source" kind="source" state="UNASSIGNED" tasks={[]} />
    ));
    await flush();

    const facts = host.querySelector(".kui-connector__facts");
    expect(facts?.textContent).toContain("no tasks");
    expect(facts?.textContent).not.toContain("0/0");
    dispose();
  });
});

describe("a connector card that may not be operated", () => {
  test("refuses the handler and the reason together, and never one without the other", async () => {
    /*
     * W8-04 disclosed this one itself and nobody had closed it. Its `ConnectorPanel` spreads
     * `cardActions(...)`, whose whole job is to hand over *either* two handlers *or* a refusal
     * sentence; replacing that spread with handlers always passed alongside the reason left all
     * 75 feature-connect cases green. The reason it stayed green is this component: the panel's
     * rule is held one level down, by a card that declines to wire a handler it was given while a
     * reason to refuse is present. Nothing asserted that, so the rule lived in two places and was
     * measured in neither.
     *
     * This is the seam worth gating rather than the panel's spread. Every caller that ever hands
     * this card both — by a spread going wrong, by a refusal arriving a tick after the props, by a
     * second feature package written against the same component — gets the refusal honoured, and a
     * principal without CONNECT:OPERATE cannot pause a connector by clicking a button that looks
     * disabled. ADR-039's fold decides who may operate; a control that says "no" and posts anyway
     * would make that decision decorative.
     *
     * The click is dispatched rather than inspected: `aria-disabled` being present is a claim about
     * markup, and what an operator does is press the thing. Both controls, because Restart is the
     * destructive one and is the one a refusal exists for.
     */
    let pauses = 0;
    let restarts = 0;
    const { host, dispose } = mount(() => (
      <ConnectorCard
        name="elastic-sink"
        kind="sink"
        state="RUNNING"
        tasks={["running"]}
        actionsDisabledReason="You do not have permission to operate connectors on payments."
        onPause={() => {
          pauses += 1;
        }}
        onRestart={() => {
          restarts += 1;
        }}
        testId="refused-connector"
      />
    ));

    const buttons = [...host.querySelectorAll("button")];
    expect(buttons).toHaveLength(2);
    for (const button of buttons) {
      expect(button.getAttribute("aria-disabled")).toBe("true");
      button.click();
      flush();
    }

    expect(pauses).toBe(0);
    expect(restarts).toBe(0);

    /* And the refusal is announced rather than only enacted: a control that silently does nothing
       teaches an operator that this product's buttons do nothing. `aria-disabled` rather than the
       `disabled` attribute is what keeps the control focusable, which is what lets the reason be
       read at all — the sentence itself lives in `Button`'s tooltip and is described *to* the
       control, so this asserts the join rather than the words. Saying the cluster's name in prose
       on the page is the panel's own rule and is gated in `feature-connect`, not here. */
    for (const button of buttons) {
      expect(button.getAttribute("aria-describedby")).not.toBeNull();
    }

    dispose();
  });
});
