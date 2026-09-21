import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Histogram, type HistogramBucket } from "./Histogram.jsx";

/**
 * The message-size distribution.
 *
 * `Default` is the design's own card: twelve buckets, the modal one solid, its neighbours muted,
 * and a three-bucket oversize tail in amber. The three inks in one series are the reason this is
 * not `BarChart`, whose colour belongs to a series and paints every bar of it alike.
 *
 * `NoOversizeThreshold` is the story worth looking at twice: with nothing served saying where
 * "oversize" starts, there is no amber at all — the component does not guess at 16 KB.
 */
const meta: Meta<typeof Histogram> = {
  title: "Charts/Histogram",
  component: Histogram,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Histogram>;

/** Bytes, the way an operator reads them. */
const bytes = (value: number): string => {
  if (value >= 1_048_576) return `${(value / 1_048_576).toFixed(value % 1_048_576 === 0 ? 0 : 1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(value % 1024 === 0 ? 0 : 1)} KB`;
  return `${value} B`;
};

/** The design's twelve buckets, 256 B doubling to 64 KB and then open-ended. */
const EDGES = [256, 512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072, 262_144, 524_288];
const COUNTS = [1_240, 3_180, 9_420, 22_800, 14_600, 7_310, 2_940, 880, 210, 46, 12, 3];

const buckets = (counts: readonly number[], oversizeFrom?: number): HistogramBucket[] =>
  EDGES.map((from, i) => ({
    from,
    to: i === EDGES.length - 1 ? undefined : EDGES[i + 1],
    count: counts[i] ?? 0,
    ...(oversizeFrom !== undefined && from >= oversizeFrom ? { tone: "warning" as const } : {}),
  }));

const READOUTS = [
  { label: "p50", value: "1.1 KB", tone: "success" as const },
  { label: "p99", value: "18 KB" },
  { label: "max", value: "0.9 MB", tone: "warning" as const },
];

/** The card as drawn: modal bucket in accent, oversize tail in amber, percentile chips beneath. */
export const Default: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram
        label="Message size distribution"
        buckets={buckets(COUNTS, 131_072)}
        formatBoundary={bytes}
        readouts={READOUTS}
      />
    </div>
  ),
};

/**
 * The same distribution with no oversize threshold served. The tail keeps its bars and loses its
 * amber: an "oversize" nobody defined is not a fact this card is entitled to state.
 */
export const NoOversizeThreshold: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram label="Message size distribution" buckets={buckets(COUNTS)} formatBoundary={bytes} />
    </div>
  ),
};

/** Buckets that counted nothing draw no bar at all, and keep their place on the axis. */
export const WithEmptyBuckets: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram
        label="Message size distribution"
        buckets={buckets([0, 0, 9_420, 22_800, 0, 0, 2_940, 0, 0, 0, 0, 0])}
        formatBoundary={bytes}
      />
    </div>
  ),
};

/**
 * Two buckets tied for tallest. Neither is highlighted, because singling one out would be a claim
 * about the distribution that is not true.
 */
export const ATiedMode: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram
        label="Message size distribution"
        buckets={buckets([100, 200, 22_800, 22_800, 400, 120, 40, 10, 4, 2, 1, 0])}
        formatBoundary={bytes}
      />
    </div>
  ),
};

/** Every bucket zero. The axis stays and the card says so in words, as the plot family does. */
export const NothingInRange: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram
        label="Message size distribution"
        buckets={buckets([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])}
        formatBoundary={bytes}
        emptyMessage="No messages produced in this range."
      />
    </div>
  ),
};

/** No buckets served at all — a topic the exporter has never seen. */
export const NoBuckets: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram label="Message size distribution" buckets={[]} formatBoundary={bytes} />
    </div>
  ),
};

/** Four buckets. The bars are capped rather than stretched, so this is not four slabs. */
export const FewBuckets: Story = {
  render: () => (
    <div style={{ width: "560px" }}>
      <Histogram
        label="Message size distribution"
        buckets={[
          { from: 0, to: 1024, count: 400 },
          { from: 1024, to: 4096, count: 1_800 },
          { from: 4096, to: 16_384, count: 620 },
          { from: 16_384, count: 40 },
        ]}
        formatBoundary={bytes}
        tickEvery={1}
      />
    </div>
  ),
};

/**
 * The extreme case: sixty buckets in a narrow card. Bars must not overlap their neighbours, and
 * the axis must print five labels rather than sixty overlapping ones.
 */
export const ManyBucketsInANarrowCard: Story = {
  render: () => (
    <div style={{ width: "300px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <Histogram
        label="Message size distribution"
        buckets={Array.from({ length: 60 }, (_, i) => ({
          from: 256 * (i + 1),
          to: 256 * (i + 2),
          count: Math.round(2_000 * Math.exp(-((i - 14) ** 2) / 90)),
        }))}
        formatBoundary={bytes}
        tickEvery={15}
      />
    </div>
  ),
};
