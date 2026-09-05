/**
 * A small coloured label: a topic's cleanup policy, a broker's role, a consumer group's state, an
 * applied filter.
 *
 * ## Tag or status pill?
 *
 * `StatusPill` is a stadium and says what state a thing is *in*. A tag is squarer — the design
 * reserves the pill shape for things you press — and says what a thing *is*: a category, a
 * property, a filter somebody typed. Keeping the two shapes apart is the only thing that stops an
 * operator reading a category as a health signal.
 *
 * ## Colour is never the only signal
 *
 * A tag always carries text. The tone tints it, and the optional dot adds a second, non-colour
 * cue, but nothing here depends on the reader being able to separate red from green — around one
 * man in twelve cannot, and a screen reader gets no colour at all.
 *
 * ## When it is a status, say so
 *
 * `live` renders `role="status"`, which makes a screen reader announce the tag when its text
 * changes without moving focus. Use it for something that changes on its own (a consumer group
 * going from `Stable` to `Rebalancing`) and leave it off for a static label: a page full of
 * announcing tags announces nothing useful, because they queue and the operator hears a paragraph
 * of state every time anything moves.
 */
import type { JSX } from "@solidjs/web";
import { Show, merge } from "solid-js";
import { Icon } from "../icon.jsx";

export type TagTone = "neutral" | "info" | "success" | "warning" | "danger";

export interface TagProps {
  /** The words. A tag with no words is a coloured smudge, so an empty one is not rendered. */
  readonly children: string;
  readonly tone?: TagTone | undefined;
  /** A filled dot before the text: the second, non-colour cue. */
  readonly dot?: boolean | undefined;
  /** Announce changes to this tag's text without moving focus. Rare — see above. */
  readonly live?: boolean | undefined;
  /** When given, the tag gains a real `<button>` to dismiss it. Used for applied filters. */
  readonly onRemove?: (() => void) | undefined;
  readonly class?: string | undefined;
  readonly "data-testid"?: string | undefined;
}

export function Tag(props: TagProps): JSX.Element {
  const p = merge({ tone: "neutral" } as const, props);

  return (
    <Show when={props.children !== ""}>
      <span
        class={["kui-tag", `kui-tag--${p.tone}`, props.class]}
        role={props.live === true ? "status" : undefined}
        data-testid={props["data-testid"]}
      >
        <Show when={props.dot === true}>
          <span class="kui-tag__dot" aria-hidden="true" />
        </Show>
        {props.children}
        <Show when={props.onRemove !== undefined}>
          <button
            type="button"
            class={["kui-tag__remove", "kui-focusable"]}
            /* The name has to say what is being removed. "×" on its own is announced as "times",
             * and a row of eight identical "Remove" buttons is no better. */
            aria-label={`Remove ${props.children}`}
            onClick={() => props.onRemove?.()}
          >
            <Icon name="close" />
          </button>
        </Show>
      </span>
    </Show>
  );
}
