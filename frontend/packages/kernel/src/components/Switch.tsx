/**
 * A switch: a control that changes the view the instant it is touched.
 *
 * ## Why this is not a checkbox
 *
 * The product already has a checkbox, and until now everything that toggled used one. The design
 * introduced a second shape (SCREENS.md §2.13, the "Show statistics" control on the topic list),
 * and two shapes for one idea would be a wart — so the two are given different jobs, and the rule
 * is about *when the change happens*, not about how the control looks:
 *
 *   - A **switch** takes effect immediately and describes a state of the view. Nothing is
 *     submitted; there is nothing to cancel. "Show statistics", "Live tail", "Group by prefix".
 *   - A **checkbox** contributes to something the user will submit, or selects a row. It has a
 *     pending period during which the world has not changed yet. "Delete these four topics",
 *     "Also purge the data".
 *
 * That distinction is worth holding to because it sets an expectation the user cannot otherwise
 * check: a switch that needed a Save button, or a checkbox that fired a request per click, would
 * each be surprising in a way that costs an operator real work to discover.
 *
 * ## It is a checkbox underneath
 *
 * The accessible control is an `<input type="checkbox" role="switch">`. `role="switch"` is exactly
 * this widget — a two-state control whose states are on and off rather than checked and unchecked —
 * and a screen reader announces it as "switch, on" rather than "checkbox, checked", which is what
 * the user sees. Building it from a `<button aria-pressed>` would also work, but it would lose the
 * form association and the label click target that come free with an input.
 *
 * The visible track and knob are drawn by a sibling `<span aria-hidden>`. The input itself is
 * present, focusable and full-size, and merely invisible — never `display: none`, which would take
 * it out of the accessibility tree and the tab order along with it.
 *
 * ## Colour is not the only signal
 *
 * The knob travels. That is the point: an operator who cannot separate the on colour from the off
 * colour can still see which end the knob is at, and a screen reader is told in words. A switch
 * that only changed colour would be unreadable to about one man in twelve.
 */
import { Show, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";

export interface SwitchProps {
  /**
   * The visible label, to the switch's left. Required: a switch with no label is a control whose
   * meaning is carried entirely by its position on the page, and the page can be redesigned.
   */
  readonly label: string;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly disabled?: boolean | undefined;
  /**
   * Why the control is disabled. Rendered as the title and as the accessible description, because
   * a disabled control with no stated reason is indistinguishable from a broken one — this is the
   * same rule the permission-gated buttons follow.
   */
  readonly disabledReason?: string | undefined;
  readonly testId?: string | undefined;
}

export function Switch(props: SwitchProps): JSX.Element {
  const describedBy = () => (props.disabled === true && props.disabledReason !== undefined ? `${id}-why` : undefined);

  // Solid's own id generator rather than a random string: it is stable across a server render and
  // the hydration that follows it, which a random value is not — and a mismatch there silently
  // detaches the description from the input.
  const id = createUniqueId();

  return (
    <label
      class={["kui-switch", { "kui-switch--disabled": props.disabled === true }]}
      title={props.disabled === true ? props.disabledReason : undefined}
    >
      <span class="kui-switch__label">{props.label}</span>

      <input
        id={id}
        class="kui-switch__input kui-focusable"
        type="checkbox"
        role="switch"
        checked={props.checked}
        disabled={props.disabled === true}
        aria-describedby={describedBy()}
        data-testid={props.testId}
        onChange={(event) => props.onChange(event.currentTarget.checked)}
      />

      {/* Decoration. The input above is the control; this is only what it looks like. */}
      <span class="kui-switch__track" aria-hidden="true">
        <span class="kui-switch__knob" />
      </span>

      <Show when={props.disabled === true && props.disabledReason !== undefined}>
        <span id={`${id}-why`} class="kui-visually-hidden">
          {props.disabledReason}
        </span>
      </Show>
    </label>
  );
}
