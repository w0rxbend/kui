import type { Meta, StoryObj } from "storybook-solidjs-vite";
import type { KuiApiClient } from "@kui/api";
import { createQueryRegistry } from "@kui/kernel";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  HEALTHY,
  THROUGHPUT_ALL_ABSENT,
  THROUGHPUT_NOT_CONFIGURED,
  THROUGHPUT_UNAVAILABLE,
  THROUGHPUT_WITH_A_GAP,
  throughputOk,
} from "./fixtures.js";
import { THROUGHPUT_PATH, dashboardHost, stubApi } from "./harness.jsx";

/**
 * The dashboard's Traffic tab, in each state its one real chart has.
 *
 * The story that matters is not the healthy one. It is `WithAGap`: the fixture behind it holds a
 * **measured zero** in its first bucket and twenty **unsampled** buckets in the middle, and drawn as
 * bars those are the same picture — no ink either way. What tells them apart is the hatched run on
 * the coverage strip under the axis and the sentence under the card, and the whole reason both
 * exist is that a chart is where "never a zero" is easiest to get wrong. If a future change makes
 * that story's hole disappear, the product has started claiming a cluster was idle during an hour
 * KUI spent restarting.
 *
 * `NotConfigured` is the second one to look at, because it is the *common* case rather than the
 * failure case: most deployments configure no exporter, and the card has to say so as a standing
 * fact about the deployment rather than as an outage with a retry button.
 *
 * Every story mounts the product's own router over a memory history and its own query registry, so
 * the tab, the `?range=` and the request are the real ones and no story can read another's answer.
 */
const meta = {
  title: "Screens/Traffic",
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

const TRAFFIC = "/ui/clusters/prod-kyiv-01/dashboard/traffic";

/** A gateway that answers the throughput endpoint with `body` and refuses everything else. */
const answering = (body: unknown): KuiApiClient => stubApi({ [THROUGHPUT_PATH]: body }).api;

/**
 * A gateway that never answers, for the waiting state.
 *
 * A pending promise rather than a slow one: a story is looked at, not run to completion, and a
 * timeout would make the skeleton disappear while somebody was reading it.
 */
const silent: KuiApiClient = {
  get: () => new Promise<never>(() => undefined),
  post: () => new Promise<never>(() => undefined),
  put: () => new Promise<never>(() => undefined),
  delete: () => new Promise<never>(() => undefined),
  patch: () => new Promise<never>(() => undefined),
  raw: {},
} as unknown as KuiApiClient;

const story = (api: KuiApiClient, at: string = TRAFFIC): Story => ({
  args: { model: toOverviewModel(HEALTHY) },
  render: (args) =>
    dashboardHost(at, () => <Overview model={args.model} queries={createQueryRegistry()} />, {
      selected: "prod-kyiv-01",
      api,
    })(),
});

/**
 * A day of throughput with a hole in it (`M03`).
 *
 * Bucket 0 was measured and was zero; buckets 100 to 119 were never sampled. The hatched run under
 * the axis is the twenty, and the caption counts them. The legend chips carry the newest measured
 * rate, which is where §3.1 puts the current value — the reason the plot is allowed no y-axis.
 */
export const WithAGap: Story = story(answering(throughputOk(THROUGHPUT_WITH_A_GAP)));

/**
 * The same window at a week's resolution, opened from the address.
 *
 * The point of the story is that the range is in the URL and not in a signal: this one is mounted
 * at `?range=7d` and the selector comes up on `7d` with the request already made for that window.
 * A range held in component state would make every link somebody pasted land on 24h.
 */
export const SevenDays: Story = story(
  answering(throughputOk(THROUGHPUT_WITH_A_GAP)),
  `${TRAFFIC}?range=7d`,
);

/**
 * A source KUI can reach and has sampled nothing from.
 *
 * The axis is drawn in full and the plot says why it is empty. This is *not* the same picture as
 * the story below: here the window exists and its emptiness is a measurement, so a reader can see
 * how long the silence is.
 */
export const NothingSampled: Story = story(answering(throughputOk(THROUGHPUT_ALL_ABSENT)));

/**
 * A deployment that configured no exporter, which is the ordinary case rather than the broken one.
 *
 * A sentence and no axis, because an axis is a claim that the quantity is measured and merely
 * absent right now — and drawing one here sends somebody to find a broken exporter that has never
 * existed. Note that the range selector stays: `Card` keeps its header in every state, and changing
 * the window is the only thing there is to try.
 */
export const NotConfigured: Story = story(answering(THROUGHPUT_NOT_CONFIGURED));

/**
 * The exporter is configured and has stopped answering, which is a different card entirely.
 *
 * A failure, with the reason, the code somebody quotes when they escalate, and a Retry that is
 * wired to the query rather than drawn for the look of it. The three cards below it keep saying
 * what they cannot measure, because that has not changed.
 */
export const ExporterDown: Story = story(answering(THROUGHPUT_UNAVAILABLE));

/**
 * The request is in flight.
 *
 * A reserved box, not a dash and not an empty axis: a figure that has not arrived must not look
 * like one that is missing, which is the rule the whole dashboard is built on and the one a chart
 * makes easiest to break.
 */
export const Loading: Story = story(silent);
