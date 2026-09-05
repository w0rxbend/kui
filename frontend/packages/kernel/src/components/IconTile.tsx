/**
 * A tinted rounded square with a glyph in it: the thing at the top-left of every stat card, and at
 * the head of the drawer's cluster card.
 *
 * ## It is decoration, and it says so
 *
 * The tile carries `aria-hidden`, and it always will. It repeats what the card's label already
 * says in words, so announcing it would make a screen reader read every card twice.
 *
 * ## The tone says what the card is about, not how the card is doing
 *
 * Brokers → success, topics → primary, production → accent, consumer lag → warning, on a
 * dashboard where every one of those was healthy (SPEC §4.4). The tone is fixed per card; the
 * card's *current* state lives in its status pill, which is a separate thing that can change.
 * Tying the tile to the value would make a healthy dashboard change colour every time a number
 * crossed a line, which is a lot of movement for no information.
 *
 * There is no "empty" tile. A tile with no glyph reads as a broken image, so a caller with nothing
 * to draw gets the neutral tone and a generic glyph rather than an empty square.
 */
import type { JSX } from "@solidjs/web";
import { merge } from "solid-js";
import { Icon, type IconName } from "../icon.jsx";

export type TileTone = "primary" | "accent" | "success" | "warning" | "danger" | "neutral";

export interface IconTileProps {
  readonly icon: IconName;
  readonly tone?: TileTone | undefined;
  readonly size?: "sm" | "md" | undefined;
  readonly class?: string | undefined;
}

export function IconTile(props: IconTileProps): JSX.Element {
  const p = merge({ tone: "neutral", size: "md" } as const, props);
  return (
    <span
      class={["kui-icon-tile", `kui-icon-tile--${p.tone}`, `kui-icon-tile--${p.size}`, props.class]}
      aria-hidden="true"
    >
      <Icon name={props.icon} />
    </span>
  );
}
