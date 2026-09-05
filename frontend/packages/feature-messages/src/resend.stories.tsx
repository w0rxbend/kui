import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { ResendDialog } from "./ResendDialog.jsx";

/**
 * Copying a range of records into another topic.
 *
 * The story to look at is `CopiedNothing`. That is a **successful** request — HTTP 200, no error,
 * no warning — whose body is `{"read":0,"written":0}`, and it is what the server answers when the
 * offsets you named have aged out of the log since you chose them. Drawn as a green tick it would be
 * the most misleading screen in the product, because the operator's next action is to go and look at
 * a destination they believe now holds their records. So it gets the warning tone, both figures are
 * shown as the figures they are, and the text says what almost certainly happened and what to do.
 *
 * The two zeroes are also the never-zero rule in the direction it is usually forgotten. `0` is a
 * measurement here and renders as `0` — never blank, never an em dash. "We copied none of them" and
 * "we could not tell how many" are different sentences and this screen is the one place the
 * difference is the whole message.
 *
 * `RetentionTookSome` and `FailedPartWay` are the other two ways `read` and `written` come apart,
 * and they need different actions: the first is nothing anyone can fix, the second means running the
 * copy again would duplicate whatever already landed.
 *
 * `Composing` is where the warnings live. They are the contract's own statements — the destination
 * gets the producer's original bytes unmarked, the copy is not atomic, retention may have moved
 * underneath it — listed one per line rather than summarised into "this cannot be undone", which is
 * a sentence every operator has clicked past.
 */
const meta: Meta<typeof ResendDialog> = {
  title: "Screens/Resend",
  component: ResendDialog,
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

/** An empty form: the destination and one range, with the warnings under them. */
export const Empty: Story = {
  args: { ...base, state: { kind: "idle" } },
};

/** A range chosen, the record count worked out, and the destination still to be typed to confirm. */
export const Composing: Story = {
  args: {
    ...base,
    initial: {
      toTopic: "orders.payments.v2.replay",
      ranges: [{ partition: 3, from: "18442800", until: "18442900" }],
    },
    state: { kind: "idle" },
  },
};

/** Several partitions at once. The total is what the cap is checked against, not each range alone. */
export const SeveralPartitions: Story = {
  args: {
    ...base,
    initial: {
      toTopic: "orders.payments.v2.replay",
      ranges: [
        { partition: 0, from: "0", until: "2500" },
        { partition: 1, from: "0", until: "2500" },
        { partition: 3, from: "18442800", until: "18443800" },
      ],
    },
    state: { kind: "idle" },
  },
};

/**
 * More records than the service will copy in one request.
 *
 * The limit appears in no schema — it exists only in the refusal — so it is checked here as well,
 * while the operator is still holding the field they need to change.
 */
export const OverTheCap: Story = {
  args: {
    ...base,
    initial: {
      toTopic: "orders.payments.v2.replay",
      ranges: [{ partition: 0, from: "0", until: "50000" }],
    },
    state: { kind: "idle" },
  },
};

export const Copying: Story = {
  args: {
    ...base,
    initial: {
      toTopic: "orders.payments.v2.replay",
      ranges: [{ partition: 3, from: "18442800", until: "18442900" }],
    },
    state: { kind: "running" },
  },
};

/** Everything the ranges named is now in the destination. */
export const Copied: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: { toTopic: "orders.payments.v2.replay", read: 100, written: 100, requested: 100 },
    },
  },
};

/**
 * A 200 that moved nothing.
 *
 * No error was returned and none was raised. The range named offsets the log no longer holds, and
 * without this panel the operator would be told the copy succeeded.
 */
export const CopiedNothing: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: { toTopic: "orders.payments.v2.replay", read: 0, written: 0, requested: 10 },
    },
  },
};

/**
 * Retention removed part of the range while the operator was deciding.
 *
 * Everything still in the log was copied, so this is not a failure — but it is not what was asked
 * for either, and the missing count is the number that says so.
 */
export const RetentionTookSome: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: { toTopic: "orders.payments.v2.replay", read: 64, written: 64, requested: 100 },
    },
  },
};

/**
 * The copy stopped part-way through.
 *
 * A resend is not atomic: the 41 records that were written stay written. Running it again without
 * looking would copy those a second time, so the panel says so rather than offering a retry.
 */
export const FailedPartWay: Story = {
  args: {
    ...base,
    state: {
      kind: "done",
      value: { toTopic: "orders.payments.v2.replay", read: 100, written: 41, requested: 100 },
    },
  },
};

/** The server refused the request. The form stays, with what was typed still in it. */
export const Refused: Story = {
  args: {
    ...base,
    initial: { toTopic: "orders.payments.v2.replay", ranges: [] },
    state: {
      kind: "failed",
      code: "KUI-VALIDATION",
      message: "a resend names no offsets, so there is nothing to copy",
    },
  },
};

/** Reading this topic is allowed; publishing into the destination is not. */
export const Forbidden: Story = {
  args: {
    ...base,
    initial: {
      toTopic: "orders.payments.v2.replay",
      ranges: [{ partition: 3, from: "18442800", until: "18442900" }],
    },
    state: {
      kind: "forbidden",
      message:
        "You do not hold a role that permits publishing into orders.payments.v2.replay on this cluster.",
    },
  },
};
