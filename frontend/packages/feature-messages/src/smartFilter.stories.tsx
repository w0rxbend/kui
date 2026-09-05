import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { SmartFilterDialog } from "./SmartFilterDialog.jsx";
import { RECORDS } from "./fixtures.js";

/**
 * Writing a filter expression, and trying it before a browse is started with it.
 *
 * The stories to look at are the three verdicts, because they are the reason this dialog exists.
 * `PreviewMatched`, `PreviewNoMatch` and `PreviewThrew` all come back from the server as the same
 * endpoint answering about the same record, and two of them carry `matched: false`. One of those two
 * is a working filter excluding a record; the other is a filter that is broken on every record in
 * the topic. From a browse they are the same empty list, and this dialog is where they are told
 * apart — so they are drawn in three different tones with three different sentences.
 *
 * `Rejected` is the fourth: an expression the compiler refused, with the server's own line and
 * column. The box keeps what was typed, because that is the moment the operator needs it.
 *
 * `NoRecordsToTryAgainst` is the state a screen usually gets wrong by hiding the control. The
 * preview needs a real record and a browse that has not been read has none, so the button is
 * disabled *with the reason*, which tells the operator what to do first.
 *
 * `NotAPredicate` is the strongest argument for the whole feature: `record.offset` **compiles**,
 * returns an id, and would have been applied to a browse without a word of warning. It is only when
 * it runs against a record that the server says it returned a number rather than true or false.
 */
const meta: Meta<typeof SmartFilterDialog> = {
  title: "Screens/Smart filter",
  component: SmartFilterDialog,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  open: true,
  onClose: () => undefined,
  onTest: () => undefined,
  onApply: () => undefined,
  topic: "orders.payments.v2",
  samples: RECORDS,
  testState: { kind: "idle" } as const,
  applyState: { kind: "idle" } as const,
};

/** Nothing typed yet. The vocabulary is one disclosure away, and the examples fill the box. */
export const Empty: Story = {
  args: base,
};

export const Writing: Story = {
  args: { ...base, source: 'record.value.status == "CAPTURED"' },
};

export const Testing: Story = {
  args: {
    ...base,
    source: 'record.value.status == "CAPTURED"',
    testState: { kind: "running" },
  },
};

/** The record the operator pointed at satisfies the expression. */
export const PreviewMatched: Story = {
  args: {
    ...base,
    source: 'record.value.status == "CAPTURED"',
    testState: { kind: "done", value: { kind: "matched" } },
  },
};

/**
 * A working filter, reporting a fact.
 *
 * Deliberately *not* a warning. This is the answer "no" from a predicate that ran perfectly, and
 * dressing it in the same furniture as a failure is how an operator learns to distrust a filter that
 * is doing exactly what they asked.
 */
export const PreviewNoMatch: Story = {
  args: {
    ...base,
    source: 'record.value.status == "REFUNDED"',
    testState: { kind: "done", value: { kind: "no-match" } },
  },
};

/**
 * The expression is legal and threw on this record — neither a match nor a non-match.
 *
 * The server's own words are shown because they name the field that was missing, which is the part
 * the operator can act on. The sentence beside them says what the browse would look like if this
 * happened on every record: an empty list, indistinguishable from an empty topic.
 */
export const PreviewThrew: Story = {
  args: {
    ...base,
    source: "record.value.customer.tier == 1",
    testState: {
      kind: "done",
      value: {
        kind: "failed",
        reason: "evaluation error at <input>:12: key 'customer' is not present in map.",
      },
    },
  },
};

/**
 * A legal expression that is not a predicate.
 *
 * This one **registers successfully** — CEL has no opinion about the type of `record.offset`, so an
 * id comes back and nothing warns anybody. Only running it produces the sentence below. Without this
 * preview the discovery happens partway through a browse over a production topic.
 */
export const NotAPredicate: Story = {
  args: {
    ...base,
    source: "record.offset",
    testState: {
      kind: "done",
      value: { kind: "failed", reason: "the filter returned Long rather than true or false" },
    },
  },
};

/**
 * The server refused to compile it, naming the line and column.
 *
 * The box keeps the expression. Clearing it on failure would throw away the work at the one moment
 * it is needed, and the position in the message is meaningless without the text it refers to.
 */
export const Rejected: Story = {
  args: {
    ...base,
    source: 'record.value.status ==',
    applyState: {
      kind: "failed",
      code: "KUI-FILTER-COMPILE",
      message:
        "line 1, column 22: mismatched input '<EOF>' expecting {'[', '{', '(', '.', '-', '!', " +
        "'true', 'false', 'null', NUM_FLOAT, NUM_INT, NUM_UINT, STRING, BYTES, IDENTIFIER}",
    },
  },
};

/**
 * Nothing has been read yet, so there is no honest record to try the filter against.
 *
 * The control stays and says why. Removing it would teach the operator the product cannot preview a
 * filter; disabling it with a sentence tells them to read some records first.
 */
export const NoRecordsToTryAgainst: Story = {
  args: { ...base, samples: [], source: 'record.keyAsText.startsWith("ord_")' },
};

/** A filter is already running: the dialog offers to remove it as well as to change it. */
export const Applied: Story = {
  args: {
    ...base,
    source: 'record.value.status == "CAPTURED"',
    onClear: () => undefined,
  },
};

/** This principal may register filters on some clusters, and not this one. */
export const Forbidden: Story = {
  args: {
    ...base,
    source: "record.partition == 0",
    applyState: {
      kind: "forbidden",
      message: "You do not hold a role that permits running smart filters on this cluster.",
    },
  },
};

/**
 * The cluster has no filter engine at all.
 *
 * `KUI-UNSUPPORTED`, and it is what the quickstart answers: the engine is configured per cluster and
 * one that was not running when the message service started has none. The refusal is the server's
 * own sentence rather than a paraphrase, because "smart filters are unavailable" would leave an
 * operator with nothing to go and check.
 */
export const NoFilterEngine: Story = {
  args: {
    ...base,
    source: "record.partition == 0",
    applyState: {
      kind: "failed",
      code: "KUI-UNSUPPORTED",
      message:
        "cluster 'quickstart' has no filter engine, so a smart filter cannot be run is not supported here",
    },
  },
};
