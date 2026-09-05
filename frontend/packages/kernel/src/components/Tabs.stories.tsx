import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { Tabs } from "./Tabs.jsx";

/**
 * The tab strip, shown wired to real state, because every interesting thing about it — the roving
 * tabindex, the arrow keys, the accent under the selected tab — only exists once something owns
 * the selection.
 */
/* No `component` on the meta. Every story here renders a composition — a strip wired to its own
 * selection, a row of tones — rather than one instance driven by `args`, and naming a component
 * would make Storybook's types demand a full set of props that no story below uses. */
const meta = {
  title: "Kernel/Tabs",
  parameters: { layout: "padded" },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function Wired(props: { readonly tabs: readonly { id: string; label: string }[] }) {
  const [selected, setSelected] = createSignal(props.tabs[0]?.id ?? "");
  const tabs = props.tabs.map((tab) => ({
    ...tab,
    body: () => <p>The {tab.label.toLowerCase()} panel. Built only once this tab is opened.</p>,
  }));
  return <Tabs tabs={tabs} selected={selected()} onSelect={setSelected} label="Topic" />;
}

/** The topic page's own strip. */
export const Topic: Story = {
  render: () => (
    <Wired
      tabs={[
        { id: "overview", label: "Overview" },
        { id: "messages", label: "Messages" },
        { id: "consumers", label: "Consumers" },
        { id: "settings", label: "Settings" },
      ]}
    />
  ),
};

/** Enough tabs to overflow a narrow window. The strip scrolls sideways rather than wrapping onto a
 * second row, which would move the panel down the page as the window is resized. */
export const Overflowing: Story = {
  render: () => (
    <div style={{ width: "360px" }}>
      <Wired
        tabs={[
          { id: "overview", label: "Overview" },
          { id: "messages", label: "Messages" },
          { id: "consumers", label: "Consumers" },
          { id: "partitions", label: "Partitions" },
          { id: "settings", label: "Settings" },
          { id: "acls", label: "Access control" },
          { id: "schema", label: "Schema" },
        ]}
      />
    </div>
  ),
};
