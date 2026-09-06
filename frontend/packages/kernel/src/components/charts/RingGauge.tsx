/**
 * One scalar as a ring, with the figure in the centre and an uppercase caption beneath
 * (SCREENS-V4.md §3.4 — the three **Request handlers** sub-tiles, and two of the stat cards).
 *
 * ## How this differs from `Donut`, which is also a ring
 *
 * `Donut` is a **parts-of-a-whole** ring: it is handed a set of segments, it sizes each arc by its
 * share of their sum, and its centre figure defaults to the *first* segment's share, because the
 * question it answers is "how much of the whole is healthy". Give it one number and it has no
 * whole to be part of.
 *
 * This is a **single scalar against an explicit domain**: 71% of network-handler capacity idle,
 * over a domain the caller states. There is no second segment; the track is not another category,
 * it is the rest of the domain.
 *
 * ## Why `goodDirection` exists, and why `Donut`'s thresholds could not be reused
 *
 * `Donut` has `warnBelow`/`criticalBelow`, which bakes in "higher is better". The design's own
 * card breaks that in one row: **64% IO idle** is good and **38% purgatory** is bad, and both are
 * percentages in the same panel with the same ramp. A component that only knows how to worry about
 * small numbers would paint the purgatory ring green, which is the exact shape of lie this family
 * exists to refuse.
 *
 * So the direction is stated, and only the pair of thresholds belonging to that direction is read.
 * The unused pair is ignored rather than merged, because a gauge that could turn amber at both
 * ends of its domain is a gauge whose colour means nothing.
 *
 * ## The unmeasured ring
 *
 * `undefined` draws the plain track and an em dash — never a full ring, and never an empty one
 * that reads as a measured zero. This is `Donut`'s all-zero rule applied to a scalar: "we could
 * not measure the request handlers" and "the request handlers are perfectly idle" are opposite
 * statements and must not be the same picture.
 *
 * ## The gauge names itself
 *
 * The ring is `aria-hidden` and the caption and figure stacked over it are real text, so the
 * obvious arrangement is to let those two be read in the flow. They cannot be: `role="img"` makes
 * this element's subtree presentational, and without a role at all the two spans arrive as loose
 * text — `71%` on one line and `NETWORK IDLE` on the next, in a panel with two more gauges beside
 * it, so which figure belongs to which caption is a matter of guessing at the reading order. So
 * the root is one image with one composed name, and the em dash never reaches a screen reader,
 * because `—` is announced as "dash" or as nothing at all depending on the reader — a rendering
 * of "not measured" that means neither.
 */
import { Show, type Component } from "solid-js";
import { ABSENT, DEFAULT_THRESHOLDS, formatPercent, fraction, type MaybeNumber } from "./format.js";

/**
 * The tone the ring and the figure carry. It is published as a `data-tone` attribute on the root
 * so that a caller — or a test — can read the *judgement* the gauge made rather than re-deriving
 * it from a fill colour, which is the one thing about a chart that a theme is allowed to change.
 */
export type GaugeTone = "success" | "warning" | "danger" | "absent";

export interface RingGaugeProps {
  /** `undefined` is "not measured", and is drawn differently from every number including zero. */
  readonly value: MaybeNumber;
  /**
   * The domain the value is a point in. Explicit rather than assumed 0..100, because the two
   * stat-card gauges are a percentage and the handler tiles are a percentage but the next caller
   * will not be — and a gauge that silently treats `4212` as "over 100%, so full" is worse than
   * one that made the caller say `max`.
   */
  readonly min?: number | undefined;
  readonly max?: number | undefined;
  /** Small, uppercased by the stylesheet: `NETWORK IDLE`. It also opens the gauge's accessible
   * name — see the note on that at the head of this file. */
  readonly caption?: string | undefined;
  /**
   * Which end of the domain is the good end. There is no default: every caller knows the answer
   * and a wrong guess here paints an incident green.
   */
  readonly goodDirection: "high" | "low";
  /** Read only when `goodDirection` is `"low"`. */
  readonly warnAbove?: number | undefined;
  readonly criticalAbove?: number | undefined;
  /** Read only when `goodDirection` is `"high"`. */
  readonly warnBelow?: number | undefined;
  readonly criticalBelow?: number | undefined;
  /** Overrides the centre figure when the caller formats it differently (`4,212`, `1.2 GB`). */
  readonly valueText?: string | undefined;
  readonly decimals?: number | undefined;
  readonly diameter?: number | undefined;
  readonly strokeWidth?: number | undefined;
}

