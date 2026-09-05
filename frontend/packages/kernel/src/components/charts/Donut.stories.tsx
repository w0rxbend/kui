/**
 * Stories for the partition-health donut.
 *
 * `AllZero` is the important one: a ring with no data must never draw as a full green circle,
 * because "everything is in sync" and "we could not count anything" would then be the same
 * picture, and the difference between them is an incident.
 */
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Donut } from "./Donut.jsx";

const meta: Meta<typeof Donut> = {
  title: "Charts/Donut",
  component: Donut,
  decorators: [(Story: () => JSX.Element) => <div style={{ width: "420px" }}>{Story()}</div>],
};
export default meta;

type Story = StoryObj<typeof Donut>;

/** The design's own numbers: 1,522 in sync, 12 under-replicated, 2 offline. */
export const Default: Story = {
  args: {
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 1522, tone: "success" },
      { label: "Under-replicated", value: 12, tone: "warning" },
      { label: "Offline", value: 2, tone: "danger" },
    ],
  },
};

/** Nothing wrong at all. One arc, and the centre figure stays at the strong text colour. */
export const Perfect: Story = {
  args: {
    centreCaption: "in sync",
    segments: [{ label: "In sync", value: 1536, tone: "success" }],
  },
};

/** Below 99% healthy: the centre figure turns amber even though most of the ring is still green. */
export const Degraded: Story = {
  args: {
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 1400, tone: "success" },
      { label: "Under-replicated", value: 120, tone: "warning" },
      { label: "Offline", value: 16, tone: "danger" },
    ],
  },
};

/** Below 95%: red. A donut whose centre reads 72.0% in white is a lie of omission. */
export const Critical: Story = {
  args: {
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 1100, tone: "success" },
      { label: "Under-replicated", value: 300, tone: "warning" },
      { label: "Offline", value: 136, tone: "danger" },
    ],
  },
};

/** A segment far below 2% of the whole. It still gets a visible arc; the legend has the number. */
export const TinySegment: Story = {
  args: {
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 99_999, tone: "success" },
      { label: "Offline", value: 1, tone: "danger" },
    ],
  },
};

/** Every segment zero: a plain grey ring, an em dash, and a caption that says what happened. */
export const AllZero: Story = {
  args: {
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 0, tone: "success" },
      { label: "Under-replicated", value: 0, tone: "warning" },
      { label: "Offline", value: 0, tone: "danger" },
    ],
  },
};

/** No segments at all — a topic that has just been created and has nothing to report yet. */
export const NoSegments: Story = {
  args: { centreCaption: "in sync", segments: [] },
};

/** Larger, as a topic-detail page would draw it. The centre type scales with the ring. */
export const Large: Story = {
  args: {
    diameter: 120,
    strokeWidth: 12,
    centreCaption: "in sync",
    segments: [
      { label: "In sync", value: 1522, tone: "success" },
      { label: "Under-replicated", value: 12, tone: "warning" },
      { label: "Offline", value: 2, tone: "danger" },
    ],
  },
};

/**
 * The extreme case: five long labels and counts in the millions, in a narrow card. The legend has
 * to keep its counts aligned and truncate its labels rather than push the ring off the card.
 */
export const ManyLongSegments: Story = {
  render: () => (
    <div style={{ width: "300px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <Donut
        centreCaption="in sync"
        segments={[
          { label: "In sync and fully replicated", value: 12_400_000, tone: "success" },
          { label: "Under-replicated", value: 240_000, tone: "warning" },
          { label: "Offline", value: 12_000, tone: "danger" },
          { label: "Reassigning", value: 4_000, tone: "series-1" },
          { label: "Leaderless for over five minutes", value: 900, tone: "series-2" },
        ]}
      />
    </div>
  ),
};
