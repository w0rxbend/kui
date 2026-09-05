import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { userEvent, within } from "storybook/test";
import { ClusterStatusCard } from "./ClusterStatusCard.jsx";
import {
  DEGRADED_CLUSTER,
  HEALTHY_CLUSTER,
  LONG_NAME_CLUSTER,
  UNREACHABLE_CLUSTER,
  VERSIONLESS_CLUSTER,
} from "./fixtures.js";

/**
 * The drawer's foot. This is the product's authoritative statement about the selected cluster: the
 * dot on the brand tile above mirrors it, but this is the one that says it in words.
 */
const meta = {
  title: "Shell/ClusterStatusCard",
  component: ClusterStatusCard,
  decorators: [
    (Story) => <div style={{ width: "196px", background: "var(--kui-color-surface-raised)" }}>{Story()}</div>,
  ],
} satisfies Meta<typeof ClusterStatusCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = { args: { cluster: HEALTHY_CLUSTER } };

export const Degraded: Story = { args: { cluster: DEGRADED_CLUSTER } };

/** Unreachable: danger-toned, says when it was last seen, and the whole card retries. */
export const Unreachable: Story = { args: { cluster: UNREACHABLE_CLUSTER } };

/** Not asked yet. Neutral, not red — "we have not checked" is not "it is broken". */
export const Unknown: Story = {
  args: { cluster: { id: "c", name: "prod-kyiv-01", health: "unknown", version: "v3.7.0" } },
};

/**
 * The version could not be read. "healthy · version unknown", never "healthy · —": a dash next to a
 * word reads as a missing dash rather than as a missing version.
 */
export const VersionUnknown: Story = { args: { cluster: VERSIONLESS_CLUSTER } };

/** No cluster configured. Neutral, and it links to the place where one is added. */
export const NoCluster: Story = { args: { configureHref: "/settings/clusters" } };

/** Unreachable with no last-seen time either: the sentence shortens rather than inventing one. */
export const UnreachableNeverSeen: Story = {
  args: { cluster: { id: "c", name: "prod-kyiv-01", health: "unreachable" } },
};

/**
 * The extreme case: a cluster named after its region, its purpose and its owner, running a vendor
 * build with a long version string. Both lines truncate; neither wraps, because the card is 54px
 * tall and a wrap pushes the drawer's foot over the list.
 */
export const LongestName: Story = { args: { cluster: LONG_NAME_CLUSTER } };

export const Hovered: Story = {
  args: { cluster: HEALTHY_CLUSTER },
  play: async ({ canvasElement }) => {
    await userEvent.hover(within(canvasElement).getByTestId("cluster-status-card"));
  },
};

export const Focused: Story = {
  args: { cluster: HEALTHY_CLUSTER },
  play: async () => {
    await userEvent.tab();
  },
};
