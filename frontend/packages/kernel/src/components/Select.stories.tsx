import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { Select, type SelectOption } from "./Select.jsx";

const SEEK: readonly SelectOption<string>[] = [
  { value: "latest", label: "Latest" },
  { value: "earliest", label: "Earliest" },
  { value: "offset", label: "Offset" },
  { value: "timestamp", label: "Timestamp" },
];

const meta = {
  title: "Primitives/Select",
  component: Select,
  args: { label: "Seek", prefix: "Seek:", options: SEEK, value: "latest" },
} satisfies Meta<typeof Select<string>>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Open it with the mouse, then with Enter, then arrow through it, then press Escape. */
export const Default: Story = {
  render: () => {
    const [value, setValue] = createSignal("latest");
    return <Select label="Seek" prefix="Seek:" options={SEEK} value={value()} onChange={setValue} />;
  },
};

export const Small: Story = {
  render: () => {
    const [value, setValue] = createSignal("latest");
    return <Select size="sm" label="Seek" prefix="Seek:" options={SEEK} value={value()} onChange={setValue} />;
  },
};

/** Nothing chosen yet. A placeholder, not an em dash — waiting for a value is not having none. */
export const NoSelection: Story = {
  render: () => {
    const [value, setValue] = createSignal<string | undefined>(undefined);
    return (
      <Select
        label="Timestamp type"
        options={[
          { value: "create", label: "CreateTime" },
          { value: "append", label: "LogAppendTime" },
        ]}
        value={value()}
        placeholder="Choose a clock…"
        onChange={setValue}
      />
    );
  },
};

/** A topic with one partition still shows the control, disabled, with the reason. */
export const DisabledWithReason: Story = {
  render: () => (
    <Select
      label="Partitions"
      prefix="Partitions:"
      options={[{ value: "1", label: "1" }]}
      value="1"
      disabled
      disabledReason="This topic has one partition, so there is nothing to choose."
    />
  ),
};

/** Some options are not selectable. The keyboard steps over them rather than sticking on them. */
export const WithDisabledOptions: Story = {
  render: () => {
    const [value, setValue] = createSignal("24h");
    return (
      <Select
        label="Range"
        options={[
          { value: "24h", label: "Last 24 hours" },
          { value: "7d", label: "Last 7 days" },
          { value: "30d", label: "Last 30 days", disabled: true },
          { value: "90d", label: "Last 90 days", disabled: true },
        ]}
        value={value()}
        onChange={setValue}
      />
    );
  },
};

/** The empty case: a menu that says so, rather than a menu that is a blank rectangle. */
export const NoOptions: Story = {
  render: () => (
    <Select
      label="Partitions"
      prefix="Partitions:"
      options={[]}
      emptyMessage="No partitions were returned for this topic."
    />
  ),
};

/**
 * The extreme case: 128 topics, which is what the screenshotted cluster has. The list scrolls
 * inside itself, type-ahead is the only usable way to navigate it, and the trigger must not grow
 * to the width of the longest name.
 */
export const ManyLongOptions: Story = {
  render: () => {
    const options: SelectOption<string>[] = Array.from({ length: 128 }, (_, i) => ({
      value: `t${i}`,
      label:
        i % 7 === 0
          ? `orders.payments.v2.reprocessing.deadletter.eu-central-1.shard-${i}`
          : `topic-${i}`,
    }));
    const [value, setValue] = createSignal("t0");
    return (
      <div style={{ width: "240px", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
        <Select label="Topic" options={options} value={value()} onChange={setValue} />
      </div>
    );
  },
};

/** Two selects side by side, as the message filter bar draws them. */
export const FilterBarPair: Story = {
  render: () => {
    const [seek, setSeek] = createSignal("latest");
    const [parts, setParts] = createSignal("all");
    return (
      <div style={{ display: "flex", gap: "12px" }}>
        <Select label="Seek" labelHidden prefix="Seek:" options={SEEK} value={seek()} onChange={setSeek} />
        <Select
          label="Partitions"
          labelHidden
          prefix="Partitions:"
          options={[
            { value: "all", label: "all 12" },
            ...Array.from({ length: 12 }, (_, i) => ({ value: String(i), label: `p ${i}` })),
          ]}
          value={parts()}
          onChange={setParts}
        />
      </div>
    );
  },
};
