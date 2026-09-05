/**
 * The row of chips under a list's controls: `All`, `Internal`, `Out of sync`, `Compacted`.
 *
 * ## What a filter chip is, as distinct from the two things it looks like
 *
 * The product now has three pill-shaped things and they are easy to confuse, so:
 *
 *   - A `StatusPill` **reports**. It says what something is, and clicking it does nothing.
 *   - A `Tag` **labels**. It sits in a table cell and names a category.
 *   - A `FilterChip` **acts**. It is a control, it toggles, and the page changes when it is used.
 *
 * They are drawn differently on purpose, and a chip is the only one of the three that is a button.
 * If a chip ever ends up looking like a pill, an operator will try to click the pills.
 *
 * ## Why the chip that would match nothing is still drawn
 *
 * A cluster with no internal topics still gets an `Internal` chip, and clicking it still produces
 * an empty table that says the filter matched nothing (SCREENS.md §2.14). Hiding the chip instead
 * would be tidier and would make the interface lie: an absent chip is indistinguishable from a
 * filter the deployment does not support, and those want different actions from the operator —
 * one is "there are none", the other is "ask your administrator".
 *
 * ## Single-select or multi-select is the caller's decision
 *
 * This component does not know. It draws chips and reports clicks; whether clicking `Internal`
 * clears `Compacted` is a question about the filters, not about the row. The topic list uses it as
 * a single-select with an `All` that clears the rest, which is why `All` is not special-cased here:
 * it is an ordinary chip whose handler happens to clear the others.
 *
 * ## Accessibility
 *
 * Each chip is a `<button>` carrying `aria-pressed`, which is the correct role for a toggle that
 * is not part of a mutually exclusive set the browser knows about. The check glyph that appears on
 * an active chip is decoration; `aria-pressed` is what is actually announced, and the chip's own
 * word is always present, so the state never rests on colour alone.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, type IconName } from "./Icon.jsx";

export interface FilterChipProps {
  readonly label: string;
  readonly active: boolean;
  readonly onToggle: () => void;
  /**
   * The chip's own glyph, shown when it is inactive. An active chip shows a check instead — the
   * design's own behaviour, and a useful one: it makes "this is on" legible without colour.
   */
  readonly icon?: IconName | undefined;
  /** A count shown after the label, e.g. `Out of sync 2`. Absent when the count is unknown. */
  readonly count?: number | undefined;
  readonly disabled?: boolean | undefined;
  readonly testId?: string | undefined;
}

export function FilterChip(props: FilterChipProps): JSX.Element {
  return (
    <button
      type="button"
      class={["kui-fchip", "kui-focusable", { "kui-fchip--active": props.active }]}
      aria-pressed={props.active ? "true" : "false"}
      disabled={props.disabled === true}
      data-testid={props.testId}
      onClick={() => props.onToggle()}
    >
      <Show when={props.active} fallback={<Show when={props.icon}>{(icon) => <Icon name={icon()} />}</Show>}>
        <Icon name="check" />
      </Show>
      <span class="kui-fchip__label">{props.label}</span>
      {/* `!== undefined` rather than a truthiness test: a chip matching zero rows must be able to
          say `0`, and `count && ...` would silently hide exactly that case. */}
      <Show when={props.count !== undefined}>
        <span class="kui-fchip__count">{props.count}</span>
      </Show>
    </button>
  );
}

export interface FilterChipBarProps {
  /**
   * The accessible name of the set, e.g. "Topic filters". A bare row of toggle buttons tells a
   * screen-reader user what each one does and nothing about what they collectively are.
   */
  readonly label: string;
  readonly children: JSX.Element;
  readonly testId?: string | undefined;
}

export function FilterChipBar(props: FilterChipBarProps): JSX.Element {
  return (
    <div class="kui-fchip-bar" role="group" aria-label={props.label} data-testid={props.testId}>
      {props.children}
    </div>
  );
}

/**
 * A convenience for the common case: a fixed set of chips, one of which is active.
 *
 * Kept separate from `FilterChipBar` rather than folded into it, because the message browser's
 * preset chips are the same shape with different behaviour (any number active, and the set arrives
 * from the user's saved presets), and a single component trying to serve both would need a mode
 * flag — at which point the two behaviours are no longer readable from the call site.
 */
export interface SingleSelectChipsProps<T extends string> {
  readonly label: string;
  readonly options: readonly { readonly value: T; readonly label: string; readonly icon?: IconName | undefined }[];
  readonly value: T;
  readonly onChange: (value: T) => void;
  readonly testId?: string | undefined;
}

export function SingleSelectChips<T extends string>(props: SingleSelectChipsProps<T>): JSX.Element {
  return (
    <FilterChipBar label={props.label} testId={props.testId}>
      <For each={props.options}>
        {(option) => (
          <FilterChip
            label={option.label}
            icon={option.icon}
            active={option.value === props.value}
            onToggle={() => props.onChange(option.value)}
          />
        )}
      </For>
    </FilterChipBar>
  );
}
