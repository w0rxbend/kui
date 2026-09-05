/**
 * A figure that takes a colour only once it has crossed a limit.
 *
 * ## Why this is a component and not a conditional class on each screen
 *
 * The design's rule is that a threshold column is *uncoloured* almost all of the time. Out-of-sync
 * replica counts are zero on a healthy cluster and consumer lag is near zero on a healthy consumer,
 * so a column drawn permanently in the warning colour is a column the operator learns to ignore
 * within a week. Colouring only the exception makes the exception the single coloured thing on a
 * screen of two hundred rows, which is the entire point.
 *
 * That rule is easy to state and easy to lose. Written into each screen separately, the third
 * screen gets it slightly wrong — colours the healthy case grey-but-not-quite, or drops the second
 * cue — and nobody notices, because each screen looks fine on its own.
 *
 * ## Colour is never the only signal
 *
 * A crossed threshold also grows a warning mark and a heavier weight, and a screen reader hears a
 * word. `Icon` renders everything `aria-hidden`, so without the visually hidden sentence below a
 * screen reader would hear the number and nothing about it being over a limit.
 *
 * ## What it does not do
 *
 * It knows no limits and it does not format the number. `thresholdLevel` will do the comparison if
 * the caller hands it the bounds; the bounds themselves belong to the screen.
 */
import type { JSX } from "@solidjs/web";
import { Show, merge } from "solid-js";
import { Icon } from "../icon.jsx";

/**
 * Three levels and not five: an operator scanning a column has to sort each cell into "fine",
 * "look at this" and "act on this" in the time it takes to scroll past, and a scale with more
 * steps than that is read as a gradient, which is to say as nothing.
 */
export type ThresholdLevel = "normal" | "warning" | "critical";

/**
 * The comparison, done once so that every screen does it the same way.
 *
 * Both bounds are *exclusive*: a value equal to `warnAbove` is still normal. That is the reading an
 * operator expects from "warn above 0" — zero out-of-sync replicas is the healthy case, not a
 * borderline one.
 *
 * There is deliberately no default for either bound. What counts as too much consumer lag, or too
 * many out-of-sync replicas, is a fact about Kafka and about this cluster; a plausible-looking
 * number invented inside a styling component would be wrong everywhere and obvious nowhere.
 */
export function thresholdLevel(
  value: number,
  warnAbove: number,
  criticalAbove?: number | undefined,
): ThresholdLevel {
  if (criticalAbove !== undefined && value > criticalAbove) return "critical";
  if (value > warnAbove) return "warning";
  return "normal";
}

/**
 * Says which side of the limit the figure is on, and nothing about what the figure means — that
 * part is the caller's, because this component has never been told.
 */
export function defaultAnnouncement(level: ThresholdLevel): string {
  switch (level) {
    case "warning":
      return "above the warning threshold";
    case "critical":
      return "above the critical threshold";
    case "normal":
      return "";
  }
}

export interface ThresholdValueProps {
  /** The figure, already formatted. */
  readonly value: string;
  /** Where that figure sits. Reactive, because lag moves on its own. */
  readonly level: ThresholdLevel;
  /** What a screen reader hears when the level is not `normal`. Overridable because "3" on its own
   *  says nothing about whether three is a problem, and only the caller knows what three *is*. */
  readonly announcement?: ((level: ThresholdLevel) => string) | undefined;
  readonly class?: string | undefined;
  readonly "data-testid"?: string | undefined;
}

export function ThresholdValue(props: ThresholdValueProps): JSX.Element {
  const p = merge({ announcement: defaultAnnouncement }, props);
  const crossed = (): boolean => props.level !== "normal";

  return (
    <span
      class={[
        "kui-threshold",
        {
          "kui-threshold--over": props.level === "warning",
          "kui-threshold--critical": props.level === "critical",
        },
        props.class,
      ]}
      data-testid={props["data-testid"]}
    >
      {/* The mark is the non-colour half of the signal, and it appears only when there is something
          to mark: a warning triangle beside every healthy zero is the same mistake as colouring
          every healthy zero. */}
      <Show when={crossed()}>
        <span class="kui-threshold__mark">
          <Icon name="warning" />
        </span>
      </Show>
      {props.value}
      <Show when={crossed()}>
        <span class="kui-visually-hidden"> ({p.announcement(props.level)})</span>
      </Show>
    </span>
  );
}
