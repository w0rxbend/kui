import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Button } from "./Button.jsx";
import { ConfirmDialog, Dialog } from "./Dialog.jsx";

/**
 * A dialog cannot be judged from a screenshot of it sitting open: half of what it has to get right
 * is what happens when it opens and closes. So every story here starts **closed**, behind a real
 * trigger, and the things to check are:
 *
 *   - focus moves into the surface and the trigger is not what `Tab` reaches next;
 *   - `Tab` wraps at both ends and never lands on the page behind the veil;
 *   - `Escape` closes it, and focus goes back to the trigger you pressed;
 *   - the page behind does not scroll while it is open.
 */
const meta: Meta<typeof Dialog> = {
  title: "Surfaces/Dialog",
  component: Dialog,
  parameters: { layout: "centered" },
};

export default meta;
type Story = StoryObj<typeof Dialog>;

/** A trigger with enough page behind it to see whether the page scrolls while the dialog is up. */
function Stage(props: { readonly children: (open: () => boolean, setOpen: (v: boolean) => void) => unknown }) {
  const [open, setOpen] = createSignal(false, { ownedWrite: true });
  return (
    <div>
      <p style={{ "max-width": "40ch", "margin-bottom": "16px" }}>
        Press the button. Then press <kbd>Escape</kbd> and check that focus came back here.
      </p>
      <Button variant="primary" onClick={() => setOpen(true)}>
        Open
      </Button>
      <div style={{ height: "120vh" }} aria-hidden="true" />
      {props.children(open, setOpen) as never}
    </div>
  );
}

export const Question: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Stage>
        {(open, setOpen) => (
          <Dialog
            open={open()}
            onClose={() => setOpen(false)}
            title="Reset offsets for payments-processor?"
            description="The group is currently Stable with 4 members."
            actions={
              <>
                <Button variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button variant="primary" onClick={() => setOpen(false)}>
                  Reset to earliest
                </Button>
              </>
            }
          >
            <p>Every member will re-read the topic from the beginning of its retention window.</p>
          </Dialog>
        )}
      </Stage>
    </div>
  ),
};

/** The three widths, so a reviewer can see they are three and not five. */
export const Sizes: Story = {
  parameters: { layout: "fullscreen" },
  render: () => {
    const [size, setSize] = createSignal<"sm" | "md" | "lg" | null>(null, { ownedWrite: true });
    return (
      <div style={{ display: "flex", gap: "12px", padding: "24px" }}>
        <Button onClick={() => setSize("sm")}>Small</Button>
        <Button onClick={() => setSize("md")}>Medium</Button>
        <Button onClick={() => setSize("lg")}>Large</Button>
        <Dialog
          open={size() !== null}
          onClose={() => setSize(null)}
          title={`A ${size() ?? ""} dialog`}
          size={size() ?? "md"}
          actions={<Button variant="primary" onClick={() => setSize(null)}>Close</Button>}
        >
          <p>Small asks a question. Medium holds a short form. Large holds a diff.</p>
        </Dialog>
      </div>
    );
  },
};

/**
 * A body longer than the window. The **body** scrolls, not the dialog — the title stays put and,
 * more importantly, the actions stay reachable. A dialog whose buttons are below the fold cannot
 * be answered, and `Escape` is not an answer.
 */
export const LongBodyScrollsInsideItself: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Stage>
        {(open, setOpen) => (
          <Dialog
            open={open()}
            onClose={() => setOpen(false)}
            title="Effective configuration"
            actions={<Button variant="primary" onClick={() => setOpen(false)}>Close</Button>}
          >
            <div style={{ display: "flex", "flex-direction": "column", gap: "8px" }}>
              {Array.from({ length: 40 }, (_, index) => (
                <div style={{ display: "flex", "justify-content": "space-between", gap: "24px" }}>
                  <span>retention.ms.override.{index}</span>
                  <span>604800000</span>
                </div>
              ))}
            </div>
          </Dialog>
        )}
      </Stage>
    </div>
  ),
};

/**
 * The confirmation for a destructive action.
 *
 * Three things to look at, and all three were defects before they were rules. The title **names
 * the object**. The consequence is **measurements, not adjectives**. And focus opens on **Cancel**,
 * because the keystroke that opened the dialog is often still going.
 */
