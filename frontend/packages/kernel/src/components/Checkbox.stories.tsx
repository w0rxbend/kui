import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { Checkbox } from "./Checkbox.jsx";

const meta = {
  title: "Primitives/Checkbox",
  component: Checkbox,
  args: { label: "Include internal topics" },
} satisfies Meta<typeof Checkbox>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Unchecked. It must be *visible* while unchecked: a checkbox drawn as nothing is the defect. */
export const Unchecked: Story = {};

export const Checked: Story = { args: { checked: true } };

/** Some but not all. Drawn as a bar, and announced as "mixed" — never as a tick. */
export const Indeterminate: Story = { args: { indeterminate: true, label: "Select all rows" } };

export const Disabled: Story = { args: { disabled: true } };
export const DisabledChecked: Story = { args: { disabled: true, checked: true } };

/** All four states together, which is the only way to see that each is distinguishable. */
export const AllStates: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "12px", "justify-items": "start" }}>
      <Checkbox label="Unchecked" />
      <Checkbox label="Checked" checked />
      <Checkbox label="Indeterminate" indeterminate />
      <Checkbox label="Disabled" disabled />
      <Checkbox label="Disabled and checked" disabled checked />
    </div>
  ),
};

/** Tab to it and press space. The ring must be on the drawn box, not on an invisible input. */
export const Interactive: Story = {
  render: () => {
    const [checked, setChecked] = createSignal(false);
    return (
      <div style={{ display: "grid", gap: "8px", "justify-items": "start" }}>
        <Checkbox label="Include internal topics" checked={checked()} onChange={setChecked} />
        <p style={{ "font-size": "11px", color: "var(--kui-color-text-muted)", margin: 0 }}>
          {checked() ? "internal topics are shown" : "internal topics are hidden"}
        </p>
      </div>
    );
  },
};

/** A table's select-all: the label is hidden because the column header already names it. */
export const LabelHidden: Story = {
  render: () => (
    <div style={{ display: "flex", "align-items": "center", gap: "8px" }}>
      <Checkbox label="Select all consumer groups" labelHidden indeterminate />
      <span style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>
        (the label is present but visually hidden)
      </span>
    </div>
  ),
};

/** The extreme case: a label that wraps. The box stays aligned to the first line, not centred. */
export const LongestLabel: Story = {
  render: () => (
    <div style={{ width: "280px", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
      <Checkbox
        checked
        label="Also delete the consumer group offsets that reference this topic, which cannot be recovered once the topic is gone"
      />
    </div>
  ),
};
