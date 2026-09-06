/**
 * A small tile carrying one or two initials for an *identifier*: a client id, a connector name, a
 * topic prefix — the tile at the left of every row of Top producers (`SCREENS-V4.md` §0.2).
 *
 * ## It is not `Avatar`, and the difference is the rule that picks the letters
 *
 * `Avatar.initialsOf` is written for a person's display name: the first letter of the first word
 * and the first letter of the *last*, because "Olena Petrenko" is a first name and a family name
 * and both matter. An identifier is not a name. Applied to
 * `orders.payments.reconciliation.v2` that rule gives `OV`, in which the `V` is the version
 * suffix — the least distinguishing character in the whole string — and the four `orders.*`
 * producers on the screen come out looking alike.
 *
 * So this component reads an identifier left to right: the first character of each of the first
 * two segments, and, when there is only one segment, its first two characters. `checkout-svc`
 * gives `CS`; `payments` gives `PA`. That is the half of the string a reader is already using to
 * tell the rows apart.
 *
 * ## The colour is a hash, and it is deliberately not a token
 *
 * Every other colour in KUI comes from `10-tokens.css`, and this one may not: a token says *what a
 * colour is for*, and "the fourth client id" is not a purpose (`SCREENS-V4.md` §0.2). The five
 * hues are a decorative ramp declared beside the component, in `27-primitives-v3.css`, and this
 * file chooses among them with a hash of the identifier.
 *
 * The hash carries a real requirement: **the same id must give the same tile everywhere**. An
 * operator learns that the plum tile is `checkout-svc`, and that only holds if the colour does not
 * depend on the order rows arrived in, on which replica served the page, or on when it was
 * reloaded. So the index is a pure function of the string — no counter, no `Math.random`, no map
 * built at render time — and the arithmetic below is 32-bit FNV-1a rather than the usual
 * `hash * 31`, because a multiply of that size leaves the double's mantissa behind and the low
 * bits, which are the bits the modulo reads, quietly stop depending on the input.
 *
 * TypeScript picks the *index*; the stylesheet holds the colour. Nothing here computes an ink,
 * which is the rule the whole design system is built on (ADR-024, ADR-048 §5).
 *
 * ## By default it is decoration
 *
 * The tile almost always sits immediately to the left of the identifier it stands for, printed in
 * full. Announcing it would read `checkout-svc` twice, once spelled out two letters at a time. So
 * with no `label` it is `aria-hidden`, and a caller that puts a monogram somewhere the id is *not*
 * written passes the `label` it should be announced by.
 */
import type { JSX } from "@solidjs/web";

/** How many hues the decorative ramp in `27-primitives-v3.css` declares. */
export const MONOGRAM_RAMP_LENGTH = 5;

/**
 * One or two initials for an identifier. Never empty: an id with nothing alphanumeric in it draws
 * a question mark rather than a blank square, for the reason `IconTile` refuses an empty tile — a
 * tile with nothing in it reads as an image that failed to load.
 */
export function monogramInitials(id: string): string {
  const segments = id.split(/[^\p{L}\p{N}]+/u).filter((part) => part.length > 0);
  const first = segments[0];
  if (first === undefined) return "?";
  const second = segments[1];
  const letters = second === undefined ? first.slice(0, 2) : `${first[0] ?? ""}${second[0] ?? ""}`;
  return letters.toUpperCase();
}

/**
 * Which entry of the decorative ramp an identifier lands on: FNV-1a over the string's code units,
 * reduced modulo the ramp's length.
 *
 * The prime is applied as shifts and adds because `hash * 16777619` exceeds what a double holds
 * exactly, and once it does the result's low bits — the only bits the modulo reads — no longer
 * depend on the input at all. `>>> 0` keeps every step inside 32 unsigned bits.
 */
export function monogramIndex(id: string): number {
  let hash = 0x811c9dc5;
  for (let position = 0; position < id.length; position += 1) {
    hash = (hash ^ id.charCodeAt(position)) >>> 0;
    hash =
      (hash + ((hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24))) >>> 0;
  }
  return hash % MONOGRAM_RAMP_LENGTH;
}

export interface MonogramProps {
  /** The identifier the tile stands for. Its letters and its colour both come from this. */
  readonly id: string;
  /**
   * What a screen reader should call the tile. Omitted — the usual case — the tile is decoration
   * and is hidden, because the id it abbreviates is written beside it.
   */
  readonly label?: string | undefined;
  /**
   * `md` — 32px, the design's Top-producers tile — is the default and is what `.kui-monogram`
   * itself declares, so it is spelled by emitting no modifier at all. `sm` is the one override.
   */
  readonly size?: "sm" | "md" | undefined;
  readonly class?: string | undefined;
  readonly testId?: string | undefined;
}

export function Monogram(props: MonogramProps): JSX.Element {
  // 1-based in the class name, so the stylesheet's five rules read as a list rather than as an
  // array index somebody has to remember is zero-based.
  const ramp = (): number => monogramIndex(props.id) + 1;
  const named = (): boolean => props.label !== undefined;
  /* Only `sm` gets a class, because only `sm` is an override: `27-primitives-v3.css` puts the 32px
   * default in `.kui-monogram` itself, so the size a tile has does not depend on a modifier being
   * emitted. Emitting `kui-monogram--md` as well would put a class in the DOM that matches no rule
   * anywhere — a false lead for whoever greps for it next, and the reason the default was moved
   * out of the base in the first place. `controls.test.tsx` asserts both halves of that. */
  const sized = (): string | undefined => (props.size === "sm" ? "kui-monogram--sm" : undefined);

  return (
    <span
      class={["kui-monogram", `kui-monogram--${ramp()}`, sized(), props.class]}
      data-testid={props.testId}
      /* `data-ramp` is what a test asserts on: two monograms for one id must agree, and asserting
         that through a class name would be asserting the class-name scheme instead of the rule. */
      data-ramp={ramp()}
      role={named() ? "img" : undefined}
      aria-label={props.label}
      aria-hidden={named() ? undefined : "true"}
    >
      {monogramInitials(props.id)}
    </span>
  );
}
