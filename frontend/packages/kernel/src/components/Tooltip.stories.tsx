import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Tooltip } from "./Tooltip.jsx";
import { Button } from "./Button.jsx";

// Default args are required rather than decorative: `content` and `children` have no defaults on
// the component, so without them here every story that supplies only a `render` would be missing
// required props as far as the type checker is concerned.
const meta = {
  title: "Primitives/Tooltip",
  component: Tooltip,
  args: {
    content: "Metrics are retained for 7 days.",
    children: <Button variant="ghost" icon="info">Why?</Button>,
  },
} satisfies Meta<typeof Tooltip>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Point at it, or tab to it. Both must work; `Escape` must dismiss it. */
export const Default: Story = {
  render: () => (
    <Tooltip content="Metrics are retained for 7 days.">
      <Button variant="ghost" icon="info">Why is 30d unavailable?</Button>
    </Tooltip>
  ),
};

/**
 * With a code. The sentence tells the operator what is happening; the code tells whoever they ask
 * for help which failure it was. Neither replaces the other.
 */
export const WithCode: Story = {
  render: () => (
    <Tooltip content="The consumer service is not responding." code="UPSTREAM_UNAVAILABLE">
      <Button variant="ghost" icon="warning">Consumer groups unavailable</Button>
    </Tooltip>
  ),
};

/** The main use: explaining a disabled control, which fires no pointer events of its own. */
export const OnADisabledButton: Story = {
  render: () => (
    <Button
      variant="primary"
      icon="plus"
      disabled
      disabledReason="You do not have permission to create topics on prod-kyiv-01."
      disabledCode="RBAC_DENIED"
    >
      Create topic
    </Button>
  ),
};

/** On plain text, for a value that has been truncated. */
export const OnText: Story = {
  render: () => (
    <Tooltip content="orders.payments.v2.reprocessing.deadletter.eu-central-1">
      <span
        tabindex="0"
        style={{ "max-width": "180px", overflow: "hidden", "text-overflow": "ellipsis", "white-space": "nowrap", display: "inline-block" }}
      >
        orders.payments.v2.reprocessing.deadletter.eu-central-1
      </span>
    </Tooltip>
  ),
};

/** The extreme case: a long sentence. It wraps inside a bounded bubble and does not leave the window. */
export const LongestContent: Story = {
  render: () => (
    <Tooltip
      content="Adding partitions cannot be undone, and it changes which partition a key lands on — so consumers that rely on per-key ordering will see keys move to a different partition from the moment this takes effect."
      code="TOPIC_PARTITIONS_IRREVERSIBLE"
    >
      <Button variant="secondary" icon="plus">Add partitions</Button>
    </Tooltip>
  ),
};

/** At the edges of the window, where an unclamped tooltip goes off screen. */
export const AtTheWindowEdges: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ height: "100vh", position: "relative" }}>
      <div style={{ position: "absolute", top: "4px", left: "4px" }}>
        <Tooltip content="Top left. There is no room above, so this one flips below.">
          <Button variant="ghost" icon="info" iconOnly>Top left</Button>
        </Tooltip>
      </div>
      <div style={{ position: "absolute", top: "4px", right: "4px" }}>
        <Tooltip content="Top right, with a sentence long enough to want to run off the edge.">
          <Button variant="ghost" icon="info" iconOnly>Top right</Button>
        </Tooltip>
      </div>
      <div style={{ position: "absolute", bottom: "4px", left: "4px" }}>
        <Tooltip content="Bottom left.">
          <Button variant="ghost" icon="info" iconOnly>Bottom left</Button>
        </Tooltip>
      </div>
      <div style={{ position: "absolute", bottom: "4px", right: "4px" }}>
        <Tooltip content="Bottom right, with a sentence long enough to want to run off the edge.">
          <Button variant="ghost" icon="info" iconOnly>Bottom right</Button>
        </Tooltip>
      </div>
    </div>
  ),
};

/** Inside a scrolling box: a portal is what stops the bubble being clipped by the box. */
export const InsideAScroller: Story = {
  render: () => (
    <div style={{ width: "220px", height: "90px", overflow: "auto", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
      <div style={{ height: "200px" }}>
        <Tooltip content="This bubble is drawn on the body, so the box around it cannot clip it.">
          <Button variant="ghost" icon="info">Hover me</Button>
        </Tooltip>
      </div>
    </div>
  ),
};
