import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { userEvent, within } from "storybook/test";
import { TopBar } from "./TopBar.jsx";
import { CLUSTERS, LONG_NAME_CLUSTER } from "./fixtures.js";

/**
 * The bar across the top of the content column.
 *
 * Two of the states below are the ones that only occur when something is wrong, and they are the
 * reason this component is worth a story file of its own: the identity service being unavailable
 * must produce a neutral person glyph rather than invented initials, and search being unavailable
 * must leave the box working.
 */
const meta = {
  title: "Shell/TopBar",
  component: TopBar,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => <div style={{ background: "var(--kui-color-surface)", "min-height": "420px" }}>{Story()}</div>,
  ],
} satisfies Meta<typeof TopBar>;

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  search: { value: "", onInput: () => {}, platform: "other" as const },
  clusters: CLUSTERS,
  currentClusterId: "prod-kyiv-01",
  theme: "dark" as const,
  accountName: "Olena Petrenko",
};

/** The design: dark theme, no unread notifications, a signed-in operator. */
export const AsDesigned: Story = { args: base };

/** Auto: the theme follows the machine. Three states, not two — and the glyph says which. */
export const ThemeFollowsSystem: Story = { args: { ...base, theme: "auto" } };

export const ThemeLight: Story = { args: { ...base, theme: "light" } };

/** Unread notifications. The count is in the accessible name; the dot alone would be colour only. */
export const WithUnreadNotifications: Story = { args: { ...base, unreadCount: 3 } };

/**
 * The identity service is unavailable. A neutral person glyph, and an accessible name that admits
 * we do not know who is signed in. Guessing initials in a product where the avatar is how you check
 * whose credentials are about to purge a topic is worse than admitting ignorance.
 */
export const IdentityUnavailable: Story = {
  args: { ...base, accountName: undefined },
};

/** Search is not answering. The field stays enabled and explains itself under the caret. */
export const SearchUnavailable: Story = {
  args: { ...base, search: { value: "orders", onInput: () => {}, status: "failed", platform: "other" } },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/** No cluster configured yet: the selector says so and opens where one is added. */
export const NoCluster: Story = {
  args: { ...base, clusters: [], currentClusterId: undefined },
};

/** The extreme case: the longest cluster name and a four-figure unread count. */
export const LongestEverything: Story = {
  args: {
    ...base,
    clusters: [LONG_NAME_CLUSTER],
    currentClusterId: "long",
    unreadCount: 1287,
  },
};

/**
 * The smallest window this bar is expected to work in. Below 900px the design collapses the search
 * field to a magnifier and the drawer to an overlay; this story is where that transition is judged,
 * and until it is built it is where the overflow is visible.
 */
export const NarrowWindow: Story = {
  args: base,
  parameters: { viewport: { defaultViewport: "mobile2" } },
  decorators: [
    (Story) => (
      <div style={{ width: "420px", background: "var(--kui-color-surface)", "min-height": "420px" }}>{Story()}</div>
    ),
  ],
};

export const ClusterMenuOpen: Story = {
  args: base,
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("cluster-selector-trigger"));
  },
};
