import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { MessagesTab } from "./MessagesTab.jsx";
import { DEFAULT_BROWSE, type BrowseQuery } from "./browse.js";
import { LONG_TOPIC, NOW, RECORDS } from "./fixtures.js";
import { NO_PREDICATES, type Predicates } from "./predicates.js";
import { scriptedSession, startedSession } from "./storyHarness.js";

/**
 * The message browser, in every state the stream can put it in.
 *
 * The states below are the ones that are unreachable from a running product without arranging a
 * broken cluster first: a browse nobody has started, one that is following live with records queued
 * behind a pause, one that finished with nothing matching a filter, one that failed halfway with
 * records already on screen, and one on a topic whose service cannot tail. Each is one click away
 * here, which is the whole reason this workshop exists.
 */
const meta = {
  title: "Messages/MessagesTab",
  component: MessagesTab,
  parameters: { layout: "padded" },
} satisfies Meta<typeof MessagesTab>;

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  topic: "orders.payments.v2",
  partitionCount: 12,
  query: DEFAULT_BROWSE,
  onQueryChange: () => undefined,
  predicates: NO_PREDICATES as Predicates,
  onPredicatesChange: () => undefined,
  now: NOW,
} as const;

/** What screenshot `02` draws: seven records, read and finished. */
export const Read: Story = {
  args: {
    ...base,
    session: startedSession({ records: RECORDS, finish: {} }, DEFAULT_BROWSE),
  },
};

/** Nothing has been read yet. Opening a topic does not start a Kafka consumer. */
export const NotStarted: Story = {
  args: { ...base, session: scriptedSession({}) },
};

/** Following the end of the topic. The pill pulses; new records arrive at the top. */
export const Live: Story = {
  args: {
    ...base,
    query: { ...DEFAULT_BROWSE, live: true },
    session: startedSession({ records: RECORDS }, { ...DEFAULT_BROWSE, live: true }),
  },
};

/**
 * A tail that is deliberately behind: the stream is still reading, and the number says how far.
 *
 * This is the state the pause exists for — a busy topic redraws faster than a person reads one row.
 */
export const Paused: Story = {
  args: {
    ...base,
    query: { ...DEFAULT_BROWSE, live: true },
    session: (() => {
      const session = startedSession(
        { records: RECORDS.slice(0, 2) },
        { ...DEFAULT_BROWSE, live: true },
      );
      session.setPaused(true);
      return session;
    })(),
  },
};

/** A browse the server can continue. The button appears because the server sent a cursor. */
export const WithAnotherPage: Story = {
  args: {
    ...base,
    session: startedSession({ records: RECORDS, finish: { cursor: "c1" } }, DEFAULT_BROWSE),
  },
};

/**
 * Read everything in the range and matched none of it.
 *
 * Not the same screen as an empty topic, and the sentence says which it is.
 */
export const NothingMatchedTheFilter: Story = {
  args: {
    ...base,
    query: { ...DEFAULT_BROWSE, contains: "REFUNDED" },
    session: startedSession({ finish: {} }, { ...DEFAULT_BROWSE, contains: "REFUNDED" }),
  },
};

/** The connection failed after four records. The four are still on screen; they are the evidence. */
export const FailedHalfway: Story = {
  args: {
    ...base,
    session: startedSession(
      { records: RECORDS.slice(0, 4), failure: "the connection was reset", finish: {} },
      DEFAULT_BROWSE,
    ),
  },
};

/** Live tailing this deployment cannot offer. The pill stays, disabled, and says so. */
export const LiveUnavailable: Story = {
  args: {
    ...base,
    liveAvailability: { available: false, reason: "The message service is not reachable." },
    session: scriptedSession({}),
  },
};

/** A topic with one partition. The selector stays and is disabled; hiding it would puzzle. */
export const OnePartition: Story = {
  args: { ...base, partitionCount: 1, session: scriptedSession({}) },
};

/** Seeking to an offset reveals a second control, and the bar wraps rather than scrolling. */
export const SeekByOffset: Story = {
  args: {
    ...base,
    query: { ...DEFAULT_BROWSE, seek: { kind: "offset", offset: "18442800" } } as BrowseQuery,
    session: scriptedSession({}),
  },
};

/** Seeking to a time. The date control is the platform's, dressed to match the bar. */
export const SeekByTimestamp: Story = {
  args: {
    ...base,
    query: {
      ...DEFAULT_BROWSE,
      seek: { kind: "timestamp", epochMillis: Date.parse("2026-09-05T09:00:00Z") },
    } as BrowseQuery,
    session: scriptedSession({}),
  },
};

