/**
 * A checkbox the product draws, built on the browser's real one.
 *
 * ## A visually-hidden `<input>` plus a drawn box
 *
 * The native checkbox is kept — hidden from the eye with `opacity: 0`, not removed with
 * `display: none` — and the tick the user sees is drawn beside it. That is the whole trick, and it
 * is chosen over a `<div role="checkbox">` because the input brings, for free and correctly:
 * form participation, the space key, `<label>` association, the `indeterminate` property, the
 * browser's own announcement in the accessibility tree, and autofill and reset behaviour. Every
 * one of those has to be reimplemented the moment the input is thrown away, and reimplemented
 * wrongly is the normal outcome.
 *
 * `opacity: 0` rather than `display: none` matters: a `display: none` input is not focusable, and
 * the entire point of keeping the real control is that it is.
 *
 * ## The half this project got wrong before
 *
 * A checkbox drawn as nothing was one of the three controls this project shipped that were perfect
 * to a screen reader and invisible to everyone else. So the drawn box has a real border at rest
 * (not a fill that only appears when checked), the checked state changes both the fill *and* draws
 * a tick, and the focus ring is drawn on the box, driven by `:focus-visible` on the hidden input.
 * Without that last rule the control would be focusable and completely invisible while focused.
 *
 * ## Indeterminate
 *
 * `indeterminate` is a DOM property, not an attribute: it cannot be set in markup and it is not
 * reflected. It is written imperatively, and — because Solid 2 flushes writes on a microtask —
 * from an effect's apply phase, where the value has settled.
 */
import type { JSX } from "@solidjs/web";
import { Show, createEffect, createUniqueId } from "solid-js";
import { Icon } from "../icon.jsx";

export interface CheckboxProps {
  /** The label. Required: a checkbox with no name is a box whose meaning is its position. */
  readonly label: string;
  /** Hide the label visually — for a table's select-all, where the column header names it. */
  readonly labelHidden?: boolean | undefined;
  readonly checked?: boolean | undefined;
  /** Some but not all of the things below are checked. Drawn as a bar, never as a tick. */
  readonly indeterminate?: boolean | undefined;
  readonly disabled?: boolean | undefined;
  readonly name?: string | undefined;
  readonly value?: string | undefined;
  readonly class?: string | undefined;
  /** A stable hook for tests. It is on the input, which is the thing a test drives. */
  readonly testId?: string | undefined;
  readonly onChange?: (checked: boolean) => void;
}

export function Checkbox(props: CheckboxProps): JSX.Element {
  const id = createUniqueId();
  let input: HTMLInputElement | undefined;

  createEffect(
    () => props.indeterminate === true,
    (value) => {
      if (input !== undefined) input.indeterminate = value;
    },
  );

  return (
    <label
      class={["kui-checkbox", { "kui-checkbox--disabled": props.disabled === true }, props.class]}
      for={id}
    >
      <input
        id={id}
        ref={(el) => {
          input = el;
          // The property is set here as well as in the effect: the effect's apply phase runs
          // after the element is in the document, but a story or a test that reads the checkbox
          // synchronously after render would otherwise see the wrong state for one microtask.
          el.indeterminate = props.indeterminate === true;
        }}
        type="checkbox"
        class="kui-checkbox__input"
        checked={props.checked === true}
        disabled={props.disabled === true}
        name={props.name}
        data-testid={props.testId}
        value={props.value}
        onChange={(event) => props.onChange?.(event.currentTarget.checked)}
      />
      <span class="kui-checkbox__box" aria-hidden="true">
        {/* Indeterminate wins over checked, because that is what the input reports: an input that
            is both `checked` and `indeterminate` announces "mixed", and drawing a tick there would
            say something the accessibility tree does not. */}
        <Show
          when={props.indeterminate === true}
          fallback={
            <Show when={props.checked === true}>
              <Icon name="check" size="0.75em" />
            </Show>
          }
        >
          <Icon name="minus" size="0.75em" />
        </Show>
      </span>
      <span class={{ "kui-visually-hidden": props.labelHidden === true }}>{props.label}</span>
    </label>
  );
}
