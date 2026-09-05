import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { StatCard } from "./StatCard.jsx";

/**
 * The four cards across the top of the dashboard.
 *
 * The stories that matter here are the last five: a zero, an unknown, a pending value and the two
 * extremes. `0` and `—` mean opposite things, and the whole point of this component is that they
 * cannot be confused — which is only checkable by looking at them next to each other.
 */
const meta: Meta<typeof StatCard> = {
  title: "Surfaces/StatCard",
  component: StatCard,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StatCard>;

const Grid = (props: { readonly children: unknown }) => (
  <div
    style={{
      display: "grid",
      "grid-template-columns": "repeat(auto-fit, minmax(240px, 1fr))",
      gap: "24px",
    }}
  >
    {props.children as never}
  </div>
);

/** The dashboard's four cards, exactly as the screenshots draw them. */
export const TheDashboardRow: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Grid>
        <StatCard
          label="BROKERS ONLINE"
          icon="brokers"
          tone="success"
          figure={{ kind: "value", text: "3", unit: "/3" }}
          pill={{ text: "all in sync", tone: "success", icon: "check" }}
        />
        <StatCard
          label="TOPICS"
          icon="topics"
          tone="primary"
          figure={{ kind: "value", text: "128" }}
          pill={{ text: "1,536 partitions", tone: "neutral" }}
        />
        <StatCard
          label="PRODUCTION"
          icon="arrow-up-right"
          tone="accent"
          figure={{ kind: "value", text: "86.4", unit: "MB/s" }}
          pill={{ text: "12% vs last hour", tone: "accent", icon: "arrow-up-right" }}
        />
        <StatCard
          label="CONSUMER LAG"
          icon="lag"
          tone="warning"
          figure={{ kind: "value", text: "4,212" }}
          pill={{ text: "fashionably late", tone: "warning" }}
        />
      </Grid>
    </div>
  ),
};

/**
 * The three renderings of a figure, side by side. This is the comparison the component exists for.
 *
 * `0` is good news and is a digit. `—` is "we could not read this" and is never a zero. The
 * skeleton is "not yet", and is a third picture again — a pending value must not look like an
 * absent one.
 */
export const ZeroPendingAndUnknown: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "value", text: "0" }}
        pill={{ text: "all caught up", tone: "success", icon: "check" }}
      />
      <StatCard label="CONSUMER LAG" icon="lag" tone="warning" figure={{ kind: "pending" }} />
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "unknown" }}
        pill={{ text: "metrics unavailable", tone: "neutral" }}
      />
    </Grid>
  ),
};

/**
 * The failed card's pill is **neutral**, not red. The metrics service being unreachable is not the
 * cluster being unhealthy, and a red pill here teaches the operator to distrust red — after which
 * the red that matters is not read either.
 */
export const FailedIsNotUnhealthy: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "value", text: "2", unit: "/3" }}
        pill={{ text: "broker 3 offline", tone: "danger", icon: "warning" }}
      />
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "unknown" }}
        pill={{ text: "cluster not answering", tone: "neutral" }}
      />
    </Grid>
  ),
};

/** Every tile tone, so the set can be judged as a set rather than one at a time. */
export const EveryTone: Story = {
  render: () => (
    <Grid>
      <StatCard label="PRIMARY" icon="topics" tone="primary" figure={{ kind: "value", text: "128" }} />
      <StatCard label="ACCENT" icon="arrow-up-right" tone="accent" figure={{ kind: "value", text: "86.4", unit: "MB/s" }} />
      <StatCard label="SUCCESS" icon="brokers" tone="success" figure={{ kind: "value", text: "3", unit: "/3" }} />
      <StatCard label="WARNING" icon="lag" tone="warning" figure={{ kind: "value", text: "4,212" }} />
      <StatCard label="DANGER" icon="warning" tone="danger" figure={{ kind: "value", text: "2" }} />
      <StatCard label="NEUTRAL" icon="info" tone="neutral" figure={{ kind: "value", text: "0" }} />
    </Grid>
  ),
};

/** A whole card as a link: hover it, and tab to it, and check the focus ring is visible. */
export const AsALink: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "value", text: "3", unit: "/3" }}
        pill={{ text: "all in sync", tone: "success", icon: "check" }}
        href="#/brokers"
      />
    </Grid>
  ),
};

/**
 * The extremes: the largest number the wire can carry, the longest label anyone has written, and
 * a pill whose text will not fit. Nothing may overlap, and the figure must not push the card
 * wider than its grid column.
 */
export const TheExtremes: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="PARTITIONS UNDER MINIMUM IN-SYNC REPLICAS ACROSS EVERY CONFIGURED CLUSTER"
        icon="warning"
        tone="danger"
        figure={{ kind: "value", text: "18,446,744,073,709,551,615" }}
        pill={{ text: "this pill's text is far longer than the card it sits in", tone: "danger", icon: "warning" }}
      />
      <StatCard
        label="X"
        icon="dot"
        tone="neutral"
        figure={{ kind: "value", text: "1" }}
        pill={{ text: "ok", tone: "success" }}
      />
    </Grid>
  ),
};

/** The smallest window. Four cards become one column; nothing scrolls sideways. */
export const NarrowWindow: Story = {
  render: () => (
    <div style={{ width: "260px" }}>
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "value", text: "4,212" }}
        pill={{ text: "fashionably late", tone: "warning" }}
      />
    </div>
  ),
};
