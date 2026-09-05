import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Button } from "./Button.jsx";
import { Card } from "./Card.jsx";
import { Skeleton } from "./EmptyState.jsx";

/**
 * The card is the box almost every region of this product is drawn in, and the reason it has its
 * own long list of stories is that its *states* are the product. A card that only ever draws its
 * happy path has been looked at once and shipped six ways.
 *
 * The order below is deliberate: read down the sidebar and you are reading the four different
 * things an empty-looking box can mean.
 */
const meta: Meta<typeof Card> = {
  title: "Surfaces/Card",
  component: Card,
  parameters: {
    layout: "padded",
  },
  argTypes: {
    state: {
      control: "select",
      options: ["ready", "loading", "empty", "filtered", "unavailable", "forbidden"],
    },
  },
};

export default meta;
type Story = StoryObj<typeof Card>;

/** Enough content to see the padding rhythm against the screenshots. */
const Rows = () => (
  <div style={{ display: "flex", "flex-direction": "column", gap: "8px" }}>
    <div style={{ display: "flex", "justify-content": "space-between" }}>
      <span>payments-processor</span>
      <span>0</span>
    </div>
    <div style={{ display: "flex", "justify-content": "space-between" }}>
      <span>clickstream-etl</span>
      <span>3,861</span>
    </div>
    <div style={{ display: "flex", "justify-content": "space-between" }}>
      <span>fraud-detector</span>
      <span>333</span>
    </div>
  </div>
);

export const Ready: Story = {
  render: () => (
    <Card title="Top consumer lag">
      <Rows />
    </Card>
  ),
};

/** The header's right-hand end stays mounted in every state, including the failing ones. */
export const WithHeaderAction: Story = {
  render: () => (
    <Card title="Throughput" headerEnd={<Button variant="ghost" size="sm">24h</Button>}>
      <Rows />
    </Card>
  ),
};

export const WithCaptionAndFooter: Story = {
  render: () => (
    <Card
      title="Broker health"
      caption="Controller: broker 1. It won the election fair and square."
      footer={<Button variant="ghost" size="sm">View all brokers</Button>}
    >
      <Rows />
    </Card>
  ),
};

/**
 * Skeletons at the size of the real content, so nothing moves when the data lands. A spinner here
 * would move the layout twice — once when it replaces what was there, once when the content
 * arrives at a different size.
 */
export const Loading: Story = {
  render: () => <Card title="Top consumer lag" state="loading" />,
};

/** When the shape is known, describe it, so the card is exactly the right height while it waits. */
export const LoadingWithKnownShape: Story = {
  render: () => (
    <Card
      title="Top consumer lag"
      state="loading"
      loadingBody={
        <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
          <Skeleton height="14px" width="70%" />
          <Skeleton height="14px" width="40%" />
          <Skeleton height="14px" width="55%" />
        </div>
      }
    />
  ),
};

/** Nothing has happened yet. This one may be gently warm — nothing is wrong. */
export const EmptyNothingYet: Story = {
  render: () => (
    <Card
      title="Topics"
      state="empty"
      message="No topics yet."
      description="Create one, or point KUI at a cluster that has some."
      stateAction={<Button variant="primary" icon="plus">Create topic</Button>}
    />
  ),
};

/**
 * The operator's own filter is hiding the data. Different words, and a different action — and it
 * must never be substituted for the story above it, which would send somebody looking for a
 * problem that is not there.
 */
export const EmptyFilteredOut: Story = {
  render: () => (
    <Card
      title="Topics"
      state="filtered"
      message="Nothing matched `payments`."
      description="128 topics are hidden by the current filter."
      stateAction={<Button variant="ghost">Clear filter</Button>}
    />
  ),
};

/**
 * The request did not come back. Note what survives: the card's title, the sentence the operator
 * can act on, the code whoever they escalate to will search for, and a retry.
 */
export const Unavailable: Story = {
  render: () => (
    <Card
      title="Consumer groups"
      state="unavailable"
      message="Consumer group data is unavailable."
      description="The consumer service is not responding."
      code="UPSTREAM_UNAVAILABLE"
      stateAction={<Button variant="secondary" icon="refresh">Retry</Button>}
    />
  ),
};

/** Refused, not broken. The panel is never hidden: hiding it makes the product look incapable. */
export const Forbidden: Story = {
  render: () => (
    <Card
      title="Consumer groups"
      state="forbidden"
      message="You do not have permission to read consumer groups on this cluster."
      description="Ask an administrator for the `consumer:read` permission."
      code="FORBIDDEN"
    />
  ),
};

/**
 * Stale is not a state, it is a layer: the card is `ready`, its content is the last known value,
 * and the badge says how old it is and why. Blanking the panel would throw away the only
 * information anybody has.
 */
export const Stale: Story = {
  render: () => (
    <Card
      title="Top consumer lag"
      stale={{
        asOf: new Date(Date.now() - 4 * 60_000),
        detail: "the metrics service is not answering",
        code: "UPSTREAM_UNAVAILABLE",
      }}
    >
      <Rows />
    </Card>
  ),
};

/**
 * All six, side by side. This is the comparison to make: three healthy panels and one that failed,
 * at the same time, and neither of them lying about the other.
 */
export const EveryState: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div
      style={{
        display: "grid",
        "grid-template-columns": "repeat(auto-fit, minmax(280px, 1fr))",
        gap: "24px",
        padding: "24px",
      }}
    >
      <Card title="Ready">
        <Rows />
      </Card>
      <Card title="Loading" state="loading" />
      <Card title="Empty" state="empty" message="No topics yet." description="Create one." />
      <Card title="Filtered" state="filtered" message="Nothing matched `payments`." stateAction={<Button variant="ghost">Clear filter</Button>} />
      <Card title="Unavailable" state="unavailable" message="Consumer group data is unavailable." code="UPSTREAM_UNAVAILABLE" stateAction={<Button variant="secondary" icon="refresh">Retry</Button>} />
      <Card title="Forbidden" state="forbidden" message="You do not have permission to read this." code="FORBIDDEN" />
    </div>
  ),
};

/**
 * The extreme case. A topic name is arbitrary and can be very long; the title wraps rather than
 * being cut, because a card that ellipsises the object's name has stopped naming the object.
 */
export const LongestPossibleStrings: Story = {
  render: () => (
    <div style={{ "max-width": "320px" }}>
      <Card
        title="orders.payments.reconciliation.v2.eu-central-1.high-throughput.retry.dead-letter"
        state="unavailable"
        message="The consumer service did not answer within the gateway's upstream timeout, so this panel has nothing to show."
        description="The last successful read was four minutes ago. The figures the dashboard is showing elsewhere are from that read."
        code="UPSTREAM_UNAVAILABLE_AFTER_RETRY_BUDGET_EXHAUSTED"
        stateAction={<Button variant="secondary" icon="refresh">Retry</Button>}
      />
    </div>
  ),
};

/**
 * A card in the narrowest window the product supports. The body must not push the card wider than
 * its column, and nothing may cause the page to scroll sideways.
 */
export const NarrowWindow: Story = {
  parameters: { viewport: { defaultViewport: "mobile1" } },
  render: () => (
    <div style={{ width: "260px" }}>
      <Card title="Broker health" caption="Controller: broker 1. It won the election fair and square.">
        <Rows />
      </Card>
    </div>
  ),
};
