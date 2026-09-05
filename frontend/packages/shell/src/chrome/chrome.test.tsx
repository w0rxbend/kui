/**
 * Rendering, interaction and accessibility for the application chrome.
 *
 * Every case here is attached to a statement made somewhere in `.agent/design/SPEC.md` or to a
 * defect this project has already paid for. Nothing asserts a colour or a pixel: those are judged in
 * Storybook against the design screenshots, because a test that asserted them in jsdom would be
 * asserting numbers jsdom invented.
 */

import { createSignal, flush } from "solid-js";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { Breadcrumb } from "./Breadcrumb.jsx";
import { ClusterSelector } from "./ClusterSelector.jsx";
import { ClusterStatusCard } from "./ClusterStatusCard.jsx";
import { EnvRail } from "./EnvRail.jsx";
import { NavDrawer } from "./NavDrawer.jsx";
import { NavItem } from "./NavItem.jsx";
import { SearchField } from "./SearchField.jsx";
import { TabStrip } from "@kui/kernel";
import { TopBar } from "./TopBar.jsx";
import { shortcutHint } from "./SearchField.jsx";
import {
  CLUSTERS,
  HEALTHY_CLUSTER,
  LONG_TOPIC,
  NAV_GROUPS,
  TOPIC_TABS,
  UNREACHABLE_CLUSTER,
  VERSIONLESS_CLUSTER,
} from "./fixtures.js";
import { describeViolations, findViolations, mount } from "./testing.js";

describe("NavItem", () => {
  it("puts the badge's meaning in the accessible name, not the fragment it shows", () => {
    const { container, dispose } = mount(() => (
      <NavItem
        destination={{
          id: "brokers",
          label: "Brokers",
          icon: "brokers",
          href: "/brokers",
          badge: { text: "3/3", tone: "success", description: "3 of 3 online" },
        }}
      />
    ));
    const link = container.querySelector("a")!;
    expect(link.getAttribute("aria-label")).toBe("Brokers, 3 of 3 online");
    // The visible fragment is hidden from assistive technology, so the number is announced once.
    expect(container.querySelector(".kui-nav-item__badge")!.getAttribute("aria-hidden")).toBe("true");
    dispose();
  });

  it("marks the current destination with aria-current so it is not only a fill", () => {
    const { container, dispose } = mount(() => (
      <NavItem
        destination={{ id: "dashboard", label: "Dashboard", icon: "dashboard", href: "/d" }}
        current={true}
      />
    ));
    expect(container.querySelector("a")!.getAttribute("aria-current")).toBe("page");
    dispose();
  });

  it("renders a disabled destination as present, explained and out of the tab order", () => {
    const { container, dispose } = mount(() => (
      <NavItem
        destination={{
          id: "ksql",
          label: "KSQL DB",
          icon: "ksql",
          href: "/ksql",
          disabled: true,
          disabledReason: "Not built yet",
        }}
      />
    ));
    // Not an anchor: there is nowhere to go, so there is no link to follow.
    expect(container.querySelector("a")).toBeNull();
    const row = container.querySelector('[data-testid="nav-ksql"]')!;
    expect(row.getAttribute("aria-disabled")).toBe("true");
    expect(row.getAttribute("title")).toBe("Not built yet");
    expect(row.getAttribute("aria-label")).toBe("KSQL DB, Not built yet");
    dispose();
  });

  it("omits the badge entirely when the count is unavailable rather than printing a zero", () => {
    const { container, dispose } = mount(() => (
      <NavItem destination={{ id: "topics", label: "Topics", icon: "topics", href: "/t" }} />
    ));
    expect(container.querySelector(".kui-nav-item__badge")).toBeNull();
    expect(container.textContent).not.toContain("0");
    dispose();
  });
});