export const DestructiveConfirmation: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Stage>
        {(open, setOpen) => (
          <ConfirmDialog
            open={open()}
            onClose={() => setOpen(false)}
            onConfirm={() => setOpen(false)}
            title="Purge orders.payments.v2?"
            consequence="This deletes 1,536 partitions' worth of records — about 128 GB. It cannot be undone."
            confirmLabel="Purge"
            confirmIcon="trash"
          />
        )}
      </Stage>
    </div>
  ),
};

/**
 * The worst of them. Deleting a topic requires the operator to type its name — not friction for
 * its own sake, but the one mechanism that makes it impossible to destroy the *wrong* topic by
 * muscle memory, because muscle memory does not know the name.
 *
 * Note that the blocked confirm button is still reachable by `Tab` and still says why.
 */
export const TypeToConfirm: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Stage>
        {(open, setOpen) => (
          <ConfirmDialog
            open={open()}
            onClose={() => setOpen(false)}
            onConfirm={() => setOpen(false)}
            title="Delete orders.payments.v2?"
            consequence="This removes the topic, its 1,536 partitions and about 128 GB of records from prod-kyiv-01. It cannot be undone."
            confirmLabel="Delete topic"
            confirmIcon="trash"
            typeToConfirm="orders.payments.v2"
          />
        )}
      </Stage>
    </div>
  ),
};

/**
 * Irreversible but not destructive. Adding partitions cannot be undone and changes which partition
 * a key lands on — so it gets a consequence sentence and a confirmation, but **not** the danger
 * silhouette. Compare it with the story above: they must not look the same.
 */
export const IrreversibleButNotDestructive: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Stage>
        {(open, setOpen) => (
          <ConfirmDialog
            open={open()}
            onClose={() => setOpen(false)}
            onConfirm={() => setOpen(false)}
            title="Add 4 partitions to orders.payments.v2?"
            consequence="Partitions cannot be removed, and adding them changes which partition a key lands on."
            confirmLabel="Add partitions"
            confirmIcon="plus"
            destructive={false}
          />
        )}
      </Stage>
    </div>
  ),
};

/** The mutation is in flight. The label stays, the width does not change, the press is refused. */
export const Busy: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <ConfirmDialog
        open
        onClose={() => {}}
        onConfirm={() => {}}
        title="Purge orders.payments.v2?"
        consequence="This deletes 1,536 partitions' worth of records — about 128 GB. It cannot be undone."
        confirmLabel="Purge"
        confirmIcon="trash"
        busy
      />
    </div>
  ),
};

/**
 * It failed. The dialog **stays open**, everything typed stays where it was, and the error appears
 * above the actions with its code. Closing on failure makes the operator reconstruct what they
 * were doing in order to find out that it did not happen.
 */
export const FailedAndStillOpen: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <ConfirmDialog
        open
        onClose={() => {}}
        onConfirm={() => {}}
        title="Delete orders.payments.v2?"
        consequence="This removes the topic, its 1,536 partitions and about 128 GB of records. It cannot be undone."
        confirmLabel="Delete topic"
        confirmIcon="trash"
        typeToConfirm="orders.payments.v2"
        error={{
          message: "The topic service refused the deletion: the topic is marked as protected.",
          code: "TOPIC_PROTECTED",
        }}
      />
    </div>
  ),
};

/** The extreme case: a topic name longer than the dialog. It wraps; it is never cut. */
export const LongestPossibleTitle: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <ConfirmDialog
        open
        onClose={() => {}}
        onConfirm={() => {}}
        title="Delete orders.payments.reconciliation.v2.eu-central-1.high-throughput.retry.dead-letter.compacted?"
        consequence="This removes the topic, its 4,096 partitions and about 3.2 TB of records from prod-kyiv-01. It cannot be undone, and the six consumer groups reading it will begin to fail on their next poll."
        confirmLabel="Delete topic"
        confirmIcon="trash"
        typeToConfirm="orders.payments.reconciliation.v2.eu-central-1.high-throughput.retry.dead-letter.compacted"
      />
    </div>
  ),
};
