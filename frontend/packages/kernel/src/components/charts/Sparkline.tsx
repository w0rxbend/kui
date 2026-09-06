/**
 * The 24px trend mark that sits at the right of a stat card (SCREENS-V4.md §3.3).
 *
 * ## Why this one chart is deliberately not accessible on its own
 *
 * Every other member of this family publishes a `ChartDataTable` and takes a tab stop, because a
 * picture that cannot be read is a picture that says nothing to a screen-reader user. This one
 * carries `aria-hidden` and publishes nothing, and that is the correct answer rather than a gap in
 * the work: a sparkline never appears alone. It appears inside a `StatCard` that has *already*
 * printed the figure it is a picture of — `128 total`, `86.4 MB/s` — so a hidden table beside it
 * would announce the same quantity a second time, in a second voice, and a tab stop would put a
 * focusable element with nothing to say between two cards. The card is the accessible rendering;
 * this is the decoration on it.
 *
 * If a sparkline is ever wanted somewhere that does *not* print its own figure, the answer is not
 * to add a table here — it is to print the figure there, because the reader who can see the mark
 * cannot read a number off it either.
 *
 * ## The ink
 *
 * The design samples #D3E3FD, which is `--kui-color-primary-container` — but only in the dark
 * theme, where that token is the pale blue. In light it is #0b57d0, a saturated blue that would
 * turn a decorative mark into the loudest thing on the card. So the default is `neutral`
 * (`--kui-color-text-muted`), which is pale-on-dark and grey-on-light and reads as the same weight
 * in both, and a caller that genuinely wants a status ink passes a tone.
 *
 * ## The geometry
 *
 * A fixed 100x24 coordinate space stretched to whatever box the card gives it
 * (`preserveAspectRatio="none"`), rather than an observed container size. `useElementSize` is the
 * right answer for a plot with an axis, ticks and a tooltip whose corner radii must not stretch;
 * it is the wrong answer for a mark this small, where the one thing that must not scale is the
 * stroke — and `vector-effect="non-scaling-stroke"` says that in one attribute for free.
 */
import { For, Show, type Component } from "solid-js";
import { toneColor, type ChartTone } from "./tone.js";

export interface SparklineProps {
  /**
   * One value per bucket. `null` is a **gap**, not a zero, and it breaks the line exactly as it
   * does in `LineChart`: a card whose producer stopped reporting for an hour must not draw a
   * straight run across the hole and call it a trend.
   */
  readonly points: readonly (number | null)[];
  readonly width?: number | undefined;
  readonly height?: number | undefined;
  readonly tone?: ChartTone | undefined;
}

/** The design's box, and the coordinate space every path below is written in. */
const VIEW_WIDTH = 100;
const VIEW_HEIGHT = 24;
/** Half the stroke, so a value at either extreme of the domain is not clipped by the viewBox. */
const PAD = 2;

interface Point {
  readonly x: number;
  readonly y: number;
}

export const Sparkline: Component<SparklineProps> = props => {
  const finite = (): number[] =>
    props.points.filter((p): p is number => p !== null && Number.isFinite(p));

  const empty = (): boolean => finite().length === 0;

  /**
   * The domain is the data's own range, not zero-to-max.
   *
   * This is the opposite of `LineChart`'s rule, and for the opposite reason. A latency chart is
   * read for its magnitude, so it starts at zero or a 1ms wobble looks like an incident. A
   * sparkline is read *only* for its shape — the magnitude is printed in 32px type beside it — so
   * a domain of zero-to-max would flatten every well-behaved series into the same straight line
   * and the mark would carry no information at all.
   *
   * A genuinely flat series is the case that has to be handled rather than divided by: it draws
   * down the middle, which is what "nothing changed" looks like.
   */
  const domain = (): { readonly min: number; readonly max: number } => {
    const values = finite();
    const min = Math.min(...values);
    const max = Math.max(...values);
    return max > min ? { min, max } : { min: min - 1, max: max + 1 };
  };

  const xAt = (index: number): number =>
    props.points.length <= 1 ? VIEW_WIDTH / 2 : (index / (props.points.length - 1)) * VIEW_WIDTH;

  const yAt = (value: number): number => {
    const { min, max } = domain();
    const share = (value - min) / (max - min);
    return VIEW_HEIGHT - PAD - share * (VIEW_HEIGHT - PAD * 2);
  };

  /** Contiguous runs of real numbers, each drawn as its own polyline. A `null` ends a run. */
  const runs = (): Point[][] => {
    const out: Point[][] = [];
    let current: Point[] = [];
    props.points.forEach((point, index) => {
      if (point === null || !Number.isFinite(point)) {
        if (current.length > 0) out.push(current);
        current = [];
        return;
      }
      current.push({ x: xAt(index), y: yAt(point) });
    });
    if (current.length > 0) out.push(current);
    return out;
  };

  /** Runs of two or more points are lines; a run of one is a dot, because a one-point line is nothing. */
  const lines = (): Point[][] => runs().filter(run => run.length > 1);
  const dots = (): Point[] => runs().filter(run => run.length === 1).map(run => run[0] as Point);

  return (
    <Show when={!empty()}>
      <svg
        class="kui-sparkline"
        width={props.width ?? 64}
        height={props.height ?? VIEW_HEIGHT}
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <For each={lines()}>
          {run => (
            <polyline
              class="kui-sparkline__line"
              points={run.map(p => `${p.x},${p.y}`).join(" ")}
              fill="none"
              stroke={toneColor(props.tone ?? "neutral")}
              vector-effect="non-scaling-stroke"
            />
          )}
        </For>
        {/* A lone measurement in the stretched space renders as a slightly narrow ellipse rather
            than a circle. Left as it is: at the card's 64x24 box the difference is under a pixel,
            and correcting it would mean measuring the element — a ResizeObserver per stat card,
            for a mark whose whole job is to be smaller than the number beside it. */}
        <For each={dots()}>
          {dot => (
            <circle
              class="kui-sparkline__dot"
              cx={dot.x}
              cy={dot.y}
              r={2}
              fill={toneColor(props.tone ?? "neutral")}
            />
          )}
        </For>
      </svg>
    </Show>
  );
};
