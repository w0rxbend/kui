import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { SegmentBar } from "./SegmentBar.jsx";
import { ProgressBar } from "./ProgressBar.jsx";

/**
 * A row of equal segments, one per thing.
 *
 * `NoTasks` and `AgainstAProgressBar` are the two that matter. The first is the case where every
 * obvious alternative is wrong — drawing nothing looks like a rendering fault, and drawing the
 * failure colour says something false. The second shows why this is not a progress bar: "2 of 6
 * failed" as a single two-thirds-green bar looks like the same information and cannot say *which*
 * two, which is the thing the operator is about to act on.
 */
const meta: Meta<typeof SegmentBar> = {
  title: "Charts/SegmentBar",
  component: SegmentBar,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof SegmentBar>;

const Framed = (props: { readonly label: string; readonly children: unknown }) => (
  <div style={{ display: "flex", "flex-direction": "column", gap: "6px", "max-width": "320px" }}>
    {props.children as never}
    <p style={{ margin: 0, "font-size": "12px", color: "var(--kui-color-text-muted)" }}>{props.label}</p>
  </div>
);

/** The four connector cards the design draws, in the order it draws them. */
export const TheConnectorCards: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "20px" }}>
      <Framed label="3/3 tasks · 1,204 msg/s · orders.*">
        <SegmentBar segments={[{ state: "ok" }, { state: "ok" }, { state: "ok" }]} />
      </Framed>
      <Framed label="6/6 tasks · 18,220 msg/s · analytics.clickstream">
        <SegmentBar segments={Array.from({ length: 6 }, () => ({ state: "ok" as const }))} />
      </Framed>
      <Framed label="1/2 tasks · 0 msg/s · audit.log">
        <SegmentBar
          segments={[
            { state: "failed", title: "Task 0 — connection refused to es-01:9200" },
            { state: "ok", title: "Task 1" },
          ]}
        />
      </Framed>
      <Framed label="0/1 tasks · 0 msg/s · users.profile.changelog">
        <SegmentBar segments={[{ state: "idle", title: "Task 0 — paused" }]} />
      </Framed>
    </div>
  ),
};

/** Every state, on one bar, so the four colours can be judged against each other. */
export const EveryState: Story = {
  render: () => (
    <Framed label="ok · warning · failed · idle">
      <SegmentBar
        segments={[
          { state: "ok", title: "Running" },
          { state: "warning", title: "Degraded" },
          { state: "failed", title: "Failed" },
          { state: "idle", title: "Paused" },
        ]}
      />
    </Framed>
  ),
};

/**
 * No segments at all: a connector that has never started, or one that is paused.
 *
 * One neutral full-width track. Not nothing — a gap reads as a fault. Not red — that would say
 * something false about a connector that is merely off.
 */
export const NoTasks: Story = {
  render: () => (
    <Framed label="0 tasks · this connector has never been started">
      <SegmentBar segments={[]} />
    </Framed>
  ),
};

/**
 * The storage meter's use: one segment per broker, at 8px, each taking its own broker's threshold
 * colour.
 *
 * The averaged alternative is the trap. A cluster at 67% overall with one broker at 83% is a
 * cluster with a problem, and a single bar at 67% hides it.
 */
export const TheStorageMeter: Story = {
  render: () => (
    <Framed label="842 GB of 1.25 TB · broker-3 hot">
      <SegmentBar
        height={8}
        segments={[
          { state: "ok", title: "broker-1.kyiv · 61%" },
          { state: "ok", title: "broker-2.kyiv · 58%" },
          { state: "warning", title: "broker-3.kyiv · 83%" },
        ]}
      />
    </Framed>
  ),
};

/**
 * The same failure said two ways.
 *
 * Above, this component: two of six failed, and hovering says which two. Below, a progress bar at
 * the same ratio: it looks like the same information, it is not, and it cannot be acted on.
 */
export const AgainstAProgressBar: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "24px", "max-width": "320px" }}>
      <Framed label="4/6 tasks running — hover a segment to find the failures">
        <SegmentBar
          segments={[
            { state: "ok", title: "Task 0" },
            { state: "ok", title: "Task 1" },
            { state: "failed", title: "Task 2 — connection refused" },
            { state: "ok", title: "Task 3" },
            { state: "failed", title: "Task 4 — connection refused" },
            { state: "ok", title: "Task 5" },
          ]}
        />
      </Framed>
      <Framed label="67% of tasks running — which ones?">
        <ProgressBar value={4} max={6} label="Tasks running" />
      </Framed>
    </div>
  ),
};

/**
 * The extremes: one segment, and forty-eight of them.
 *
 * Forty-eight is a real number — it is a task per partition on the design's own
 * `analytics.clickstream`. The gaps must not eat the segments; if they do, the gap has to shrink
 * above some count rather than the bar becoming a dashed line.
 */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "20px", "max-width": "320px" }}>
      <Framed label="1 task">
        <SegmentBar segments={[{ state: "ok" }]} />
      </Framed>
      <Framed label="48 tasks, three of them failed">
        <SegmentBar
          segments={Array.from({ length: 48 }, (_, index) => ({
            state: index % 17 === 3 ? ("failed" as const) : ("ok" as const),
            title: `Task ${index}`,
          }))}
        />
      </Framed>
    </div>
  ),
};
