import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Sparkline } from "./Sparkline.jsx";

/**
 * The 24px trend mark on a stat card.
 *
 * `Empty` is the story that matters. A card with nothing to trend draws *nothing* — not a flat
 * line along the bottom, which would assert that the quantity was measured and found to be zero.
 * `WithAGap` is the second: an exporter that stopped reporting for three buckets breaks the mark
 * in two, rather than running a straight line across the outage.
 *
 * Each story is framed the way the card frames it: a figure on the left, the mark on the right,
 * because this component is never seen on its own and judging it on its own is how it ends up
 * outshouting the number it is a picture of.
 */
const meta: Meta<typeof Sparkline> = {
  title: "Charts/Sparkline",
  component: Sparkline,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Sparkline>;

/** A stat card, near enough: the figure, the label under it, and the mark at the right. */
const Card = (props: {
  readonly figure: string;
  readonly unit?: string;
  readonly label: string;
  readonly children: unknown;
}) => (
  <div
    style={{
      display: "flex",
      "align-items": "center",
      "justify-content": "space-between",
      gap: "16px",
      width: "260px",
      padding: "16px",
      "border-radius": "12px",
      background: "var(--kui-color-surface-elevated)",
      border: "1px solid var(--kui-color-border)",
    }}
  >
    <div>
      <p style={{ margin: 0, "font-size": "28px", "line-height": 1, color: "var(--kui-color-text-strong)" }}>
        {props.figure}
        <span style={{ "font-size": "13px", "margin-left": "4px", color: "var(--kui-color-text-muted)" }}>
          {props.unit ?? ""}
        </span>
      </p>
      <p style={{ margin: "6px 0 0", "font-size": "12px", color: "var(--kui-color-text-muted)" }}>{props.label}</p>
    </div>
    {props.children as never}
  </div>
);

const RISING = [61, 64, 63, 68, 72, 75, 74, 81, 86, 92, 98, 128];
const JAGGED = [62, 58, 71, 66, 54, 49, 41, 73, 78, 74, 91, 86];

/** The design's `Topics 128 total` card: a rising trend, drawn in the muted ink. */
export const Rising: Story = {
  render: () => (
    <Card figure="128" unit="total" label="Topics">
      <Sparkline points={RISING} />
    </Card>
  ),
};

/** The produce-rate card. The same mark, a noisier series — the shape is the whole message. */
export const Jagged: Story = {
  render: () => (
    <Card figure="86.4" unit="MB/s" label="Produce rate">
      <Sparkline points={JAGGED} />
    </Card>
  ),
};

/**
 * `Partitions in sync` at 99.1% for twelve buckets. A domain of zero-to-max would draw this as a
 * line at the top of the box; the data's own range draws it down the middle, which is what
 * "nothing changed" looks like.
 */
export const Flat: Story = {
  render: () => (
    <Card figure="99.1" unit="%" label="Partitions in sync">
      <Sparkline points={[99.1, 99.1, 99.1, 99.1, 99.1, 99.1, 99.1, 99.1]} />
    </Card>
  ),
};

/** The exporter was down for three buckets. Two marks, not one line drawn across the hole. */
export const WithAGap: Story = {
  render: () => (
    <Card figure="71.2" unit="MB/s" label="Consume rate">
      <Sparkline points={[62, 58, 71, null, null, null, 78, 74, 91, 86]} />
    </Card>
  ),
};

/** One surviving measurement. A dot, because a line through a single point is nothing at all. */
export const SinglePoint: Story = {
  render: () => (
    <Card figure="4.1" unit="MB/s" label="Produce rate">
      <Sparkline points={[null, null, 4.1, null, null]} />
    </Card>
  ),
};

/**
 * Nothing collected. The card keeps its figure and draws no mark — the case the design is explicit
 * about, because a flat line at zero is a claim and an absence is not.
 */
export const Empty: Story = {
  render: () => (
    <Card figure="—" label="Consumer lag">
      <Sparkline points={[]} />
    </Card>
  ),
};

/** Every bucket a gap, which is the same picture as none at all and must not be a different one. */
export const AllGaps: Story = {
  render: () => (
    <Card figure="—" label="Consumer lag">
      <Sparkline points={[null, null, null, null]} />
    </Card>
  ),
};

/** A status ink, for the rare caller whose card is about health rather than about volume. */
export const Toned: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "16px", "flex-wrap": "wrap" }}>
      <Card figure="12" unit="partitions" label="Under-replicated">
        <Sparkline points={[0, 0, 2, 4, 9, 12, 12, 12]} tone="warning" />
      </Card>
      <Card figure="99.98" unit="%" label="Controller uptime">
        <Sparkline points={[99.9, 99.94, 99.96, 99.97, 99.98, 99.98]} tone="success" />
      </Card>
    </div>
  ),
};

/**
 * The extreme case: 180 buckets in a 64px box, which is what a 30-day range does to a mark built
 * for a day. It has to stay a shape rather than becoming a solid block.
 */
export const ManyBuckets: Story = {
  render: () => (
    <Card figure="86.4" unit="MB/s" label="Produce rate">
      <Sparkline points={Array.from({ length: 180 }, (_, i) => 50 + Math.sin(i / 6) * 30 + (i % 7))} />
    </Card>
  ),
};

/** Stretched wide, to check that the stroke stays 2px rather than scaling with the box. */
export const Wide: Story = {
  render: () => (
    <div style={{ width: "480px" }}>
      <Sparkline points={JAGGED} width={480} height={48} />
    </div>
  ),
};
