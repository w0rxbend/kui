import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { Switch } from "./Switch.jsx";
import { Checkbox } from "./Checkbox.jsx";

/**
 * The switch, and the thing it is most often confused with.
 *
 * The story that matters here is the last but one: a switch and a checkbox side by side. They look
 * similar enough that the difference has to be a *rule* rather than a preference, and the rule is
 * about when the change happens — a switch takes effect at once, a checkbox waits for a submit.
 * Seeing them together is the only way to judge whether the two shapes are distinct enough for
 * that rule to be readable on screen.
 */
const meta: Meta<typeof Switch> = {
  title: "Controls/Switch",
  component: Switch,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Switch>;

const Row = (props: { readonly children: unknown }) => (
  <div style={{ display: "flex", "flex-direction": "column", gap: "16px", "align-items": "flex-start" }}>
    {props.children as never}
  </div>
);

/** Off and on, which is the whole component. Check that the knob is at the end you expect. */
export const OffAndOn: Story = {
  render: () => (
    <Row>
      <Switch label="Show statistics" checked={false} onChange={() => undefined} />
      <Switch label="Show statistics" checked onChange={() => undefined} />
    </Row>
  ),
};

/** Live, so the travel can be watched. Click it; the knob should move, not jump. */
export const Interactive: Story = {
  render: () => {
    const [on, setOn] = createSignal(false);
    return (
      <Row>
        <Switch label="Show statistics" checked={on()} onChange={setOn} />
        <p style={{ color: "var(--kui-color-text-muted)", "font-size": "13px" }}>
          Statistics are {on() ? "shown" : "hidden"}.
        </p>
      </Row>
    );
  },
};

/**
 * Disabled, with and without a stated reason.
 *
 * The one on the right is the only acceptable form. A disabled control with no explanation is
 * indistinguishable from a broken one, and the operator's next move is to file a bug rather than
 * to ask for a permission.
 */
export const Disabled: Story = {
  render: () => (
    <Row>
      <Switch label="Show statistics" checked={false} disabled onChange={() => undefined} />
      <Switch
        label="Show statistics"
        checked
        disabled
        disabledReason="Statistics need the metrics service, which is not configured for this cluster."
        onChange={() => undefined}
      />
    </Row>
  ),
};

/**
 * The comparison the component exists to survive: a switch beside a checkbox.
 *
 * If these two ever become hard to tell apart, the rule dividing them stops being visible and an
 * operator will expect a Save button that is not there.
 */
export const AgainstACheckbox: Story = {
  render: () => (
    <Row>
      <Switch label="Show statistics — takes effect now" checked onChange={() => undefined} />
      <Checkbox label="Also purge the data — takes effect on submit" checked onChange={() => undefined} />
    </Row>
  ),
};

/**
 * The extreme: a label longer than the row it is in. The track must not be pushed off the end or
 * shrunk, because a switch narrower than its knob is not a switch.
 */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ width: "280px", display: "flex", "flex-direction": "column", gap: "16px" }}>
      <Switch
        label="Show the per-partition statistics panel, including the throughput history for every replica"
        checked
        onChange={() => undefined}
      />
      <Switch label="On" checked={false} onChange={() => undefined} />
    </div>
  ),
};
