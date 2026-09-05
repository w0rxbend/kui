import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { TrackPage } from "./TrackPage.jsx";
import { emptyQuery } from "./track.js";

/**
 * Following one business event across several topics.
 *
 * The story to look at is `NothingRead`. "Nothing matched" and "nothing was read" are the same
 * screen without the scanned count, and they mean opposite things — the value is not in those topics
 * in that window, versus the window was empty and nothing has been established at all. That
 * distinction is the difference between a support engineer closing a ticket correctly and closing it
 * wrongly, so the count is on screen either way and the empty state says which case this is.
 */
const meta: Meta<typeof TrackPage> = {
  title: "Screens/Track a message",
  component: TrackPage,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const query = {
  ...emptyQuery(new Date("2026-09-05T12:00:00Z")),
  value: "4711",
  topics: ["orders.v1", "orders.payments.v2", "orders.v1.DLQ"],
};

const base = { query, onQueryChange: () => undefined, onSearch: () => undefined };

const HITS = [
  {
    topic: "orders.v1",
    partition: 3,
    offset: 148_991,
    timestamp: "2026-09-05T11:02:14Z",
    key: "4711",
    value: '{"id":"4711","state":"placed"}',
  },
  {
    topic: "orders.payments.v2",
    partition: 1,
    offset: 9_004,
    timestamp: "2026-09-05T11:02:19Z",
    key: "4711",
    value: '{"order":"4711","amountMinor":2599}',
  },
  {
    topic: "orders.v1.DLQ",
    partition: 0,
    offset: 12,
    timestamp: "2026-09-05T11:02:41Z",
    key: "4711",
    // A tombstone. The single most important thing a row on this screen can be, and it is a dash
    // with the word beside it rather than a blank cell.
    value: null,
  },
];

export const Empty: Story = {
  args: { ...base, state: { kind: "idle" } },
};

export const Searching: Story = {
  args: { ...base, state: { kind: "running" } },
};

/** The answer: three records in three topics, in time order. */
export const Found: Story = {
  args: {
    ...base,
    state: { kind: "done", value: { hits: HITS, scanned: 48_204, matched: 3, truncated: false } },
  },
};

/** Records were read and none matched: the value is not in those topics in that window. */
export const NothingMatched: Story = {
  args: {
    ...base,
    state: { kind: "done", value: { hits: [], scanned: 48_204, matched: 0, truncated: false } },
  },
};

/**
 * *Nothing was read at all.* The opposite conclusion from `NothingMatched`, and the screen says so:
 * the window holds no records in these topics, and it should be widened before anybody concludes the
 * value is not there.
 */
export const NothingRead: Story = {
  args: {
    ...base,
    state: { kind: "done", value: { hits: [], scanned: 0, matched: 0, truncated: false } },
  },
};

/** The read stopped at its budget. Said as a warning, not a footnote. */
export const Truncated: Story = {
  args: {
    ...base,
    state: { kind: "done", value: { hits: HITS, scanned: 1_000_000, matched: 3, truncated: true } },
  },
};

/** A window that ends before it starts. Caught here, because the server would answer "no matches". */
export const InvertedWindow: Story = {
  args: {
    ...base,
    query: { ...query, from: "2026-09-05T12:00:00Z", to: "2026-09-05T11:00:00Z" },
    state: { kind: "idle" },
  },
};

export const Forbidden: Story = {
  args: {
    ...base,
    state: { kind: "idle" },
    disabledReason: "You do not have permission to read messages on this cluster.",
  },
};
