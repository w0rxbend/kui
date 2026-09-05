import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { ProduceDrawer } from "./ProduceDrawer.jsx";

/**
 * Writing a record into a topic.
 *
 * The story to look at is `Tombstone`. On a compacted topic a record whose value is null tells Kafka
 * to delete that key for good; a record whose value is the empty string is an ordinary record that
 * happens to hold no characters. One text box cannot express both, so the tombstone is a *switch*
 * that says in words what it does. Inferring it from an empty box would mean an operator who cleared
 * the field to start again deleted a key by pressing Backspace.
 *
 * `Written` is the other one: a successful produce says where the record landed rather than that it
 * was sent. "Sent" is not something an operator can go and check; "partition 3, offset 148,991" is.
 */
const meta: Meta<typeof ProduceDrawer> = {
  title: "Screens/Produce",
  component: ProduceDrawer,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  open: true,
  onClose: () => undefined,
  onSend: () => undefined,
  topic: "orders.payments.v2",
  partitionCount: 12,
};

export const Empty: Story = {
  args: { ...base, state: { kind: "idle" } },
};

export const Sending: Story = {
  args: { ...base, state: { kind: "running" } },
};

/** Where it landed, from the broker's own acknowledgement. */
export const Written: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: [{ partition: 3, offset: 148_991, timestamp: "2026-09-05T12:00:00Z" }],
    },
  },
};

/** Several copies, which is what the count field is for. */
export const WroteMany: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: [
        { partition: 0, offset: 10, timestamp: "2026-09-05T12:00:00Z" },
        { partition: 1, offset: 4, timestamp: "2026-09-05T12:00:00Z" },
        { partition: 0, offset: 11, timestamp: "2026-09-05T12:00:00Z" },
      ],
    },
  },
};

/**
 * The broker acknowledged and said nothing about where it went.
 *
 * Said plainly rather than claiming a position: an offset this screen invented would be one somebody
 * goes looking for.
 */
export const AcknowledgedWithoutPosition: Story = {
  args: { ...base, state: { kind: "done", value: [] } },
};

/** Refused. The drawer stays open with everything composed still in it. */
export const Refused: Story = {
  args: {
    ...base,
    state: {
      kind: "failed",
      message: "The record is larger than this topic's max.message.bytes.",
      code: "KUI-RECORD-TOO-LARGE",
    },
  },
};

/** A topic whose partition count is not known here: the field offers no range, and says so. */
export const UnknownPartitionCount: Story = {
  args: { ...base, partitionCount: 0, state: { kind: "idle" } },
};
