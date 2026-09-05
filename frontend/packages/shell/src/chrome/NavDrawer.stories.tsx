import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { NavDrawer } from "./NavDrawer.jsx";
import {
  DEGRADED_CLUSTER,
  HEALTHY_CLUSTER,
  LONG_NAME_CLUSTER,
  NAV_GROUPS,
  NAV_GROUPS_DEGRADED,
  UNREACHABLE_CLUSTER,
  VERSIONLESS_CLUSTER,
} from "./fixtures.js";
import type { NavGroup } from "./types.js";

/**
 * The whole drawer, assembled. The individual pieces have their own stories; these are the
 * combinations that occur in a running product, including the several that no screenshot shows.
 */
const meta = {
  title: "Shell/NavDrawer",
  component: NavDrawer,
  parameters: { layout: "fullscreen" },
  decorators: [
    /* A fixed-height frame, because the drawer's layout is the interesting part: head and foot
     * pinned, only the middle scrolling. A drawer rendered at its content's height proves nothing. */
    (Story) => (
      <div style={{ height: "760px", display: "flex", background: "var(--kui-color-surface)" }}>{Story()}</div>
    ),
  ],
} satisfies Meta<typeof NavDrawer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The design, reproduced: healthy cluster, one group needing attention, KSQL not built yet. */
export const AsDesigned: Story = {
  args: {
    groups: NAV_GROUPS,
    currentId: "dashboard",
    cluster: HEALTHY_CLUSTER,
  },
};

/** A different page selected, to check that the selected pill moves and nothing else does. */
export const TopicsSelected: Story = {
  args: { groups: NAV_GROUPS, currentId: "topics", cluster: HEALTHY_CLUSTER },
};

/** Nothing selected: the shell renders this for a moment on a cold load, and on a 404. */
export const NothingSelected: Story = {
  args: { groups: NAV_GROUPS, cluster: HEALTHY_CLUSTER },
};

/**
 * A broker is down, six groups need attention, the topic count could not be fetched and Connect is
 * not answering. Everything that is wrong is said in words as well as in colour.
 */
export const Degraded: Story = {
  args: { groups: NAV_GROUPS_DEGRADED, currentId: "brokers", cluster: DEGRADED_CLUSTER },
};

/** The cluster is not answering. The card becomes a button that asks again. */
export const ClusterUnreachable: Story = {
  args: { groups: NAV_GROUPS_DEGRADED, currentId: "dashboard", cluster: UNREACHABLE_CLUSTER },
};

/** The version could not be read. "version unknown", never a dash beside a word. */
export const VersionUnknown: Story = {
  args: { groups: NAV_GROUPS, currentId: "dashboard", cluster: VERSIONLESS_CLUSTER },
};

/** A first run: no cluster configured yet. Nothing here is drawn as an error, because it is not one. */
export const NoClusterConfigured: Story = {
  args: { groups: NAV_GROUPS, currentId: "dashboard", configureHref: "/settings/clusters" },
};

/**
 * The capability registry did not answer, so the shell knows the destinations but none of the
 * counts. Every badge is omitted rather than shown as zero, and every destination is still there
 * and still navigable — the page behind it is what explains the outage.
 */
export const NoCountsAvailable: Story = {
  args: {
    groups: NAV_GROUPS.map((group) => ({
      ...group,
      destinations: group.destinations.map(({ badge: _badge, ...rest }) => rest),
    })) satisfies NavGroup[],
    currentId: "dashboard",
    cluster: HEALTHY_CLUSTER,
  },
};

/**
 * The extreme case. A deployment with every ecosystem feature enabled and a cluster named after its
 * region, its purpose and its owner. The list scrolls; the brand block and the cluster card do not
 * move, which is the property this story exists to prove.
 */
export const LongestEverything: Story = {
  args: {
    groups: [
      NAV_GROUPS[0]!,
      {
        heading: "ECOSYSTEM",
        destinations: [
          ...NAV_GROUPS[1]!.destinations,
          { id: "acl", label: "Access control lists and quotas", icon: "lock", href: "/acl" },
          { id: "quotas", label: "Client quotas", icon: "lag", href: "/quotas" },
          { id: "audit", label: "Audit log", icon: "info", href: "/audit" },
          { id: "mirror", label: "MirrorMaker replication flows", icon: "topology", href: "/mirror" },
          { id: "registry", label: "Schema compatibility rules", icon: "schema", href: "/rules" },
          { id: "smt", label: "Single message transforms", icon: "settings", href: "/smt" },
        ],
      },
    ],
    currentId: "mirror",
    cluster: LONG_NAME_CLUSTER,
  },
};

/**
 * The keyboard journey. Tab lands on the first destination and the ring is visible; this story is
 * the one that fails if somebody removes `outline` for tidiness.
 */
export const FocusedDestination: Story = {
  args: { groups: NAV_GROUPS, currentId: "dashboard", cluster: HEALTHY_CLUSTER },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.tab();
    const dashboard = canvas.getByTestId("nav-dashboard");
    await expect(dashboard).toHaveFocus();
  },
};

/** Hover, held, so the fill can be judged against the design's #242930. */
export const HoveredDestination: Story = {
  args: { groups: NAV_GROUPS, currentId: "dashboard", cluster: HEALTHY_CLUSTER },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.hover(canvas.getByTestId("nav-topics"));
  },
};
