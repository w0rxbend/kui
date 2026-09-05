/**
 * Rendering, interaction and accessibility for the control primitives: button, text field, select,
 * checkbox, status pill, icon tile, avatar and tooltip.
 *
 * Every case below is tied either to a statement in `.agent/design/SPEC.md` or to a defect this
 * project has already paid for. Nothing here asserts a colour, a size or a position: jsdom has no
 * layout engine, so a test that did would be asserting a number jsdom invented. Those are judged
 * by looking at the stories against the design screenshots — which is a step, not a substitute,
 * because "correct in the accessibility tree and invisible to everyone else" is a shape of defect
 * this project has shipped three times and no assertion below could have caught.
 *
 * Two Solid 2 rules run through the whole file. `flush()` before asserting, because a setter
 * queues and the DOM catches up on the next microtask. And dispose at the end, because reactive
 * primitives need an owner and leaving them alive leaks listeners into the next case.
 */

import userEvent from "@testing-library/user-event";
import { createSignal, flush } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";

import { Avatar, initialsOf } from "./Avatar.jsx";
import { Button } from "./Button.jsx";
import { Checkbox } from "./Checkbox.jsx";
import { IconTile } from "./IconTile.jsx";
import { Select } from "./Select.jsx";
import { StatusPill } from "./StatusPill.jsx";
import { TextField } from "./TextField.jsx";
import { Tooltip } from "./Tooltip.jsx";
import { describeViolations, findViolations, mount } from "./testing.js";

const disposers: (() => void)[] = [];

function render(component: Parameters<typeof mount>[0]): HTMLElement {
  const mounted = mount(component);
  disposers.push(mounted.dispose);
  return mounted.container;
}

afterEach(() => {
  while (disposers.length > 0) disposers.pop()?.();
  // Tooltips are portalled onto `<body>`, so they outlive their container unless the owner is
  // disposed — which the line above does. This is the assertion that it worked.
  expect(document.querySelectorAll(".kui-tooltip")).toHaveLength(0);
});

async function expectNoViolations(container: HTMLElement): Promise<void> {
  const violations = await findViolations(container);
  expect(describeViolations(violations), describeViolations(violations)).toBe("");
}

/* ------------------------------------------------------------------------------------------- */

