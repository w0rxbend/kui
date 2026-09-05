import type { JSX } from "@solidjs/web";
import { Show } from "solid-js";
import { Icon, type IconName } from "./Icon.jsx";

/**
 * What a region shows when it holds no rows — and, crucially, *which* kind of nothing it is.
 *
 * ## The distinction this component exists to make
 *
 * An empty region is ambiguous. It can mean "there is nothing here yet", "your filter matched
 * nothing", "we could not reach the service", or "you are not allowed to see this". They look
 * identical if you draw all four as blank space, and they want four different things from the
 * person reading them: wait, clear the filter, retry, or ask an administrator. Blank space says
 * none of that, and the failure mode is the worst one available — a request that failed silently
 * is indistinguishable from a cluster that genuinely has no topics.
 *
 * So this component takes the *situation* rather than a title, and every situation carries its own
 * glyph, its own words and its own action. Design spec §4.0, §4.16 and §7.1.
 *
 * ## The frame never disappears
 *
 * None of these renderings replaces the panel around it. A table in the `unavailable` situation
 * keeps its header row, so the columns still say what the table would have held; a card keeps its
 * title. A gateway aggregation is partial by design, and a page has to be able to show three
 * healthy panels and one that failed at the same time without either of them lying.
 *
 * ## Why the error code is on screen
 *
 * `UPSTREAM_UNAVAILABLE` means nothing to the operator and everything to whoever they paste it to.
 * It is rendered in mono at the subtle text colour — present, findable, and not competing with the
 * sentence above it. Swallowing it turns a five-minute support conversation into an hour.
 */

/** Which kind of nothing this is. Not a style: each one implies different words and a different
 * next action, and they must never be substituted for one another. */
export type EmptyKind =
  /** There is genuinely no data yet. Normal, not a problem. */
  | "empty"
  /** There is data, but the current filter excludes all of it. */
  | "filtered"
  /** The request did not come back. The data may well exist. */
  | "unavailable"
  /** The request was refused. The data exists and is not yours to read. */
  | "forbidden";

/* The optional properties below are written `?: T | undefined` rather than `?: T`. Under
 * `exactOptionalPropertyTypes` (tsconfig.base.json) the short form means "either present with a T,
 * or absent" and refuses an explicit `undefined` — which makes the prop impossible to *forward*
 * from a wrapper whose own value is `T | undefined`, and every wrapper of this component has one.
 * The long form keeps the strictness that matters (a typo is still a type error) without making
 * composition impossible. */
export interface EmptyStateProps {
  readonly kind: EmptyKind;
  /** One short sentence naming the situation. Sentence case, ends with a full stop. */
  readonly title: string;
  /** One sentence saying what it means or what to do about it. Optional but nearly always wanted. */
  readonly description?: string | undefined;
  /**
   * The stable error code, for `unavailable` and `forbidden`. Rendered in mono so it can be read
   * over the phone and selected without catching the prose either side of it.
   */
  readonly code?: string | undefined;
  /** One action, at most. Two actions in an empty state is a menu, and nobody reads a menu here. */
  readonly action?: JSX.Element | undefined;
  /** Marks this element for a test to find. Never used for styling. */
  readonly testId?: string | undefined;
}

/* Four situations, four glyphs. The glyph is never the message — the title and the description
 * carry that — but a reader who has learnt the shapes recognises which of the four they are
 * looking at before they have read a word, and that is worth one icon each. */
const glyphFor: Record<EmptyKind, IconName> = {
  empty: "inbox",
  filtered: "search",
  unavailable: "error",
  forbidden: "lock",
};

export function EmptyState(props: EmptyStateProps): JSX.Element {
  return (
    <div
      class={["kui-empty-state", `kui-empty-state--${props.kind}`]}
      data-testid={props.testId}
      // A list announces its emptiness in words rather than by being empty (§7.9). `status` puts
      // the sentence in the accessibility tree as a live region, so a reader who filtered a table
      // down to nothing is told so rather than left listening to silence.
      role="status"
    >
      <div class="kui-empty-state__icon" aria-hidden="true">
        <Icon name={glyphFor[props.kind]} />
      </div>
      <p class="kui-empty-state__title">{props.title}</p>
      <Show when={props.description}>
        {(description) => <p class="kui-empty-state__description">{description()}</p>}
      </Show>
      <Show when={props.code}>
        {(code) => (
          <p class="kui-empty-state__code">
            <code>{code()}</code>
          </p>
        )}
      </Show>
      <Show when={props.action}>
        {(action) => <div class="kui-empty-state__action">{action()}</div>}
      </Show>
    </div>
  );
}

/**
 * A block that stands in for a value that has not arrived yet.
 *
 * ## Why a skeleton and not a spinner
 *
 * A spinner inside a card moves the layout twice: once when it replaces the content that was
 * there, and again when the content lands and is a different size. A skeleton is drawn at the
 * size the real content will be, so nothing moves. §7.1.
 *
 * ## Why this is not a dash
 *
 * `—` means "this field is genuinely empty" — a record with a null key, a group with no
 * coordinator. A value that is still loading is a different fact and must look different, or the
 * reader concludes that a group has no coordinator when in truth nobody has asked yet. The two
 * renderings and the third one (a stale value under a badge) are three pictures and never collapse
 * into one. §4.0.
 *
 * The animation is suppressed under `prefers-reduced-motion` by the stylesheet, not by a check
 * here: a media query re-evaluates when the operating system setting changes, and a value read
 * once at render does not.
 */
export interface SkeletonProps {
  /** Any CSS length. Defaults to filling its container. */
  readonly width?: string | undefined;
  /** Any CSS length. Defaults to one line box at the current font size. */
  readonly height?: string | undefined;
  readonly testId?: string | undefined;
}

export function Skeleton(props: SkeletonProps): JSX.Element {
  return (
    <span
      class="kui-skeleton"
      data-testid={props.testId}
      style={{ width: props.width ?? "100%", height: props.height ?? "1.25em" }}
      // The block is decoration. The region it sits in carries `aria-busy`, which is what tells a
      // screen reader that something is coming; announcing every placeholder would be noise.
      aria-hidden="true"
    />
  );
}

/**
 * What a cell shows when the value is genuinely absent.
 *
 * An em dash, at the muted text colour, and never a zero. `0` and "no value" are different facts:
 * a consumer group with zero lag is caught up, and one whose lag could not be read is a group
 * nobody knows anything about. Rendering the second as the first is how an outage gets reported as
 * a healthy cluster. §4.0, §4.18.
 */
export function Missing(): JSX.Element {
  return (
    <span class="kui-missing" title="No value">
      —
    </span>
  );
}
