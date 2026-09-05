/**
 * A row of equal segments, one per thing, each coloured by that thing's state.
 *
 * ## What it is for
 *
 * Two places in the design use this and they are the same picture: a connector's tasks
 * (SCREENS.md §2.18) and the cluster's per-broker storage (§2.7). Both are "there are N of these,
 * here is the state of each", where N is small enough to draw and the identity of the failing one
 * matters.
 *
 * ## Why it is not a `ProgressBar` and not a stacked bar
 *
 * A `ProgressBar` draws one quantity against a limit — 61% of a disk. A stacked bar draws parts of
 * a whole, sized by their share. This draws *neither*: the segments are equal, because a connector
 * with three tasks has three equal tasks, and sizing them by anything would invent a quantity that
 * does not exist. What varies is only the colour, and the count.
 *
 * That is also why "2 of 6 tasks failed" must not be drawn as a two-thirds-green bar. It looks
 * like the same information and it is not: a single bar cannot say *which* two, and which two is
 * the thing the operator is about to act on.
 *
 * ## The empty track
 *
 * A connector with no tasks — paused, or never started — draws one full-width segment in
 * `--kui-color-surface-overlay`. This is the case worth being careful about, because the obvious
 * alternatives are both wrong: drawing nothing leaves a gap that reads as a rendering fault, and
 * drawing the segments in the failure colour says something false. A neutral track says "there is
 * nothing running here", which is exactly true.
 *
 * ## Accessibility
 *
 * The bar is `aria-hidden` and the caller states the same thing in text beside it — `3/3 tasks`,
 * `842 GB of 1.25 TB · broker-3 hot`. A `role="img"` with a generated label would announce a
 * sentence nobody wrote, and it would be a second, drifting copy of a sentence that is already on
 * screen. Colour is never the only signal, and here the text below the bar is the signal.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

/**
 * The state of one segment.
 *
 * A closed union rather than a colour, because the call site should be saying what the task *is*,
 * not what colour to paint it — the mapping from state to token is this component's business and
 * it changes with the theme.
 */
export type SegmentState = "ok" | "warning" | "failed" | "idle";

export interface SegmentBarSegment {
  readonly state: SegmentState;
  /**
   * What this segment is, for the title attribute: `Task 0`, `broker-3 · 83%`. Optional, because
   * the storage meter's segments are not individually identified in the design; when it is
   * present, hovering a segment names it, which is how the operator finds *which* one is red.
   */
  readonly title?: string | undefined;
}

export interface SegmentBarProps {
  readonly segments: readonly SegmentBarSegment[];
  /** Height in pixels. The design draws 6px for tasks and 8px for the storage meter. */
  readonly height?: number | undefined;
  readonly testId?: string | undefined;
}

export function SegmentBar(props: SegmentBarProps): JSX.Element {
  const height = () => `${props.height ?? 6}px`;

  return (
    <div
      class="kui-segbar"
      style={{ "--kui-segbar-height": height() }}
      aria-hidden="true"
      data-testid={props.testId}
    >
      <Show
        when={props.segments.length > 0}
        fallback={<span class="kui-segbar__seg kui-segbar__seg--idle" title="Nothing is running" />}
      >
        <For each={props.segments}>
          {(segment) => (
            <span class={`kui-segbar__seg kui-segbar__seg--${segment.state}`} title={segment.title} />
          )}
        </For>
      </Show>
    </div>
  );
}
