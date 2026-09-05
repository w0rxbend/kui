import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { Breadcrumb } from "./Breadcrumb.jsx";
import { LONG_TOPIC } from "./fixtures.js";

/**
 * The trail above a page title.
 *
 * The stories that matter are the long ones. Kafka names are long, and the two obvious failures — a
 * trail that wraps to a second line and pushes the title down, and a trail that overflows and makes
 * the whole page scroll sideways — are both visible here at a glance.
 */
const meta = {
  title: "Shell/Breadcrumb",
  component: Breadcrumb,
  decorators: [(Story) => <div style={{ width: "520px", padding: "8px" }}>{Story()}</div>],
} satisfies Meta<typeof Breadcrumb>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The design: two levels. */
export const AsDesigned: Story = {
  args: { trail: [{ label: "Topics", href: "/topics" }, { label: "orders.payments.v2" }] },
};

/** One level: the page is a root. The single crumb is the current page and is not a link. */
export const SingleCrumb: Story = { args: { trail: [{ label: "Consumer groups" }] } };

/** Three levels, which is the deepest trail the product currently produces. */
export const ThreeLevels: Story = {
  args: {
    trail: [
      { label: "Topics", href: "/topics" },
      { label: "orders.payments.v2", href: "/topics/orders.payments.v2" },
      { label: "Partition 7" },
    ],
  },
};

/** Empty. Renders nothing visible but keeps its landmark, so the page's structure does not change. */
export const Empty: Story = { args: { trail: [] } };

/**
 * Longer than the width. The middle collapses to a button, not to a decorative ellipsis: an
 * ellipsis nobody can press is a lie about there being more.
 */
export const Collapsed: Story = {
  args: {
    trail: [
      { label: "Clusters", href: "/clusters" },
      { label: "prod-kyiv-01", href: "/clusters/prod-kyiv-01" },
      { label: "Topics", href: "/topics" },
      { label: "orders.payments.v2", href: "/topics/orders.payments.v2" },
      { label: "Partition 7" },
    ],
  },
};

/** The same trail, expanded by pressing the button. */
export const CollapsedThenExpanded: Story = {
  args: Collapsed.args,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByTestId("breadcrumb-expand"));
    await expect(canvas.queryByTestId("breadcrumb-expand")).toBeNull();
  },
};

/** The extreme case: a real Kafka topic name in a trail. It truncates; it does not wrap. */
export const LongestName: Story = {
  args: { trail: [{ label: "Topics", href: "/topics" }, { label: LONG_TOPIC }] },
};

/** The same trail in a narrow column, which is where a wrap would show itself. */
export const LongestNameNarrow: Story = {
  args: { trail: [{ label: "Topics", href: "/topics" }, { label: LONG_TOPIC }] },
  decorators: [(Story) => <div style={{ width: "240px", padding: "8px" }}>{Story()}</div>],
};

export const FocusedLink: Story = {
  args: { trail: [{ label: "Topics", href: "/topics" }, { label: "orders.payments.v2" }] },
  play: async () => {
    await userEvent.tab();
  },
};
