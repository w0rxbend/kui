import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { StatTile } from "./StatTile.jsx";
import { StatCard } from "./StatCard.jsx";

/**
 * The landing-page statistic card.
 *
 * Two stories carry the weight. `AgainstAStatCard` is why there are two of these components at all
 * — they differ by the order of two lines, and the difference has to be visible enough that
 * choosing between them is a real decision rather than a coin toss. `TheFourAbsences` is the rule
 * this repository has already broken once: `0`, `—`, "not measured" and a skeleton are four
 * different statements, and a card that renders any two of them the same way reports an outage as
 * good news.
 */
const meta: Meta<typeof StatTile> = {
  title: "Surfaces/StatTile",
  component: StatTile,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StatTile>;

const Grid = (props: { readonly children: unknown }) => (
  <div
    style={{ display: "grid", "grid-template-columns": "repeat(auto-fit, minmax(240px, 1fr))", gap: "20px" }}
  >
    {props.children as never}
  </div>
);

/** The topic list's four tiles, exactly as the design draws them. */
export const TheTopicListRow: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "20px" }}>
      <Grid>
        <StatTile
          label="TOTAL TOPICS"
          icon="topics"
          tone="primary"
          figure={{ kind: "value", text: "128" }}
          chip={{ text: "10 created this month" }}
        />
        <StatTile
          label="TOTAL PARTITIONS"
          icon="partitions"
          tone="accent"
          figure={{ kind: "value", text: "1,536" }}
          chip={{ text: "12 avg per topic", tone: "positive" }}
        />
        <StatTile
          label="TOTAL STORAGE"
          icon="disk"
          tone="warning"
          figure={{ kind: "value", text: "842", unit: "GB" }}
          chip={{ text: "3.2% this week", tone: "positive", icon: "arrow-up-right" }}
        />
        <StatTile
          label="AVG REPLICATION"
          icon="brokers"
          tone="success"
          figure={{ kind: "value", text: "2.9" }}
          chip={{ text: "3 topics at RF 2", tone: "attention" }}
        />
      </Grid>
    </div>
  ),
};

/** The topic overview's four, which put a qualifier under every figure. */
export const TheTopicOverviewRow: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "20px" }}>
      <Grid>
        <StatTile
          label="PARTITIONS"
          icon="partitions"
          tone="primary"
          figure={{ kind: "value", text: "48" }}
          chip={{ text: "RF 3 · min.isr 2" }}
        />
        <StatTile
          label="SIZE ON DISK"
          icon="disk"
          tone="warning"
          figure={{ kind: "value", text: "540.3", unit: "GB" }}
          chip={{ text: "retention 7 days" }}
        />
        <StatTile
          label="PRODUCE RATE"
          icon="arrow-up-right"
          tone="accent"
          figure={{ kind: "value", text: "18,220", unit: "/s" }}
          chip={{ text: "avg message 1.1 KB" }}
        />
        <StatTile
          label="CONSUMER GROUPS"
          icon="consumers"
          tone="success"
          figure={{ kind: "value", text: "1" }}
          chip={{ text: "all replicas in sync", tone: "positive", icon: "check" }}
        />
      </Grid>
    </div>
  ),
};

/**
 * The four ways a tile can fail to have a number, side by side. This is the comparison the
 * component exists for.
 *
 *   - `0` — good news, and a digit.
 *   - skeleton — not yet.
 *   - `—` — we asked and the answer did not come back. Retry might help.
 *   - "not measured" — nobody is measuring this. Retrying will never help; go and configure
 *     something.
 *
 * The last two are the pair that is easiest to collapse into one, and the one that costs the most:
 * they call for opposite actions from the operator.
 */
export const TheFourAbsences: Story = {
  render: () => (
    <Grid>
      <StatTile label="CONSUMER LAG" icon="lag" tone="warning" figure={{ kind: "value", text: "0" }} />
      <StatTile label="CONSUMER LAG" icon="lag" tone="warning" figure={{ kind: "pending" }} />
      <StatTile label="CONSUMER LAG" icon="lag" tone="warning" figure={{ kind: "unknown" }} />
      <StatTile
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "not-measured", why: "No metrics backend is configured for this cluster." }}
      />
    </Grid>
  ),
};

/**
 * The two statistic cards next to each other, with the same numbers.
 *
 * Left, the dashboard's `StatCard`: the figure first, because the operator is scanning for one
 * that is wrong. Right, this component: the label first, because the operator already knows the
 * figures are here and wants a particular one.
 */
export const AgainstAStatCard: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="TOTAL PARTITIONS"
        icon="partitions"
        tone="accent"
        figure={{ kind: "value", text: "1,536" }}
        pill={{ text: "all in sync", tone: "success", icon: "check" }}
      />
      <StatTile
        label="TOTAL PARTITIONS"
        icon="partitions"
        tone="accent"
        figure={{ kind: "value", text: "1,536" }}
        chip={{ text: "12 avg per topic", tone: "positive" }}
      />
    </Grid>
  ),
};

/** Every chip tone, and a tile with no chip, so the set can be judged as a set. */
export const EveryChipTone: Story = {
  render: () => (
    <Grid>
      <StatTile label="NEUTRAL" icon="topics" tone="primary" figure={{ kind: "value", text: "128" }} chip={{ text: "a plain fact" }} />
      <StatTile
        label="POSITIVE"
        icon="brokers"
        tone="success"
        figure={{ kind: "value", text: "3" }}
        chip={{ text: "better than yesterday", tone: "positive", icon: "arrow-up-right" }}
      />
      <StatTile
        label="ATTENTION"
        icon="warning"
        tone="warning"
        figure={{ kind: "value", text: "12" }}
        chip={{ text: "worth a look", tone: "attention", icon: "warning" }}
      />
      <StatTile label="NO CHIP" icon="info" tone="neutral" figure={{ kind: "value", text: "7" }} />
    </Grid>
  ),
};

/** A whole tile as a link. Hover it, then tab to it, and check the focus ring is visible. */
export const AsALink: Story = {
  render: () => (
    <Grid>
      <StatTile
        label="TOTAL TOPICS"
        icon="topics"
        tone="primary"
        figure={{ kind: "value", text: "128" }}
        chip={{ text: "10 created this month" }}
        href="#/topics"
      />
    </Grid>
  ),
};

/**
 * The extremes: the largest number the wire can carry, a label nobody would write, and a chip
 * whose text will not fit. Nothing may overlap, and no tile may push its grid column wider.
 */
export const TheExtremes: Story = {
  render: () => (
    <Grid>
      <StatTile
        label="PARTITIONS UNDER MINIMUM IN-SYNC REPLICAS ACROSS EVERY CONFIGURED CLUSTER"
        icon="warning"
        tone="danger"
        figure={{ kind: "value", text: "18,446,744,073,709,551,615" }}
        chip={{ text: "and this chip's text is longer than the tile it is drawn in", tone: "attention" }}
      />
      <StatTile label="X" icon="dot" tone="neutral" figure={{ kind: "value", text: "1" }} />
    </Grid>
  ),
};

/** The smallest window. One column, nothing scrolling sideways. */
export const NarrowWindow: Story = {
  render: () => (
    <div style={{ width: "260px" }}>
      <StatTile
        label="TOTAL STORAGE"
        icon="disk"
        tone="warning"
        figure={{ kind: "value", text: "842", unit: "GB" }}
        chip={{ text: "3.2% this week", tone: "positive", icon: "arrow-up-right" }}
      />
    </div>
  ),
};
