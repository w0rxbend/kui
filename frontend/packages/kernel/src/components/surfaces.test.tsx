/**
 * Rendering, interaction and accessibility for the surfaces: the card, the stat card, the stale
 * badge, the dialog, the confirmation, the drawer, the toast and the banner.
 *
 * Every case below is attached either to a statement in `.agent/design/SPEC.md` or to a defect this
 * project has already paid for. Nothing here asserts a colour, a size or a position: jsdom has no
 * layout engine, so a test that did would be asserting numbers jsdom invented. Those are judged by
 * looking at the stories against the design screenshots.
 */

import { createSignal, flush } from "solid-js";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { Banner } from "./Banner.jsx";
import { Card } from "./Card.jsx";
import { ConfirmDialog, Dialog } from "./Dialog.jsx";
import { Drawer } from "./Drawer.jsx";
import { StaleBadge, relativeAge } from "./StaleBadge.jsx";
import { StatCard } from "./StatCard.jsx";
import { ToastRegion, clearToasts, dismissToast, notify, toasts, MAX_VISIBLE_TOASTS } from "./Toast.jsx";
import { describeViolations, findViolations, mount } from "./testing.js";

/** The longest strings the product can be asked to draw. Every surface gets one of these. */
const LONG_TOPIC =
  "orders.payments.reconciliation.v2.eu-central-1.high-throughput.retry.dead-letter.compacted";
const LONG_SENTENCE =
  "The consumer service did not answer within the gateway's upstream timeout, so the figures on " +
  "this panel are the last ones it returned, and the count of groups needing attention in the " +
  "navigation drawer has been withheld rather than shown as zero.";

/** A real click on a real element. See the note at the `Dialog` veil test. */
function clickOn(element: HTMLElement): void {
  element.dispatchEvent(new MouseEvent("click", { bubbles: true }));
}

afterEach(() => {
  clearToasts();
  vi.useRealTimers();
});

/* ------------------------------------------------------------------------------------------- */

