import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Button } from "./Button.jsx";
import { ToastRegion, clearToasts, notify } from "./Toast.jsx";

/**
 * Toasts.
 *
 * Two of these stories are about time, which no screenshot can show, so they have to be *used*:
 * `AFailureNeverGoesAway` — leave it and check it is still there a minute later — and
 * `TheTimerPausesUnderThePointer`, where hovering the stack has to stop the countdown, because
 * somebody reaching for the dismiss button must not have the toast vanish out from under them.
 */
const meta: Meta<typeof ToastRegion> = {
  title: "Surfaces/Toast",
  component: ToastRegion,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof ToastRegion>;

function Stage(props: { readonly children: unknown }) {
  return (
    <div style={{ padding: "24px", "min-height": "60vh" }}>
      <div style={{ display: "flex", gap: "12px", "flex-wrap": "wrap", "margin-bottom": "16px" }}>
        {props.children as never}
        <Button variant="ghost" onClick={() => clearToasts()}>
          Clear
        </Button>
      </div>
      <ToastRegion />
    </div>
  );
}

/** The four tones. Only the rule and the glyph carry the tone; the words stay at full contrast. */
export const EveryTone: Story = {
  render: () => (
    <Stage>
      <Button onClick={() => notify("Topic created", { message: "orders.payments.v3 is on prod-kyiv-01." })}>
        Success
      </Button>
      <Button onClick={() => notify("KUI is in read-only mode", { tone: "info", message: "Mutations are disabled for this cluster." })}>
        Info
      </Button>
      <Button onClick={() => notify("Offsets reset with 2 warnings", { tone: "warning", message: "Two partitions had no committed offset." })}>
        Warning
      </Button>
      <Button onClick={() => notify("Could not create topic", { tone: "danger", message: "The topic service is not responding.", code: "UPSTREAM_UNAVAILABLE" })}>
        Danger
      </Button>
    </Stage>
  ),
};

/**
 * A failure never disappears on a timer, even when the caller asks it to. The reader may not have
 * finished, and the code in it is the thing they have to write down. Press this and leave it.
 */
export const AFailureNeverGoesAway: Story = {
  render: () => (
    <Stage>
      <Button
        variant="danger"
        icon="warning"
        onClick={() =>
          notify("Could not produce the record", {
            tone: "danger",
            message: "The broker rejected it: larger than max.message.bytes.",
            code: "RECORD_TOO_LARGE",
            // Asked for one second. Overruled, on purpose.
            durationMs: 1000,
          })
        }
      >
        Raise a failure that asked to vanish
      </Button>
    </Stage>
  ),
};

/** Raise one, then keep the pointer on it. The countdown stops; move away and it resumes. */
export const TheTimerPausesUnderThePointer: Story = {
  render: () => (
    <Stage>
      <Button onClick={() => notify("Topic created", { durationMs: 4000, message: "Hover this toast to hold it." })}>
        Raise a four-second toast
      </Button>
    </Stage>
  ),
};

/** One action, most usefully Undo. Taking it dismisses the toast. */
export const WithAnAction: Story = {
  render: () => (
    <Stage>
      <Button
        onClick={() =>
          notify("Consumer group deleted", {
            message: "payments-processor is gone from prod-kyiv-01.",
            action: { label: "Undo", onClick: () => notify("Consumer group restored") },
          })
        }
      >
        Delete something undoable
      </Button>
    </Stage>
  ),
};

/**
 * More than the stack draws. Three are shown and the rest are counted, because a column of nine
 * toasts covers the thing the operator was trying to look at.
 */
export const MoreThanFit: Story = {
  render: () => (
    <Stage>
      <Button
        onClick={() => {
          for (let index = 0; index < 6; index += 1) {
            notify(`Topic ${index} created`, { durationMs: null });
          }
        }}
      >
        Raise six at once
      </Button>
    </Stage>
  ),
};

/** The extreme: the longest message the product can produce, in the narrowest stack. */
export const TheLongestMessage: Story = {
  render: () => (
    <Stage>
      <Button
        onClick={() =>
          notify("Could not reset offsets for payments-processor", {
            tone: "danger",
            message:
              "The consumer service accepted the request and then failed while committing: two of the four members " +
              "had left the group between the plan being made and the commit being attempted, so the reset was " +
              "applied to two partitions and not to the other two.",
            code: "PARTIAL_MUTATION_REBALANCE_IN_PROGRESS",
            action: { label: "Retry", onClick: () => {} },
          })
        }
      >
        Raise the longest one
      </Button>
    </Stage>
  ),
};
