/**
 * The tab strip, the breadcrumb trail, the tag, the magnitude bar and the threshold figure.
 *
 * Every case below is attached to a statement in the component's own comment or to a defect this
 * project has already paid for. Nothing asserts a colour, a size or a position: jsdom has no layout
 * engine, so a test that did would be asserting numbers jsdom invented.
 */

import { createSignal, flush } from "solid-js";
import { describe, expect, it, vi } from "vitest";

import { Breadcrumbs } from "./Breadcrumbs.jsx";
import { MagnitudeBar, percentage } from "./MagnitudeBar.jsx";
import { Tabs, tabTarget } from "./Tabs.jsx";
import { Tag } from "./Tag.jsx";
import { ThresholdValue, thresholdLevel } from "./ThresholdValue.jsx";
import { describeViolations, findViolations, mount } from "./testing.js";

const TABS = [
  { id: "overview", label: "Overview", body: () => <p>overview panel</p> },
  { id: "messages", label: "Messages", body: () => <p>messages panel</p> },
  { id: "consumers", label: "Consumers", body: () => <p>consumers panel</p> },
];

function key(element: Element, name: string): void {
  element.dispatchEvent(new KeyboardEvent("keydown", { key: name, bubbles: true, cancelable: true }));
}

/* --- Tabs ------------------------------------------------------------------------------------ */

describe("Tabs", () => {
  it("builds only the selected panel, so an unopened tab issues no requests", () => {
    const built: string[] = [];
    const tabs = TABS.map((tab) => ({
      ...tab,
      body: () => {
        built.push(tab.id);
        return <p>{tab.id} panel</p>;
      },
    }));
    const { container, dispose } = mount(() => (
      <Tabs tabs={tabs} selected="overview" onSelect={() => {}} />
    ));

    expect(built).toEqual(["overview"]);
    expect(container.querySelectorAll("[role=tabpanel]")).toHaveLength(1);
    dispose();
  });

  it("puts exactly one tab in the browser's Tab order", () => {
    const { container, dispose } = mount(() => (
      <Tabs tabs={TABS} selected="messages" onSelect={() => {}} />
    ));

    const order = [...container.querySelectorAll("[role=tab]")].map((t) => t.getAttribute("tabindex"));
    expect(order).toEqual(["-1", "0", "-1"]);
    dispose();
  });

  it("moves selection and focus together with the arrow keys, and wraps", () => {
    const [selected, setSelected] = createSignal("consumers");
    const { container, dispose } = mount(() => (
      <Tabs tabs={TABS} selected={selected()} onSelect={setSelected} />
    ));

    key(container.querySelector("[role=tablist]")!, "ArrowRight");
    flush();

    expect(selected()).toBe("overview");
    expect(document.activeElement?.getAttribute("data-tab-id")).toBe("overview");
    dispose();
  });

  it("stops the browser scrolling the page on an arrow key", () => {
    const { container, dispose } = mount(() => (
      <Tabs tabs={TABS} selected="overview" onSelect={() => {}} />
    ));

    const event = new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true, cancelable: true });
    container.querySelector("[role=tablist]")!.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    dispose();
  });

  it("renders no panel for a selection that matches no tab, rather than guessing", () => {
    const { container, dispose } = mount(() => (
      <Tabs tabs={TABS} selected="does-not-exist" onSelect={() => {}} />
    ));

    expect(container.querySelectorAll("[role=tabpanel]")).toHaveLength(0);
    expect(container.querySelectorAll("[role=tab]")).toHaveLength(3);
    dispose();
  });

  it("leaves the open panel alone when the tab list changes around it", () => {
    const [tabs, setTabs] = createSignal(TABS);
    const { container, dispose } = mount(() => (
      <Tabs tabs={tabs()} selected="overview" onSelect={() => {}} />
    ));

    const panel = container.querySelector("[role=tabpanel]");
    setTabs([...TABS, { id: "config", label: "Config", body: () => <p>config panel</p> }]);
    flush();

    expect(container.querySelector("[role=tabpanel]")).toBe(panel);
    dispose();
  });

  it("wires each tab to its own panel and back", async () => {
    const { container, dispose } = mount(() => (
      <Tabs tabs={TABS} selected="overview" onSelect={() => {}} label="Topic" />
    ));

    const tab = container.querySelector("[role=tab][aria-selected=true]")!;
    const panel = container.querySelector("[role=tabpanel]")!;
    expect(tab.getAttribute("aria-controls")).toBe(panel.id);
    expect(panel.getAttribute("aria-labelledby")).toBe(tab.id);

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });

  it("computes the keyboard target, including both wraps and the ends", () => {
    expect(tabTarget("ArrowRight", "consumers", TABS)).toBe("overview");
    expect(tabTarget("ArrowLeft", "overview", TABS)).toBe("consumers");
    expect(tabTarget("Home", "messages", TABS)).toBe("overview");
    expect(tabTarget("End", "messages", TABS)).toBe("consumers");
    expect(tabTarget("a", "messages", TABS)).toBeUndefined();
    expect(tabTarget("ArrowRight", "missing", TABS)).toBeUndefined();
    expect(tabTarget("ArrowRight", "overview", [])).toBeUndefined();
  });
});

/* --- Breadcrumbs ----------------------------------------------------------------------------- */

