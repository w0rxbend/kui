/**
 * A segmented control: two to four mutually exclusive choices, all of them visible at once.
 *
 * ## What it is for, and when a `Select` is right instead
 *
 * The design uses this shape in five places — Table/Cards on the topic list, JSON/Table in the
 * message browser, the time window 5m/15m/1h/24h, the schema version v1/v2/v3, and the
 * compatibility mode BACKWARD/FORWARD/FULL/NONE (SCREENS.md §2.12, §2.17, §3.5). They have one
 * thing in common that decides the shape: **the alternatives matter as much as the choice**. An
 * operator looking at a message browser needs to know that a Table view exists; hiding it inside a
 * dropdown means they never find out.
 *
 * So: a segmented control when the options are few, stable, and worth advertising. A `Select` when
 * they are many, or when they arrive from the server and the count is not known in advance. Five is
 * the point at which this stops working — at five the segments are too narrow to label honestly,
 * and the answer is a `Select`, not a smaller font.
 *
 * ## It is a radio group, not a row of buttons
 *
 * The implementation is `role="radiogroup"` over real `<input type="radio">`s, which buys three
 * behaviours that a row of buttons would each have to reimplement, badly:
 *
 *   - Arrow keys move between the options and select as they go; Tab moves *past* the whole group
 *     rather than through it. That is what a keyboard user expects of a set of alternatives, and
 *     it is the browser's behaviour, not ours.
 *   - The group has one tab stop. A row of four buttons has four, and a page with three segmented
 *     controls would cost twelve tab presses to cross.
 *   - A screen reader announces "2 of 4", which is information a row of pressed/unpressed buttons
 *     does not carry.
 *
 * The inputs are visually hidden but present and focusable — never `display: none`, which would
 * remove them from the tab order and the accessibility tree together.
 *
 * ## Why the value is a generic type parameter
 *
 * The caller's value is usually a union of string literals (`"table" | "cards"`). Typing this
 * component as `string` would let a typo compile and then silently select nothing at run time,
 * which is a blank pane with no error. With the parameter, an option whose value is not in the
 * union is a type error at the call site.
 */
import { For, Show, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, type IconName } from "./Icon.jsx";

export interface Segment<T extends string> {
  readonly value: T;
  /**
   * The visible text. Always present, even when there is an icon: SPEC.md §4.0's rule is that
   * colour is never the only signal, and a glyph alone is the same failure in another form — a
   * grid-of-squares icon means "cards" only to someone who already knows.
   */
  readonly label: string;
  readonly icon?: IconName | undefined;
  readonly disabled?: boolean | undefined;
}

export interface SegmentedControlProps<T extends string> {
  /**
   * The accessible name of the whole group, e.g. "View" or "Time window". Not drawn: the segments
   * are self-explanatory on screen, but a screen reader arriving at a radio group with no name can
   * only say "radio group".
   */
  readonly label: string;
  readonly segments: readonly Segment<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
  /** Fills the width it is given rather than sizing to its content. */
  readonly stretch?: boolean | undefined;
  readonly size?: "sm" | "md" | undefined;
  readonly testId?: string | undefined;
}

export function SegmentedControl<T extends string>(props: SegmentedControlProps<T>): JSX.Element {
  // One name per instance, so that two segmented controls on the same page are two groups rather
  // than one group of eight. Radio grouping is by `name`, and a shared name is a subtle bug: the
  // second control silently deselects the first. Solid's id generator rather than a random string,
  // so that a server render and its hydration agree.
  const name = createUniqueId();

  return (
    <div
      class={[
        "kui-segmented",
        { "kui-segmented--stretch": props.stretch === true, "kui-segmented--sm": props.size === "sm" },
      ]}
      role="radiogroup"
      aria-label={props.label}
      data-testid={props.testId}
    >
      <For each={props.segments}>
        {(segment) => (
          <label
            class={[
              "kui-segmented__item",
              {
                "kui-segmented__item--active": segment.value === props.value,
                "kui-segmented__item--disabled": segment.disabled === true,
              },
            ]}
          >
            <input
              class="kui-segmented__input kui-focusable"
              type="radio"
              name={name}
              value={segment.value}
              checked={segment.value === props.value}
              disabled={segment.disabled === true}
              onChange={() => props.onChange(segment.value)}
            />
            <Show when={segment.icon}>{(icon) => <Icon name={icon()} />}</Show>
            <span class="kui-segmented__text">{segment.label}</span>
          </label>
        )}
      </For>
    </div>
  );
}