/**
 * A full circle as a path rather than a `<circle>`, so the arc and the track are different
 * elements in every sense: the track is always drawn and the arc exists only when there is
 * something to draw. Counting `<path>` elements is then the honest test for "did this gauge claim
 * a measurement", which a dash-array on a shared circle would not be.
 *
 * Drawn from twelve o'clock, clockwise, which is where the design starts and where a reader
 * expects a gauge to start.
 */
function ringPath(cx: number, cy: number, r: number): string {
  return `M ${cx} ${cy - r} A ${r} ${r} 0 1 1 ${cx} ${cy + r} A ${r} ${r} 0 1 1 ${cx} ${cy - r}`;
}

export const RingGauge: Component<RingGaugeProps> = props => {
  const diameter = (): number => props.diameter ?? 72;
  const stroke = (): number => props.strokeWidth ?? 7;
  const radius = (): number => (diameter() - stroke()) / 2;

  const min = (): number => props.min ?? 0;
  const max = (): number => props.max ?? 100;
  const known = (): boolean => props.value !== undefined && Number.isFinite(props.value);

  /** Where the value sits in its domain, 0..1, with the denominator guarded as ever. */
  const share = (): number => {
    if (!known()) return 0;
    return fraction((props.value as number) - min(), max() - min());
  };

  /** The domain expressed as a percentage, which is what both the arc and the thresholds read. */
  const percent = (): number => share() * 100;

  /**
   * The defaults mirror the product's single pair of thresholds (`DEFAULT_THRESHOLDS`, 75 and 90),
   * which are written for a quantity where *low* is good — a disk. For a gauge where high is good
   * the mirror image is the honest reading of the same policy: worry at 25, alarm at 10.
   */
  const tone = (): GaugeTone => {
    if (!known()) return "absent";
    const p = percent();
    if (props.goodDirection === "low") {
      if (p >= (props.criticalAbove ?? DEFAULT_THRESHOLDS.critical)) return "danger";
      if (p >= (props.warnAbove ?? DEFAULT_THRESHOLDS.warn)) return "warning";
      return "success";
    }
    if (p <= (props.criticalBelow ?? 100 - DEFAULT_THRESHOLDS.critical)) return "danger";
    if (p <= (props.warnBelow ?? 100 - DEFAULT_THRESHOLDS.warn)) return "warning";
    return "success";
  };

  const figure = (): string =>
    props.valueText ?? formatPercent(known() ? percent() : undefined, props.decimals ?? 0);

  /**
   * The accessible name: what this gauge is, then what it reads.
   *
   * Spelled out rather than left to the em dash, because the unmeasured case is the one this
   * component exists for and "not measured" is the sentence it is supposed to be making. A gauge
   * with no caption still names its reading — one number is a poorer name than two facts, but it
   * is not nothing, and the caller who omitted the caption has printed the subject elsewhere.
   */
  const label = (): string => {
    const reading = known() ? figure() : "not measured";
    return props.caption === undefined ? reading : `${props.caption}: ${reading}`;
  };

  return (
    <div class="kui-gauge" data-tone={tone()} role="img" aria-label={label()}>
      <div class="kui-gauge__ring" style={{ width: `${diameter()}px`, height: `${diameter()}px` }}>
        {/* Kept `aria-hidden` even though `role="img"` above already makes the subtree
            presentational, for the same reason `Donut`'s is: the two facts are drawn twice, once
            as a picture and once as text, and neither drawing may be announced beside the name. */}
        <svg
          class="kui-gauge__svg"
          width={diameter()}
          height={diameter()}
          viewBox={`0 0 ${diameter()} ${diameter()}`}
          aria-hidden="true"
        >
          <circle
            class="kui-gauge__track"
            cx={diameter() / 2}
            cy={diameter() / 2}
            r={radius()}
            fill="none"
            stroke="var(--kui-color-surface-overlay)"
            stroke-width={stroke()}
          />
          {/* No arc at all when the value is unknown, and none for a share of zero: a round
              line cap on a zero-length dash still paints a dot, which is the defect `Donut`
              already records. */}
          <Show when={known() && share() > 0}>
            <path
              class="kui-gauge__arc"
              d={ringPath(diameter() / 2, diameter() / 2, radius())}
              fill="none"
              stroke-width={stroke()}
              stroke-linecap="round"
              // `pathLength` re-scales the dash units so a segment's length is literally its
              // percentage. No 2*pi*r anywhere, so a change of radius cannot break the geometry.
              pathLength="100"
              stroke-dasharray={`${percent()} ${100 - percent()}`}
            />
          </Show>
        </svg>

        <div class="kui-gauge__centre">
          <span class="kui-gauge__figure">{known() ? figure() : ABSENT}</span>
          <Show when={props.caption}>
            <span class="kui-gauge__caption">{props.caption}</span>
          </Show>
        </div>
      </div>
    </div>
  );
};
