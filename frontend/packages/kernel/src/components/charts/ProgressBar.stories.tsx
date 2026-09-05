/**
 * Stories for the progress bar.
 *
 * The two that matter most are `Unknown` and `Zero`, side by side in `EveryState`: a 0%-full disk
 * and a disk nobody could measure mean opposite things, and if they draw the same picture the
 * product is quietly lying every time an exporter goes down.
 */
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Card } from "../Card.jsx";
import { ProgressBar } from "./ProgressBar.jsx";

const meta: Meta<typeof ProgressBar> = {
  title: "Charts/ProgressBar",
  component: ProgressBar,
  decorators: [
    (Story: () => JSX.Element) => <div style={{ width: "420px" }}>{Story()}</div>,
  ],
};
export default meta;

type Story = StoryObj<typeof ProgressBar>;

/** Under the warning threshold: the fill is success-toned, as broker-1 is in the design. */
export const Normal: Story = {
  args: { label: "broker-1.kyiv disk usage", caption: "disk", value: 61 },
};

/** Past 75%. The design's broker-3 sits here, and the bar turns amber. */
export const Warning: Story = {
  args: { label: "broker-3.kyiv disk usage", caption: "disk", value: 83 },
};

/** Past 90%. The colour changes and so does the weight of the figure — colour is never alone. */
export const Critical: Story = {
  args: { label: "broker-2.kyiv disk usage", caption: "disk", value: 96 },
};

/** A genuine zero. The track is empty and the figure reads `0%`. */
export const Zero: Story = {
  args: { label: "broker-4.kyiv disk usage", caption: "disk", value: 0 },
};

/** Full. The fill reaches both rounded ends of the track rather than stopping short of them. */
export const Full: Story = {
  args: { label: "broker-5.kyiv disk usage", caption: "disk", value: 100 },
};

/**
 * Unmeasurable. No fill at all and an em dash where the percentage goes — deliberately a different
 * picture from `Zero` above, which is the whole point of the component.
 */
export const Unknown: Story = {
  args: { label: "broker-6.kyiv disk usage", caption: "disk", value: undefined },
};

/**
 * The guarded denominator. A max of zero cannot produce a full bar, however the caller got there.
 */
export const ZeroMaximum: Story = {
  args: { label: "queue depth", caption: "queue", value: 40, max: 0, valueText: "40 of 0" },
};

/** A caller that formats its own figure — bytes, not per cent. */
export const CustomValueText: Story = {
  args: { label: "broker-1.kyiv disk usage", caption: "disk", value: 61, valueText: "3.4 / 5.6 TB" },
};

/** A value beyond the maximum. Clamped to the track rather than overflowing it. */
export const OverMaximum: Story = {
  args: { label: "partition count", caption: "used", value: 1800, max: 1536, valueText: "117%" },
};

/** Every state at once, which is the only way to see that they are actually distinguishable. */
export const EveryState: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "16px" }}>
      <ProgressBar label="normal" caption="disk" value={61} />
      <ProgressBar label="warning" caption="disk" value={83} />
      <ProgressBar label="critical" caption="disk" value={96} />
      <ProgressBar label="zero" caption="disk" value={0} />
      <ProgressBar label="unknown" caption="disk" value={undefined} />
    </div>
  ),
};

/**
 * The extreme case: a very long caption and a very long figure in a narrow container. The track
 * must keep a usable width and nothing may push the row into a horizontal scroll.
 */
export const Narrow: Story = {
  render: () => (
    <div style={{ width: "220px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <ProgressBar
        label="broker-12.eu-central-1.internal disk usage"
        caption="log directory"
        value={83}
        valueText="18,446,744,073,709 GB"
      />
    </div>
  ),
};

/**
 * The broker-health panel as the dashboard composes it, including the caption at its foot.
 *
 * The caption is where SPEC §6.3 rule 3 lives: the aside ("It won the election fair and square")
 * is attached to the *healthy* branch only. `NoController` below is the same panel with the
 * failure branch, and there is deliberately no joke on it — a cheerful sentence over a broken
 * cluster tells the operator the product has not noticed.
 */
export const BrokerHealthPanel: Story = {
  render: () => (
    <Card title="Broker health" caption="Controller: broker 1. It won the election fair and square.">
      <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
        <ProgressBar label="broker-1.kyiv disk usage" caption="disk" value={61} />
        <ProgressBar label="broker-2.kyiv disk usage" caption="disk" value={58} />
        <ProgressBar label="broker-3.kyiv disk usage" caption="disk" value={83} />
      </div>
    </Card>
  ),
};

/** The same panel with no controller elected. Plain voice, and the caption turns red. */
export const NoController: Story = {
  render: () => (
    <Card
      title="Broker health"
      caption={<span class="kui-chart-caption--alarm">No controller. The cluster has not elected one.</span>}
    >
      <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
        <ProgressBar label="broker-1.kyiv disk usage" caption="disk" value={61} />
        <ProgressBar label="broker-2.kyiv disk usage" caption="disk" value={undefined} />
        <ProgressBar label="broker-3.kyiv disk usage" caption="disk" value={96} />
      </div>
    </Card>
  ),
};