describe("Breadcrumbs", () => {
  it("links every step but the one you are on, and marks that one current", async () => {
    const { container, dispose } = mount(() => (
      <Breadcrumbs
        crumbs={[
          { label: "Clusters", href: "/ui/clusters" },
          { label: "local", href: "/ui/clusters/local" },
          { label: "orders" },
        ]}
      />
    ));

    expect([...container.querySelectorAll("a")].map((a) => a.textContent)).toEqual([
      "Clusters",
      "local",
    ]);
    const current = container.querySelector("[aria-current=page]")!;
    expect(current.textContent).toBe("orders");
    expect(current.tagName).toBe("SPAN");

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });

  it("hides the separators from a screen reader and draws one fewer than there are steps", () => {
    const { container, dispose } = mount(() => (
      <Breadcrumbs crumbs={[{ label: "a", href: "/a" }, { label: "b", href: "/b" }, { label: "c" }]} />
    ));

    const separators = container.querySelectorAll(".kui-breadcrumbs__separator");
    expect(separators).toHaveLength(2);
    expect([...separators].every((s) => s.getAttribute("aria-hidden") === "true")).toBe(true);
    dispose();
  });
});

/* --- Tag ------------------------------------------------------------------------------------- */

describe("Tag", () => {
  it("is silent by default and announces only when asked", () => {
    const { container, dispose } = mount(() => (
      <>
        <Tag>compact</Tag>
        <Tag live>Rebalancing</Tag>
      </>
    ));

    expect(container.querySelectorAll("[role=status]")).toHaveLength(1);
    expect(container.querySelector("[role=status]")!.textContent).toContain("Rebalancing");
    dispose();
  });

  it("names the remove button after the thing it removes", async () => {
    const onRemove = vi.fn();
    const { container, dispose } = mount(() => (
      <Tag onRemove={onRemove} tone="info" dot>
        partition = 3
      </Tag>
    ));

    const button = container.querySelector("button")!;
    expect(button.getAttribute("aria-label")).toBe("Remove partition = 3");
    button.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    flush();
    expect(onRemove).toHaveBeenCalledOnce();

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });

  it("draws nothing at all for empty text, rather than a coloured smudge", () => {
    const { container, dispose } = mount(() => <Tag>{""}</Tag>);
    expect(container.querySelector(".kui-tag")).toBeNull();
    dispose();
  });
});

/* --- MagnitudeBar ---------------------------------------------------------------------------- */

describe("MagnitudeBar", () => {
  it("clamps a fraction rather than painting outside its own track", () => {
    expect(percentage(-3)).toBe("0%");
    expect(percentage(1.4)).toBe("100%");
    expect(percentage(Number.NaN)).toBe("0%");
    expect(percentage(0.12345)).toBe("12.3%");
  });

  it("prints the figure and hides the bar, so the quantity is said once", async () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBar value="112.9 GB" fraction={0.42} label="orders" />
    ));

    expect(container.querySelector(".kui-magnitude__value")!.textContent).toBe("112.9 GB");
    expect(container.querySelector(".kui-magnitude__track")!.getAttribute("aria-hidden")).toBe("true");
    expect(container.querySelector<HTMLElement>(".kui-magnitude__fill")!.style.width).toBe("42%");

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });

  it("drops the name row in the inline form, where the table row already says what it is", () => {
    const { container, dispose } = mount(() => <MagnitudeBar value="7" fraction={0.1} inline />);
    expect(container.querySelector(".kui-magnitude__row")).toBeNull();
    expect(container.querySelector(".kui-magnitude--inline")).not.toBeNull();
    dispose();
  });
});

/* --- ThresholdValue -------------------------------------------------------------------------- */

describe("ThresholdValue", () => {
  it("treats both bounds as exclusive, so a healthy zero stays normal", () => {
    expect(thresholdLevel(0, 0)).toBe("normal");
    expect(thresholdLevel(1, 0)).toBe("warning");
    expect(thresholdLevel(10, 0, 10)).toBe("warning");
    expect(thresholdLevel(11, 0, 10)).toBe("critical");
  });

  it("colours nothing and marks nothing while the figure is under the limit", () => {
    const { container, dispose } = mount(() => <ThresholdValue value="0" level="normal" />);
    const span = container.querySelector(".kui-threshold")!;
    expect(span.className).toBe("kui-threshold");
    expect(container.querySelector(".kui-threshold__mark")).toBeNull();
    expect(span.textContent).toBe("0");
    dispose();
  });

  it("adds a mark and a spoken reason once it is over, both appearing together", () => {
    const [level, setLevel] = createSignal<"normal" | "warning" | "critical">("normal");
    const { container, dispose } = mount(() => (
      <ThresholdValue value="4,182" level={level()} />
    ));

    setLevel("critical");
    flush();

    expect(container.querySelector(".kui-threshold--critical")).not.toBeNull();
    expect(container.querySelector(".kui-threshold__mark")).not.toBeNull();
    expect(container.querySelector(".kui-visually-hidden")!.textContent).toBe(
      " (above the critical threshold)",
    );
    dispose();
  });

  it("lets the caller say what the number means, because it has never been told", () => {
    const { container, dispose } = mount(() => (
      <ThresholdValue
        value="12"
        level="warning"
        announcement={() => "12 partitions are under-replicated"}
      />
    ));

    expect(container.querySelector(".kui-visually-hidden")!.textContent).toBe(
      " (12 partitions are under-replicated)",
    );
    dispose();
  });
});
