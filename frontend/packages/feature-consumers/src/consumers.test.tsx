/**
 * The consumer screens' tests.
 *
 * Two kinds, deliberately separated. The pure ones exercise the rules — which chip a state gets,
 * when lag turns amber, what sentence the voice picks, whether the form refuses — and need no DOM.
 * The rendered ones exercise the things that are only true once markup exists: that an unreadable
 * lag draws an em dash rather than a zero, that a narrow window drops a column instead of hiding
 * it, and that pressing Preview always changes the screen.
 *
 * Everything a Solid 2 test has to remember is in `testing.ts`: `flush()` before asserting, and
 * dispose at the end.
 */

import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { describeViolations, findViolations, mount } from "./testing.js";
import {
  LAG_WARN_ABOVE,
  UNREADABLE_STATE_CHIP,
  groupsVoice,
  healthOf,
  lagLevel,
  stateChip,
} from "./model.js";
import { EMPTY_RESET_FORM, partitionLag, recordsMoved, resetRequestOf, subscriptions, targetOption } from "./detail.js";
import { GroupList } from "./GroupList.jsx";
import { GroupDetail } from "./GroupDetail.jsx";
import { ResetWizard, scopeSentence } from "./ResetWizard.jsx";
import { DEGRADED_GROUPS, NO_OP_PLAN, SAMPLE_GROUPS, SAMPLE_GROUP_DETAIL, SAMPLE_PLAN } from "./fixtures.js";

const noop = (): void => {};

describe("group state chips", () => {
  it("says Rebalancing for both rebalancing states", () => {
    expect(stateChip("PREPARING_REBALANCE").label).toBe("Rebalancing");
    expect(stateChip("COMPLETING_REBALANCE").label).toBe("Rebalancing");
  });

  it("keeps Empty neutral, because a batch job with no members is not a problem", () => {
    expect(stateChip("EMPTY").tone).toBe("neutral");
  });

  it("draws a state that could not be read as a dash, never as the word Unknown", () => {
    // Kafka's own UNKNOWN is a fact the coordinator reported; a dash is KUI failing to ask.
    expect(stateChip("UNKNOWN").label).toBe("Unknown");
    expect(UNREADABLE_STATE_CHIP.label).toBe("—");
  });
});

describe("lag levels", () => {
  it("puts the screenshot's own figures either side of the boundary", () => {
    expect(lagLevel(333)).toBe("normal");
    expect(lagLevel(3_861)).toBe("warning");
  });

  it("treats the threshold itself as still ordinary", () => {
    expect(lagLevel(LAG_WARN_ABOVE)).toBe("normal");
    expect(lagLevel(LAG_WARN_ABOVE + 1)).toBe("warning");
  });
});

describe("the voice", () => {
  it("keeps the aside only while everything is healthy", () => {
    expect(groupsVoice({ kind: "healthy", total: 14, rebalancing: 1 })).toBe(
      "14 groups. One is rebalancing again. We don't judge.",
    );
    expect(groupsVoice({ kind: "lagging", total: 14, behind: 2 })).not.toContain("judge");
    expect(groupsVoice({ kind: "unavailable" })).toBe("Consumer group data is unavailable.");
  });

  it("describes a lagging page as lagging even while something is also rebalancing", () => {
    // Ordering is the rule: the operator has to act on the lag, not on the rebalance.
    expect(healthOf(SAMPLE_GROUPS, 0).kind).toBe("lagging");
  });

  it("reports missing coordinators ahead of everything else, because the rows are then incomplete", () => {
    expect(healthOf(SAMPLE_GROUPS, 2).kind).toBe("incomplete");
  });
});

