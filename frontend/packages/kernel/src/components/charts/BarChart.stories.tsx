/**
 * Stories for the grouped column chart.
 *
 * Look at `InACard` first — it is the composition the design actually draws, title and legend and
 * range selector on one line — and then at `Empty`, `AllZero` and `Narrow`, which are the three
 * that have no happy-path equivalent to compare against.
 */
import { createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Card } from "../Card.jsx";
import { BarChart } from "./BarChart.jsx";
import { ChartLegend } from "./ChartLegend.jsx";
import { HOURS, HOUR_TICKS, THROUGHPUT } from "./fixtures.js";
import { RangeSelector } from "./RangeSelector.jsx";

const meta: Meta<typeof BarChart> = {
  title: "Charts/BarChart",
  component: BarChart,
  decorators: [(Story: () => JSX.Element) => <div style={{ width: "860px" }}>{Story()}</div>],
};
export default meta;

type Story = StoryObj<typeof BarChart>;

const mbPerSecond = (value: number): string => `${value} MB/s`;

/** Two series over 24 hourly buckets, which is what the dashboard shows. */
export const Default: Story = {
  args: {
    label: "Throughput over the last 24 hours",
    series: THROUGHPUT,
    categories: HOURS,
    ticks: HOUR_TICKS,
    format: mbPerSecond,
  },
};

/** One series. The bar sits centred in its group rather than hugging the left of it. */
export const SingleSeries: Story = {
  args: {
    label: "Produced messages",
    series: [THROUGHPUT[0]!],
    categories: HOURS,
    ticks: HOUR_TICKS,
    format: mbPerSecond,
  },
};

/** Five series, which is as many as the palette names. */
export const FiveSeries: Story = {
  args: {
    label: "Throughput by listener",
    categories: ["00:00", "06:00", "12:00", "18:00", "now"],
    series: [
      { label: "internal", tone: "series-1", points: [40, 52, 61, 58, 66] },
      { label: "external", tone: "series-2", points: [22, 31, 44, 39, 41] },
      { label: "replication", tone: "series-3", points: [12, 14, 19, 17, 21] },
      { label: "controller", tone: "series-4", points: [4, 5, 6, 5, 7] },
      { label: "admin", tone: "series-5", points: [1, 2, 2, 1, 3] },
    ],
    format: mbPerSecond,
  },
};

/** A bucket the exporter missed. It draws nothing at all, rather than a zero-height bar. */
export const WithGap: Story = {
  args: {
    label: "Throughput with a gap",
    categories: ["00:00", "06:00", "12:00", "18:00", "now"],
    series: [
      { label: "produce", tone: "series-1", points: [62, null, 71, 66, 54] },
      { label: "consume", tone: "series-2", points: [55, null, 66, 59, 47] },
    ],
    format: mbPerSecond,
  },
};

/** Every value zero. The bars vanish rather than drawing full height — the guarded denominator. */
export const AllZero: Story = {
  args: {
    label: "Throughput on an idle cluster",
    categories: ["00:00", "06:00", "12:00", "18:00", "now"],
    series: [
      { label: "produce", tone: "series-1", points: [0, 0, 0, 0, 0] },
      { label: "consume", tone: "series-2", points: [0, 0, 0, 0, 0] },
    ],
    format: mbPerSecond,
  },
};

/** No data in the range. The message is centred and the axis is still drawn beneath it. */
export const Empty: Story = {
  args: {
    label: "Throughput over the last 24 hours",
    categories: HOURS,
    ticks: HOUR_TICKS,
    series: [
      { label: "produce", tone: "series-1", points: HOURS.map(() => null) },
      { label: "consume", tone: "series-2", points: HOURS.map(() => null) },
    ],
    emptyMessage: "No throughput in the last 24 hours.",
  },
};

/** One bucket, which is the degenerate case the group arithmetic has to survive. */
export const SingleBucket: Story = {
  args: {
    label: "Throughput right now",
    categories: ["now"],
    series: [
      { label: "produce", tone: "series-1", points: [86] },
      { label: "consume", tone: "series-2", points: [78] },
    ],
    format: mbPerSecond,
  },
};

/**
 * The extreme case in both directions: 180 buckets and values in the billions. The bars have to
 * become slivers with gaps rather than overlapping, and the tooltip has to stay inside the card.
 */
export const ManyBuckets: Story = {
  args: {
    label: "Throughput over 180 buckets",
    categories: Array.from({ length: 180 }, (_, i) => `t-${180 - i}`),
    ticks: [0, 45, 90, 135, 179],
    series: [
      {
        label: "produce",
        tone: "series-1",
        points: Array.from({ length: 180 }, (_, i) => 900_000_000 + Math.round(Math.sin(i / 6) * 400_000_000)),
      },
      {
        label: "consume",
        tone: "series-2",
        points: Array.from({ length: 180 }, (_, i) => 800_000_000 + Math.round(Math.cos(i / 5) * 350_000_000)),
      },
    ],
    format: (v: number) => `${(v / 1_000_000).toFixed(1)} M/s`,
  },
};

/** The smallest window the dashboard supports. The plot redraws; it does not scroll sideways. */
export const Narrow: Story = {
  render: () => (
    <div style={{ width: "260px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <BarChart
        label="Throughput over the last 24 hours"
        series={THROUGHPUT}
        categories={HOURS}
        ticks={HOUR_TICKS}
        format={mbPerSecond}
        height={140}
      />
    </div>
  ),
};

/** The composition from the dashboard: card, legend and range selector in the header. */
export const InACard: Story = {
  render: () => {
    const [range, setRange] = createSignal("24h");
    return (
      <Card
        title="Throughput"
        headerEnd={
          <>
            <ChartLegend
              items={[
                { label: "produce", tone: "series-1" },
                { label: "consume", tone: "series-2" },
              ]}
            />
            <RangeSelector
              label="Throughput range"
              value={range()}
              onChange={setRange}
              options={[
                { value: "24h", label: "24h" },
                { value: "7d", label: "7d" },
                { value: "30d", label: "30d" },
              ]}
            />
          </>
        }
      >
        <BarChart
          label="Throughput over the last 24 hours"
          series={THROUGHPUT}
          categories={HOURS}
          ticks={HOUR_TICKS}
          format={mbPerSecond}
        />
      </Card>
    );
  },
};

/** The panel's own failure, with the range selector still usable — changing it is a retry. */
export const Unavailable: Story = {
  render: () => {
    const [range, setRange] = createSignal("24h");
    return (
      <Card
        title="Throughput"
        state="unavailable"
        message="Throughput is unavailable."
        description="The metrics service is not responding."
        code="UPSTREAM_UNAVAILABLE"
        headerEnd={
          <RangeSelector
            label="Throughput range"
            value={range()}
            onChange={setRange}
            options={[
              { value: "24h", label: "24h" },
              { value: "7d", label: "7d" },
              { value: "30d", label: "30d" },
            ]}
          />
        }
      />
    );
  },
};

/** Loading. Skeletons at the plot's real height, so nothing moves when the data lands. */
export const Loading: Story = {
  render: () => <Card title="Throughput" state="loading" bodyMinHeight="180px" />,
};
