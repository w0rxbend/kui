import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createQueryRegistry } from "@kui/kernel";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  CONSUMERS_UNAVAILABLE,
  HEALTHY,
  LOADING,
  NO_DISK_SIZES,
  PARTIAL_DISKS,
  SPARSE_SUMMARY,
  UNHEALTHY,
  ZERO_BYTE_DISKS,
} from "./fixtures.js";
import { dashboardHost } from "./harness.jsx";
import type { OverviewData } from "./load.js";

/**
 * The cluster dashboard, in each state it has to survive.
 *
 * The stories that matter here are not the healthy one. They are the ones below it: a cluster
 * mid-incident, where every cheerful sentence has to turn itself off; a broker too old to report a
 * disk size, where a bar must be blank rather than empty; one service down while the rest are up;
 * a cluster that reports no partition counts at all, which is the state a reviewer never clicks;
 * and the loading state, where a figure that has not arrived must not look like one that is missing.
 * Those are the states that are expensive to reach against a real cluster and cheap to get wrong.
 *
 * Every story mounts the product's own router over a memory history, because the tab and the cluster
 * come from the address and from nowhere else. That is what lets the tabs be separate stories rather
 * than one story with a control on it — and it is why the Storage stories are honest about what the
 * Storage tab does at that address, rather than about what a prop said it should do.
 *
 * The Traffic tab has stories of its own in `Traffic.stories.tsx`, because the states worth drawing
 * there are states of a *request* rather than of the model these stories vary. The Throughput card
 * appears on the Overview tab too, and in these stories it draws the not-configured sentence: the
 * harness's default gateway answers `not_configured`, which is the honest answer for a fixture
 * cluster that has no exporter behind it.
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

const DASHBOARD = "/ui/clusters/prod-kyiv-01/dashboard";

const story = (data: OverviewData, at: string = DASHBOARD): Story => ({
  args: { model: toOverviewModel(data) },
  /* Its own registry per story. The shared one is a browser tab's view of one server, which is
     right in the product and wrong in a gallery: every story here names the same cluster, so they
     would otherwise all draw whichever one was opened first. */
  render: (args) =>
    dashboardHost(at, () => <Overview model={args.model} queries={createQueryRegistry()} />, {
      selected: "prod-kyiv-01",
    })(),
});

/** Screenshots `01` and `05`: everything answered, everything fine. */
export const Healthy: Story = story(HEALTHY);

/**
 * Nothing has come back yet.
 *
 * Look for skeletons and the *absence* of pills and of the in-sync ring. A dash here would say the
 * figure is missing; a cheerful "all in sync" would be a claim made before anybody asked; and a ring
 * drawn round a skeleton would reserve a box for a picture of a number nobody has.
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

/**
 * A broker that reports no partition counts at all.
 *
 * The state a reviewer never clicks: the donut, the in-sync ring, the partition pill and the
 * partition total all go quiet together, and the lede says which figures are blank and why. Every
 * one of them has a tempting zero available, and none of them may take it.
 */
export const SparseSummary: Story = story(SPARSE_SUMMARY);

/** The Storage tab (`M04`): the same stat cards, then exactly two cards, then the page ends. */
export const StorageTab: Story = story(HEALTHY, `${DASHBOARD}/storage`);

/**
 * The Storage tab on a cluster whose disks report no size.
 *
 * Three bare tracks, a sentence under each, and no legend — a key naming four prefixes the reader
 * cannot see anywhere reads as a rendering fault rather than as an unmeasured disk. The voice line
 * changes with it: nothing is eating anybody's budget on a card with no capacity in it.
 */
export const StorageWithoutDiskSizes: Story = story(NO_DISK_SIZES, `${DASHBOARD}/storage`);

/**
 * The Storage tab with one directory that reported replicas and no size.
 *
 * Broker 1 has a second disk holding more `orders.*` than its first one, and no capacity. Its bar
 * must show the first disk's share only: a segment attributed against a capacity that excludes the
 * disk it lives on runs past the end of the track, and the bar clamps rather than saying so.
 */
export const StorageWithAnUnmeasuredDisk: Story = story(PARTIAL_DISKS, `${DASHBOARD}/storage`);

/**
 * The Storage tab on disks that answered, and answered zero.
 *
 * The state between `StorageWithoutDiskSizes` and `StorageTab` that belongs to neither: both byte
 * figures are present, so nothing is skipped, and there is still no denominator. Every row's detail
 * line must be the sentence and not `0 B of 0 B` — the same sentence the broker-health bars carry on
 * the Overview tab, because both come out of one arithmetic. It is a real answer: a directory read
 * while a volume was being remounted reports exactly this.
 */
export const StorageWithZeroByteDisks: Story = story(ZERO_BYTE_DISKS, `${DASHBOARD}/storage`);

/**
 * The Overview tab on the same disks, which is the other half of that agreement.
 *
 * Three broker bars with no fill and the reason under each. If this story and the one above ever
 * disagree about what a zero-byte disk is, one of them is lying to an operator comparing brokers.
 */
export const ZeroByteDisks: Story = story(ZERO_BYTE_DISKS);

/**
 * The Storage tab while the answers are still in flight.
 *
 * The card waits rather than reporting a failure: the broker list can land before the log
 * directories do, and a card that called that an outage would raise a false alarm once per load.
 */
export const StorageLoading: Story = story(LOADING, `${DASHBOARD}/storage`);

/**
 * A tab nobody has, which somebody will nevertheless type.
 *
 * It lands on the overview with the overview marked. The address is user-editable and a typo is not
 * an error state — a blank body under a strip with nothing current is the worst of the answers
 * available here.
 */
export const UnknownTab: Story = story(HEALTHY, `${DASHBOARD}/nonsense`);

/**
 * The root address, which names no cluster.
 *
 * The strip is absent rather than disabled: its segments would have no address to point at. The
 * body is unchanged, because the model is fetched for whichever cluster the shell has selected.
 */
export const NoClusterInTheAddress: Story = {
  args: { model: toOverviewModel(HEALTHY) },
  render: (args) =>
    dashboardHost("/ui", () => <Overview model={args.model} queries={createQueryRegistry()} />)(),
};
