import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { TabStrip } from "./TabStrip.jsx";
import type { Tab } from "./TabStrip.jsx";

/** The topic page's own strip, which is where this component is actually used. */
const TOPIC_TABS: readonly Tab[] = [
  { id: "overview", label: "Overview", icon: "info", href: "#overview" },
  { id: "messages", label: "Messages", icon: "messages", href: "#messages" },
  { id: "consumers", label: "Consumers", icon: "consumers", href: "#consumers", count: 14 },
  { id: "settings", label: "Settings", icon: "settings", href: "#settings" },
];

/**
 * The tab strip under a page header.
 *
 * These are links, not ARIA tabs: opening one changes the URL and loads a different page. The
 * distinction is invisible on screen and load-bearing to a screen reader, which would otherwise be
 * promised that Left and Right move between panels that are already in the document.
 */
const meta = {
  title: "Shell/TabStrip",
  component: TabStrip,
  decorators: [(Story) => <div style={{ width: "720px" }}>{Story()}</div>],
} satisfies Meta<typeof TabStrip>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The design: four tabs on a topic, Messages selected, Consumers carrying a count. */
export const AsDesigned: Story = {
  args: { tabs: TOPIC_TABS, currentId: "messages", label: "Topic sections" },
};

export const FirstSelected: Story = {
  args: { tabs: TOPIC_TABS, currentId: "overview", label: "Topic sections" },
};

/**
 * A compacted topic, which has no retention settings. The tab is omitted rather than disabled:
 * "does not apply" and "temporarily broken" are different statements, and a dimmed tab says the
 * second one.
 */
export const TabOmitted: Story = {
  args: { tabs: TOPIC_TABS.filter((t: Tab) => t.id !== "settings"), currentId: "messages", label: "Topic sections" },
};

/** A single tab. Still a strip, still underlined, so the page does not change shape. */
export const SingleTab: Story = {
  args: { tabs: [TOPIC_TABS[0]!], currentId: "overview", label: "Topic sections" },
};

/** Nothing to show. The rule stays, so the header does not jump when tabs arrive. */
export const Empty: Story = { args: { tabs: [], currentId: "", label: "Topic sections" } };

/** A count of zero is still a count and is still printed: zero consumers is a fact worth knowing. */
export const ZeroCount: Story = {
  args: {
    tabs: TOPIC_TABS.map((t: Tab) => (t.id === "consumers" ? { ...t, count: 0 } : t)),
    currentId: "consumers",
    label: "Topic sections",
  },
};

/** The extreme case: the largest count this product will print, and long labels with it. */
export const LargestCount: Story = {
  args: {
    tabs: [
      { id: "overview", label: "Overview", icon: "info", href: "#" },
      { id: "messages", label: "Messages", icon: "messages", href: "#", count: 18_442_901 },
      { id: "consumers", label: "Consumer groups and their members", icon: "consumers", href: "#", count: 1287 },
      { id: "settings", label: "Configuration and retention", icon: "settings", href: "#" },
      { id: "acl", label: "Access control", icon: "lock", href: "#" },
      { id: "schema", label: "Schema", icon: "schema", href: "#" },
    ],
    currentId: "messages",
    label: "Topic sections",
  },
};

/**
 * More tabs than room. The strip scrolls inside its own box; the page never scrolls sideways,
 * because a page that scrolls sideways hides the right-hand end of every row on it.
 */
export const NarrowWindow: Story = {
  args: LargestCount.args,
  decorators: [(Story) => <div style={{ width: "320px" }}>{Story()}</div>],
};

export const FocusedTab: Story = {
  args: { tabs: TOPIC_TABS, currentId: "messages", label: "Topic sections" },
  play: async ({ canvasElement }) => {
    await userEvent.tab();
    await expect(within(canvasElement).getByTestId("tab-overview")).toHaveFocus();
  },
};

export const HoveredTab: Story = {
  args: { tabs: TOPIC_TABS, currentId: "messages", label: "Topic sections" },
  play: async ({ canvasElement }) => {
    await userEvent.hover(within(canvasElement).getByTestId("tab-consumers"));
  },
};
