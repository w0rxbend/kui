import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { NavItem } from "./NavItem.jsx";
import { LONG_TOPIC, TOPIC_TREE } from "./fixtures.js";

/**
 * One destination, in every state it has.
 *
 * The badge stories are the ones worth reading. A badge is a number with a meaning attached, and
 * the tone follows the meaning: `3/3` brokers is success and `2/3` is danger, while `128` topics is
 * neutral however large it grows, because a lot of topics is not a problem.
 */
const meta = {
  title: "Shell/NavItem",
  component: NavItem,
  decorators: [
    (Story) => (
      <ul style={{ width: "180px", padding: 0, margin: 0, background: "var(--kui-color-surface-raised)" }}>
        {Story()}
      </ul>
    ),
  ],
} satisfies Meta<typeof NavItem>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Idle: Story = {
  args: { destination: { id: "dashboard", label: "Dashboard", icon: "dashboard", href: "/dashboard" } },
};

export const Current: Story = {
  args: {
    destination: { id: "dashboard", label: "Dashboard", icon: "dashboard", href: "/dashboard" },
    current: true,
  },
};

export const Hovered: Story = {
  args: { destination: { id: "topics", label: "Topics", icon: "topics", href: "/topics" } },
  play: async ({ canvasElement }) => {
    await userEvent.hover(within(canvasElement).getByTestId("nav-topics"));
  },
};

export const Focused: Story = {
  args: { destination: { id: "topics", label: "Topics", icon: "topics", href: "/topics" } },
  play: async ({ canvasElement }) => {
    await userEvent.tab();
    await expect(within(canvasElement).getByTestId("nav-topics")).toHaveFocus();
  },
};

/** Healthy: every broker is online. */
export const BadgeSuccess: Story = {
  args: {
    destination: {
      id: "brokers",
      label: "Brokers",
      icon: "brokers",
      href: "/brokers",
      badge: { text: "3/3", tone: "success", description: "3 of 3 online" },
    },
  },
};

/** The same badge shape, the opposite meaning. `2/3` is red, and it is never green. */
export const BadgeDanger: Story = {
  args: {
    destination: {
      id: "brokers",
      label: "Brokers",
      icon: "brokers",
      href: "/brokers",
      badge: { text: "2/3", tone: "danger", description: "2 of 3 online, 1 offline" },
    },
  },
};

export const BadgeWarning: Story = {
  args: {
    destination: {
      id: "consumers",
      label: "Consumers",
      icon: "consumers",
      href: "/consumers",
      badge: { text: "1", tone: "warning", description: "1 group needs attention" },
    },
  },
};

/** A count, and counts are never a problem. */
export const BadgeNeutral: Story = {
  args: {
    destination: {
      id: "topics",
      label: "Topics",
      icon: "topics",
      href: "/topics",
      badge: { text: "128", tone: "neutral", description: "128 topics" },
    },
  },
};

/**
 * The count could not be fetched. The badge is absent, because the two alternatives both lie: `0`
 * is a statement about the cluster, and a spinner in a 20px badge is three grey pixels that read as
 * a rendering fault.
 */
export const BadgeUnavailable: Story = {
  args: { destination: { id: "topics", label: "Topics", icon: "topics", href: "/topics" } },
};

/**
 * Refused. Present, dimmed, not focusable, and it says why on hover and to a screen reader — the
 * one state ADR-032 renders as a disabled row, and the only one `destinationFor` produces one for.
 * A dead row with no explanation is worse than no row at all.
 *
 * It was a "soon" badge over `KSQL DB` until this wave, which was true while ksqlDB was unbuilt.
 * It is built now, so the story draws a state a principal can actually be in.
 */
export const DisabledForbidden: Story = {
  args: {
    destination: {
      id: "ksql",
      label: "ksqlDB",
      icon: "ksql",
      href: "/ksql",
      disabled: true,
      disabledReason: "You do not have permission to run ksqlDB statements on this cluster",
    },
  },
};

/** Disabled for a different reason: the service behind it is down, and the reason says so. */
export const DisabledUnavailable: Story = {
  args: {
    destination: {
      id: "connect",
      label: "Kafka Connect",
      icon: "connect",
      href: "/connect",
      disabled: true,
      disabledReason: "The connect service is not answering",
    },
  },
};

/**
 * The extreme case: a label longer than the drawer and a four-character badge. The label truncates
 * with an ellipsis and the badge is never squeezed, because the badge is the part that cannot be
 * guessed from context.
 */
export const LongestLabel: Story = {
  args: {
    destination: {
      id: "long",
      label: LONG_TOPIC,
      icon: "topics",
      href: "/topics",
      badge: { text: "9,999", tone: "neutral", description: "9,999 topics" },
    },
  },
};

/**
 * A branch: the row is a link *and* a disclosure, and the two are separate controls.
 *
 * Clicking the label goes to the topic list; clicking the chevron opens the rows beneath. Merging
 * them — a row that toggles, or a chevron that navigates — would cost whichever affordance lost,
 * and both are wanted.
 */
export const Expanded: Story = {
  args: {
    destination: {
      id: "topics",
      label: "Topics",
      icon: "topics",
      href: "/topics",
      badge: { text: "128", tone: "neutral", description: "128 topics" },
      children: TOPIC_TREE,
      expanded: true,
    },
  },
};

/** The same row closed. The subtree is removed from the document, not hidden with CSS. */
export const Collapsed: Story = {
  args: {
    destination: {
      id: "topics",
      label: "Topics",
      icon: "topics",
      href: "/topics",
      badge: { text: "128", tone: "neutral", description: "128 topics" },
      children: TOPIC_TREE,
    },
  },
};

/**
 * The disclosure worked from the keyboard, which is what a real `<button>` buys and a `div` with a
 * click handler does not.
 */
export const DisclosureOpenedByKeyboard: Story = {
  args: {
    destination: {
      id: "topics",
      label: "Topics",
      icon: "topics",
      href: "/topics",
      children: TOPIC_TREE,
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.tab();
    await userEvent.tab();
    await expect(canvas.getByTestId("nav-topics-disclosure")).toHaveFocus();
    await userEvent.keyboard("{Enter}");
    await expect(canvas.getByTestId("nav-topics-subtree")).toBeInTheDocument();
  },
};
