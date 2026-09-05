import { For } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import {
  EXTREME_RECORD,
  NOW,
  RECORDS,
  TOMBSTONE,
  TOO_LARGE,
  UNDECODABLE,
} from "./listFixtures.js";
import { recordKey } from "./record.js";
import { RecordList, RecordRow } from "./RecordRow.jsx";

/**
 * A record in the message list, collapsed and expanded.
 *
 * Five of the stories below are payloads that are not JSON: a tombstone, a payload too large to
 * preview, one the deserializer refused, one that is plain text, and one with a header carrying
 * bytes that are not UTF-8. Three of those have been drawn as an empty row in this product's
 * history, and an empty row is indistinguishable from a record that genuinely holds the empty
 * string — which is why they have stories and the happy path has one.
 */
const meta = {
  title: "Kernel/RecordRow",
  component: RecordRow,
  parameters: { layout: "padded" },
  decorators: [
    (Story, context) => (
      /* The list lives here and ONLY here.
       *
       * A `RecordRow` renders an `<li>`, so every story needs a `<ul>` around it or axe reports
       * `listitem` — hence the decorator. But three stories used to build a `RecordList` of their
       * own inside this one, which produced a `<ul>` directly inside a `<ul>`: `list` and
       * `listitem` both fail, and a screen reader announces a list of one item that is a list.
       * Those stories now render bare rows and inherit this list. */
      /* The width goes on a wrapper *outside* the list, driven by a parameter, rather than on a
         per-story decorator. A story decorator runs inside the meta one, so a `<div>` there lands
         as a direct child of this `<ul>` — which axe reports as `list`, and which makes the row
         inside it an `<li>` with no list parent. */
      <div style={{ width: (context.parameters["width"] as string | undefined) ?? "auto" }}>
        <RecordList label="Records in orders.payments.v2">{Story()}</RecordList>
      </div>
    ),
  ],
} satisfies Meta<typeof RecordRow>;

export default meta;
type Story = StoryObj<typeof meta>;

const first = RECORDS[0];
if (first === undefined) throw new Error("fixture missing");

/** Screenshot `02`: offset, partition pill, key, one-line JSON, relative time, chevron. */
export const AsDesigned: Story = { args: { record: first, now: NOW } };

/** Screenshot `03`: the same row, open. The four labelled boxes, the header chips, the
 * pretty-printed payload. */
export const Expanded: Story = { args: { record: first, now: NOW, initiallyExpanded: true } };

/**
 * The whole row is the control, and opening it pushes the rows below it down.
 *
 * Click anywhere on the summary — not just the chevron — and the second record moves down the
 * page rather than being covered. An overlay would hide the neighbouring records, which is exactly
 * what somebody comparing two payloads is trying not to lose.
 */
export const OpensInPlace: StoryObj = {
  render: () => (
    <For each={RECORDS}>
      {(record) => <RecordRow record={record} now={NOW} testId={`record-${recordKey(record)}`} />}
    </For>
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const buttons = canvas.getAllByRole("button", { expanded: false });
    const summary = buttons[0];
    if (summary === undefined) throw new Error("no rows rendered");

    const secondBefore = canvas.getByTestId("record-1:18442900").getBoundingClientRect().top;
    await userEvent.click(summary);
    await expect(summary).toHaveAttribute("aria-expanded", "true");
    const secondAfter = canvas.getByTestId("record-1:18442900").getBoundingClientRect().top;
    await expect(secondAfter).toBeGreaterThan(secondBefore);
  },
};

/** Keyboard: the summary is a real `<button>`, so Tab reaches it and Enter opens it, with no key
 * handling written by hand. */
export const OpensFromTheKeyboard: Story = {
  args: { record: first, now: NOW },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const summary = canvas.getByRole("button");
    summary.focus();
    await expect(summary).toHaveFocus();
    await userEvent.keyboard("{Enter}");
    await expect(summary).toHaveAttribute("aria-expanded", "true");
  },
};

/**
 * A tombstone: null key, null value.
 *
 * The key reads `— (tombstone)` rather than a bare dash, because a null key in a compacted topic
 * *is* the deletion — "no key" and "this record deletes that key" are different statements. Not an
 * error, and not an empty row.
 */
