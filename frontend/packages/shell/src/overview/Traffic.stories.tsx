import type { Meta, StoryObj } from "storybook-solidjs-vite";
import type { KuiApiClient } from "@kui/api";
import { createQueryRegistry } from "@kui/kernel";

import { Overview } from "./Overview.jsx";
import { toOverviewModel } from "./load.js";
import {
  HANDLER_ONE_ABSENT,
  HANDLER_READINGS,
  HEALTHY,
  LATENCY_ALL_ABSENT,
  LATENCY_WITH_A_GAP,
  PRODUCERS_BY_CLIENT,
  PRODUCERS_BY_TOPIC,
  RECORD_SIZE_MEAN,
  THROUGHPUT_ALL_ABSENT,
  THROUGHPUT_NOT_CONFIGURED,
  THROUGHPUT_STALE,
  THROUGHPUT_UNAVAILABLE,
  THROUGHPUT_WITH_A_GAP,
  handlersOk,
  latencyOk,
  producersOk,
  recordSizeOk,
  throughputOk,
} from "./fixtures.js";
import {
  HANDLERS_PATH,
  LATENCY_PATH,
  PRODUCERS_PATH,
  RECORD_SIZE_PATH,
  THROUGHPUT_PATH,
  UNCONFIGURED_METRICS,
  dashboardHost,
  stubApi,
} from "./harness.jsx";

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

/**
 * A gateway that answers the throughput endpoint with `body`, and the other four `not_configured`.
 *
 * `not_configured` is the honest default for a fixture cluster with no exporter, and it keeps a
 * story about the throughput card from being surrounded by four red failure panels that are not
 * what it is showing.
 */
const answering = (body: unknown): KuiApiClient =>
  stubApi({ ...UNCONFIGURED_METRICS, [THROUGHPUT_PATH]: body }).api;

/** A gateway answering whichever of the five endpoints a story is about. */
const serving = (answers: Readonly<Record<string, unknown>>): KuiApiClient =>
  stubApi({ ...UNCONFIGURED_METRICS, ...answers }).api;

/** Every card answering at once: the tab as a deployment with a widened exporter ruleset sees it. */
const EVERYTHING: Readonly<Record<string, unknown>> = {
  [THROUGHPUT_PATH]: throughputOk(THROUGHPUT_WITH_A_GAP),
  [LATENCY_PATH]: latencyOk(LATENCY_WITH_A_GAP),
  [HANDLERS_PATH]: handlersOk(HANDLER_READINGS),
  [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_TOPIC),
  [RECORD_SIZE_PATH]: recordSizeOk(RECORD_SIZE_MEAN),
};

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

/**
 * Every card answering, which is the tab a widened exporter ruleset produces (`M03`).
 *
 * Four things on this screen are worth looking at together, because each is a decision that could
 * have gone the flattering way instead:
 *
 *  - the latency line breaks over the same twenty steps the throughput bars do, rather than running
 *    straight across them;
 *  - **PURGATORY** is a count with a unit and not a ring, because a queue length is not a share of
 *    anything and there is no ceiling to divide it by;
 *  - the producers card is headed `Top producers · topic`, because that is what the server named
 *    its rows — the design asked for `client.id`, which a broker does not publish without quotas;
 *  - the record-size card prints a mean and says the twelve-bucket distribution beside it is not
 *    measured, rather than spreading one number across twelve columns.
 */
export const EveryCardAnswering: Story = story(serving(EVERYTHING));

/**
 * The same deployment with client quotas configured, so the producers really are client ids.
 *
 * The only difference from the story above is the field the server filled, and the card's heading
 * follows it. That is wave 5's rule 7 as a picture: the design's word may not be printed over a
 * different measurement, so the word is read from the answer.
 */
export const ProducersByClientId: Story = story(
  serving({ ...EVERYTHING, [PRODUCERS_PATH]: producersOk(PRODUCERS_BY_CLIENT) }),
);

/**
 * One reading the exporter served the name of and not the value.
 *
 * §3.4's absent rule: the plain track and an em dash, never a full ring and never an empty one that
 * reads as a measured zero. Beside a gauge that did measure something, so the difference between
 * "not measured" and "perfectly idle" is visible in one card.
 */
export const OneGaugeUnmeasured: Story = story(
  serving({ ...EVERYTHING, [HANDLERS_PATH]: handlersOk(HANDLER_ONE_ABSENT) }),
);

/**
 * A reachable exporter that has never sampled anything.
 *
 * The story `trafficLede` exists for, and the one nothing asserted until this wave: the voice line
 * says "Nothing has been sampled in this window yet" rather than "KUI measures the first of those",
 * because a cheerful line over 288 blank steps is the cheerful-line-over-a-broken-cluster failure
 * wearing a different hat.
 */
export const NothingSampledAnywhere: Story = story(
  serving({
    [THROUGHPUT_PATH]: throughputOk(THROUGHPUT_ALL_ABSENT),
    [LATENCY_PATH]: latencyOk(LATENCY_ALL_ABSENT),
  }),
);

/**
 * The exporter has stopped answering and the buffer still holds the last window.
 *
 * The state that had no fixture, no story and no render case before this wave — which is how the
 * caption's stale branch came to be deletable with the suite green. The data is still drawn, which
 * is the point of `stale`: a blank panel at the moment something is wrong is worse than an old
 * figure that says it is old. There is no badge, deliberately — `Fetched.stale` carries no
 * timestamp and inventing one is the defect the brokers screen was repaired for — so the sentence
 * under the card is the *only* thing saying this is not current.
 */
export const StaleSeries: Story = story(answering(THROUGHPUT_STALE));
