import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { TextField } from "./TextField.jsx";

const meta = {
  title: "Primitives/TextField",
  component: TextField,
  args: { label: "Filter", placeholder: "Filter by key or value…" },
} satisfies Meta<typeof TextField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** The top bar's search field: a glyph inside the control, a shortcut badge at the trailing edge. */
export const SearchField: Story = {
  render: () => (
    <div style={{ width: "324px" }}>
      <TextField
        label="Search"
        labelHidden
        type="search"
        icon="search"
        hintKey="⌘K"
        placeholder="Search topics, groups, anything…"
        help="Press Command K from anywhere to focus this field."
      />
    </div>
  ),
};

/** On a machine that is not a Mac the hint must not show a Mac glyph. */
export const SearchFieldOnLinux: Story = {
  render: () => (
    <div style={{ width: "324px" }}>
      <TextField label="Search" labelHidden type="search" icon="search" hintKey="Ctrl K" placeholder="Search topics, groups, anything…" />
    </div>
  ),
};

export const WithValue: Story = { args: { value: "orders.payments" } };

export const WithHelp: Story = {
  args: { label: "Partition count", help: "Partitions cannot be removed once added." },
};

/**
 * Invalid. The border is red *and* there is a sentence: the colour is never the only signal, and
 * the sentence is joined to the input with `aria-describedby` so it is heard, not just seen.
 */
export const Invalid: Story = {
  args: {
    label: "Partition count",
    value: "0",
    error: "A topic needs at least one partition.",
  },
};

export const InvalidWithHelp: Story = {
  args: {
    label: "Partition count",
    value: "0",
    help: "Partitions cannot be removed once added.",
    error: "A topic needs at least one partition.",
  },
};

export const Disabled: Story = { args: { value: "orders.payments.v2", disabled: true } };

export const ReadOnly: Story = { args: { value: "orders.payments.v2", readOnly: true } };

/** Offsets and keys are compared character by character, so they get the mono face. */
export const Monospaced: Story = {
  args: { label: "Offset", mono: true, value: "18442901", placeholder: "18,442,901" },
};

export const Small: Story = { args: { size: "sm", value: "payments" } };

/** Empty and unlabelled-looking: the label is present but visually hidden. */
export const LabelHidden: Story = {
  args: { label: "Filter records", labelHidden: true, placeholder: "Filter by key or value…" },
};

/**
 * The extreme case. A Kafka topic name is up to 249 characters; a record key can be far longer.
 * The field must scroll its own text rather than growing, and the box must not push the layout.
 */
export const LongestValue: Story = {
  render: () => (
    <div style={{ width: "260px", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
      <TextField
        label="Record key"
        mono
        value={"ord_" + "9f21ac".repeat(40)}
        help="249 characters is the maximum a topic name may be; keys have no such limit."
      />
    </div>
  ),
};

/** The longest error message, which is where a validation panel usually breaks. */
export const LongestError: Story = {
  render: () => (
    <div style={{ width: "260px" }}>
      <TextField
        label="Topic name"
        value="orders payments v2"
        error="A topic name may contain only letters, digits, dots, underscores and hyphens, may not be `.` or `..`, and may be at most 249 characters long."
      />
    </div>
  ),
};

/** Typing works and the element is never rebuilt: the caret must stay where you put it. */
export const Controlled: Story = {
  render: () => {
    const [value, setValue] = createSignal("");
    return (
      <div style={{ width: "320px", display: "grid", gap: "8px" }}>
        <TextField label="Filter" value={value()} onInput={(v) => setValue(v)} placeholder="Type here…" />
        <p style={{ "font-size": "11px", color: "var(--kui-color-text-muted)", margin: 0 }}>
          {value() === "" ? "nothing typed yet" : `${value().length} characters`}
        </p>
      </div>
    );
  },
};
