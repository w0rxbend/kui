import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { IconTile } from "./IconTile.jsx";
import { iconNames } from "../icon.jsx";

const meta = {
  title: "Primitives/IconTile",
  component: IconTile,
  args: { icon: "brokers", tone: "success" },
} satisfies Meta<typeof IconTile>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Success: Story = {};
export const Primary: Story = { args: { icon: "topics", tone: "primary" } };
export const Accent: Story = { args: { icon: "messages", tone: "accent" } };
export const Warning: Story = { args: { icon: "lag", tone: "warning" } };
export const Danger: Story = { args: { icon: "warning", tone: "danger" } };
export const Neutral: Story = { args: { icon: "info", tone: "neutral" } };

/** The dashboard's four, in the pairings the screenshots show. */
export const DashboardSet: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "16px" }}>
      <IconTile icon="brokers" tone="success" />
      <IconTile icon="topics" tone="primary" />
      <IconTile icon="messages" tone="accent" />
      <IconTile icon="lag" tone="warning" />
    </div>
  ),
};

export const Sizes: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "16px", "align-items": "center" }}>
      <IconTile icon="shield" tone="success" size="sm" />
      <IconTile icon="shield" tone="success" size="md" />
    </div>
  ),
};

/** Every tone against every size, to catch a pairing whose glyph disappears into its fill. */
export const Matrix: Story = {
  render: () => {
    const tones = ["primary", "accent", "success", "warning", "danger", "neutral"] as const;
    return (
      <div style={{ display: "grid", "grid-template-columns": "repeat(6, auto)", gap: "16px" }}>
        {tones.map((tone) => <IconTile icon="shield" tone={tone} />)}
        {tones.map((tone) => <IconTile icon="shield" tone={tone} size="sm" />)}
      </div>
    );
  },
};

/** The whole set, so a glyph that draws badly at 18px is found here rather than in a screen. */
export const EveryGlyph: Story = {
  render: () => (
    <div style={{ display: "grid", "grid-template-columns": "repeat(8, auto)", gap: "12px" }}>
      {iconNames.map((name) => (
        <div style={{ display: "grid", "justify-items": "center", gap: "4px" }}>
          <IconTile icon={name} tone="neutral" />
          <span style={{ "font-size": "9px", color: "var(--kui-color-text-subtle)" }}>{name}</span>
        </div>
      ))}
    </div>
  ),
};
