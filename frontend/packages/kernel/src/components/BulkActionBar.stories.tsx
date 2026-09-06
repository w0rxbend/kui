import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { BulkActionBar, type BulkAction } from "./BulkActionBar.jsx";
import { Button } from "./Button.jsx";

/**
 * The pill that floats over a list once rows are selected.
 *
 * Two stories are the reason this file exists. `Forbidden` is the rule the design states in as
 * many words — an action the principal may not take is disabled with its reason, never hidden —
 * and the only way to judge it is to look at it beside `Default` and see that nothing moved.
 * `NoSelection` renders nothing at all, which is a state worth having a story for precisely
 * because there is nothing to see: an empty bar or a hidden one would still be sitting on top of
 * the last row of the list.
 */
const meta = {
  title: "Surfaces/BulkActionBar",
  component: BulkActionBar,
  parameters: { layout: "fullscreen" },
  decorators: [
    // The bar is `position: fixed`, so a padded story canvas would tell nothing about where it
    // lands. This box is the page it floats over.
    (Story) => (
      <div style={{ "min-height": "320px", padding: "24px" }}>
        <p style={{ color: "var(--kui-color-text-muted)" }}>
          The topic list would be here. The bar floats over its last rows.
        </p>
        {Story()}
      </div>
    ),
  ],
} satisfies Meta<typeof BulkActionBar>;

export default meta;
type Story = StoryObj<typeof meta>;

const actions: readonly BulkAction[] = [
  { id: "config", label: "Edit config", icon: "settings", onSelect: () => {} },
  { id: "purge", label: "Purge", icon: "trash", destructive: true, onSelect: () => {} },
  { id: "delete", label: "Delete", icon: "trash", destructive: true, onSelect: () => {} },
];

/** The design's bar: two topics selected, three actions, a way out. */
export const Default: Story = {
  args: { count: 2, noun: "topic", actions, onDismiss: () => {} },
};

/** One, so the noun is singular. "1 topics selected" is the kind of detail an operator reads as
 * carelessness about everything else on the screen. */
export const One: Story = {
  args: { count: 1, noun: "topic", actions, onDismiss: () => {} },
};

/**
 * Nothing selected. The story renders the page and no bar — not an empty one, not a hidden one.
 * A bar that is always in the document is a strip of the window nobody can use, and one that is
 * merely invisible still swallows the clicks meant for the row underneath it.
 */
export const NoSelection: Story = {
  args: { count: 0, noun: "topic", actions, onDismiss: () => {} },
  play: async ({ canvasElement }) => {
    await expect(canvasElement.querySelector(".kui-bulkbar")).toBeNull();
  },
};

/**
 * The principal may not delete topics on this cluster.
 *
 * Delete stays exactly where it is, disabled, carrying the sentence that says why — tab to it and
 * the reason appears, because the control is still focusable. Hiding it would slide Purge into
 * its place, and the same gesture would then purge for one operator and delete for another.
 */
export const Forbidden: Story = {
  args: {
    count: 4,
    noun: "topic",
    onDismiss: () => {},
    actions: [
      actions[0] as BulkAction,
      actions[1] as BulkAction,
      {
        id: "delete",
        label: "Delete",
        icon: "trash",
        destructive: true,
        disabledReason: "You do not have permission to delete topics on this cluster.",
        onSelect: () => {},
      },
    ],
  },
};

/** Every action forbidden — a read-only principal. The bar still says what was selected, and the
 * dismiss button still works, because clearing a selection is not a permission. */
export const ReadOnlyPrincipal: Story = {
  args: {
    count: 4,
    noun: "topic",
    onDismiss: () => {},
    actions: actions.map((action) => ({
      ...action,
      disabledReason: "This cluster is configured read-only.",
    })),
  },
};

/** Live: tick the count up and down, and watch the bar arrive at one and leave at zero. */
export const Interactive: StoryObj = {
  render: () => {
    const [count, setCount] = createSignal(2);
    return (
      <>
        {/* The product's own button, not a bare `<button>`: the browser's default control is grey
            on grey and fails contrast, and a story that trips the a11y sweep on its own scaffolding
            teaches everybody to ignore the sweep. */}
        <div style={{ display: "flex", gap: "8px" }}>
          <Button size="sm" variant="secondary" onClick={() => setCount((n) => n + 1)}>
            Select one more
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setCount((n) => Math.max(0, n - 1))}>
            Deselect one
          </Button>
        </div>
        <BulkActionBar
          count={count()}
          noun="topic"
          actions={actions}
          onDismiss={() => setCount(0)}
        />
      </>
    );
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole("button", { name: /clear selection/i }));
    await expect(canvasElement.querySelector(".kui-bulkbar")).toBeNull();
  },
};

/** More actions than a narrow window holds. They scroll inside the bar rather than pushing the
 * dismiss button off the end of it, which would leave an operator unable to close it. */
export const NarrowWindow: StoryObj = {
  decorators: [(Story) => <div style={{ width: "360px", "min-height": "240px" }}>{Story()}</div>],
  render: () => (
    <BulkActionBar
      count={12}
      noun="consumer group"
      plural="consumer groups"
      onDismiss={() => {}}
      actions={[
        { id: "reset", label: "Reset offsets", icon: "restart", onSelect: () => {} },
        { id: "config", label: "Edit config", icon: "settings", onSelect: () => {} },
        { id: "export", label: "Export", icon: "download", onSelect: () => {} },
        { id: "delete", label: "Delete", icon: "trash", destructive: true, onSelect: () => {} },
      ]}
    />
  ),
};
