/**
 * The four cards across the top of the dashboard: a label, one large number, and a verdict.
 *
 * ## What it is for
 *
 * A stat card answers a question the operator asks before they ask anything else — how many
 * brokers, how many topics, how fast, how far behind. It is read in about a second, from across a
 * desk, so it has exactly three parts and no more: what this is (the small-caps label and the
 * tinted tile), the number, and whether the number is all right (the pill).
 *
 * ## The tile's tone and the pill's tone are different decisions
 *
 * The tile is toned by what the card is *about* — brokers success, topics primary, production
 * accent, lag warning — and it never changes. The pill is toned by what the number currently *is*.
 * Tying the tile to the value would make the whole dashboard change colour every time anything
 * moved, and the eye would stop being able to find the card it wanted.
 *
 * ## Zero is a number. A dash is not.
 *
 * This is the rule the component exists to enforce, and it is worth being blunt about, because
 * getting it wrong reports an outage as a healthy cluster:
 *
 *   - A cluster with **no consumer lag** shows `0`, with a success pill. That is good news.
 *   - A cluster whose lag **could not be computed** shows `—`, with a neutral pill saying so.
 *
 * Printing `0` for an unknown is the worst available failure: the most reassuring possible
 * rendering of the least reassuring possible state. So `figure` is a discriminated union rather
 * than `number | undefined`, and a call site has to say which of the three it means.
 *
 * A failed metric also gets a **neutral** pill, not a red one. The metrics service being
 * unreachable is not the cluster being unhealthy, and saying so in red teaches the operator to
 * distrust red — after which the red that matters is not read either.
 *
 * ## Loading
 *
 * The label and the tile do not depend on data, so they draw at once and never move. Only the
 * figure is a skeleton, and it is a skeleton the size of the figure, so nothing on the card shifts
 * when the number lands.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Skeleton } from "./EmptyState.jsx";
import { IconTile, type TileTone } from "./IconTile.jsx";
import { StatusPill, type PillTone } from "./StatusPill.jsx";
import type { IconName } from "./Icon.jsx";

/**
 * The figure, as a discriminated union rather than as `number | undefined`.
 *
 * `undefined` would let "we do not know" be produced by a missing property, a renamed field or a
 * stray `?? undefined`, and at the point of rendering none of those is distinguishable from the
 * real thing. Naming the three cases means the difference has to be stated where it is known.
 */
export type StatFigure =
  /** A known value, already formatted for display: `128`, `86.4`, `4,212`, `0`. */
  | { readonly kind: "value"; readonly text: string; readonly unit?: string | undefined }
  /** Not asked yet, or in flight. Draws a skeleton the size of the figure. */
  | { readonly kind: "pending" }
  /** Asked, and the answer did not come back. Draws an em dash. Never a zero. */
  | { readonly kind: "unknown" };

export interface StatPill {
  readonly text: string;
  readonly tone: PillTone;
  /** A leading glyph. Colour is never the only signal, so the text always says it too. */
  readonly icon?: IconName | undefined;
}

export interface StatCardProps {
  /**
   * Written uppercase in the source, not transformed by CSS. `text-transform` leaves a screen
   * reader reading the original mixed-case string, and on an acronym the two disagree.
   */
  readonly label: string;
  readonly icon: IconName;
  /** What the card is *about*. Constant for the life of the card. */
  readonly tone: TileTone;
  readonly figure: StatFigure;
  readonly pill?: StatPill | undefined;
  /**
   * Makes the whole card a link. The label and the figure are both inside it, so the accessible
   * name is the card's whole content — which is what somebody tabbing the dashboard wants to hear.
   */
  readonly href?: string | undefined;
  readonly testId?: string | undefined;
}

function Figure(props: { readonly figure: StatFigure }): JSX.Element {
  return (
    <p class="kui-stat__figure">
      <Show when={props.figure.kind === "pending"}>
        <Skeleton width="5.5rem" height="2rem" />
      </Show>
      <Show when={props.figure.kind === "unknown"}>
        <span class="kui-stat__unknown" title="This value could not be read">
          —
        </span>
      </Show>
      <Show when={props.figure.kind === "value" ? (props.figure as { text: string }) : undefined}>
        {(value) => (
          <>
            {/* `tabular-nums` in the stylesheet: a figure that changes every few seconds must not
                change width, or the whole row of cards twitches whenever anything updates. */}
            <span class="kui-stat__value">{value().text}</span>
            <Show when={(props.figure as { unit?: string | undefined }).unit}>
              {(unit) => <span class="kui-stat__unit">{unit()}</span>}
            </Show>
          </>
        )}
      </Show>
    </p>
  );
}

function Content(props: StatCardProps): JSX.Element {
  return (
    <>
      <span class="kui-stat__head">
        <IconTile icon={props.icon} tone={props.tone} />
        <span class="kui-stat__label">{props.label}</span>
      </span>

      <Figure figure={props.figure} />

      <Show when={props.pill}>
        {(pill) => (
          <span class="kui-stat__pill-slot">
            <StatusPill tone={pill().tone} icon={pill().icon}>
              {pill().text}
            </StatusPill>
          </span>
        )}
      </Show>
    </>
  );
}

export function StatCard(props: StatCardProps): JSX.Element {
  // `aria-busy` is how a screen reader is told that the skeleton is a placeholder. Without it the
  // card reads as a label with no value, which is the same thing it reads as when the value is
  // genuinely absent — the exact confusion this component exists to prevent.
  const busy = () => (props.figure.kind === "pending" ? "true" : undefined);

  return (
    <Show
      when={props.href}
      fallback={
        <div class="kui-stat" data-testid={props.testId} aria-busy={busy()}>
          {Content(props)}
        </div>
      }
    >
      {(href) => (
        <a class="kui-stat kui-stat--link kui-focusable" href={href()} data-testid={props.testId} aria-busy={busy()}>
          {Content(props)}
        </a>
      )}
    </Show>
  );
}
