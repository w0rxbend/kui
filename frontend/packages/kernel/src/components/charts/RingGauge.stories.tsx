import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { RingGauge } from "./RingGauge.jsx";

/**
 * A single scalar against an explicit domain.
 *
 * `TheRequestHandlersCard` is the story this component exists for: 71% network idle, 64% IO idle
 * and 38% purgatory, side by side in one card, with the first two green and the third amber. Any
 * gauge that only knows how to worry about small numbers paints the purgatory ring green — which
 * is why `goodDirection` is required and has no default.
 *
 * `Unmeasured` is the second: the plain track and an em dash. Never a full ring, which is what
 * "we could not read the exporter" would otherwise look like.
 */
const meta: Meta<typeof RingGauge> = {
  title: "Charts/RingGauge",
  component: RingGauge,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof RingGauge>;

/** The `--kui-color-surface-hover` sub-tile the design draws each handler gauge on. */
const Tile = (props: { readonly children: unknown }) => (
  <div
    style={{
      display: "flex",
      "align-items": "center",
      "justify-content": "center",
      padding: "16px",
      "border-radius": "12px",
      background: "var(--kui-color-surface-hover)",
    }}
  >
    {props.children as never}
  </div>
);

/**
 * The design's own card. Note that 64% and 38% are both "a percentage in the middle of the range"
 * and they carry opposite news; only `goodDirection` separates them.
 */
export const TheRequestHandlersCard: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "12px", "flex-wrap": "wrap" }}>
      <Tile>
        <RingGauge value={71} caption="network idle" goodDirection="high" />
      </Tile>
      <Tile>
        <RingGauge value={64} caption="io idle" goodDirection="high" />
      </Tile>
      <Tile>
        <RingGauge value={38} caption="purgatory" goodDirection="low" warnAbove={35} criticalAbove={60} />
      </Tile>
    </div>
  ),
};

/** Higher is better, comfortably above both thresholds. */
export const HighIsGood: Story = {
  args: { value: 71, caption: "network idle", goodDirection: "high" },
};

/** Higher is better, and it has fallen into the warning band. */
export const HighIsGoodWarning: Story = {
  args: { value: 22, caption: "io idle", goodDirection: "high" },
};

/** Higher is better, and it has fallen through the floor. */
export const HighIsGoodCritical: Story = {
  args: { value: 6, caption: "io idle", goodDirection: "high" },
};

/** Lower is better, still under the stated limit. */
export const LowIsGood: Story = {
  args: { value: 18, caption: "purgatory", goodDirection: "low", warnAbove: 35, criticalAbove: 60 },
};

/** Lower is better, over the limit — the same 38% that is fine on an idle gauge. */
export const LowIsGoodWarning: Story = {
  args: { value: 38, caption: "purgatory", goodDirection: "low", warnAbove: 35, criticalAbove: 60 },
};

/** Lower is better, well over the alarm. */
export const LowIsGoodCritical: Story = {
  args: { value: 74, caption: "purgatory", goodDirection: "low", warnAbove: 35, criticalAbove: 60 },
};

/** Nothing collected: the bare track and an em dash. Compare with `Zero` directly below it. */
export const Unmeasured: Story = {
  args: { value: undefined, caption: "purgatory", goodDirection: "low" },
};

/**
 * A measured zero, which is a fact and looks different from the absence above: the ring is empty
 * *and* the figure reads `0%`. On a `goodDirection: "high"` gauge that is the worst possible news,
 * so it is red rather than blank.
 */
export const Zero: Story = {
  args: { value: 0, caption: "io idle", goodDirection: "high" },
};

/** Full. The dash-array runs the whole ring, so the arc closes rather than leaving a hairline. */
export const Full: Story = {
  args: { value: 100, caption: "network idle", goodDirection: "high" },
};

/**
 * A domain that is not a percentage, with the figure formatted by the caller: 4,212 messages of
 * lag against a 10,000-message alert threshold. The ring reads the domain; the centre reads the
 * caller's own string.
 */
export const ADomainThatIsNotPercent: Story = {
  args: {
    value: 4212,
    max: 10_000,
    valueText: "4,212",
    caption: "consumer lag",
    goodDirection: "low",
  },
};

/** The stat-card size, beside the handler-tile size, to check both against the design. */
export const Sizes: Story = {
  render: () => (
    <div style={{ display: "flex", "align-items": "center", gap: "20px" }}>
      <RingGauge value={99.98} decimals={2} caption="uptime" goodDirection="high" diameter={44} strokeWidth={5} />
      <RingGauge value={71} caption="network idle" goodDirection="high" />
      <RingGauge value={71} caption="network idle" goodDirection="high" diameter={120} strokeWidth={12} />
    </div>
  ),
};

/**
 * The extreme case: the longest caption a handler gauge could plausibly get, at the smallest size
 * it is drawn at. It has to wrap inside the ring rather than run out over the arc.
 */
export const LongCaption: Story = {
  render: () => (
    <Tile>
      <RingGauge value={38} caption="request purgatory occupancy" goodDirection="low" warnAbove={35} />
    </Tile>
  ),
};
