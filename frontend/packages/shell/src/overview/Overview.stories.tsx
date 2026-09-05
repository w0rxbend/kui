import type { Meta, StoryObj } from "storybook-solidjs-vite";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  CONSUMERS_UNAVAILABLE,
  HEALTHY,
  LOADING,
  NO_DISK_SIZES,
  SPARSE_SUMMARY,
  UNHEALTHY,
} from "./fixtures.js";
import type { OverviewData } from "./load.js";

/**
 * The cluster overview, in each state it has to survive.
 *
 * The stories that matter here are not the healthy one. They are the four below it: a cluster
 * mid-incident, where every cheerful sentence has to turn itself off; a broker too old to report a
 * disk size, where a bar must be blank rather than empty; one service down while the rest are up;
 * and the loading state, where a figure that has not arrived must not look like one that is missing.
 * Those are the states that are expensive to reach against a real cluster and cheap to get wrong.
 */
const meta = {
  title: "Screens/Overview",
  component: Overview,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div style={{ padding: "0 var(--kui-page-gutter)", background: "var(--kui-color-surface)" }}>{Story()}</div>
    ),
  ],
} satisfies Meta<typeof Overview>;

export default meta;
type Story = StoryObj<typeof meta>;

const story = (data: OverviewData): Story => ({ args: { model: toOverviewModel(data) } });

/** Screenshots `01` and `05`: everything answered, everything fine. */
export const Healthy: Story = story(HEALTHY);

/**
 * Nothing has come back yet.
 *
 * Look for skeletons and the *absence* of pills. A dash here would say the figure is missing; a
 * cheerful "all in sync" would be a claim made before anybody asked.
 */
export const Loading: Story = story(LOADING);

/**
 * A cluster mid-incident.
 *
 * Every joke on the screen should be gone: no coffee, no "fashionably late". The lede should name
 * the offline partitions, and the pills should carry the state rather than the reassurance.
 */
export const Unhealthy: Story = story(UNHEALTHY);

/**
 * Kafka older than 3.3, whose log directories report no capacity.
 *
 * The three disk bars must be blank *and* explained. A blank bar with no sentence beside it is
 * indistinguishable from a disk that is empty, which is the most reassuring possible rendering of
 * "we have no idea".
 */
export const NoDiskSizes: Story = story(NO_DISK_SIZES);

/**
 * The consumer service is down and everything else is up.
 *
 * One panel says so; the other five keep reporting. A dashboard that fails whole because one of its
 * five services is unavailable is the failure mode ADR-039 exists to prevent — and the panel most
 * likely to be down is the one describing whatever has gone wrong.
 */
export const OneServiceDown: Story = story(CONSUMERS_UNAVAILABLE);

/** A broker that reports no partition counts at all: the donut and two pills go quiet. */
export const SparseSummary: Story = story(SPARSE_SUMMARY);
