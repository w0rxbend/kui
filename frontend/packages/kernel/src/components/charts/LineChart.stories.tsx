/**
 * Stories for the line chart.
 *
 * `WithGap` is the one that earns the component's shape: a missing measurement breaks the line
 * rather than being drawn through, because a straight run across a hole is a picture of data that
 * was never collected.
 */
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Card } from "../Card.jsx";
import { ChartLegend } from "./ChartLegend.jsx";
import { LATENCY, LATENCY_MINUTES, LATENCY_WITH_GAP } from "./fixtures.js";
import { LineChart } from "./LineChart.jsx";

const meta: Meta<typeof LineChart> = {
  title: "Charts/LineChart",
  component: LineChart,
  decorators: [(Story: () => JSX.Element) => <div style={{ width: "620px" }}>{Story()}</div>],
};
export default meta;

type Story = StoryObj<typeof LineChart>;

const ms = (value: number): string => `${value}ms`;

/** Two series, as the dashboard's p99 latency card draws them. */
export const Default: Story = {
  args: {
    label: "p99 latency over the last hour",
    series: LATENCY,
    categories: LATENCY_MINUTES,
    ticks: [0, 6, 13],
    format: ms,
  },
};

/**
 * A quarter of an hour with no measurements. Both lines break; neither interpolates across the
 * hole, and the hidden data table shows an em dash for those buckets.
 */
export const WithGap: Story = {
  args: {
    label: "p99 latency with an exporter outage",
    series: LATENCY_WITH_GAP,
    categories: LATENCY_MINUTES,
    ticks: [0, 6, 13],
    format: ms,
  },
};

/** A single surviving measurement between two outages. It draws as a dot rather than vanishing. */
export const OnePointBetweenGaps: Story = {
  args: {
    label: "p99 latency, mostly missing",
    categories: ["-60 min", "-45 min", "-30 min", "-15 min", "now"],
    series: [{ label: "produce", tone: "series-1", points: [null, null, 14, null, null] }],
    format: ms,
  },
};

/** One series. */
export const SingleSeries: Story = {
  args: {
    label: "Produce latency",
    categories: LATENCY_MINUTES,
    ticks: [0, 6, 13],
    series: [LATENCY[0]!],
    format: ms,
  },
};

/** Every value zero: a flat line along the baseline, not an empty plot. Zero is a measurement. */
export const AllZero: Story = {
  args: {
    label: "p99 latency on an idle cluster",
    categories: ["-60 min", "-30 min", "now"],
    series: [{ label: "produce", tone: "series-1", points: [0, 0, 0] }],
    format: ms,
  },
};

/** Nothing at all in the range. */
export const Empty: Story = {
  args: {
    label: "p99 latency over the last hour",
    categories: LATENCY_MINUTES,
    ticks: [0, 6, 13],
    series: [
      { label: "produce", tone: "series-1", points: LATENCY_MINUTES.map(() => null) },
      { label: "fetch", tone: "series-2", points: LATENCY_MINUTES.map(() => null) },
    ],
    emptyMessage: "No latency samples in the last hour.",
  },
};

/** A single spike three orders of magnitude above the rest, which is what a real incident looks like. */
export const ExtremeSpike: Story = {
  args: {
    label: "p99 latency during an incident",
    categories: ["-60 min", "-45 min", "-30 min", "-15 min", "now"],
    series: [{ label: "produce", tone: "series-1", points: [14, 16, 42_000, 900, 18] }],
    format: ms,
  },
};

/** Two buckets, the fewest a line can be drawn from. */
export const TwoPoints: Story = {
  args: {
    label: "p99 latency",
    categories: ["-1 min", "now"],
    series: [{ label: "produce", tone: "series-1", points: [14, 9] }],
    format: ms,
  },
};

/** The smallest window. The line redraws narrower; the card does not scroll sideways. */
export const Narrow: Story = {
  render: () => (
    <div style={{ width: "240px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <LineChart
        label="p99 latency over the last hour"
        series={LATENCY}
        categories={LATENCY_MINUTES}
        ticks={[0, 6, 13]}
        format={ms}
        height={110}
      />
    </div>
  ),
};

/**
 * The dashboard's composition. Note that the legend carries the current value as well as the
 * colour: it is a readout, not just a key, and that is what lets the plot have no y-axis.
 */
export const InACard: Story = {
  render: () => (
    <Card
      title="Latency · p99"
      headerEnd={
        <ChartLegend
          items={[
            { label: "produce", tone: "series-1", value: "14ms" },
            { label: "fetch", tone: "series-2", value: "9ms" },
          ]}
        />
      }
    >
      <LineChart
        label="p99 latency over the last hour"
        series={LATENCY}
        categories={LATENCY_MINUTES}
        ticks={[0, 6, 13]}
        format={ms}
      />
    </Card>
  ),
};
