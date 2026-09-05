/**
 * A single-line text input.
 *
 * ## The box is the control, the input is the text
 *
 * The fill, the radius and the focus ring live on a wrapper, not on the `<input>`. That is what
 * lets a leading glyph and a trailing hint sit *inside* the control — the search field's magnifier
 * and its `⌘K` badge (SPEC §4.8) are inside one 38px box, not three things in a row that happen to
 * line up. It is also the only reason an `outline: none` is allowed anywhere in this product: the
 * input's own outline is removed *because* the box around it draws one, and the two rules are
 * three lines apart in `27-primitives.css` so neither can be deleted without the other being
 * obviously wrong.
 *
 * ## A field must not rebuild while somebody is typing in it
 *
 * This component holds no state of its own. It is uncontrolled by default and controlled when the
 * caller passes `value`, and in neither case does it re-create the `<input>` element. That is the
 * component's half of the rule; the caller's half is not to put the field inside a `<Show>` or a
 * `<Loading>` whose condition flips when results arrive, because that unmounts the element and the
 * caret with it (SPEC §4.26).
 *
 * ## Errors are attached, not adjacent
 *
 * The message is joined to the input with `aria-describedby` and the input carries
 * `aria-invalid`, so a screen reader user hears the problem when they reach the field rather than
 * discovering red text they were never told about. And the message is text — the red border is
 * never the only signal.
 */
import type { JSX } from "@solidjs/web";
import { Show, createUniqueId, merge, omit } from "solid-js";
import { Icon, type IconName } from "../icon.jsx";

export interface TextFieldProps {
  /** Written in the source in the case it should be read in. Never `text-transform`ed. */
  readonly label: string;
  /** Hide the label visually when the surrounding layout already names the field. Never drop it. */
  readonly labelHidden?: boolean | undefined;
  readonly value?: string | undefined;
  readonly placeholder?: string | undefined;
  readonly icon?: IconName | undefined;
  /** A keyboard hint rendered as a small badge inside the trailing edge, e.g. `⌘K`. */
  readonly hintKey?: string | undefined;
  /** Help text under the field. Always visible; not a tooltip. */
  readonly help?: string | undefined;
  /** When set, the field is invalid and this is the reason. Presence is the invalid flag. */
  readonly error?: string | undefined;
  readonly disabled?: boolean | undefined;
  readonly readOnly?: boolean | undefined;
  readonly required?: boolean | undefined;
  readonly size?: "sm" | "md" | undefined;
  /** Offsets, keys and payload fragments are compared character by character; they get the mono face. */
  readonly mono?: boolean | undefined;
  readonly type?: "text" | "search" | "number" | "password" | undefined;
  readonly name?: string | undefined;
  readonly id?: string | undefined;
  readonly class?: string | undefined;
  readonly onInput?: (value: string, event: InputEvent) => void;
  readonly onKeyDown?: (event: KeyboardEvent) => void;
  readonly ref?: (el: HTMLInputElement) => void;
}

export function TextField(props: TextFieldProps): JSX.Element {
  const generated = createUniqueId();
  const inputId = (): string => props.id ?? `kui-field-${generated}`;
  const helpId = `kui-help-${generated}`;
  const errorId = `kui-error-${generated}`;
  const p = merge({ size: "md", type: "text" } as const, props);

  /**
   * Both descriptions are named, in reading order. Passing only one of them would make the other
   * silently unreachable — help text that a sighted user can read and a screen reader user cannot
   * is the same defect as an invisible focus ring, in the other direction.
   */
  const describedBy = (): string | undefined => {
    const ids = [props.help !== undefined ? helpId : "", props.error !== undefined ? errorId : ""]
      .filter((s) => s !== "")
      .join(" ");
    return ids === "" ? undefined : ids;
  };

  return (
    <div
      class={[
        "kui-textfield",
        `kui-textfield--${p.size}`,
        { "kui-textfield--invalid": props.error !== undefined, "kui-textfield--disabled": props.disabled === true },
        props.class,
      ]}
    >
      <label
        for={inputId()}
        class={["kui-textfield__label", { "kui-visually-hidden": props.labelHidden === true }]}
      >
        {props.label}
      </label>
      <div class="kui-textfield__box">
        <Show when={props.icon}>{(name) => <Icon name={name()} class="kui-textfield__glyph" />}</Show>
        <input
          {...omit(
            props,
            "label",
            "labelHidden",
            "icon",
            "hintKey",
            "help",
            "error",
            "size",
            "mono",
            "class",
            "onInput",
            "id",
            "ref",
          )}
          id={inputId()}
          type={p.type}
          class={["kui-textfield__input", { "kui-textfield__input--mono": props.mono === true }]}
          aria-invalid={props.error !== undefined ? "true" : undefined}
          aria-describedby={describedBy()}
          ref={props.ref}
          onInput={(event) => props.onInput?.(event.currentTarget.value, event)}
        />
        {/* Decoration for a shortcut the field's help text already states. A screen reader reading
            "command K" out of the middle of a search box would be noise. */}
        <Show when={props.hintKey}>
          {(hint) => (
            <span class="kui-textfield__hint-key" aria-hidden="true">
              {hint()}
            </span>
          )}
        </Show>
      </div>
      <Show when={props.help}>
        {(help) => (
          <span id={helpId} class="kui-textfield__help">
            {help()}
          </span>
        )}
      </Show>
      {/* `role="alert"` so a validation message that appears after the fact is announced. The
          message is text as well as colour: the red border is never the only signal. */}
      <Show when={props.error}>
        {(error) => (
          <span id={errorId} class="kui-textfield__error" role="alert">
            <Icon name="error" />
            {error()}
          </span>
        )}
      </Show>
    </div>
  );
}