export const Tombstone: Story = { args: { record: TOMBSTONE, now: NOW } };

export const TombstoneExpanded: Story = {
  args: { record: TOMBSTONE, now: NOW, initiallyExpanded: true },
};

/**
 * The deserializer failed.
 *
 * The reason is the whole diagnosis — "Avro schema 42 not found" tells the operator exactly what
 * to fix — and the expansion offers the raw bytes, because they are the only thing left that is
 * definitely true. Never an empty row.
 */
export const CouldNotDeserialize: Story = {
  args: { record: UNDECODABLE, now: NOW, initiallyExpanded: true },
};

/** Too large to preview. The size and what to do about it, not a truncated four megabytes of
 * JSON pasted into a 12px row. */
export const TooLargeToPreview: Story = { args: { record: TOO_LARGE, now: NOW } };

/** Not JSON, and that is fine. The `{}` glyph becomes a text glyph and the payload is shown as it
 * arrived, rather than being reported as an error because it failed to parse. */
export const NotJson: Story = {
  args: { record: RECORDS[2] as (typeof RECORDS)[number], now: NOW, initiallyExpanded: true },
};

/**
 * No headers.
 *
 * The `HEADERS` label stays and is followed by `— none`. Dropping the label entirely makes the
 * reader wonder whether the product looked, which is a worse thing to leave them wondering than
 * "there are none".
 */
export const NoHeaders: Story = {
  args: { record: RECORDS[2] as (typeof RECORDS)[number], now: NOW, initiallyExpanded: true },
};

/** Arrived while live-tailing: a one-shot wash that fades. It does not repeat — a fast tail would
 * otherwise be a permanently green list, which says nothing at all. */
export const JustArrived: Story = { args: { record: first, now: NOW, arrived: true } };

/**
 * Every extreme at once.
 *
 * An offset past 2^53 — which is why an offset is carried as a string, since a JavaScript number
 * would render the last digits wrong — a key longer than its column, five headers including one
 * empty and one binary, a deeply nested payload, and a schema attached so the expansion draws
 * *five* boxes in a grid built for four. The grid reflows rather than squashing.
 */
export const EveryExtremeAtOnce: Story = {
  args: { record: EXTREME_RECORD, now: NOW, initiallyExpanded: true },
};

/** The same record collapsed. The key and the value both ellipsise and neither wraps: a wrapped
 * preview makes one row two lines tall, and a list of five hundred then has rows of different
 * heights for no reason the reader chose. */
export const EveryExtremeCollapsed: Story = { args: { record: EXTREME_RECORD, now: NOW } };

/** In a 380px column. The value column is the only one that gives way, so the offset, the
 * partition and the time stay readable at every width. */
export const NarrowWindow: Story = {
  args: { record: EXTREME_RECORD, now: NOW, initiallyExpanded: true },
  parameters: { width: "380px" },
};

/** The whole list, as screenshot `02` draws it, with the awkward records mixed in among the
 * ordinary ones — which is how they arrive in a real topic. */
export const TheWholeList: StoryObj = {
  render: () => (
    <For each={[...RECORDS, TOMBSTONE, UNDECODABLE, TOO_LARGE, EXTREME_RECORD]}>
      {(record) => <RecordRow record={record} now={NOW} />}
    </For>
  ),
};

/**
 * Relative times across the whole range, including one in the future.
 *
 * A producer whose clock is ahead of the broker's writes a record stamped in the future. It is
 * common enough that treating it as a fault would cry wolf, so it reads "in 3s" — true, and a
 * visible hint that a clock is wrong somewhere.
 */
export const EveryRelativeTime: StoryObj = {
  render: () => {
    const offsets = [-3, 2, 45, 3600 * 2, 86_400 * 5];
    return (
      <For each={offsets}>
        {(seconds, index) => (
          <RecordRow
            record={{
              ...first,
              offset: String(1000 + index()),
              timestamp: new Date(NOW - seconds * 1000).toISOString(),
            }}
            now={NOW}
          />
        )}
      </For>
    );
  },
};
