import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { ChartLegend } from "./ChartLegend.jsx";
import { StackedBar, type StackedBarSegment } from "./StackedBar.jsx";

/**
 * Parts of a capacity, sized by their share.
 *
 * `TheStorageCard` is the design's own picture and shows the legend contract: **one** legend under
 * three rows, not one per row, because a key repeated three times is not three keys.
 *
 * `UnknownCapacity` is the story that matters most. A broker whose log directories reported no
 * size has no denominator, so it draws the neutral track and no fill — the same rule
 * `diskPercentOf` applies. It is deliberately placed next to `Empty`, a broker with a known,
 * measured, genuinely-empty disk, because those two mean opposite things and must not look alike.
 */
const meta: Meta<typeof StackedBar> = {
  title: "Charts/StackedBar",
  component: StackedBar,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StackedBar>;

const GB = 1024 ** 3;
const gigabytes = (value: number): string => `${Math.round(value / GB)} GB`;

/**
 * Four prefixes in one bar — which is why `--kui-color-series-6` had to be a real token rather
 * than an alias of one of the first five (SCREENS-V4.md §0.1): an alias would repeat an ink
 * inside this single mark.
 */
const KEYS = [
  { label: "analytics.*", tone: "series-1" as const },
  { label: "inventory.*", tone: "series-2" as const },
  { label: "orders.*", tone: "series-4" as const },
  { label: "other", tone: "series-6" as const },
];

const row = (values: readonly number[]): StackedBarSegment[] =>
  KEYS.map((key, i) => ({ ...key, value: (values[i] ?? 0) * GB }));

/** The design's card: three brokers, one shared legend, the hottest figure inked amber. */
export const TheStorageCard: Story = {
  render: () => (
    <div style={{ width: "520px", display: "flex", "flex-direction": "column", gap: "14px" }}>
      <div style={{ display: "flex", "flex-direction": "column", gap: "10px" }}>
        <StackedBar
          label="broker-1 disk usage"
          segments={row([120, 96, 88, 43])}
          capacity={419 * GB}
          valueText="347 GB"
          format={gigabytes}
        />
        <StackedBar
          label="broker-2 disk usage"
          segments={row([88, 70, 64, 32])}
          capacity={419 * GB}
          valueText="254 GB"
          format={gigabytes}
        />
        <StackedBar
          label="broker-3 disk usage"
          segments={row([84, 66, 62, 29])}
          capacity={419 * GB}
          valueText="241 GB"
          format={gigabytes}
        />
      </div>
      <ChartLegend items={KEYS.map(k => ({ label: k.label, tone: k.tone }))} />
    </div>
  ),
};

/** A single bar standing alone, which is the one case that draws its own legend. */
export const WithItsOwnLegend: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar
        label="broker-1 disk usage"
        segments={row([120, 96, 88, 43])}
        capacity={419 * GB}
        valueText="347 GB"
        format={gigabytes}
        legend
      />
    </div>
  ),
};

/** Past the alarm threshold: the segments keep their prefix inks, the figure turns red. */
export const Critical: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar
        label="broker-4 disk usage"
        segments={row([160, 120, 92, 24])}
        capacity={419 * GB}
        valueText="396 GB"
        format={gigabytes}
      />
    </div>
  ),
};

/**
 * No capacity reported. The neutral track and nothing else — not a full bar, and not a bar sized
 * against the segments' own sum, which would be a ratio computed against nothing.
 */
export const UnknownCapacity: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar
        label="broker-5 disk usage"
        segments={row([120, 96, 88, 43])}
        capacity={undefined}
        valueText="—"
        format={gigabytes}
      />
    </div>
  ),
};

/** A measured, genuinely empty disk: a known capacity, no segments, and a real `0 GB`. */
export const Empty: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar label="broker-6 disk usage" segments={[]} capacity={419 * GB} valueText="0 GB" format={gigabytes} />
    </div>
  ),
};

/** One prefix owning the whole disk, which is the picture an operator is looking for. */
export const OneSegment: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar
        label="broker-7 disk usage"
        segments={[{ label: "analytics.clickstream", tone: "series-1", value: 380 * GB }]}
        capacity={419 * GB}
        valueText="380 GB"
        format={gigabytes}
      />
    </div>
  ),
};

/**
 * The segments sum to more than the capacity — a directory sampled just after a compaction against
 * a size read just before. The bar ends where the capacity does rather than overflowing its track.
 */
export const OverCapacity: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <StackedBar
        label="broker-8 disk usage"
        segments={row([200, 180, 160, 120])}
        capacity={419 * GB}
        valueText="660 GB"
        format={gigabytes}
      />
    </div>
  ),
};

/**
 * The extreme case: nine prefixes and a very long key, in a narrow card. Every segment has to stay
 * visible in the bar and the legend has to truncate rather than push the row apart.
 */
export const ManyLongSegments: Story = {
  render: () => (
    <div style={{ width: "300px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <StackedBar
        label="broker-9 disk usage"
        capacity={419 * GB}
        valueText="401 GB"
        format={gigabytes}
        legend
        segments={[
          { label: "orders.payments.reconciliation.eu-central-1", tone: "series-1", value: 120 * GB },
          { label: "analytics.clickstream", tone: "series-2", value: 96 * GB },
          { label: "inventory.*", tone: "series-3", value: 70 * GB },
          { label: "users.*", tone: "series-4", value: 48 * GB },
          { label: "notifications.*", tone: "series-5", value: 32 * GB },
          { label: "other", tone: "series-6", value: 35 * GB },
        ]}
      />
    </div>
  ),
};