describe("Button", () => {
  it("renders its label and calls back when pressed", async () => {
    const onClick = vi.fn();
    const container = render(() => (
      <Button icon="plus" onClick={onClick}>
        Create topic
      </Button>
    ));
    const button = container.querySelector("button");
    expect(button?.textContent).toContain("Create topic");
    await userEvent.click(button!);
    flush();
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("names an icon-only button by the action, and hides the glyph", () => {
    const container = render(() => (
      <Button variant="ghost" icon="bell" iconOnly>
        Notifications, 3 unread
      </Button>
    ));
    const button = container.querySelector("button");
    // The name is real text in the document rather than an `aria-label`, so it survives
    // translation tooling and a text search of the page.
    expect(button?.textContent).toContain("Notifications, 3 unread");
    expect(container.querySelector(".kui-visually-hidden")?.textContent).toBe("Notifications, 3 unread");
    expect(container.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
  });

  /**
   * The reason `aria-disabled` is used rather than the `disabled` attribute. A `disabled` element
   * is skipped by Tab and fires no pointer events, so the explanation of *why* it is disabled is
   * unreachable by keyboard and unreachable by hover — the reason exists and nobody can read it.
   */
  it("keeps a disabled action focusable, refuses the press, and explains itself", async () => {
    const onClick = vi.fn();
    const container = render(() => (
      <Button
        icon="plus"
        disabled
        disabledReason="You do not have permission to create topics on prod-kyiv-01."
        disabledCode="RBAC_DENIED"
        onClick={onClick}
      >
        Create topic
      </Button>
    ));
    const button = container.querySelector("button")!;

    expect(button.getAttribute("aria-disabled")).toBe("true");
    expect(button.disabled).toBe(false);

    button.focus();
    expect(document.activeElement).toBe(button);

    await userEvent.click(button);
    flush();
    expect(onClick).not.toHaveBeenCalled();

    const describedBy = button.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    const tip = document.getElementById(describedBy!);
    expect(tip?.textContent).toContain("You do not have permission to create topics");
    // The sentence tells the operator what happened; the code tells whoever they ask which
    // failure it was. Neither replaces the other.
    expect(tip?.textContent).toContain("RBAC_DENIED");
  });

  it("refuses a second press while busy, and keeps its label", async () => {
    const onClick = vi.fn();
    const container = render(() => (
      <Button icon="send" busy onClick={onClick}>
        Produce message
      </Button>
    ));
    const button = container.querySelector("button")!;
    expect(button.getAttribute("aria-busy")).toBe("true");
    // The label stays: a button that shrinks to a spinner moves whatever is beside it out from
    // under the pointer, and the next thing pressed is not the thing aimed at.
    expect(button.textContent).toContain("Produce message");
    await userEvent.click(button);
    flush();
    expect(onClick).not.toHaveBeenCalled();
  });

  it("gives the destructive variant a silhouette of its own", () => {
    const container = render(() => (
      <>
        <Button variant="primary" icon="plus">Create</Button>
        <Button variant="danger" icon="trash">Purge</Button>
      </>
    ));
    const [primary, danger] = [...container.querySelectorAll("button")];
    expect(primary?.className).toContain("kui-btn--primary");
    expect(danger?.className).toContain("kui-btn--danger");
    // The outline alone would be a colour-only distinction, so the type requires a glyph and the
    // markup must actually contain one.
    expect(danger?.querySelector("svg")).not.toBeNull();
  });

  /**
   * Found by the `LongestLabel` story before it was found here. A confirmation button labelled
   * with a 60-character topic name grew to its label's width and ran off the side of the window,
   * because `white-space: nowrap` says what not to do and nothing about what to do instead. The
   * label now lives in its own element so it can be given an ellipsis; this asserts the element,
   * because the ellipsis itself is a painted result and jsdom paints nothing.
   */
  it("puts its label in an element that can be truncated, without losing the text", () => {
    const long = "Purge orders.payments.v2.reprocessing.deadletter.eu-central-1";
    const container = render(() => (
      <Button variant="danger" icon="trash">
        {long}
      </Button>
    ));
    const label = container.querySelector(".kui-btn__label");
    expect(label?.textContent).toBe(long);
    // The glyph is outside the truncating element: a button whose icon was cut in half to fit a
    // label would be a button with no silhouette, and the silhouette is the whole point of the
    // destructive variant.
    expect(label?.querySelector("svg")).toBeNull();
    expect(container.querySelector("button")?.querySelector("svg")).not.toBeNull();
  });

  it("has no accessibility violations across its variants and states", async () => {
    const container = render(() => (
      <>
        <Button variant="primary" icon="plus">Create topic</Button>
        <Button variant="secondary" icon="send">Produce message</Button>
        <Button variant="danger" icon="trash">Purge</Button>
        <Button variant="ghost" icon="bell" iconOnly>Notifications</Button>
        <Button variant="primary" busy>Saving</Button>
        <Button variant="primary" disabled disabledReason="Not permitted.">Create topic</Button>
      </>
    ));
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("TextField", () => {
  it("associates its label with its input", () => {
    const container = render(() => <TextField label="Filter" />);
    const input = container.querySelector("input")!;
    const label = container.querySelector("label")!;
    expect(label.getAttribute("for")).toBe(input.id);
    expect(input.id).not.toBe("");
  });

  it("reports what was typed", async () => {
    const onInput = vi.fn();
    const container = render(() => <TextField label="Filter" onInput={onInput} />);
    await userEvent.type(container.querySelector("input")!, "pay");
    flush();
    expect(onInput).toHaveBeenCalledTimes(3);
    expect(onInput.mock.calls.at(-1)?.[0]).toBe("pay");
  });

  /**
   * A drawer must not rebuild while somebody is typing in it. The component's half of that rule is
   * that changing the value never replaces the element — so the caret, the selection and the IME
   * composition all survive. This asserts element identity, which is the thing that would break.
   */
  it("does not replace the input element when its value changes", async () => {
    const [value, setValue] = createSignal("a");
    const container = render(() => <TextField label="Filter" value={value()} />);
    const before = container.querySelector("input");
    setValue("ab");
    flush();
    expect(container.querySelector("input")).toBe(before);
  });

  it("attaches its error to the input and announces it", () => {
    const container = render(() => (
      <TextField label="Partitions" value="0" help="Cannot be removed later." error="A topic needs at least one partition." />
    ));
    const input = container.querySelector("input")!;
    expect(input.getAttribute("aria-invalid")).toBe("true");

    const described = (input.getAttribute("aria-describedby") ?? "").split(" ").filter((s) => s !== "");
    // Both descriptions, in reading order. Naming only one makes the other silently unreachable.
    expect(described).toHaveLength(2);
    const text = described.map((id) => document.getElementById(id)?.textContent ?? "").join(" | ");
    expect(text).toContain("Cannot be removed later.");
    expect(text).toContain("A topic needs at least one partition.");
    expect(container.querySelector('[role="alert"]')?.textContent).toContain("at least one partition");
  });

  it("keeps a hidden label a real label, not a missing one", async () => {
    const container = render(() => (
      <TextField label="Search" labelHidden icon="search" hintKey="⌘K" placeholder="Search topics, groups, anything…" />
    ));
    expect(container.querySelector("label")?.className).toContain("kui-visually-hidden");
    // The keyboard badge is decoration: "command K" read out of the middle of a search box is noise.
    expect(container.querySelector(".kui-textfield__hint-key")?.getAttribute("aria-hidden")).toBe("true");
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

const SEEK = [
  { value: "latest", label: "Latest" },
  { value: "earliest", label: "Earliest" },
  { value: "offset", label: "Offset" },
  { value: "timestamp", label: "Timestamp" },
] as const;

describe("Select", () => {
  function renderSelect(props: Partial<Parameters<typeof Select<string>>[0]> = {}) {
    const [value, setValue] = createSignal<string | undefined>("latest");
    const onChange = vi.fn((v: string) => setValue(v));
    const container = render(() => (
      <Select
        label="Seek"
        options={[...SEEK]}
        value={value()}
        onChange={onChange}
        {...(props as Record<string, never>)}
      />
    ));
    return { container, onChange, value, trigger: container.querySelector("button")! };
  }

  it("is a combobox that says whether it is open", () => {
    const { trigger } = renderSelect();
    expect(trigger.getAttribute("role")).toBe("combobox");
    expect(trigger.getAttribute("aria-haspopup")).toBe("listbox");
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
  });

  it("opens from the keyboard and moves an active option without moving focus", async () => {
    const { container, trigger } = renderSelect();
    trigger.focus();
    await userEvent.keyboard("{Enter}");
    flush();
    expect(trigger.getAttribute("aria-expanded")).toBe("true");

    // Focus stays on the trigger. Moving it into a floating list means every close path has to put
    // it back, and the one path somebody forgets drops focus onto `<body>`.
    expect(document.activeElement).toBe(trigger);

    const first = trigger.getAttribute("aria-activedescendant");
    await userEvent.keyboard("{ArrowDown}");
    flush();
    const second = trigger.getAttribute("aria-activedescendant");
    expect(second).not.toBe(first);
    expect(document.getElementById(second!)?.textContent).toContain("Earliest");
    expect(container.querySelectorAll('[role="option"]')).toHaveLength(4);
  });

  it("selects with Enter and closes", async () => {
    const { trigger, onChange } = renderSelect();
    trigger.focus();
    await userEvent.keyboard("{Enter}{ArrowDown}{Enter}");
    flush();
    expect(onChange).toHaveBeenCalledWith("earliest");
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
  });

  it("closes on Escape and gives focus back", async () => {
    const { trigger } = renderSelect();
    trigger.focus();
    await userEvent.keyboard("{Enter}");
    flush();
    await userEvent.keyboard("{Escape}");
    flush();
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(document.activeElement).toBe(trigger);
  });

  it("steps over a disabled option instead of sticking on it", async () => {
    const [value, setValue] = createSignal("24h");
    const container = render(() => (
      <Select
        label="Range"
        options={[
          { value: "24h", label: "Last 24 hours" },
          { value: "7d", label: "Last 7 days", disabled: true },
          { value: "30d", label: "Last 30 days" },
        ]}
        value={value()}
        onChange={setValue}
      />
    ));
    const trigger = container.querySelector("button")!;
    trigger.focus();
    await userEvent.keyboard("{Enter}{ArrowDown}");
    flush();
    const active = document.getElementById(trigger.getAttribute("aria-activedescendant")!);
    expect(active?.textContent).toContain("Last 30 days");
  });

  it("jumps to an option by typing its first letters", async () => {
    const { trigger } = renderSelect();
    trigger.focus();
    await userEvent.keyboard("{Enter}");
    flush();
    await userEvent.keyboard("ti");
    flush();
    const active = document.getElementById(trigger.getAttribute("aria-activedescendant")!);
    expect(active?.textContent).toContain("Timestamp");
  });

  /** The empty case: a menu that says so, rather than a blank rectangle the user cannot read. */
  it("says when there is nothing to choose from", async () => {
    const container = render(() => (
      <Select label="Partitions" options={[]} emptyMessage="No partitions were returned for this topic." />
    ));
    const trigger = container.querySelector("button")!;
    await userEvent.click(trigger);
    flush();
    expect(container.querySelectorAll('[role="option"]')).toHaveLength(0);
    expect(container.textContent).toContain("No partitions were returned for this topic.");
  });

  it("explains itself when disabled", async () => {
    const container = render(() => (
      <Select
        label="Partitions"
        options={[{ value: "1", label: "1" }]}
        value="1"
        disabled
        disabledReason="This topic has one partition, so there is nothing to choose."
      />
    ));
    const trigger = container.querySelector("button")!;
    expect(trigger.disabled).toBe(true);
    const described = document.getElementById(trigger.getAttribute("aria-describedby")!);
    expect(described?.textContent).toContain("nothing to choose");
  });

  it("has no accessibility violations open or closed", async () => {
    const { container, trigger } = renderSelect();
    await expectNoViolations(container);
    await userEvent.click(trigger);
    flush();
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Checkbox", () => {
  it("is a real checkbox, not a div wearing a role", () => {
    const container = render(() => <Checkbox label="Include internal topics" />);
    const input = container.querySelector("input")!;
    expect(input.type).toBe("checkbox");
    expect(input.getAttribute("role")).toBeNull();
  });

  it("toggles when the label is clicked", async () => {
    const onChange = vi.fn();
    const container = render(() => <Checkbox label="Include internal topics" onChange={onChange} />);
    await userEvent.click(container.querySelector("label")!);
    flush();
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("toggles from the keyboard with space", async () => {
    const onChange = vi.fn();
    const container = render(() => <Checkbox label="Include internal topics" onChange={onChange} />);
    container.querySelector("input")!.focus();
    await userEvent.keyboard(" ");
    flush();
    expect(onChange).toHaveBeenCalledWith(true);
  });

  /**
   * `indeterminate` is a DOM property, not an attribute: it cannot be written in markup and is not
   * reflected, so a component that forgets it announces "not checked" for a partial selection.
   */
  it("sets indeterminate as a property and announces mixed", () => {
    const container = render(() => <Checkbox label="Select all rows" indeterminate />);
    const input = container.querySelector("input")!;
    expect(input.indeterminate).toBe(true);
    // Indeterminate wins over checked in the drawing, because that is what the input reports.
    expect(container.querySelector(".kui-checkbox__box")?.innerHTML).toContain("svg");
  });

  it("keeps the drawn box out of the accessibility tree", () => {
    const container = render(() => <Checkbox label="Include internal topics" checked />);
    expect(container.querySelector(".kui-checkbox__box")?.getAttribute("aria-hidden")).toBe("true");
  });

  it("has no accessibility violations in any state", async () => {
    const container = render(() => (
      <>
        <Checkbox label="Unchecked" />
        <Checkbox label="Checked" checked />
        <Checkbox label="Indeterminate" indeterminate />
        <Checkbox label="Disabled" disabled />
        <Checkbox label="Hidden label" labelHidden />
      </>
    ));
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("StatusPill", () => {
  it("renders nothing at all rather than an empty stadium", () => {
    const container = render(() => <StatusPill tone="success">{""}</StatusPill>);
    expect(container.querySelector(".kui-pill")).toBeNull();
  });

  it("carries text as well as colour", () => {
    const container = render(() => <StatusPill tone="danger" dot>2 offline</StatusPill>);
    expect(container.textContent).toContain("2 offline");
    // The dot repeats the tone and says nothing on its own.
    expect(container.querySelector(".kui-pill__dot")?.getAttribute("aria-hidden")).toBe("true");
  });

  it("is silent by default and announces only when asked", () => {
    const container = render(() => (
      <>
        <StatusPill tone="neutral">1,536 partitions</StatusPill>
        <StatusPill tone="warning" live>Rebalancing</StatusPill>
      </>
    ));
    const pills = [...container.querySelectorAll(".kui-pill")];
    expect(pills[0]?.getAttribute("role")).toBeNull();
    expect(pills[1]?.getAttribute("role")).toBe("status");
  });

  it("is a button, with a pressed state, when it is a toggle", async () => {
    const onClick = vi.fn();
    const container = render(() => (
      <StatusPill tone="success" dot pressed onClick={onClick}>
        LIVE
      </StatusPill>
    ));
    const button = container.querySelector("button")!;
    expect(button.getAttribute("aria-pressed")).toBe("true");
    await userEvent.click(button);
    flush();
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("has no accessibility violations across its tones", async () => {
    const container = render(() => (
      <>
        <StatusPill tone="success" icon="check">all in sync</StatusPill>
        <StatusPill tone="warning">fashionably late</StatusPill>
        <StatusPill tone="danger">2 offline</StatusPill>
        <StatusPill tone="accent">12% vs last hour</StatusPill>
        <StatusPill tone="neutral">1,536 partitions</StatusPill>
      </>
    ));
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("IconTile", () => {
  it("is decoration and stays out of the accessibility tree", () => {
    const container = render(() => <IconTile icon="brokers" tone="success" />);
    const tile = container.querySelector(".kui-icon-tile")!;
    expect(tile.getAttribute("aria-hidden")).toBe("true");
    // Never empty: an empty tile reads as a broken image.
    expect(tile.querySelector("svg")).not.toBeNull();
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Avatar", () => {
  it("takes initials from the shapes a display name actually arrives in", () => {
    expect(initialsOf("Olena Petrenko")).toBe("OP");
    expect(initialsOf("admin")).toBe("AD");
    expect(initialsOf("svc.connect-worker")).toBe("SW");
    expect(initialsOf("o.petrenko@example.com")).toBe("OC");
    expect(initialsOf("Maria de los Ángeles Fernández García")).toBe("MG");
    expect(initialsOf("李")).toBe("李");
    expect(initialsOf("   ")).toBe("");
  });

  /** Inventing somebody's initials is worse than admitting you do not know them. */
  it("draws a person glyph, not a guess, when the name is unknown", () => {
    const container = render(() => <Avatar />);
    const avatar = container.querySelector(".kui-avatar")!;
    expect(avatar.className).toContain("kui-avatar--unknown");
    expect(avatar.textContent).toBe("");
    expect(avatar.getAttribute("aria-label")).toContain("name unavailable");
  });

  it("names itself with the whole name, not with two letters of it", () => {
    const container = render(() => <Avatar name="Olena Petrenko" />);
    const avatar = container.querySelector(".kui-avatar")!;
    expect(avatar.getAttribute("aria-label")).toBe("Account: Olena Petrenko");
    expect(avatar.querySelector("[aria-hidden]")?.textContent).toBe("OP");
  });

  it("has no accessibility violations as a label or as a button", async () => {
    const container = render(() => (
      <>
        <Avatar name="Olena Petrenko" />
        <Avatar name="Olena Petrenko" onClick={() => {}} />
        <Avatar />
      </>
    ));
    await expectNoViolations(container);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("Tooltip", () => {
  it("appears on focus and describes the focusable thing inside it", async () => {
    const container = render(() => (
      <Tooltip content="Metrics are retained for 7 days." code="RETENTION_LIMIT">
        <button type="button">30 days</button>
      </Tooltip>
    ));
    const button = container.querySelector("button")!;
    // The description lands on the element the user focuses, not on the wrapper around it: a
    // screen reader announces a description when focus reaches the *described* element.
    const id = button.getAttribute("aria-describedby");
    expect(id).toBeTruthy();

    button.focus();
    flush();
    await Promise.resolve();
    flush();
    expect(document.getElementById(id!)?.textContent).toContain("retained for 7 days");
  });

  it("closes on Escape", async () => {
    const container = render(() => (
      <Tooltip content="Metrics are retained for 7 days.">
        <button type="button">30 days</button>
      </Tooltip>
    ));
    container.querySelector("button")!.focus();
    flush();
    expect(document.querySelector(".kui-tooltip")).not.toBeNull();
    await userEvent.keyboard("{Escape}");
    flush();
    expect(document.querySelector(".kui-tooltip")).toBeNull();
  });

  it("renders nothing when it has nothing to say", () => {
    render(() => (
      <Tooltip content="" >
        <button type="button">30 days</button>
      </Tooltip>
    ));
    expect(document.querySelector(".kui-tooltip")).toBeNull();
  });
});
