/**
 * The statistic card used on section landing pages: the label first, then the figure.
 *
 * ## Why there are two of these
 *
 * `StatCard` and `StatTile` differ by the order of two lines, which sounds like a styling variant
 * and is not. They are read differently, and the design uses each in one place consistently
 * (SCREENS.md §2.11):
 *
 *   - `StatCard` — figure, then label. On the **dashboard**, where the operator is scanning for a
 *     number that is wrong. The eye lands on `3` and only then reads `BROKERS ONLINE`. The card
 *     carries a status pill, because the question being asked is "is this all right?".
 *   - `StatTile` — label, then figure. On the **section landing pages** (topics, brokers, a topic's
 *     overview), where the operator already knows which figures are here and wants a particular
 *     one. The eye lands on `TOTAL PARTITIONS` and then reads `1,536`. The card carries a
 *     qualifying chip rather than a verdict, because the question is "how many?", not "is this all
 *     right?".
 *
 * The rule for choosing, stated so nobody has to reverse-engineer it from where they happen to be:
 * **a dashboard summarises health, a landing page summarises inventory.** If a reader would ask
 * "is that bad?" put a `StatCard` there. If they would ask "of what?" put a `StatTile`.
 *
 * ## The chip is not a status pill
 *
 * `10 created this month`, `12 avg per topic`, `↗ 3.2% this week`, `3 topics at RF 2` — these
 * qualify the figure. They are not verdicts on it, and they must not be pill-shaped, because a
 * pill is this product's shape for "here is a judgement" and one of these is a bare fact.
 *
 * The one that comes closest is `3 topics at RF 2`, which is a fact an operator will read as a
 * warning. That is why the chip has a tone: the fact is stated plainly and the tone lets it be
 * drawn in a colour that admits it wants attention, without becoming a verdict on the figure
 * above it.
 *
 * ## Zero, unknown and not-measured
 *
 * The `StatFigure` union is shared with `StatCard`, deliberately, and for the reason its own
 * documentation gives at length: printing `0` for a value that could not be read is the most
 * reassuring possible rendering of the least reassuring possible state.
 *
 * There is a fourth case here that the dashboard does not have, and it is the one this repository
 * has already got wrong once: a figure that is **not measured for this cluster** — no metrics
 * backend is configured, so the number does not exist and never will, as distinct from a request
 * that failed and might succeed on retry. It takes its own kind, so that a call site cannot
 * collapse "we cannot reach the metrics service" into "this deployment does not have one".
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Skeleton } from "./EmptyState.jsx";
import { IconTile, type TileTone } from "./IconTile.jsx";
import { Icon, type IconName } from "./Icon.jsx";
import type { StatFigure } from "./StatCard.jsx";

/**
 * The figure, extended with the case the landing pages need.
 *
 * `StatFigure`'s three cases plus `not-measured`. Reusing the base union rather than redeclaring
 * it means a formatter that produces a figure can feed either component, and the two can never
 * drift into disagreeing about what `pending` means.
 */
export type TileFigure =
  | StatFigure
  /**
   * This deployment does not measure this. Not an error, not pending, and never a zero: there is
   * no number and there will not be one until something is configured.
   */
  | { readonly kind: "not-measured"; readonly why?: string | undefined };

/** How the qualifying chip is drawn. Neutral unless the fact itself wants attention. */
export type TileChipTone = "neutral" | "positive" | "attention";

export interface TileChip {
  readonly text: string;
  readonly tone?: TileChipTone | undefined;
  readonly icon?: IconName | undefined;
}

export interface StatTileProps {
  /**
   * Written uppercase in the source, not transformed by CSS: `text-transform` leaves a screen
   * reader reading the original mixed-case string, and on an acronym the two disagree.
   */
  readonly label: string;
  readonly icon: IconName;
  /** What the tile is *about*. Constant for the life of the tile; never toned by the value. */
  readonly tone: TileTone;
  readonly figure: TileFigure;
  readonly unit?: string | undefined;
  readonly chip?: TileChip | undefined;
  readonly href?: string | undefined;
  readonly testId?: string | undefined;
}

function Figure(props: { readonly figure: TileFigure; readonly unit?: string | undefined }): JSX.Element {
  return (
    <p class="kui-tile__figure">
      <Show when={props.figure.kind === "pending"}>
        <Skeleton width="6rem" height="2rem" />
      </Show>

      <Show when={props.figure.kind === "unknown"}>
        <span class="kui-tile__absent" title="This value could not be read">
          —
        </span>
      </Show>

      <Show when={props.figure.kind === "not-measured" ? props.figure : undefined}>
        {(absent) => (
          /* Words, not a dash. "—" says "there is no value"; this says "nobody is measuring it",
             and the difference tells the operator whether to retry or to go and configure
             something. */
          <span class="kui-tile__absent kui-tile__absent--words" title={absent().why ?? "Not measured for this cluster"}>
            not measured
          </span>
        )}
      </Show>

      <Show when={props.figure.kind === "value" ? props.figure : undefined}>
        {(value) => (
          <>
            {/* `tabular-nums` in the stylesheet: a figure that updates must not change width, or
                the whole row of tiles twitches every time anything moves. */}
            <span class="kui-tile__value">{value().text}</span>
            <Show when={value().unit ?? props.unit}>{(unit) => <span class="kui-tile__unit">{unit()}</span>}</Show>
          </>
        )}
      </Show>
    </p>
  );
}

function Content(props: StatTileProps): JSX.Element {
  return (
    <>
      <span class="kui-tile__head">
        <IconTile icon={props.icon} tone={props.tone} />
        <span class="kui-tile__label">{props.label}</span>
      </span>

      <Figure figure={props.figure} unit={props.unit} />

      <Show when={props.chip}>
        {(chip) => (
          <span class={`kui-tile__chip kui-tile__chip--${chip().tone ?? "neutral"}`}>
            <Show when={chip().icon}>{(icon) => <Icon name={icon()} />}</Show>
            {chip().text}
          </span>
        )}
      </Show>
    </>
  );
}

export function StatTile(props: StatTileProps): JSX.Element {
  const busy = () => (props.figure.kind === "pending" ? "true" : undefined);

  return (
    <Show
      when={props.href}
      fallback={
        <div class="kui-tile" data-testid={props.testId} aria-busy={busy()}>
          {Content(props)}
        </div>
      }
    >
      {(href) => (
        <a class="kui-tile kui-tile--link kui-focusable" href={href()} data-testid={props.testId} aria-busy={busy()}>
          {Content(props)}
        </a>
      )}
    </Show>
  );
}
