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

import { AccountMenu } from "./AccountMenu.jsx";
import { AppearancePopover, type AppearancePreferences } from "./AppearancePopover.jsx";
import { BrandBlock } from "./BrandBlock.jsx";
import { Breadcrumb } from "./Breadcrumb.jsx";
import { ClusterSelector } from "./ClusterSelector.jsx";
import { ClusterStatusCard } from "./ClusterStatusCard.jsx";
import { EnvRail } from "./EnvRail.jsx";
import { NavDrawer } from "./NavDrawer.jsx";
import { NavItem } from "./NavItem.jsx";
import { SearchField } from "./SearchField.jsx";
import { StorageMeter } from "./StorageMeter.jsx";
import { TabStrip, createRootPreference } from "@kui/kernel";
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";
import { TopBar } from "./TopBar.jsx";
import { shortcutHint } from "./SearchField.jsx";
import {
  CLUSTERS,
  DEFECTIVE_CLUSTER,
  HEALTHY_CLUSTER,
  LONG_TOPIC,
  NAV_GROUPS,
  NAV_GROUPS_EMPTY_ECOSYSTEM,
  NAV_GROUPS_WITH_TREE,
  TOPIC_TABS,
  UNCOUNTED_CLUSTER,
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


describe("BrandBlock", () => {
  /* `SCREENS-V4.md` §2.1. The three parts of the caption are three independent figures, and the
   * rule the whole component exists for is that a part nobody supplied is dropped rather than
   * filled in — every one of the cases below is a way of getting that wrong that reads as a
   * rendering fault or, worse, as a fact. */

  it("writes the three-part caption with one separator between each part", () => {
    const { container, dispose } = mount(() => <BrandBlock cluster={DEFECTIVE_CLUSTER} />);
    expect(container.querySelector(".kui-brand__caption")!.textContent).toBe("1 URP · v3.7.0 · 3 brokers");
    dispose();
  });

  it("takes the health word as the first token when there is no defect to report", () => {
    const { container, dispose } = mount(() => <BrandBlock cluster={HEALTHY_CLUSTER} />);
    // A green dot beside the word "healthy" says one thing twice; "1 URP" above says what the dot
    // cannot. The token is variable in kind, which is the whole reason the caption exists.
    expect(container.querySelector(".kui-brand__caption")!.textContent).toBe("healthy · v3.7.0 · 3 brokers");
    dispose();
  });

  it("says nothing about brokers when nobody has counted them", () => {
    const { container, dispose } = mount(() => <BrandBlock cluster={UNCOUNTED_CLUSTER} />);
    // Not `— brokers`, which reads as a missing dash rather than as a missing figure, and not
    // `0 brokers`, which is an assertion about the cluster nobody made.
    expect(container.textContent).not.toContain("brokers");
    expect(container.textContent).not.toContain("—");
    expect(container.querySelector(".kui-brand__name")!.textContent).toBe("prod-kyiv-01");
    expect(container.querySelector(".kui-brand__dot")).not.toBeNull();
    dispose();
  });

  it("drops the version alone rather than leaving an empty slot for it", () => {
    const { container, dispose } = mount(() => <BrandBlock cluster={VERSIONLESS_CLUSTER} />);
    expect(container.querySelector(".kui-brand__caption")!.textContent).toBe("healthy");
    dispose();
  });

  it("draws no caption at all before the first scrape has answered", () => {
    const { container, dispose } = mount(() => (
      <BrandBlock cluster={{ id: "c", name: "prod-kyiv-01", health: "unknown" }} />
    ));
    expect(container.querySelector(".kui-brand__caption")).toBeNull();
    expect(container.querySelector(".kui-brand__dot--unknown")).not.toBeNull();
    dispose();
  });

  it("takes the registration link from the caller and omits it when there is none", () => {
    // Never a literal: this is the only route to cluster registration in the product, and a
    // hand-written address goes on compiling after a route segment is renamed.
    const withHref = mount(() => <BrandBlock cluster={HEALTHY_CLUSTER} manageHref="/ui/clusters/manage" />);
    const add = withHref.container.querySelector('[data-testid="brand-add-cluster"]') as HTMLAnchorElement;
    expect(add.getAttribute("href")).toBe("/ui/clusters/manage");
    expect(add.textContent).toContain("Add a cluster");
    withHref.dispose();

    const without = mount(() => <BrandBlock cluster={HEALTHY_CLUSTER} />);
    expect(without.container.querySelector('[data-testid="brand-add-cluster"]')).toBeNull();
    without.dispose();
  });

  it("says there is no cluster rather than drawing an empty head", () => {
    const { container, dispose } = mount(() => <BrandBlock manageHref="/ui/clusters/manage" />);
    expect(container.querySelector(".kui-brand__name")!.textContent).toBe("no cluster");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => (
      <BrandBlock cluster={DEFECTIVE_CLUSTER} manageHref="/ui/clusters/manage" />
    ));
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});

describe("StorageMeter", () => {
  it("draws its 'not known' rendering for an empty list, and no zeroed bars", () => {
    // The rendering it has drawn in every deployment since it was built, because until this wave
    // nothing had ever handed it data. An empty bar reads as 0% — "your disks are empty" — which is
    // both wrong and the most comforting available misreading.
    const { container, dispose } = mount(() => <StorageMeter brokers={[]} />);
    expect(container.textContent).toContain("Disk usage could not be read for this cluster.");
    expect(container.querySelector(".kui-storage__percent--unknown")!.textContent).toBe("—");
    expect(container.textContent).not.toContain("0%");
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

  it("renders nothing at all for a group with no destinations", async () => {
    // ECOSYSTEM's only state until M9. A lettered heading over an empty list reads as a list that
    // failed to load, and sends an operator hunting for an outage that does not exist — the same
    // misreading ADR-032's `not_configured → hidden` rule prevents one level up.
    const { container, dispose } = mount(() => (
      <NavDrawer groups={NAV_GROUPS_EMPTY_ECOSYSTEM} cluster={HEALTHY_CLUSTER} />
    ));
    const headings = [...container.querySelectorAll(".kui-nav-group__heading")].map((h) => h.textContent);
    expect(headings).toEqual(["CLUSTER"]);
    expect(container.querySelectorAll(".kui-nav-group__list").length).toBe(1);
    expect(container.textContent).not.toContain("ECOSYSTEM");
    dispose();
  });

  it("orders a nested tree by rank: favourites first, internal last", () => {
    // The fixture is handed over in the wrong order on purpose — `internal` first, the favourite
    // last — because `nav/topicTree.ts` already emits them sorted and a renderer that merely
    // preserved its input would pass every test until somebody assembled the children from two
    // sources the other way round.
    const { container, dispose } = mount(() => (
      <NavDrawer groups={NAV_GROUPS_WITH_TREE} currentId="topics" cluster={HEALTHY_CLUSTER} />
    ));
    const subtree = container.querySelector('[data-testid="nav-topics-subtree"]')!;
    const labels = [...subtree.querySelectorAll(".kui-nav-item__label")].map((el) => el.textContent);
    expect(labels[0]).toBe("orders.payments.v2");
    expect(labels.at(-1)).toBe("internal");
    dispose();
  });

  it("opens and closes a branch from its disclosure, and removes the subtree rather than hiding it", async () => {
    const collapsed = NAV_GROUPS_WITH_TREE.map((group) => ({
      ...group,
      destinations: group.destinations.map((destination) =>
        destination.id === "topics" ? { ...destination, expanded: false } : destination,
      ),
    }));
    const { container, dispose } = mount(() => (
      <NavDrawer groups={collapsed} currentId="topics" cluster={HEALTHY_CLUSTER} />
    ));
    const disclosure = container.querySelector('[data-testid="nav-topics-disclosure"]') as HTMLButtonElement;
    expect(disclosure.getAttribute("aria-expanded")).toBe("false");
    // Not merely hidden: a collapsed subtree left in the document is still in the tab order and
    // still read aloud, which is the whole failure a disclosure exists to prevent.
    expect(container.querySelector('[data-testid="nav-topics-subtree"]')).toBeNull();

    await userEvent.click(disclosure);
    flush();
    expect(disclosure.getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelector('[data-testid="nav-topics-subtree"]')).not.toBeNull();
    dispose();
  });

  it("keeps the label a link and the disclosure a separate control", () => {
    const { container, dispose } = mount(() => (
      <NavDrawer groups={NAV_GROUPS_WITH_TREE} currentId="topics" cluster={HEALTHY_CLUSTER} />
    ));
    // Two affordances, both wanted: an operator who knows which prefix they want expands, and one
    // who wants the whole list clicks the label. Merging them costs whichever loses.
    expect((container.querySelector('[data-testid="nav-topics"]') as HTMLAnchorElement).tagName).toBe("A");
    expect((container.querySelector('[data-testid="nav-topics-disclosure"]') as HTMLElement).tagName).toBe(
      "BUTTON",
    );
    dispose();
  });

  it("puts the cluster at its head, with the registration link the caller supplied", () => {
    const { container, dispose } = mount(() => (
      <NavDrawer groups={NAV_GROUPS} cluster={DEFECTIVE_CLUSTER} manageHref="/ui/clusters/manage" />
    ));
    expect(container.querySelector(".kui-brand__name")!.textContent).toBe("staging-eu-01");
    expect(container.querySelector('[data-testid="brand-add-cluster"]')!.getAttribute("href")).toBe(
      "/ui/clusters/manage",
    );
    // The product wordmark is gone from the drawer entirely (§2.1); the rail marks the product.
    expect(container.textContent).not.toContain("Kafka UI");
    dispose();
  });

  it("has no accessibility violations with a tree expanded", async () => {
    const { container, dispose } = mount(() => (
      <NavDrawer
        groups={NAV_GROUPS_WITH_TREE}
        currentId="topics"
        cluster={HEALTHY_CLUSTER}
        manageHref="/ui/clusters/manage"
      />
    ));
    expect(describeViolations(await findViolations(container))).toBe("");
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

describe("the appearance popover", () => {
  /**
   * Preferences that paint a detached element and remember nothing.
   *
   * The application hands the popover the kernel's module-level singletons; a suite that drove
   * those would share `localStorage` with the next suite, need a browser that has one, and repaint
   * the test runner. The point of the props is exactly that this substitution is possible — and the
   * substitution is the *object*, never a second storage key, because two spellings of one
   * preference is the failure this control exists to avoid.
   */
  const preferences = (): AppearancePreferences => {
    const root = document.createElement("div");
    return {
      theme: createRootPreference<ThemeChoice>({
        attribute: "data-theme",
        storageKey: "kui.theme",
        values: ["auto", "light", "dark"],
        fallback: "auto",
        attributeValue: (chosen) => (chosen === "auto" ? null : chosen),
        storage: null,
        root,
      }),
      accent: createRootPreference<AccentChoice>({
        attribute: "data-accent",
        storageKey: "kui.accent",
        values: ["blue", "teal", "green", "amber"],
        fallback: "blue",
        attributeValue: (chosen) => (chosen === "blue" ? null : chosen),
        storage: null,
        root,
      }),
      density: createRootPreference<DensityChoice>({
        attribute: "data-density",
        storageKey: "kui.density",
        values: ["comfortable", "compact"],
        fallback: "comfortable",
        attributeValue: (chosen) => (chosen === "compact" ? "compact" : null),
        storage: null,
        root,
      }),
    };
  };

  it("offers three theme segments, because the preference has three values", async () => {
    // `SCREENS-V4.md` §7.4, settled in favour of keeping `auto`: it is the default, and it is the
    // one the other two cannot express — a laptop that turns dark at sunset turns KUI with it.
    const chosen = preferences();
    const { container, dispose } = mount(() => <AppearancePopover preferences={chosen} />);
    const group = container.querySelector('[data-testid="appearance-theme"]')!;
    const labels = [...group.querySelectorAll("label")].map((label) => label.textContent?.trim());
    expect(labels).toEqual(["Auto", "Light", "Dark"]);
    dispose();
  });

  it("writes each choice to the preference the settings page writes to", async () => {
    const chosen = preferences();
    const { container, dispose } = mount(() => <AppearancePopover preferences={chosen} />);

    await userEvent.click(container.querySelector('[data-testid="appearance-theme"] input[value="dark"]')!);
    await userEvent.click(container.querySelector('[data-testid="appearance-accent"] input[value="teal"]')!);
    await userEvent.click(
      container.querySelector('[data-testid="appearance-density"] input[value="compact"]')!,
    );
    flush();

    expect(chosen.theme.choice()).toBe("dark");
    expect(chosen.accent.choice()).toBe("teal");
    expect(chosen.density.choice()).toBe("compact");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => <AppearancePopover preferences={preferences()} />);
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });

  it("opens under the top bar's sliders glyph and closes on Escape", async () => {
    const chosen = preferences();
    const { container, dispose } = mount(() => (
      <TopBar
        crumbs={[{ label: "prod-kyiv-01" }]}
        search={{ value: "", onInput: () => {}, platform: "other" }}
        appearance={chosen}
      />
    ));
    const control = container.querySelector('[data-testid="appearance-control"]') as HTMLButtonElement;
    expect(control.getAttribute("aria-expanded")).toBe("false");

    await userEvent.click(control);
    flush();
    expect(container.querySelector('[data-testid="appearance-popover"]')).not.toBeNull();
    expect(control.getAttribute("aria-expanded")).toBe("true");

    await userEvent.keyboard("{Escape}");
    flush();
    expect(container.querySelector('[data-testid="appearance-popover"]')).toBeNull();
    // Focus goes back to the glyph. Escape that left focus on a removed element drops the keyboard
    // user at the top of the document.
    expect(document.activeElement).toBe(control);
    dispose();
  });

  it("closes when the pointer goes down outside it, and not when it goes down inside", async () => {
    const chosen = preferences();
    const { container, dispose } = mount(() => (
      <TopBar search={{ value: "", onInput: () => {}, platform: "other" }} appearance={chosen} />
    ));
    await userEvent.click(container.querySelector('[data-testid="appearance-control"]')!);
    flush();

    // A press that lands on a control inside the panel must not close it: `mousedown` and not
    // `click` is exactly so that a drag which begins on a segment and overshoots it is still the
    // gesture the operator meant.
    await userEvent.pointer({
      keys: "[MouseLeft>]",
      target: container.querySelector('[data-testid="appearance-theme"]')!,
    });
    flush();
    expect(container.querySelector('[data-testid="appearance-popover"]')).not.toBeNull();

    await userEvent.pointer({ keys: "[MouseLeft>]", target: document.body });
    flush();
    expect(container.querySelector('[data-testid="appearance-popover"]')).toBeNull();
    dispose();
  });

  it("cycles the theme preference from the top bar's glyph, and names the mode in words", async () => {
    const chosen = preferences();
    const { container, dispose } = mount(() => (
      <TopBar search={{ value: "", onInput: () => {}, platform: "other" }} appearance={chosen} />
    ));
    const control = container.querySelector('[data-testid="theme-control"]') as HTMLButtonElement;
    expect(control.getAttribute("aria-label")).toContain("Theme: follows system");

    await userEvent.click(control);
    flush();
    expect(chosen.theme.choice()).toBe("light");
    expect(control.getAttribute("aria-label")).toContain("Theme: light");

    await userEvent.click(control);
    flush();
    expect(chosen.theme.choice()).toBe("dark");

    await userEvent.click(control);
    flush();
    // Back to `auto`, which is a state a two-way toggle cannot return to at all.
    expect(chosen.theme.choice()).toBe("auto");
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

describe("the account panel", () => {
  it("does not draw a panel the caller did not supply", () => {
    // A deployment with `authType: "disabled"` passes no handler and no panel. The avatar has to
    // stay a picture: a button that opens an empty panel, or a "Sign out" that clears a session
    // that never existed, both teach the operator that the rail's controls do nothing.
    const { container, dispose } = mount(() => (
      <EnvRail environments={CLUSTERS} currentId="prod-kyiv-01" accountName="Olena Petrenko" />
    ));
    expect(container.querySelector(".kui-rail__account button")).toBeNull();
    expect(container.querySelector('[data-testid="account-menu"]')).toBeNull();
    dispose();
  });

  it("keeps the panel shut until the caller says it is open", () => {
    const { container, dispose } = mount(() => (
      <EnvRail
        environments={CLUSTERS}
        currentId="prod-kyiv-01"
        accountName="Olena Petrenko"
        onOpenAccount={() => undefined}
        accountPanel={<AccountMenu name="olena" onSignOut={() => undefined} />}
      />
    ));
    // Openness belongs to whoever can also close it — Escape, a click elsewhere. A panel that owned
    // its own openness would be one nothing else could dismiss.
    expect(container.querySelector('[data-testid="account-menu"]')).toBeNull();
    dispose();
  });

  it("shows the panel, anchored to the avatar, when it is open", () => {
    const { container, dispose } = mount(() => (
      <EnvRail
        environments={CLUSTERS}
        currentId="prod-kyiv-01"
        accountName="Olena Petrenko"
        onOpenAccount={() => undefined}
        accountOpen
        accountPanel={<AccountMenu name="olena" onSignOut={() => undefined} />}
      />
    ));
    const panel = container.querySelector(".kui-rail__account .kui-rail__account-panel");
    expect(panel).not.toBeNull();
    expect(panel!.querySelector('[data-testid="account-name"]')!.textContent).toBe("olena");
    dispose();
  });

  it("says who is signed in, and offers exactly one way out", async () => {
    let signedOut = 0;
    const { container, dispose } = mount(() => (
      <AccountMenu name="olena.petrenko" authType="form" onSignOut={() => (signedOut += 1)} />
    ));
    const button = container.querySelector("button")!;
    expect(button.textContent).toContain("Sign out");
    await userEvent.click(button);
    await flush();
    expect(signedOut).toBe(1);
    dispose();
  });

  it("refuses a second click while the first sign-out is still out", async () => {
    // Two logouts are harmless at the gateway, but the second one's answer arrives against a page
    // that is already reloading, and its failure would be reported as though the first had failed.
    let signedOut = 0;
    const { container, dispose } = mount(() => (
      <AccountMenu name="olena.petrenko" busy onSignOut={() => (signedOut += 1)} />
    ));
    await userEvent.click(container.querySelector("button")!);
    await flush();
    expect(signedOut).toBe(0);
    dispose();
  });

  it("says the session is still live when signing out did not work", () => {
    const { container, dispose } = mount(() => (
      <AccountMenu
        name="olena.petrenko"
        failure="Signing out did not work. You are still signed in."
        onSignOut={() => undefined}
      />
    ));
    expect(container.textContent).toContain("still signed in");
    // And the way out is still there to try again. A failure that removed the button would leave
    // the operator with a live session and nothing to press.
    expect(container.querySelector("button")!.textContent).toContain("Sign out");
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => (
      <AccountMenu name="olena.petrenko" authType="form" onSignOut={() => undefined} />
    ));
    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});
