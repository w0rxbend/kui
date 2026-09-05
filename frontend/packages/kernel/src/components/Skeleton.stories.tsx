import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Missing, Skeleton } from "./EmptyState.jsx";

/**
 * The three pictures a value can have when it is not simply there, side by side. This is the
 * whole point of the story: they are three, they must stay three, and the only way to know they
 * are distinguishable is to look at them next to each other.
 *
 *   - **pending** — a block the size of the value. Something is coming.
 *   - **absent** — an em dash. The field is genuinely empty: a null key, a group with no
 *     coordinator.
 *   - **stale** — the last known value, dimmed, under a badge (see `Surfaces/StaleBadge`).
 *
 * Collapse any two of them and an outage is reported as a healthy cluster.
 */
const meta: Meta<typeof Skeleton> = {
  title: "Surfaces/Skeleton",
  component: Skeleton,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Skeleton>;

const Cell = (props: { readonly label: string; readonly children: unknown }) => (
  <div style={{ display: "flex", "flex-direction": "column", gap: "6px" }}>
    <span style={{ "font-size": "11px", "font-weight": 600, "letter-spacing": "0.06em", color: "var(--kui-color-text-muted)" }}>
      {props.label}
    </span>
    <span style={{ "font-size": "20px" }}>{props.children as never}</span>
  </div>
);

export const PendingAbsentAndPresent: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "48px", "align-items": "flex-start" }}>
      <Cell label="PENDING">
        <Skeleton width="120px" height="24px" />
      </Cell>
      <Cell label="ABSENT">
        <Missing />
      </Cell>
      <Cell label="PRESENT">4,212</Cell>
      <Cell label="STALE">
        <span style={{ opacity: "var(--kui-opacity-stale)" }}>4,212</span>
      </Cell>
    </div>
  ),
};

/** At the sizes it actually stands in for: a heading, a line of prose, a figure, a table row. */
export const AtEverySize: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "16px", "max-width": "420px" }}>
      <Skeleton width="240px" height="24px" />
      <Skeleton width="100%" />
      <Skeleton width="80%" />
      <Skeleton width="88px" height="32px" />
      <div style={{ display: "flex", gap: "12px" }}>
        <Skeleton width="30%" height="14px" />
        <Skeleton width="20%" height="14px" />
        <Skeleton width="15%" height="14px" />
      </div>
    </div>
  ),
};

/**
 * A whole table's worth, at the real row height, so that the table does not resize when the data
 * lands. Six rows is the number the product uses.
 */
export const ATableWaiting: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "1px", width: "480px" }}>
      {Array.from({ length: 6 }, () => (
        <div style={{ display: "flex", gap: "16px", "align-items": "center", height: "48px" }}>
          <Skeleton width="35%" height="14px" />
          <Skeleton width="72px" height="18px" />
          <Skeleton width="15%" height="14px" />
          <Skeleton width="20%" height="14px" />
        </div>
      ))}
    </div>
  ),
};

/**
 * The reduced-motion case. Turn the operating system's "reduce motion" setting on and reload: the
 * shimmer is **suppressed**, not slowed — a slow shimmer is still a shimmer — and the block keeps
 * its fill, so "something is coming" is still drawn.
 */
export const ReducedMotion: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "12px", "max-width": "420px" }}>
      <p style={{ color: "var(--kui-color-text-muted)" }}>
        With reduce-motion on, these must be flat blocks with no moving highlight.
      </p>
      <Skeleton width="100%" />
      <Skeleton width="60%" />
    </div>
  ),
};
