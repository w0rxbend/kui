import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { StatusPill } from "./StatusPill.jsx";

const meta = {
  title: "Primitives/StatusPill",
  component: StatusPill,
  args: { children: "all in sync", tone: "success" },
} satisfies Meta<typeof StatusPill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Success: Story = { args: { tone: "success", icon: "check", children: "all in sync" } };
export const Warning: Story = { args: { tone: "warning", children: "fashionably late" } };
export const Danger: Story = { args: { tone: "danger", children: "2 offline" } };
export const Accent: Story = { args: { tone: "accent", icon: "arrow-up-right", children: "12% vs last hour" } };
export const Neutral: Story = { args: { tone: "neutral", children: "1,536 partitions" } };

/** Every tone at once. The words carry the meaning; the colour only repeats it. */
export const AllTones: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
      <StatusPill tone="success" icon="check">all in sync</StatusPill>
      <StatusPill tone="warning">fashionably late</StatusPill>
      <StatusPill tone="danger">2 offline</StatusPill>
      <StatusPill tone="accent" icon="arrow-up-right">12% vs last hour</StatusPill>
      <StatusPill tone="neutral">1,536 partitions</StatusPill>
    </div>
  ),
};

/** The consumer table's state chips, and the mapping SPEC §4.17 fixes once for everybody. */
export const GroupStates: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
      <StatusPill tone="success">Stable</StatusPill>
      <StatusPill tone="warning">Rebalancing</StatusPill>
      <StatusPill tone="neutral">Empty</StatusPill>
      <StatusPill tone="danger">Dead</StatusPill>
      <StatusPill tone="neutral" title="Kafka reported this group's state as Unknown.">Unknown</StatusPill>
      <StatusPill tone="neutral" title="The group's state could not be read.">—</StatusPill>
    </div>
  ),
};

export const WithDot: Story = { args: { tone: "success", dot: true, children: "LIVE" } };

/** A live tail. The dot breathes; under `prefers-reduced-motion` it stops and keeps its shape. */
export const Pulsing: Story = { args: { tone: "success", dot: true, pulsing: true, children: "LIVE" } };

/** LIVE is a toggle, not a label, so it is a button — pressable, focusable, and it says so. */
export const Toggle: Story = {
  render: () => {
    const [live, setLive] = createSignal(true);
    return (
      <StatusPill
        tone={live() ? "success" : "neutral"}
        dot
        pulsing={live()}
        pressed={live()}
        onClick={() => setLive((v) => !v)}
      >
        {live() ? "LIVE" : "PAUSED"}
      </StatusPill>
    );
  },
};

export const ToggleDisabled: Story = {
  render: () => (
    <StatusPill tone="neutral" dot disabled onClick={() => {}} title="Live tailing is unavailable while the message service is down.">
      LIVE unavailable
    </StatusPill>
  ),
};

/**
 * The empty case. A pill with no text renders **nothing at all** rather than an empty stadium,
 * because an empty stadium is a shape with no meaning and reads as a rendering bug.
 */
export const EmptyRendersNothing: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "8px", "align-items": "center" }}>
      <span style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>before</span>
      <StatusPill tone="success">{""}</StatusPill>
      <span style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>after</span>
    </div>
  ),
};

/** The extreme case: a pill given a sentence. It ellipsises rather than pushing its row wider. */
export const LongestLabel: Story = {
  render: () => (
    <div style={{ width: "220px", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
      <StatusPill tone="warning">
        47 partitions are under-replicated across 3 brokers
      </StatusPill>
    </div>
  ),
};

/** The largest number, with separators. Never narrated, always formatted. */
export const LargestNumber: Story = {
  args: { tone: "neutral", children: "9,223,372,036,854,775,807 partitions" },
};
