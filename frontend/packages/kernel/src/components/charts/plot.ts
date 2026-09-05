/**
 * What a plotted chart is made of, and the two pieces of geometry both plots share.
 */
import type { ChartTone } from "./tone.js";

export interface Series {
  readonly label: string;
  readonly tone: ChartTone;
  /**
   * One value per category, in the same order. `null` is a **gap**, not a zero: SPEC §4.22 says
   * a series with a gap must break the line rather than interpolate across it, because
   * interpolating invents data and hides the outage that caused the gap.
   */
  readonly points: readonly (number | null)[];
}

export interface PlotProps {
  readonly series: readonly Series[];
  /** One label per bucket. Its length is the authority for how many buckets there are. */
  readonly categories: readonly string[];
  /** Indices of the categories that get a printed axis tick. Defaults to first, middle and last. */
  readonly ticks?: readonly number[];
  /** Formats a value for the tooltip, the legend and the hidden table. */
  readonly format?: (value: number) => string;
}

/** The largest value across every series, with gaps ignored. Zero when there is nothing. */
export function seriesMax(series: readonly Series[]): number {
  let max = 0;
  for (const s of series) {
    for (const point of s.points) {
      if (point !== null && Number.isFinite(point) && point > max) max = point;
    }
  }
  return max;
}

/** True when no series holds a single plottable number — the "no data in this range" case. */
export function isPlotEmpty(series: readonly Series[]): boolean {
  return !series.some(s => s.points.some(p => p !== null && Number.isFinite(p)));
}

/** First, middle and last, which is what a range axis needs when the caller says nothing. */
export function defaultTicks(count: number): number[] {
  if (count <= 0) return [];
  if (count <= 2) return count === 1 ? [0] : [0, 1];
  return [0, Math.floor((count - 1) / 2), count - 1];
}

/**
 * A rectangle with only its top corners rounded.
 *
 * The design's columns are round on top and square where they meet the baseline; an `rx` on a
 * `<rect>` rounds all four and makes short bars look like lozenges floating off the axis. The
 * radius is clamped to half the height so a one-pixel bar cannot produce a path that folds back
 * on itself.
 */
export function topRoundedRect(x: number, y: number, width: number, height: number, radius: number): string {
  const r = Math.max(0, Math.min(radius, width / 2, height));
  return [
    `M ${x} ${y + height}`,
    `L ${x} ${y + r}`,
    `Q ${x} ${y} ${x + r} ${y}`,
    `L ${x + width - r} ${y}`,
    `Q ${x + width} ${y} ${x + width} ${y + r}`,
    `L ${x + width} ${y + height}`,
    "Z",
  ].join(" ");
}
