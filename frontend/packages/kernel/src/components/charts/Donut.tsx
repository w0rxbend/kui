/**
 * The partition-health ring of SPEC §4.23.
 *
 * ## Why the arcs are `stroke-dasharray` on one circle each
 *
 * A ring segment is an arc of constant width, which is exactly what a stroked circle is. Setting
 * `pathLength="100"` re-scales the dash units so that a segment's length is literally its
 * percentage — no `2πr` arithmetic anywhere, and therefore no place for a radius change to
 * silently break the geometry.
 *
 * ## Why the centre figure is HTML and not `<svg:text>`
 *
 * The SVG carries `aria-hidden`, because everything it draws is also printed in the legend beside
 * it, and a screen reader announcing both reads the same figures twice. If the centre percentage
 * lived inside the SVG it would be hidden with it — the one number the design puts in the largest
 * type in the panel would be unreachable. So the ring is the picture, and the figure is HTML
 * stacked over it: real text, real tokens, selectable, announced.
 *
 * ## The lie of omission this component must not tell
 *
 * A donut with no data must never draw a full green ring. Missing data and perfect health look
 * identical that way, and the first time an operator finds out which one they were looking at is
 * the incident review. All-zero draws a plain `--kui-color-surface-overlay` ring with an em dash
 * in the middle; unknown does not draw a ring at all and the caller shows the unavailable state.
 */
import { For, Show, type Component } from "solid-js";
import { ABSENT, formatCount, formatPercent } from "./format.js";
import { toneColor, type ChartTone } from "./tone.js";
import { ChartLegend, type LegendItem } from "./ChartLegend.jsx";

export interface DonutSegment {
  readonly label: string;
  readonly value: number;
  readonly tone: ChartTone;
}

export interface DonutProps {
  readonly segments: readonly DonutSegment[];
  /** The small caption under the centre figure. Uppercased by the stylesheet. */
  readonly centreCaption?: string;
  /**
   * The share the centre figure reports, in per cent. Defaults to the first segment's share,
   * which is the design's reading: the ring is about how much of the whole is healthy.
   */
  readonly healthyPercent?: number;
  readonly diameter?: number;
  readonly strokeWidth?: number;
  /** Below these the centre figure turns amber, then red (SPEC §4.23). */
  readonly warnBelow?: number;
  readonly criticalBelow?: number;
}

/** A segment smaller than this would draw as nothing; the legend stays the authority for the number. */
const MIN_ARC = 1.5;

export const Donut: Component<DonutProps> = props => {
  // 84, not the design's 72.
  //
  // The design draws a 72px ring with an 18px figure in it. 18px is not a step on this product's
  // type scale (11, 12, 14, 16, 20, 24, 32), and at the nearest step that fits — 16px — a
  // five-character percentage like `99.1%` is wider than a 72px ring's 56px hole and collides
  // with the stroke. Rather than invent a one-off font size, the ring grows: the figure stays on
  // the scale and `99.1%` sits clear of the arc. Raised as a finding — either the type ramp gains
  // an 18px step, or the ring is 84.
  const diameter = (): number => props.diameter ?? 84;
  const stroke = (): number => props.strokeWidth ?? 8;
  const radius = (): number => (diameter() - stroke()) / 2;

  const total = (): number => props.segments.reduce((sum, s) => sum + (Number.isFinite(s.value) ? s.value : 0), 0);
  const empty = (): boolean => total() <= 0;

  /** Each segment's arc length in `pathLength=100` units, plus where it starts. */
  const arcs = (): readonly { readonly segment: DonutSegment; readonly length: number; readonly offset: number }[] => {
    const sum = total();
    if (sum <= 0) return [];
    let cursor = 0;
    return props.segments.map(segment => {
      const share = (segment.value / sum) * 100;
      const length = segment.value > 0 ? Math.max(MIN_ARC, share) : 0;
      const offset = cursor;
      cursor += share;
      return { segment, length, offset };
    });
  };

  const healthy = (): number | undefined => {
    if (props.healthyPercent !== undefined) return props.healthyPercent;
    if (empty()) return undefined;
    const first = props.segments[0];
    return first === undefined ? undefined : (first.value / total()) * 100;
  };

  const centreLevel = (): string => {
    const h = healthy();
    if (h === undefined) return "absent";
    if (h < (props.criticalBelow ?? 95)) return "critical";
    if (h < (props.warnBelow ?? 99)) return "warning";
    return "normal";
  };

  const legendItems = (): LegendItem[] =>
    props.segments.map(s => ({ label: s.label, tone: s.tone, value: formatCount(s.value) }));

  return (
    <div class="kui-donut">
      <div class="kui-donut__ring" style={{ width: `${diameter()}px`, height: `${diameter()}px` }}>
        <svg
          width={diameter()}
          height={diameter()}
          viewBox={`0 0 ${diameter()} ${diameter()}`}
          aria-hidden="true"
          class="kui-donut__svg"
        >
          {/* The track. In the all-zero case it is the only thing drawn, which is what stops
              "no data" from looking like "everything is fine". */}
          <circle
            cx={diameter() / 2}
            cy={diameter() / 2}
            r={radius()}
            fill="none"
            stroke="var(--kui-color-surface-overlay)"
            stroke-width={stroke()}
          />
          <Show when={!empty()}>
            {/* Zero-length arcs are dropped rather than rendered with a zero dash. `stroke-linecap`
                is `round`, and a round cap on a zero-length dash still paints a dot the width of
                the stroke — so a segment with a value of 0 drew a visible mark on the ring, in its
                own colour, indistinguishable from a small non-zero segment. On the partition donut
                that meant a healthy cluster appeared to have offline partitions. */}
            <For each={arcs().filter(arc => arc.length > 0)}>
              {arc => (
                <circle
                  cx={diameter() / 2}
                  cy={diameter() / 2}
                  r={radius()}
                  fill="none"
                  stroke={toneColor(arc.segment.tone)}
                  stroke-width={stroke()}
                  stroke-linecap="round"
                  pathLength="100"
                  stroke-dasharray={`${arc.length} ${100 - arc.length}`}
                  stroke-dashoffset={-arc.offset}
                  // Start at twelve o'clock rather than three, which is where the design starts.
                  transform={`rotate(-90 ${diameter() / 2} ${diameter() / 2})`}
                />
              )}
            </For>
          </Show>
        </svg>

        <div class="kui-donut__centre">
          <span class={["kui-donut__figure", `kui-donut__figure--${centreLevel()}`]}>
            {empty() ? ABSENT : formatPercent(healthy(), 1)}
          </span>
          <Show when={props.centreCaption}>
            <span class="kui-donut__caption">{empty() ? "no partitions" : props.centreCaption}</span>
          </Show>
        </div>
      </div>

      {/* The legend is not decoration here: it is the accessible rendering of the whole chart,
          which is why it prints every label and every count as ordinary text. */}
      <ChartLegend items={legendItems()} variant="block" />
    </div>
  );
};
