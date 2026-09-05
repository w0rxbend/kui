/**
 * Stories for the horizontal bar list.
 *
 * `AllZero` is the one to look at. It is the shape that produced the defect this component exists
 * to prevent: divide by a maximum of zero, hand the browser `width: NaN%`, and every bar draws
 * full — a panel that says the worst possible news about a cluster that is perfectly fine.
 */
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { LONG_LABEL } from "./fixtures.js";
import { MagnitudeBarList } from "./MagnitudeBarList.jsx";

const meta: Meta<typeof MagnitudeBarList> = {
  title: "Charts/MagnitudeBarList",
  component: MagnitudeBarList,
  decorators: [(Story: () => JSX.Element) => <div style={{ width: "540px" }}>{Story()}</div>],
};
export default meta;

type Story = StoryObj<typeof MagnitudeBarList>;

/** The dashboard's "Top consumer lag", entry for entry, including the zero at the foot. */
export const Default: Story = {
  args: {
    entries: [
      { label: "clickstream-etl", value: 3861, tone: "warning" },
      { label: "fraud-detector", value: 333 },
      { label: "email-dispatcher", value: 18 },
      { label: "payments-processor", value: 0, tone: "success" },
    ],
  },
};

/**
 * Every value zero. No bar can say anything, so the panel says it in words — otherwise a row of
 * empty tracks reads as "still loading".
 */
export const AllZero: Story = {
  args: {
    entries: [
      { label: "clickstream-etl", value: 0, tone: "success" },
      { label: "fraud-detector", value: 0, tone: "success" },
      { label: "payments-processor", value: 0, tone: "success" },
    ],
  },
};

/** An explicit maximum of zero, which is the other way into the same division. */
export const ZeroMaximum: Story = {
  args: {
    max: 0,
    entries: [
      { label: "clickstream-etl", value: 3861 },
      { label: "fraud-detector", value: 333 },
    ],
  },
};

/** One entry whose lag could not be read: no bar at all, and an em dash instead of a figure. */
export const WithUnknownValue: Story = {
  args: {
    entries: [
      { label: "clickstream-etl", value: 3861, tone: "warning" },
      { label: "fraud-detector", value: undefined },
      { label: "email-dispatcher", value: 18 },
    ],
  },
};

/**
 * A value four orders of magnitude below the largest. It must still draw a visible stub: "small"
 * rendering as "none" is how a slow consumer disappears from the panel that exists to find it.
 */
export const TinyValues: Story = {
  args: {
    entries: [
      { label: "clickstream-etl", value: 4_000_000 },
      { label: "fraud-detector", value: 400 },
      { label: "email-dispatcher", value: 4 },
      { label: "payments-processor", value: 1 },
    ],
  },
};

/** Nothing to list at all. */
export const Empty: Story = {
  args: { entries: [], emptyMessage: "No consumer groups are behind." },
};

/**
 * The extreme case: the largest lag anyone will ever see, next to a group id long enough to be a
 * sentence. The figure must stay legible and the label must truncate rather than wrap the row.
 */
export const ExtremeValues: Story = {
  args: {
    entries: [
      { label: LONG_LABEL, value: 9_007_199_254_740_991, tone: "danger" },
      { label: "b", value: 1 },
    ],
  },
};

/** Every tone, so a reader can check that none of them is illegible on the card's surface. */
export const Tones: Story = {
  args: {
    entries: [
      { label: "neutral", value: 100 },
      { label: "success", value: 80, tone: "success" },
      { label: "warning", value: 60, tone: "warning" },
      { label: "danger", value: 40, tone: "danger" },
      { label: "primary", value: 20, tone: "primary" },
      { label: "accent", value: 10, tone: "accent" },
    ],
  },
};