describe("NavDrawer", () => {
  it("labels each group's list with its own heading", () => {
    const { container, dispose } = mount(() => <NavDrawer groups={NAV_GROUPS} cluster={HEALTHY_CLUSTER} />);
    const lists = container.querySelectorAll(".kui-nav-group__list");
    expect(lists.length).toBe(2);
    const headingId = lists[0]!.getAttribute("aria-labelledby")!;
    expect(container.querySelector(`#${headingId}`)!.textContent).toBe("CLUSTER");
    dispose();
  });

  it("writes the group headings uppercase in the markup rather than transforming them", () => {
    const { container, dispose } = mount(() => <NavDrawer groups={NAV_GROUPS} cluster={HEALTHY_CLUSTER} />);
    const headings = [...container.querySelectorAll(".kui-nav-group__heading")].map((h) => h.textContent);
    expect(headings).toEqual(["CLUSTER", "ECOSYSTEM"]);
    dispose();
  });

  it("has no accessibility violations in the healthy case", async () => {
    const { container, dispose } = mount(() => (
      <NavDrawer groups={NAV_GROUPS} currentId="dashboard" cluster={HEALTHY_CLUSTER} />
    ));
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

describe("ClusterStatusCard", () => {
  it("says an unknown version in words, never as a dash", () => {
    const { container, dispose } = mount(() => <ClusterStatusCard cluster={VERSIONLESS_CLUSTER} />);
    expect(container.textContent).toContain("healthy · version unknown");
    expect(container.textContent).not.toContain("—");
    dispose();
  });

  it("turns an unreachable cluster into a control that asks again", async () => {
    let retried = 0;
    const { container, dispose } = mount(() => (
      <ClusterStatusCard cluster={UNREACHABLE_CLUSTER} onRetry={() => (retried += 1)} />
    ));
    const card = container.querySelector('[data-testid="cluster-status-card"]') as HTMLButtonElement;
    expect(card.tagName).toBe("BUTTON");
    expect(card.getAttribute("aria-label")).toContain("Check again");
    expect(container.textContent).toContain("last seen 4m ago");
    await userEvent.click(card);
    expect(retried).toBe(1);
    dispose();
  });

  it("offers to add a cluster when none is configured, and does not call it an error", () => {
    const { container, dispose } = mount(() => <ClusterStatusCard configureHref="/settings/clusters" />);
    expect(container.textContent).toContain("no cluster · add one");
    expect(container.querySelector(".kui-cluster-card__tile--danger")).toBeNull();
    dispose();
  });
});

describe("SearchField", () => {
  it("shows the platform's own shortcut", () => {
    expect(shortcutHint("apple")).toBe("⌘K");
    expect(shortcutHint("other")).toBe("Ctrl K");
  });

  it("keeps the same input element across a results update, so typing is never interrupted", async () => {
    /* The defect this guards: a field rebuilt mid-word loses the caret position, the composition
     * state of an input-method editor, and sometimes the text. If somebody wraps the input in a
     * conditional, the node identity changes and this fails. */
    const [status, setStatus] = createSignal<"idle" | "searching" | "ready">("idle");
    const { container, dispose } = mount(() => (
      <SearchField value="ord" onInput={() => {}} status={status()} results={[]} platform="other" />
    ));
    const before = container.querySelector("input")!;
    setStatus("searching");
    flush();
    setStatus("ready");
    flush();
    expect(container.querySelector("input")).toBe(before);
    dispose();
  });

  it("reports what it typed", async () => {
    const seen: string[] = [];
    const { container, dispose } = mount(() => (
      <SearchField value="" onInput={(v) => seen.push(v)} platform="other" />
    ));
    await userEvent.type(container.querySelector("input")!, "ord");
    expect(seen.at(-1)).toBe("ord");
    dispose();
  });

  it("stays enabled and explains itself when search is not answering", async () => {
    const { container, dispose } = mount(() => (
      <SearchField value="orders" onInput={() => {}} status="failed" platform="other" />
    ));
    const input = container.querySelector("input")!;
    expect(input.disabled).toBe(false);
    input.focus();
    flush();
    expect(container.textContent).toContain("Search is not answering right now.");
    dispose();
  });

  it("distinguishes searching, empty and failed from one another", () => {
    for (const [status, expected] of [
      ["searching", "Searching"],
      ["empty", "Nothing matches"],
      ["failed", "Search is not answering"],
    ] as const) {
      const { container, dispose } = mount(() => (
        <SearchField value="q" onInput={() => {}} status={status} platform="other" />
      ));
      container.querySelector("input")!.focus();
      flush();
      expect(container.textContent).toContain(expected);
      dispose();
    }
  });

  it("has a real label and not only a placeholder", () => {
    const { container, dispose } = mount(() => <SearchField value="" onInput={() => {}} platform="other" />);
    const input = container.querySelector("input")!;
    const label = container.querySelector(`label[for="${input.id}"]`);
    expect(label?.textContent).toContain("Search topics");
    dispose();
  });
});

describe("ClusterSelector", () => {
  it("opens, walks with the arrow keys and selects with Enter", async () => {
    const chosen: string[] = [];
    const { container, dispose } = mount(() => (
      <ClusterSelector clusters={CLUSTERS} currentId="prod-kyiv-01" onSelect={(id) => chosen.push(id)} />
    ));
    const trigger = container.querySelector('[data-testid="cluster-selector-trigger"]') as HTMLButtonElement;
    await userEvent.click(trigger);
    flush();
    const listbox = container.querySelector('[role="listbox"]') as HTMLElement;
    listbox.focus();
    await userEvent.keyboard("{ArrowDown}");
    flush();
    await userEvent.keyboard("{Enter}");
    flush();
    expect(chosen).toEqual(["staging-fra"]);
    dispose();
  });

  it("closes on Escape and gives focus back to the trigger", async () => {
    const { container, dispose } = mount(() => <ClusterSelector clusters={CLUSTERS} currentId="prod-kyiv-01" />);
    const trigger = container.querySelector('[data-testid="cluster-selector-trigger"]') as HTMLButtonElement;
    await userEvent.click(trigger);
    flush();
    const listbox = container.querySelector('[role="listbox"]') as HTMLElement;
    listbox.focus();
    await userEvent.keyboard("{Escape}");
    flush();
    expect(container.querySelector('[role="listbox"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    dispose();
  });

  it("wraps at both ends rather than stopping, and Home and End jump", async () => {
    const { container, dispose } = mount(() => <ClusterSelector clusters={CLUSTERS} currentId="prod-kyiv-01" />);
    await userEvent.click(container.querySelector('[data-testid="cluster-selector-trigger"]')!);
    flush();
    const listbox = container.querySelector('[role="listbox"]') as HTMLElement;
    listbox.focus();
    await userEvent.keyboard("{ArrowUp}");
    flush();
    expect(listbox.getAttribute("aria-activedescendant")).toContain("analytics");
    await userEvent.keyboard("{Home}");
    flush();
    expect(listbox.getAttribute("aria-activedescendant")).toContain("prod-kyiv-01");
    await userEvent.keyboard("{End}");
    flush();
    expect(listbox.getAttribute("aria-activedescendant")).toContain("analytics");
    dispose();
  });

  it("still opens with a single cluster, because that is where adding a second lives", async () => {
    const { container, dispose } = mount(() => (
      <ClusterSelector clusters={[HEALTHY_CLUSTER]} currentId="prod-kyiv-01" />
    ));
    await userEvent.click(container.querySelector('[data-testid="cluster-selector-trigger"]')!);
    flush();
    expect(container.textContent).toContain("Add a cluster");
    dispose();
  });

  it("reads 'no cluster' when there are none, and is still operable", async () => {
    const { container, dispose } = mount(() => <ClusterSelector clusters={[]} />);
    const trigger = container.querySelector('[data-testid="cluster-selector-trigger"]')!;
    expect(trigger.textContent).toContain("no cluster");
    await userEvent.click(trigger);
    flush();
    expect(container.textContent).toContain("Add a cluster");
    dispose();
  });

  it("marks the current cluster to both eyes and screen readers", async () => {
    const { container, dispose } = mount(() => <ClusterSelector clusters={CLUSTERS} currentId="staging-fra" />);
    await userEvent.click(container.querySelector('[data-testid="cluster-selector-trigger"]')!);
    flush();
    const option = container.querySelector('[data-testid="cluster-option-staging-fra"]')!;
    expect(option.getAttribute("aria-selected")).toBe("true");
    // ...and a tick, because aria-selected is not visible.
    expect(option.querySelector(".kui-cluster-select__check")).not.toBeNull();
    dispose();
  });

  it("has no accessibility violations while open", async () => {
    const { container, dispose } = mount(() => <ClusterSelector clusters={CLUSTERS} currentId="prod-kyiv-01" />);
    await userEvent.click(container.querySelector('[data-testid="cluster-selector-trigger"]')!);
    flush();
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

describe("Breadcrumb", () => {
  it("does not make the current page a link", () => {
    const { container, dispose } = mount(() => (
      <Breadcrumb trail={[{ label: "Topics", href: "/topics" }, { label: "orders.payments.v2" }]} />
    ));
    expect(container.querySelectorAll("a").length).toBe(1);
    expect(container.querySelector('[aria-current="page"]')!.textContent).toBe("orders.payments.v2");
    dispose();
  });

  it("collapses a long trail behind a real button and expands it when pressed", async () => {
    const trail = [
      { label: "Clusters", href: "/c" },
      { label: "prod-kyiv-01", href: "/c/p" },
      { label: "Topics", href: "/t" },
      { label: LONG_TOPIC, href: "/t/x" },
      { label: "Partition 7" },
    ];
    const { container, dispose } = mount(() => <Breadcrumb trail={trail} />);
    const more = container.querySelector('[data-testid="breadcrumb-expand"]') as HTMLButtonElement;
    expect(more.getAttribute("aria-label")).toBe("Show 2 hidden levels");
    await userEvent.click(more);
    flush();
    expect(container.querySelector('[data-testid="breadcrumb-expand"]')).toBeNull();
    expect(container.querySelectorAll("li").length).toBe(5);
    dispose();
  });

  it("keeps its landmark when the trail is empty, so the page's structure does not change", () => {
    const { container, dispose } = mount(() => <Breadcrumb trail={[]} />);
    expect(container.querySelector('nav[aria-label="Breadcrumb"]')).not.toBeNull();
    dispose();
  });

  it("has no accessibility violations when collapsed", async () => {
    const { container, dispose } = mount(() => (
      <Breadcrumb
        trail={[
          { label: "Clusters", href: "/c" },
          { label: "prod-kyiv-01", href: "/c/p" },
          { label: "Topics", href: "/t" },
          { label: "orders.payments.v2", href: "/t/x" },
          { label: "Partition 7" },
        ]}
      />
    ));
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});

describe("TabStrip", () => {
  it("is a navigation landmark of links, and never claims the ARIA tab pattern", () => {
    const { container, dispose } = mount(() => (
      <TabStrip tabs={TOPIC_TABS} currentId="messages" label="Topic sections" />
    ));
    expect(container.querySelector('nav[aria-label="Topic sections"]')).not.toBeNull();
    expect(container.querySelector('[role="tablist"]')).toBeNull();
    expect(container.querySelectorAll("a").length).toBe(TOPIC_TABS.length);
    dispose();
  });

  it("marks the current tab with aria-current as well as with a fill", () => {
    const { container, dispose } = mount(() => (
      <TabStrip tabs={TOPIC_TABS} currentId="messages" label="Topic sections" />
    ));
    const current = container.querySelector('[aria-current="page"]')!;
    expect(current.textContent).toContain("Messages");
    expect(current.classList.contains("kui-page-tabs__tab--current")).toBe(true);
    dispose();
  });

  it("prints a zero count, because zero consumers is a fact", () => {
    const tabs = TOPIC_TABS.map((t) => (t.id === "consumers" ? { ...t, count: 0 } : t));
    const { container, dispose } = mount(() => (
      <TabStrip tabs={tabs} currentId="consumers" label="Topic sections" />
    ));
    expect(container.querySelector('[data-testid="tab-consumers"]')!.textContent).toContain("0");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => (
      <TabStrip tabs={TOPIC_TABS} currentId="messages" label="Topic sections" />
    ));
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});

describe("TopBar", () => {
  const base = {
    crumbs: [{ label: "prod-kyiv-01", href: "#c" }, { label: "Topics" }],
    search: { value: "", onInput: () => {}, platform: "other" as const },
  };

  it("says which theme mode is in force, in words, for all three modes", () => {
    for (const [theme, expected] of [
      ["auto", "Theme: follows system"],
      ["light", "Theme: light"],
      ["dark", "Theme: dark"],
    ] as const) {
      const { container, dispose } = mount(() => <TopBar {...base} theme={theme} />);
      const control = container.querySelector('[data-testid="theme-control"]')!;
      expect(control.getAttribute("aria-label")).toContain(expected);
      dispose();
    }
  });

  it("puts the unread count in the accessible name rather than only in a badge", () => {
    const { container, dispose } = mount(() => <TopBar {...base} theme="dark" unreadCount={3} />);
    expect(container.querySelector('[data-testid="notifications"]')!.getAttribute("aria-label")).toBe(
      "Notifications, 3 unread",
    );
    expect(container.querySelector(".kui-bell__badge")).not.toBeNull();
    dispose();
  });

  it("draws no marker at all when nothing is unread", () => {
    const { container, dispose } = mount(() => <TopBar {...base} theme="dark" unreadCount={0} />);
    expect(container.querySelector(".kui-bell__badge")).toBeNull();
    dispose();
  });

  it("opens the panel only when the caller says it is open", () => {
    // The panel's open state is the caller's, so that Escape and a click elsewhere can close it
    // from outside the bar. A bar that owned it would be a panel nothing else could dismiss.
    const closed = mount(() => <TopBar {...base} theme="dark" />);
    expect(closed.container.querySelector('[data-testid="notification-panel"]')).toBeNull();
    closed.dispose();

    const open = mount(() => (
      <TopBar {...base} theme="dark" notificationsOpen notifications={{ kind: "ready", notices: [] }} />
    ));
    expect(open.container.querySelector('[data-testid="notification-panel"]')).not.toBeNull();
    open.dispose();
  });

  it("says where you are, and does not link the page you are on", () => {
    const { container, dispose } = mount(() => <TopBar {...base} theme="dark" />);
    const current = container.querySelector('[aria-current="page"]')!;
    expect(current.textContent).toBe("Topics");
    expect(current.tagName).not.toBe("A");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => <TopBar {...base} theme="dark" unreadCount={2} />);
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});

describe("EnvRail", () => {
  it("admits it does not know who is signed in rather than inventing initials", () => {
    // Moved here from `TopBar` with the avatar itself. The property is the point, not where it
    // lives: two invented initials are worse than admitting we do not know, in a product where the
    // avatar is how you check whose credentials are about to purge a topic.
    const { container, dispose } = mount(() => <EnvRail environments={CLUSTERS} currentId="prod-kyiv-01" />);
    const avatar = container.querySelector(".kui-avatar")!;
    expect(avatar.getAttribute("aria-label")).toContain("unavailable");
    expect(avatar.textContent).toBe("");
    dispose();
  });

  it("names every environment in full, because a single letter is not an identifier", () => {
    // `prod-kyiv-01` and `prod-eu-02` are both drawn as "P". The accessible name is the only thing
    // that distinguishes them, so it is asserted rather than left to the design's discretion.
    const { container, dispose } = mount(() => (
      <EnvRail
        environments={[
          { id: "1", name: "prod-kyiv-01", health: "healthy" },
          { id: "2", name: "prod-eu-02", health: "degraded" },
        ]}
        currentId="1"
      />
    ));
    const tiles = [...container.querySelectorAll('[data-testid^="env-tile-"]')];
    expect(tiles.map((tile) => tile.getAttribute("aria-label"))).toEqual([
      "prod-kyiv-01 — healthy",
      "prod-eu-02 — degraded",
    ]);
    dispose();
  });

  it("keeps its width when no cluster has arrived yet", () => {
    // The frame's geometry must not depend on how many clusters exist: a rail that appeared with
    // the first response would shift the whole page sideways at an arbitrary moment.
    const { container, dispose } = mount(() => <EnvRail environments={[]} />);
    expect(container.querySelector('[data-testid="env-rail"]')).not.toBeNull();
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => (
      <EnvRail environments={CLUSTERS} currentId="prod-kyiv-01" accountName="Olena Petrenko" />
    ));
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});
