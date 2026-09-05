import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Breadcrumbs } from "./Breadcrumbs.jsx";
import { MagnitudeBar } from "./MagnitudeBar.jsx";
import { Tag } from "./Tag.jsx";
import { ThresholdValue } from "./ThresholdValue.jsx";

/**
 * The tag, and the three small things that sit beside it in a list: the trail above a page, the
 * proportional bar in a size column, and the figure that colours only once it is over a limit.
 *
 * They are shown together on purpose. The threshold's whole argument is that the coloured cell
 * should be the only coloured thing on the screen, and that claim is only checkable next to the
 * quiet ones.
 */
/* No `component` on the meta. Every story here renders a composition — a strip wired to its own
 * selection, a row of tones — rather than one instance driven by `args`, and naming a component
 * would make Storybook's types demand a full set of props that no story below uses. */
const meta = {
  title: "Kernel/Tag",
  parameters: { layout: "padded" },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** The five tones. Each one carries words; none of them depends on the reader separating the hues. */
export const Tones: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap", "align-items": "center" }}>
      <Tag>compact</Tag>
      <Tag tone="info">delete</Tag>
      <Tag tone="success" dot>
        Stable
      </Tag>
      <Tag tone="warning" dot>
        Rebalancing
      </Tag>
      <Tag tone="danger" dot>
        Dead
      </Tag>
    </div>
  ),
};

/** Applied filters. Each one can be taken off, and the button says which one it takes off. */
export const Removable: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
      <Tag tone="info" onRemove={() => {}}>
        partition = 3
      </Tag>
      <Tag tone="info" onRemove={() => {}}>
        key contains "order-"
      </Tag>
    </div>
  ),
};

/** Where you are, and how to get back. */
export const Trail: Story = {
  render: () => (
    <Breadcrumbs
      crumbs={[
        { label: "Clusters", href: "#" },
        { label: "production-eu", href: "#" },
        { label: "Topics", href: "#" },
        { label: "orders.payments.v2" },
      ]}
    />
  ),
};

/** A size column. The bars answer "which is the big one" before a digit is read. */
export const Magnitudes: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "12px", width: "320px" }}>
      <MagnitudeBar label="orders" value="112.9 GB" fraction={1} />
      <MagnitudeBar label="payments" value="48.2 GB" fraction={0.43} />
      <MagnitudeBar label="audit" value="6.1 GB" fraction={0.054} accent />
      <MagnitudeBar value="812 MB" fraction={0.007} inline />
    </div>
  ),
};

/** Four out-of-sync counts, one of which matters. That is the entire argument for the component. */
export const Thresholds: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "8px", "justify-items": "start" }}>
      <ThresholdValue value="0" level="normal" />
      <ThresholdValue value="0" level="normal" />
      <ThresholdValue value="2" level="warning" />
      <ThresholdValue value="11" level="critical" />
    </div>
  ),
};