describe("Card", () => {
  it("keeps its title and its frame in every failing state", () => {
    for (const state of ["loading", "empty", "filtered", "unavailable", "forbidden"] as const) {
      const { container, dispose } = mount(() => (
        <Card title="Consumer groups" state={state} message="Consumer group data is unavailable.">
          <p>content</p>
        </Card>
      ));
      // The frame never disappears: a page shows three healthy panels and one that failed at the
      // same time, and the failed one still has to say what it is.
      expect(container.querySelector(".kui-panel__title")!.textContent).toContain("Consumer groups");
      expect(container.querySelector(".kui-panel")).not.toBeNull();
      dispose();
    }
  });

  it("does not render its children while it is showing a state instead of content", () => {
    const { container, dispose } = mount(() => (
      <Card title="Throughput" state="unavailable" message="Metrics are unavailable.">
        <p data-testid="content">the real content</p>
      </Card>
    ));
    expect(container.querySelector('[data-testid="content"]')).toBeNull();
    dispose();
  });

  it("says something different for 'nothing yet' and for 'your filter matched nothing'", () => {
    const empty = mount(() => (
      <Card title="Topics" state="empty" message="No topics yet." description="Create one." />
    ));
    const filtered = mount(() => (
      <Card title="Topics" state="filtered" message="Nothing matched `payments`." />
    ));
    // Substituting one for the other sends somebody looking for a problem that is not there.
    expect(empty.container.textContent).toContain("No topics yet.");
    expect(filtered.container.textContent).toContain("Nothing matched");
    expect(filtered.container.textContent).not.toContain("No topics yet.");
    empty.dispose();
    filtered.dispose();
  });

  it("keeps the failure code verbatim so it can be quoted", () => {
    const { container, dispose } = mount(() => (
      <Card
        title="Consumer groups"
        state="unavailable"
        message="Consumer group data is unavailable."
        description="The consumer service is not responding."
        code="UPSTREAM_UNAVAILABLE"
      />
    ));
    // The sentence is what the operator can act on; the code is what whoever they escalate to
    // searches for. Neither is allowed to replace the other.
    expect(container.textContent).toContain("The consumer service is not responding.");
    expect(container.querySelector(".kui-empty-state__code")!.textContent).toBe("UPSTREAM_UNAVAILABLE");
    dispose();
  });

  it("marks itself busy while loading, so the skeletons are not read as the content", () => {
    const { container, dispose } = mount(() => <Card title="Broker health" state="loading" />);
    expect(container.querySelector(".kui-panel")!.getAttribute("aria-busy")).toBe("true");
    expect(container.querySelectorAll(".kui-skeleton").length).toBeGreaterThan(0);
    dispose();
  });

  it("keeps stale content on screen under a badge rather than blanking it", () => {
    const { container, dispose } = mount(() => (
      <Card
        title="Consumer lag"
        stale={{ asOf: new Date(Date.now() - 4 * 60_000), detail: "the metrics service is not answering", code: "UPSTREAM_UNAVAILABLE" }}
      >
        <p data-testid="content">4,212</p>
      </Card>
    ));
    // The last known value is more useful than nothing, provided the interface says it is the last
    // known value.
    expect(container.querySelector('[data-testid="content"]')!.textContent).toBe("4,212");
    expect(container.querySelector(".kui-stale-badge")).not.toBeNull();
    expect(container.querySelector(".kui-panel__body")!.className).toContain("kui-panel__body--stale");
    dispose();
  });

  it("has no axe violations in any state, including with a very long title", async () => {
    for (const state of ["ready", "loading", "empty", "unavailable", "forbidden"] as const) {
      const { container, dispose } = mount(() => (
        <Card title={LONG_TOPIC} state={state} message={LONG_SENTENCE} code="UPSTREAM_UNAVAILABLE">
          <p>content</p>
        </Card>
      ));
      const violations = await findViolations(container);
      expect(describeViolations(violations)).toBe("");
      dispose();
    }
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("StatCard", () => {
  it("draws a zero as a zero", () => {
    const { container, dispose } = mount(() => (
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "value", text: "0" }}
        pill={{ text: "all caught up", tone: "success" }}
      />
    ));
    // A cluster with no lag is good news, and the good news is a digit.
    expect(container.querySelector(".kui-stat__value")!.textContent).toBe("0");
    expect(container.querySelector(".kui-stat__unknown")).toBeNull();
    dispose();
  });

  it("draws an unknown as a dash and never as a zero", () => {
    const { container, dispose } = mount(() => (
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "unknown" }}
        pill={{ text: "metrics unavailable", tone: "neutral" }}
      />
    ));
    // Printing 0 for an unknown is the most reassuring rendering of the least reassuring state.
    expect(container.querySelector(".kui-stat__unknown")!.textContent).toBe("—");
    expect(container.querySelector(".kui-stat__value")).toBeNull();
    expect(container.textContent).not.toContain("0");
    dispose();
  });

  it("draws a skeleton and marks itself busy while the figure is pending", () => {
    const { container, dispose } = mount(() => (
      <StatCard label="TOPICS" icon="topics" tone="primary" figure={{ kind: "pending" }} />
    ));
    expect(container.querySelector(".kui-stat")!.getAttribute("aria-busy")).toBe("true");
    expect(container.querySelector(".kui-skeleton")).not.toBeNull();
    // A pending value must not look like an absent one.
    expect(container.querySelector(".kui-stat__unknown")).toBeNull();
    dispose();
  });

  it("keeps the unit beside the figure rather than inside it", () => {
    const { container, dispose } = mount(() => (
      <StatCard label="PRODUCTION" icon="lag" tone="accent" figure={{ kind: "value", text: "86.4", unit: "MB/s" }} />
    ));
    expect(container.querySelector(".kui-stat__value")!.textContent).toBe("86.4");
    expect(container.querySelector(".kui-stat__unit")!.textContent).toBe("MB/s");
    dispose();
  });

  it("has no axe violations at the extremes", async () => {
    const { container, dispose } = mount(() => (
      <StatCard
        label="PARTITIONS UNDER MINIMUM IN-SYNC REPLICAS"
        icon="warning"
        tone="danger"
        figure={{ kind: "value", text: "18,446,744,073,709,551,615" }}
        pill={{ text: LONG_SENTENCE, tone: "danger", icon: "warning" }}
        href="/brokers"
      />
    ));
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("StaleBadge", () => {
  it("rounds to whole units so it does not change every second", () => {
    const now = new Date("2026-09-05T12:00:00Z");
    expect(relativeAge(new Date("2026-09-05T11:59:58Z"), now)).toBe("just now");
    expect(relativeAge(new Date("2026-09-05T11:59:30Z"), now)).toBe("30s ago");
    expect(relativeAge(new Date("2026-09-05T11:56:00Z"), now)).toBe("4m ago");
    expect(relativeAge(new Date("2026-09-05T09:00:00Z"), now)).toBe("3h ago");
    expect(relativeAge(new Date("2026-09-02T12:00:00Z"), now)).toBe("3d ago");
  });

  it("carries the sentence and the code, and puts the absolute time in a title", () => {
    const asOf = new Date("2026-09-05T11:56:00Z");
    const { container, dispose } = mount(() => (
      <StaleBadge
        asOf={asOf}
        now={new Date("2026-09-05T12:00:00Z")}
        detail="the metrics service is not answering"
        code="UPSTREAM_UNAVAILABLE"
      />
    ));
    expect(container.textContent).toContain("the metrics service is not answering");
    expect(container.querySelector(".kui-stale-badge__code")!.textContent).toBe("UPSTREAM_UNAVAILABLE");
    const time = container.querySelector("time")!;
    expect(time.getAttribute("datetime")).toBe(asOf.toISOString());
    expect(time.getAttribute("title")).toBe(asOf.toLocaleString());
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Dialog", () => {
  it("is absent from the document while closed, not merely hidden", () => {
    const { dispose } = mount(() => (
      <Dialog open={false} onClose={() => {}} title="Create topic">
        <input data-testid="field" />
      </Dialog>
    ));
    // Hidden is not enough: a hidden form is still submittable and still found by find-in-page.
    expect(document.querySelector('[data-testid="field"]')).toBeNull();
    dispose();
  });

  it("moves focus inside on open and gives it back on close", async () => {
    const [open, setOpen] = createSignal(true, { ownedWrite: true });
    const opener = document.createElement("button");
    document.body.appendChild(opener);
    opener.focus();

    const { dispose } = mount(() => (
      <Dialog open={open()} onClose={() => setOpen(false)} title="Create topic">
        <input data-testid="field" />
      </Dialog>
    ));
    await Promise.resolve();
    // The surface itself, not its first control: a screen reader then reads the dialog's name and
    // description before anything else, instead of announcing "Close button" and leaving the
    // reader to go looking for the question.
    expect(document.activeElement).toBe(document.querySelector('[role="dialog"]'));

    setOpen(false);
    flush();
    await Promise.resolve();
    // Losing focus on close drops the user at the top of the document, which after a confirmation
    // on row 400 of a table is a small catastrophe.
    expect(document.activeElement).toBe(opener);

    opener.remove();
    dispose();
  });

  it("wraps Tab at both ends so focus cannot walk out onto the page behind", async () => {
    const user = userEvent.setup();
    const { dispose } = mount(() => (
      <Dialog open onClose={() => {}} title="Create topic" actions={<button type="button">Create</button>}>
        <input data-testid="field" />
      </Dialog>
    ));
    await Promise.resolve();

    const stops = Array.from(
      (document.querySelector('[role="dialog"]') as HTMLElement).querySelectorAll("button, input"),
    ) as HTMLElement[];
    const first = stops[0] as HTMLElement;
    const last = stops[stops.length - 1] as HTMLElement;

    last.focus();
    await user.tab();
    // Past the last control, back to the first — never out into a page the user cannot see and
    // cannot get back from.
    expect(document.activeElement).toBe(first);

    await user.tab({ shift: true });
    expect(document.activeElement).toBe(last);
    dispose();
  });

  it("puts focus where the caller asks", async () => {
    let field: HTMLInputElement | undefined;
    const { dispose } = mount(() => (
      <Dialog open onClose={() => {}} title="Create topic" initialFocus={() => field ?? null}>
        <input data-testid="field" ref={(element: HTMLInputElement) => (field = element)} />
      </Dialog>
    ));
    await Promise.resolve();
    expect(document.activeElement).toBe(document.querySelector('[data-testid="field"]'));
    dispose();
  });

  it("closes on Escape", async () => {
    const user = userEvent.setup();
    let closed = 0;
    const { dispose } = mount(() => (
      <Dialog open onClose={() => (closed += 1)} title="Create topic">
        <input data-testid="field" />
      </Dialog>
    ));
    await Promise.resolve();
    await user.keyboard("{Escape}");
    expect(closed).toBe(1);
    dispose();
  });

  it("closes when the veil itself is clicked, and not when the surface is", async () => {
    const user = userEvent.setup();
    let closed = 0;
    const { dispose } = mount(() => (
      <Dialog open onClose={() => (closed += 1)} title="Create topic">
        <p data-testid="body">body</p>
      </Dialog>
    ));
    await Promise.resolve();
    await user.click(document.querySelector('[data-testid="body"]') as HTMLElement);
    expect(closed).toBe(0);
    // Dispatched natively rather than through `userEvent`, which decides where a click lands from
    // layout — and jsdom has none, so it never reaches an element that covers the window.
    clickOn(document.querySelector(".kui-modal-scrim") as HTMLElement);
    expect(closed).toBe(1);
    dispose();
  });

  it("names itself from its title and describes itself from its description", async () => {
    const { dispose } = mount(() => (
      <Dialog open onClose={() => {}} title="Create topic" description="Topics cannot be renamed.">
        <input />
      </Dialog>
    ));
    await Promise.resolve();
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    const labelled = document.getElementById(dialog.getAttribute("aria-labelledby") as string);
    const described = document.getElementById(dialog.getAttribute("aria-describedby") as string);
    expect(labelled!.textContent).toBe("Create topic");
    expect(described!.textContent).toBe("Topics cannot be renamed.");
    dispose();
  });

  it("has no axe violations", async () => {
    const { dispose } = mount(() => (
      <Dialog open onClose={() => {}} title={LONG_TOPIC} description={LONG_SENTENCE} actions={<button type="button">OK</button>}>
        <p>{LONG_SENTENCE}</p>
      </Dialog>
    ));
    await Promise.resolve();
    const violations = await findViolations(document.querySelector(".kui-modal-scrim") as HTMLElement);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("ConfirmDialog", () => {
  const props = {
    title: `Purge ${LONG_TOPIC}?`,
    consequence: "This deletes 1,536 partitions' worth of records — about 128 GB. It cannot be undone.",
    confirmLabel: "Purge",
    confirmIcon: "trash",
  } as const;

  it("opens with focus on Cancel, not on the button that destroys something", async () => {
    const { dispose } = mount(() => (
      <ConfirmDialog open onClose={() => {}} onConfirm={() => {}} {...props} />
    ));
    await Promise.resolve();
    // The keystroke that opened the dialog is often still going.
    expect((document.activeElement as HTMLElement).textContent).toContain("Cancel");
    dispose();
  });

  it("refuses to confirm until the object's name is typed exactly", async () => {
    const user = userEvent.setup();
    let confirmed = 0;
    const { dispose } = mount(() => (
      <ConfirmDialog open onClose={() => {}} onConfirm={() => (confirmed += 1)} {...props} typeToConfirm="orders.payments.v2" />
    ));
    await Promise.resolve();

    const confirm = () =>
      Array.from(document.querySelectorAll("button")).find((b) => b.textContent?.includes("Purge")) as HTMLButtonElement;

    expect(confirm().getAttribute("aria-disabled")).toBe("true");
    await user.click(confirm());
    expect(confirmed).toBe(0);

    const input = document.querySelector(".kui-confirm__input") as HTMLInputElement;
    await user.type(input, "orders.payments.v1");
    flush();
    expect(confirm().getAttribute("aria-disabled")).toBe("true");

    await user.clear(input);
    await user.type(input, "orders.payments.v2");
    flush();
    await Promise.resolve();
    expect(confirm().getAttribute("aria-disabled")).toBeNull();
    await user.click(confirm());
    expect(confirmed).toBe(1);
    dispose();
  });

  it("keeps the blocked confirm button reachable and explained", async () => {
    const { dispose } = mount(() => (
      <ConfirmDialog open onClose={() => {}} onConfirm={() => {}} {...props} typeToConfirm="orders.payments.v2" />
    ));
    await Promise.resolve();
    const confirm = Array.from(document.querySelectorAll("button")).find((b) =>
      b.textContent?.includes("Purge"),
    ) as HTMLButtonElement;
    // `aria-disabled` rather than `disabled`, so a keyboard user can reach it and hear the reason.
    expect(confirm.hasAttribute("disabled")).toBe(false);
    expect(confirm.getAttribute("aria-disabled")).toBe("true");
    dispose();
  });

  it("draws the destructive action with the danger silhouette and a glyph", async () => {
    const { dispose } = mount(() => <ConfirmDialog open onClose={() => {}} onConfirm={() => {}} {...props} />);
    await Promise.resolve();
    const confirm = Array.from(document.querySelectorAll("button")).find((b) =>
      b.textContent?.includes("Purge"),
    ) as HTMLButtonElement;
    // The outline alone would be a colour-only distinction, so the glyph is required as well.
    expect(confirm.className).toContain("kui-btn--danger");
    expect(confirm.querySelector("svg")).not.toBeNull();
    dispose();
  });

  it("stays open when the mutation fails, and announces the failure with its code", async () => {
    const { dispose } = mount(() => (
      <ConfirmDialog
        open
        onClose={() => {}}
        onConfirm={() => {}}
        {...props}
        error={{ message: "The topic service refused the purge.", code: "MUTATION_REJECTED" }}
      />
    ));
    await Promise.resolve();
    const alert = document.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain("The topic service refused the purge.");
    expect(alert.textContent).toContain("MUTATION_REJECTED");
    // Throwing the dialog away on failure makes the operator reconstruct what they were doing in
    // order to find out that it did not happen.
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    dispose();
  });

  it("has no axe violations while blocked", async () => {
    const { dispose } = mount(() => (
      <ConfirmDialog open onClose={() => {}} onConfirm={() => {}} {...props} typeToConfirm="orders.payments.v2" />
    ));
    await Promise.resolve();
    const violations = await findViolations(document.querySelector(".kui-modal-scrim") as HTMLElement);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Drawer", () => {
  it("ignores a click on the veil, because it usually holds something somebody typed", async () => {
    const user = userEvent.setup();
    let closed = 0;
    const { dispose } = mount(() => (
      <Drawer open onClose={() => (closed += 1)} title="Produce to orders.payments.v2">
        <textarea data-testid="editor" />
      </Drawer>
    ));
    await Promise.resolve();
    clickOn(document.querySelector(".kui-sheet-scrim") as HTMLElement);
    expect(closed).toBe(0);
    // Escape is still a deliberate act and still closes it.
    await user.keyboard("{Escape}");
    expect(closed).toBe(1);
    dispose();
  });

  it("does not rebuild the form when something else in the drawer changes", async () => {
    const user = userEvent.setup();
    const [serdes, setSerdes] = createSignal<readonly string[]>([], { ownedWrite: true });

    const { dispose } = mount(() => (
      <Drawer
        open
        onClose={() => {}}
        title="Produce to orders.payments.v2"
        footer={<span data-testid="serde-count">{serdes().length} serdes</span>}
      >
        {/* The async region is around the select only, never around the editor. */}
        <select data-testid="serde">
          {serdes().map((serde) => (
            <option>{serde}</option>
          ))}
        </select>
        <textarea data-testid="editor" />
      </Drawer>
    ));
    await Promise.resolve();

    const editor = document.querySelector('[data-testid="editor"]') as HTMLTextAreaElement;
    // The braces are doubled because userEvent reads `{` as the start of a key descriptor.
    await user.type(editor, '{{"id":42}');

    // The serdes arrive after the drawer opened. This is the exact moment the shipped defect threw
    // the payload away.
    setSerdes(["String", "JSON", "Avro"]);
    flush();

    const editorAfter = document.querySelector('[data-testid="editor"]') as HTMLTextAreaElement;
    expect(editorAfter).toBe(editor);
    expect(editorAfter.value).toBe('{"id":42}');
    expect(document.querySelector('[data-testid="serde-count"]')!.textContent).toBe("3 serdes");
    dispose();
  });

  it("has no axe violations", async () => {
    const { dispose } = mount(() => (
      <Drawer
        open
        onClose={() => {}}
        title={`Produce to ${LONG_TOPIC}`}
        description={LONG_SENTENCE}
        error={{ message: "The broker rejected the record.", code: "RECORD_TOO_LARGE" }}
        footer={<button type="button">Produce</button>}
      >
        <label for="v">Value</label>
        <textarea id="v" />
      </Drawer>
    ));
    await Promise.resolve();
    const violations = await findViolations(document.querySelector(".kui-sheet-scrim") as HTMLElement);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Toast", () => {
  it("never lets a failure disappear on a timer", () => {
    notify("Could not create topic", { tone: "danger", durationMs: 1000, code: "UPSTREAM_UNAVAILABLE" });
    // Solid 2 batches writes onto a microtask, so the store is not readable until it is drained.
    flush();
    // The caller asked for a second. A failure carries a code the reader may need to write down,
    // so the request is overruled here rather than trusted at every call site.
    expect(toasts()[0]!.durationMs).toBeNull();
  });

  it("dismisses a confirmation once its time is up, and not before", () => {
    vi.useFakeTimers();
    const { dispose } = mount(() => <ToastRegion />);
    notify("Topic created", { durationMs: 1000 });
    flush();
    expect(toasts()).toHaveLength(1);

    vi.advanceTimersByTime(500);
    expect(toasts()).toHaveLength(1);
    vi.advanceTimersByTime(600);
    flush();
    expect(toasts()).toHaveLength(0);
    dispose();
  });

  it("pauses the timer while the pointer is on the stack", () => {
    vi.useFakeTimers();
    const { dispose } = mount(() => <ToastRegion />);
    notify("Topic created", { durationMs: 1000 });
    flush();

    const stack = document.querySelector(".kui-notice-stack") as HTMLElement;
    stack.dispatchEvent(new MouseEvent("mouseenter", { bubbles: false }));
    flush();
    vi.advanceTimersByTime(5000);
    flush();
    // Somebody reaching for the dismiss button must not have the toast vanish out from under them.
    expect(toasts()).toHaveLength(1);

    stack.dispatchEvent(new MouseEvent("mouseleave", { bubbles: false }));
    flush();
    vi.advanceTimersByTime(1100);
    flush();
    expect(toasts()).toHaveLength(0);
    dispose();
  });

  it("keeps the live region in the document when there is nothing to say", () => {
    const { container, dispose } = mount(() => <ToastRegion />);
    // A live region created at the moment it receives its first message is, in several
    // screen-reader and browser combinations, not announced at all.
    const region = container.querySelector(".kui-notice-stack") as HTMLElement;
    expect(region).not.toBeNull();
    expect(region.getAttribute("aria-live")).toBe("polite");
    dispose();
  });

  it("shows a bounded number and says how many more are waiting", () => {
    const { container, dispose } = mount(() => <ToastRegion />);
    for (let i = 0; i < MAX_VISIBLE_TOASTS + 2; i += 1) notify(`Topic ${i} created`, { durationMs: null });
    flush();
    expect(container.querySelectorAll(".kui-notice")).toHaveLength(MAX_VISIBLE_TOASTS);
    expect(container.querySelector(".kui-notice-stack__queued")!.textContent).toBe("2 more");
    dispose();
  });

  it("names what each dismiss button dismisses", () => {
    const { container, dispose } = mount(() => <ToastRegion />);
    notify("Topic created", { durationMs: null });
    flush();
    const dismiss = container.querySelector(".kui-notice__dismiss") as HTMLElement;
    // Three identical "Close" buttons in a stack are three buttons nobody can tell apart.
    expect(dismiss.getAttribute("aria-label")).toBe("Dismiss: Topic created");
    dispose();
  });

  it("keeps both when two are raised in the same tick", () => {
    // Solid 2 batches writes onto a microtask and applies an updater to the last *committed*
    // value, so `setToasts(prev => [...prev, mine])` twice in one tick would have both computed
    // from the empty list and the second would have won. One confirmation would vanish, silently,
    // and only when two things happened at once — which is when it matters most.
    notify("Topic created", { durationMs: null });
    notify("Offsets reset", { durationMs: null });
    flush();
    expect(toasts().map((t) => t.title)).toEqual(["Topic created", "Offsets reset"]);
  });

  it("removes the one asked for and leaves the rest", () => {
    const first = notify("A", { durationMs: null });
    notify("B", { durationMs: null });
    dismissToast(first);
    flush();
    expect(toasts().map((t) => t.title)).toEqual(["B"]);
  });

  it("has no axe violations", async () => {
    const { container, dispose } = mount(() => <ToastRegion />);
    notify("Could not produce the record", {
      tone: "danger",
      message: LONG_SENTENCE,
      code: "RECORD_TOO_LARGE",
      action: { label: "Retry", onClick: () => {} },
    });
    flush();
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Banner", () => {
  it("interrupts for a failure and waits its turn for anything else", () => {
    const danger = mount(() => <Banner tone="danger" message="The cluster is not answering." />);
    const info = mount(() => <Banner tone="info" message="KUI is in read-only mode." />);
    expect(danger.container.querySelector(".kui-banner")!.getAttribute("role")).toBe("alert");
    // Making everything an alert means the first one is the only one anybody hears.
    expect(info.container.querySelector(".kui-banner")!.getAttribute("role")).toBe("status");
    danger.dispose();
    info.dispose();
  });

  it("keeps the code beside the sentence", () => {
    const { container, dispose } = mount(() => (
      <Banner tone="danger" message="The cluster is not answering." code="CLUSTER_UNREACHABLE" />
    ));
    expect(container.querySelector(".kui-banner__code")!.textContent).toBe("CLUSTER_UNREACHABLE");
    dispose();
  });

  it("is only dismissible when it is given a way to be", async () => {
    const user = userEvent.setup();
    let dismissed = 0;
    const fixed = mount(() => <Banner tone="danger" message="The cluster is not answering." />);
    expect(fixed.container.querySelector(".kui-banner__dismiss")).toBeNull();
    fixed.dispose();

    const closable = mount(() => (
      <Banner tone="info" message="KUI is in read-only mode." onDismiss={() => (dismissed += 1)} />
    ));
    await user.click(closable.container.querySelector(".kui-banner__dismiss") as HTMLElement);
    expect(dismissed).toBe(1);
    closable.dispose();
  });

  it("has no axe violations", async () => {
    const { container, dispose } = mount(() => (
      <Banner
        tone="danger"
        message={LONG_SENTENCE}
        code="CLUSTER_UNREACHABLE"
        action={<button type="button">Retry</button>}
        onDismiss={() => {}}
      />
    ));
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});
