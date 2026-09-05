import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { userEvent, within } from "storybook/test";
import { TopBar } from "./TopBar.jsx";
import { LONG_TOPIC } from "./fixtures.js";

/**
 * The band across the top of the content column.
 *
 * It lost two things when the environment rail arrived — the cluster selector and the account
 * avatar — and gained two: the installation breadcrumb on the left, and a notifications panel that
 * opens under the bell. The stories that matter are still the ones where something is wrong:
 * search that is not answering must leave the box usable, and a trail longer than the band must
 * collapse rather than push the controls off the right.
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
  crumbs: [{ label: "prod-kyiv-01", href: "#cluster" }, { label: "Topics" }],
  search: { value: "", onInput: () => {}, platform: "other" as const },
  theme: "dark" as const,
};

/** The design: dark theme, a cluster and a section in the trail, nothing unread. */
export const AsDesigned: Story = { args: base };

/** Auto: the theme follows the machine. Three states, not two — and the glyph says which. */
export const ThemeFollowsSystem: Story = { args: { ...base, theme: "auto" } };

export const ThemeLight: Story = { args: { ...base, theme: "light" } };

/** Unread notifications. The count is in the accessible name; the dot alone would be colour only. */
export const WithUnreadNotifications: Story = { args: { ...base, unreadCount: 3 } };

/** The panel, open under its bell. This is the anchoring the frame relies on. */
export const NotificationsOpen: Story = {
  args: {
    ...base,
    unreadCount: 2,
    notificationsOpen: true,
    notifications: {
      kind: "ready",
      notices: [
        {
          id: "a",
          severity: "warning",
          title: "clickstream-etl is rebalancing",
          body: "12 members, lag climbing past 3.8k.",
          at: new Date(Date.now() - 120_000),
        },
        {
          id: "b",
          severity: "danger",
          title: "Connector elastic-audit-sink failed",
          body: "Task 0: connection refused to es-01:9200.",
          at: new Date(Date.now() - 840_000),
        },
      ],
    },
  },
};

/** The panel with nothing in it. It still opens, and says so — see `Notifications.tsx`. */
export const NotificationsEmpty: Story = {
  args: { ...base, notificationsOpen: true, notifications: { kind: "ready", notices: [] } },
};

/** Search is not answering. The field stays enabled and explains itself under the caret. */
export const SearchUnavailable: Story = {
  args: { ...base, search: { value: "orders", onInput: () => {}, status: "failed", platform: "other" } },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/** The overview, which is the one page with no section in its trail. */
export const AtTheRoot: Story = {
  args: { ...base, crumbs: [{ label: "prod-kyiv-01" }] },
};

/** No cluster chosen yet, so there is no trail at all. The band still draws its controls. */
export const NoTrail: Story = {
  args: { ...base, crumbs: [] },
};

/**
 * The extreme: a trail deep enough to collapse, on a topic name of the length this product meets,
 * and a four-figure unread count.
 *
 * The trail must collapse to `…` rather than wrap or push the controls off the right — a top bar
 * that grows to fit a topic name moves every page's content down.
 */
export const LongestEverything: Story = {
  args: {
    ...base,
    crumbs: [
      { label: "prod-eu-central-1-payments-platform-primary-01", href: "#cluster" },
      { label: "Topics", href: "#topics" },
      { label: LONG_TOPIC, href: "#topic" },
      { label: "Messages" },
    ],
    unreadCount: 1287,
  },
};

/**
 * The smallest window this band is expected to work in. Below 900px the design collapses the search
 * field to a magnifier; this story is where that transition is judged, and until it is built it is
 * where the overflow is visible.
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
