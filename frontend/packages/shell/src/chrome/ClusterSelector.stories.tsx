import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { ClusterSelector } from "./ClusterSelector.jsx";
import { CLUSTERS, HEALTHY_CLUSTER, LONG_NAME_CLUSTER } from "./fixtures.js";

/**
 * Which cluster the whole application is pointed at.
 *
 * The keyboard stories are not padding. This control gave up a native `<select>` in order to show a
 * health dot and a version on each row, and everything the native control did for free has to be
 * written out and checked: Up, Down, Home, End, Enter, Escape, and focus returning to the trigger.
 */
const meta = {
  title: "Shell/ClusterSelector",
  component: ClusterSelector,
  decorators: [(Story) => <div style={{ padding: "8px 0 320px", width: "320px" }}>{Story()}</div>],
} satisfies Meta<typeof ClusterSelector>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Closed: Story = {
  args: { clusters: CLUSTERS, currentId: "prod-kyiv-01" },
};

/** Open, with a healthy, a degraded and an unreachable cluster in the list, plus one with no version. */
export const Open: Story = {
  args: { clusters: CLUSTERS, currentId: "prod-kyiv-01" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};

/**
 * One cluster. The control still opens, because the menu is where "add a cluster" lives — a
 * deployment with a single cluster still needs a way to gain a second.
 */
export const SingleCluster: Story = {
  args: { clusters: [HEALTHY_CLUSTER], currentId: "prod-kyiv-01" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};

/** None configured. Reads "no cluster" and opens straight to the way to add one. Not an error. */
export const NoClusters: Story = {
  args: { clusters: [] },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};

/** The trigger, focused. The ring must be visible, not merely present. */
export const Focused: Story = {
  args: { clusters: CLUSTERS, currentId: "prod-kyiv-01" },
  play: async ({ canvasElement }) => {
    await userEvent.tab();
    await expect(within(canvasElement).getByTestId("cluster-selector-trigger")).toHaveFocus();
  },
};

/**
 * Opened from the keyboard and walked with the arrow keys. The active row is pointed at with
 * `aria-activedescendant` rather than focused, which is the listbox pattern: moving real focus row
 * to row makes a screen reader re-announce the whole container on every key press.
 */
export const KeyboardWalked: Story = {
  args: { clusters: CLUSTERS, currentId: "prod-kyiv-01" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByTestId("cluster-selector-trigger"));
    await userEvent.keyboard("{ArrowDown}{ArrowDown}");
    await expect(canvas.getByTestId("cluster-option-dev-local")).toHaveClass("kui-cluster-select__option--active");
  },
};

/** The extreme case: names longer than the trigger, and a vendor build's version string. */
export const LongestNames: Story = {
  args: {
    clusters: [
      LONG_NAME_CLUSTER,
      { id: "b", name: "staging-eu-central-1-payments-platform-secondary-02", health: "degraded", version: "v3.6.1" },
    ],
    currentId: "long",
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};

/** A long list: the menu scrolls inside itself rather than growing past the window. */
export const ManyClusters: Story = {
  args: {
    clusters: Array.from({ length: 24 }, (_, i) => ({
      id: `c${i}`,
      name: `prod-region-${String(i).padStart(2, "0")}`,
      health: i % 5 === 0 ? ("degraded" as const) : ("healthy" as const),
      version: "v3.7.0",
    })),
    currentId: "c0",
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};