/** The extreme case: the longest real topic name, and a principal who may not publish. */
export const Extreme: Story = {
  args: {
    ...base,
    topic: LONG_TOPIC,
    partitionCount: 128,
    mayProduce: false,
    onProduce: () => undefined,
    produceDisabledReason: "You do not hold a role that permits producing to this topic.",
    session: startedSession({ records: RECORDS, finish: { cursor: "c1" } }, DEFAULT_BROWSE),
  },
};

/**
 * The two typed predicates, filled in.
 *
 * Neither of them is a query parameter. Together they compile to
 * `record.keyAsText.startsWith("ord_") && record.valueAsText.contains("UAH")`, which the route
 * registers before it starts a browse — which is why the state worth looking at is the *controls*,
 * not the expression: an operator who has to read CEL to know what their filter does has been given
 * a text box, not a control.
 */
export const TypedPredicates: Story = {
  args: {
    ...base,
    predicates: {
      key: { mode: "starts", text: "ord_" },
      value: { mode: "contains", text: "UAH" },
    },
    session: startedSession({ records: RECORDS.slice(0, 3), finish: {} }, DEFAULT_BROWSE),
  },
};

/**
 * A bounded offset range: a start the endpoint takes, and an end it does not.
 *
 * The arrow between the two boxes is the only thing on the bar that says they are one control. The
 * upper bound is a predicate — `record.offset <= 18442900` — because the browse endpoint has a
 * start position and no stop, which is exactly the gap `SCREENS-V4.md` §3.12 records.
 */
export const OffsetRange: Story = {
  args: {
    ...base,
    query: { ...DEFAULT_BROWSE, seek: { kind: "offset", offset: "18442800" } } as BrowseQuery,
    predicates: { untilOffset: "18442900" },
    session: scriptedSession({}),
  },
};

/**
 * A time window, chosen from a chip.
 *
 * `NOW` minus fifteen minutes, so the `15m` chip is the lit one on every screenshot. A window is a
 * *start*: it can be lit while LIVE is on, which is a forward read that begins fifteen minutes ago
 * and then follows, and not the invalid request it looks like.
 */
export const TimeWindow: Story = {
  args: {
    ...base,
    query: {
      ...DEFAULT_BROWSE,
      seek: { kind: "timestamp", epochMillis: NOW - 15 * 60_000 },
    } as BrowseQuery,
    session: scriptedSession({}),
  },
};

/**
 * The `PRESETS` row, with the four the design draws.
 *
 * Every other story here is the *absent* state of this row, which is the point: a preset set that is
 * not configured draws no row rather than an empty one with a heading.
 */
export const Presets: Story = {
  args: {
    ...base,
    predicates: { value: { mode: "contains", text: "DECLINED" } },
    presets: [
      { name: "Declined only", predicates: { value: { mode: "contains", text: "DECLINED" } } },
      { name: "Big tickets", predicates: {}, expression: "record.value.amount > 1000" },
      { name: "Refunds", predicates: { key: { mode: "starts", text: "rfnd_" } } },
      { name: "Non-UAH", predicates: {}, expression: 'record.value.currency != "UAH"' },
    ],
    onApplyPreset: () => undefined,
    onRemovePreset: () => undefined,
    onSavePreset: () => undefined,
    session: scriptedSession({}),
  },
};

/**
 * A topic whose partition count KUI has not been told.
 *
 * The selector is disabled and reads `all partitions` — words, no figure. It read `all 0` and
 * offered an empty menu for as long as the route above it hard-coded a zero, which is the same
 * screen as a topic that has no partitions, which is not a thing a topic can be.
 */
export const PartitionCountUnknown: Story = {
  args: { ...base, partitionCount: undefined, session: scriptedSession({}) },
};

/**
 * A Read that started no browse at all.
 *
 * Distinct from a browse that failed: this one never opened a stream, because the cluster would not
 * compile the filter the controls describe. Starting anyway would return the unfiltered topic under
 * a bar that says a filter is applied — the message service drops a `filterSource` that has no
 * `filterId` beside it, in silence.
 */
export const FilterRefused: Story = {
  args: {
    ...base,
    predicates: { key: { mode: "starts", text: "ord_" } },
    refusal:
      "KUI did not start the browse: this cluster would not compile the filter these controls " +
      "describe. cluster 'quickstart' has no filter engine, so a smart filter cannot be run",
    session: scriptedSession({}),
  },
};