describe("the group list", () => {
  it("draws every row from screenshot 04 with its state and its lag", async () => {
    const { container, dispose } = mount(() => <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `/g/${id}`} />);
    await flush();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(6);
    expect(container.textContent).toContain("payments-processor");
    expect(container.textContent).toContain("Rebalancing");
    // The cell carries the figure plus the visually-hidden "(high lag)" a screen reader hears.
    expect(container.querySelector('[data-testid="group-clickstream-etl-lag"]')?.textContent).toContain("3,861");
    dispose();
  });

  it("gives a group's name a real href, so copy-link and open-in-new-tab work", async () => {
    const { container, dispose } = mount(() => <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `/g/${id}`} />);
    await flush();
    const link = container.querySelector<HTMLAnchorElement>(".kui-cg-name__link");
    expect(link?.getAttribute("href")).toBe("/g/payments-processor");
    dispose();
  });

  it("draws an unreadable lag as a dash and a caught-up group as a zero", async () => {
    const { container, dispose } = mount(() => <GroupList rows={DEGRADED_GROUPS} hrefFor={(id) => `/g/${id}`} />);
    await flush();
    // The row whose lag could not be computed has no figure at all, only the dash and its reason.
    expect(container.querySelector('[data-testid="group-unreadable-lag-lag"]')).toBeNull();
    const text = container.textContent ?? "";
    expect(text).toContain("—");
    // And a zero is still a zero somewhere on the healthy fixture, not a dash.
    const zero = mount(() => <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `/g/${id}`} />);
    await flush();
    expect(zero.container.querySelector('[data-testid="group-payments-processor-lag"]')?.textContent).toBe("0");
    zero.dispose();
    dispose();
  });

  it("says something different when a filter matched nothing than when there is nothing", async () => {
    const filtered = mount(() => (
      <GroupList rows={[]} hrefFor={() => "#"} failure={{ kind: "filtered", term: "payments", onClear: noop }} />
    ));
    await flush();
    expect(filtered.container.textContent).toContain("Nothing matched payments.");
    expect(filtered.container.textContent).toContain("Clear filter");
    filtered.dispose();

    const empty = mount(() => <GroupList rows={[]} hrefFor={() => "#"} />);
    await flush();
    expect(empty.container.textContent).toContain("No consumer groups yet.");
    expect(empty.container.textContent).not.toContain("Clear filter");
    empty.dispose();
  });

  it("keeps a frame, a code and a retry when the request failed", async () => {
    const { container, dispose } = mount(() => (
      <GroupList
        rows={[]}
        hrefFor={() => "#"}
        failure={{ kind: "unavailable", message: "Consumer group data is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop }}
      />
    ));
    await flush();
    expect(container.textContent).toContain("UPSTREAM_UNAVAILABLE");
    expect(container.textContent).toContain("Retry");
    // The frame survives: the card still names itself.
    expect(container.querySelector('[data-testid="consumer-groups-card"]')).not.toBeNull();
    dispose();
  });

  it("drops COORDINATOR and TOPICS in a narrow window, rather than hiding them", async () => {
    // Dropped from the array and not `display: none`: a hidden column is still in the
    // accessibility tree and still counted in the row, so a screen-reader user would hear a cell
    // nobody can see. jsdom has no `matchMedia`, so the narrow window is stubbed.
    const original = window.matchMedia;
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: (query: string) => ({
        matches: true,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }),
    });
    try {
      const { container, dispose } = mount(() => <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `/g/${id}`} />);
      await flush();
      const headers = [...container.querySelectorAll("th")].map((th) => th.textContent);
      expect(headers).not.toContain("Coordinator");
      expect(headers).not.toContain("Topics");
      // The three that never go.
      expect(headers).toContain("Group id");
      expect(headers).toContain("State");
      expect(headers).toContain("Lag");
      dispose();
    } finally {
      if (original === undefined) Reflect.deleteProperty(window, "matchMedia");
      else Object.defineProperty(window, "matchMedia", { configurable: true, value: original });
    }
  });

  it("has no axe violations", async () => {
    const { container, dispose } = mount(() => <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `/g/${id}`} onOpen={noop} />);
    await flush();
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

describe("the group detail page", () => {
  it("carries no voice line, because it is a page about one object", async () => {
    const { container, dispose } = mount(() => (
      <GroupDetail group={SAMPLE_GROUP_DETAIL} listHref="/groups" reset={{ plan: async () => ({ ok: false, problem: "no" }), apply: async () => ({ ok: false, problem: "no" }) }} />
    ));
    await flush();
    expect(container.querySelector(".kui-page-head__voice")).toBeNull();
    dispose();
  });

  it("prints a stalled commit rate as a word rather than as a zero beside a large lag", async () => {
    const { container, dispose } = mount(() => (
      <GroupDetail
        group={{ ...SAMPLE_GROUP_DETAIL, pace: 0 }}
        listHref="/groups"
        reset={{ plan: async () => ({ ok: false, problem: "no" }), apply: async () => ({ ok: false, problem: "no" }) }}
      />
    ));
    await flush();
    expect(container.querySelector('[data-testid="group-pace"]')?.textContent).toBe("Stalled");
    dispose();
  });

  it("drops the static-id column when no member has one", async () => {
    const { container, dispose } = mount(() => (
      <GroupDetail group={SAMPLE_GROUP_DETAIL} listHref="/groups" reset={{ plan: async () => ({ ok: false, problem: "no" }), apply: async () => ({ ok: false, problem: "no" }) }} />
    ));
    await flush();
    const headers = [...container.querySelectorAll('[data-testid="group-members-table"] th')].map((th) => th.textContent);
    expect(headers).not.toContain("Static id");
    dispose();
  });

  it("refuses to offer delete while the group still has members, and says why", async () => {
    const { container, dispose } = mount(() => (
      <GroupDetail
        group={SAMPLE_GROUP_DETAIL}
        listHref="/groups"
        onDelete={noop}
        reset={{ plan: async () => ({ ok: false, problem: "no" }), apply: async () => ({ ok: false, problem: "no" }) }}
      />
    ));
    await flush();
    // `aria-disabled`, not `disabled`: the button stays focusable so a keyboard user can reach it
    // and read the reason, which a `disabled` element does not let them do.
    const button = container.querySelector<HTMLButtonElement>(".kui-page-head__actions button");
    expect(button?.getAttribute("aria-disabled")).toBe("true");
    // The reason reaches the operator through the button's tooltip, which opens on focus as well
    // as on hover — a disabled control with no reason is worse than no control. The bubble is
    // portalled to `document.body`, so it is not inside the mounted container.
    button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
    await flush();
    expect(document.body.querySelector('[role="tooltip"]')?.textContent).toContain("Stop its consumers first.");
    dispose();
  });

  it("computes a partition's lag only when both offsets are there", () => {
    expect(partitionLag({ topic: "t", partition: 0, committed: 10, endOffset: 25, memberId: null })).toBe(15);
    expect(partitionLag({ topic: "t", partition: 0, committed: null, endOffset: 25, memberId: null })).toBeNull();
    expect(partitionLag({ topic: "t", partition: 0, committed: 10, endOffset: null, memberId: null })).toBeNull();
    // A commit read a moment before the end offset can cross it; a negative lag reads as a bug.
    expect(partitionLag({ topic: "t", partition: 0, committed: 30, endOffset: 25, memberId: null })).toBe(0);
  });

  it("lists the group's topics with their partitions, sorted", () => {
    expect(subscriptions(SAMPLE_GROUP_DETAIL)).toEqual([
      { topic: "clickstream", partitions: [0, 1, 2, 3] },
      { topic: "sessions", partitions: [0] },
    ]);
  });
});

describe("the reset form's refusals", () => {
  const partitions = [0, 1, 2];

  it("refuses an empty topic with a sentence rather than with silence", () => {
    const answer = resetRequestOf(EMPTY_RESET_FORM, partitions);
    expect(answer.ok).toBe(false);
    expect(answer.ok === false && answer.problem).toContain("Choose a topic");
  });

  it("refuses a shift of zero, which would move nothing while looking like an action", () => {
    const answer = resetRequestOf({ ...EMPTY_RESET_FORM, topic: "t", target: "SHIFT_BY", shiftBy: "0" }, partitions);
    expect(answer.ok).toBe(false);
  });

  it("accepts a negative shift, because rewinding is the common case", () => {
    const answer = resetRequestOf({ ...EMPTY_RESET_FORM, topic: "t", target: "SHIFT_BY", shiftBy: "-4200" }, partitions);
    expect(answer.ok && answer.request.shiftBy).toBe(-4_200);
  });

  it("refuses a topic with no partitions rather than sending a request that resets nothing", () => {
    const answer = resetRequestOf({ ...EMPTY_RESET_FORM, topic: "t" }, []);
    expect(answer.ok).toBe(false);
  });

  it("names the extra field each target needs, so the form cannot show the wrong one", () => {
    expect(targetOption("EARLIEST").parameter).toBeNull();
    expect(targetOption("TIMESTAMP").parameter).toBe("timestamp");
    expect(targetOption("TIMESTAMP").hint).toContain("moves to its end");
  });

  it("counts the records a plan moves, ignoring direction", () => {
    expect(recordsMoved(SAMPLE_PLAN)).toBe(4_998 + 0 + 11_300);
  });

  it("says how many partitions are in scope, and says so plainly when there are none", () => {
    expect(scopeSentence(0)).toContain("no offsets");
    expect(scopeSentence(1)).toBe("1 partition will be moved.");
    expect(scopeSentence(12)).toBe("12 partitions will be moved.");
  });
});

describe("the reset wizard", () => {
  const topics = [{ topic: "clickstream", partitions: [0, 1, 2, 3] }];

  async function openWizard(overrides: Partial<Parameters<typeof ResetWizard>[0]> = {}) {
    const mounted = mount(() => (
      <ResetWizard
        topics={topics}
        plan={async () => ({ ok: true, plan: SAMPLE_PLAN })}
        apply={async () => ({ ok: true, receipt: SAMPLE_PLAN })}
        formatTime={() => "09:19"}
        {...overrides}
      />
    ));
    await flush();
    click(mounted.container, "button");
    await flush();
    return mounted;
  }

  it("shows the plan when Preview is pressed — the defect this rewrite exists to fix", async () => {
    const { container, dispose } = await openWizard();
    expect(container.querySelector('[data-testid="group-reset-plan"]')).toBeNull();

    clickText(container, "Preview the plan");
    await flush();
    // Two frames: the promise resolves on a microtask, then Solid flushes the write.
    await flush();

    const plan = container.querySelector('[data-testid="group-reset-plan-table"]');
    expect(plan).not.toBeNull();
    expect(container.querySelector('[data-testid="group-reset-summary"]')?.textContent).toContain("4 partitions move");
    expect(container.querySelector('[data-testid="group-reset-warnings"]')?.textContent).toContain("KIP-122");
    dispose();
  });

  it("says why, rather than nothing, when the form cannot be turned into a request", async () => {
    const { container, dispose } = await openWizard({ topics: [] });
    clickText(container, "Preview the plan");
    await flush();
    const problem = container.querySelector('[data-testid="group-reset-problem"]');
    expect(problem?.textContent).toContain("Choose a topic");
    expect(problem?.getAttribute("role")).toBe("alert");
    dispose();
  });

  it("goes back to the form with the reason when the server refuses to plan", async () => {
    const { container, dispose } = await openWizard({ plan: async () => ({ ok: false, problem: "The cluster is read-only." }) });
    clickText(container, "Preview the plan");
    await flush();
    await flush();
    expect(container.querySelector('[data-testid="group-reset-problem"]')?.textContent).toBe("The cluster is read-only.");
    expect(container.querySelector('[data-testid="group-reset-form"]')).not.toBeNull();
    dispose();
  });

  it("offers nothing to apply when the plan changes nothing", async () => {
    const { container, dispose } = await openWizard({ plan: async () => ({ ok: true, plan: NO_OP_PLAN }) });
    clickText(container, "Preview the plan");
    await flush();
    await flush();
    expect(container.textContent).toContain("Every partition is already where this reset would put it.");
    expect(hasText(container, "Apply this plan")).toBe(false);
    dispose();
  });

  it("stays on the plan, and does not re-plan, when the token has expired", async () => {
    const { container, dispose } = await openWizard({ apply: async () => ({ ok: false, problem: "That plan has expired." }) });
    clickText(container, "Preview the plan");
    await flush();
    await flush();
    clickText(container, "Apply this plan");
    await flush();
    await flush();
    expect(container.querySelector('[data-testid="group-reset-problem"]')?.textContent).toBe("That plan has expired.");
    expect(container.querySelector('[data-testid="group-reset-plan-table"]')).not.toBeNull();
    dispose();
  });

  it("shows what the broker wrote, not what the browser asked for", async () => {
    const { container, dispose } = await openWizard();
    clickText(container, "Preview the plan");
    await flush();
    await flush();
    clickText(container, "Apply this plan");
    await flush();
    await flush();
    expect(container.querySelector('[data-testid="group-reset-receipt-table"]')).not.toBeNull();
    dispose();
  });

  it("does not open at all for somebody who may not reset offsets, and says why", async () => {
    const mounted = mount(() => (
      <ResetWizard
        topics={topics}
        permitted={false}
        refusal="You do not have permission to reset offsets on this cluster."
        plan={async () => ({ ok: true, plan: SAMPLE_PLAN })}
        apply={async () => ({ ok: true, receipt: SAMPLE_PLAN })}
      />
    ));
    await flush();
    const button = mounted.container.querySelector<HTMLButtonElement>("button");
    expect(button?.getAttribute("aria-disabled")).toBe("true");
    button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
    await flush();
    expect(document.body.querySelector('[role="tooltip"]')?.textContent).toContain("do not have permission");
    mounted.dispose();
  });

  it("has no axe violations with a plan on screen", async () => {
    const { container, dispose } = await openWizard();
    clickText(container, "Preview the plan");
    await flush();
    await flush();
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

function click(root: ParentNode, selector: string): void {
  root.querySelector<HTMLElement>(selector)?.click();
}

function buttons(root: ParentNode): HTMLButtonElement[] {
  return [...root.querySelectorAll<HTMLButtonElement>("button")];
}

function clickText(root: ParentNode, text: string): void {
  const button = buttons(root).find((one) => (one.textContent ?? "").includes(text));
  if (button === undefined) throw new Error(`No button reading "${text}". Buttons: ${buttons(root).map((b) => b.textContent).join(" | ")}`);
  button.click();
}

function hasText(root: ParentNode, text: string): boolean {
  return buttons(root).some((one) => (one.textContent ?? "").includes(text));
}
