/**
 * The button.
 *
 * ## Four variants, and why the destructive one looks different
 *
 * This project shipped a defect that cost it: **delete, empty and add-partitions looked identical
 * to one another and to ordinary actions.** An operator scanning a row of buttons had nothing but
 * the words to tell "show me this" from "destroy this", and the words are the thing you read last.
 *
 * The answer (SPEC §4.13) is a silhouette, not a colour:
 *
 *   - `primary` and `secondary` are filled. They differ from each other in *which* pair fills
 *     them, and both read as "a thing to press".
 *   - `danger` is **outlined**, and it is outlined rather than filled on purpose. A filled red
 *     button is the loudest thing on a page, which sends the eye first to the action you least
 *     want taken by accident. The outline gives it a shape no other button has without competing
 *     for the first glance.
 *   - `ghost` is unfilled and quiet: toolbar and row actions.
 *
 * Because an outline alone is a colour-only distinction, and around one man in twelve cannot
 * separate the red from the green, **`danger` requires an `icon`**. That is enforced by the type
 * below, not by a comment: `ButtonProps` for the danger variant does not typecheck without one.
 *
 * ## Disabled means "disabled, and here is why"
 *
 * An action the current principal may not take is rendered disabled with a reason, never hidden —
 * a hidden button makes an operator think the product cannot do the thing at all (SPEC §4.13).
 * So `disabledReason` is required whenever `disabled` is set, and the button is marked with
 * `aria-disabled` rather than the `disabled` attribute.
 *
 * That choice is deliberate and it has a cost worth stating. `aria-disabled` does not stop the
 * browser dispatching clicks, so this component swallows them itself. What it buys is that the
 * control **stays focusable**: a `disabled` element is skipped by Tab and fires no pointer events,
 * so its explanation is unreachable by keyboard and unreachable by hover — which is to say the
 * reason exists and nobody can read it.
 *
 * ## Busy keeps its width
 *
 * When `busy` is set the leading glyph is replaced by a spinner and the label stays. The button
 * does not shrink to a spinner, because a button that changes size mid-press moves whatever is
 * next to it out from under the pointer, and the second thing the operator clicks is not the thing
 * they aimed at.
 */
import type { JSX } from "@solidjs/web";
import { Show, merge, omit } from "solid-js";
import { Icon, type IconName } from "../icon.jsx";
import { Spinner } from "./Spinner.jsx";
import { Tooltip } from "./Tooltip.jsx";

export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "sm" | "md";

interface ButtonBase {
  readonly size?: ButtonSize | undefined;
  readonly icon?: IconName | undefined;
  /**
   * An icon-only button still needs a name that says the *action*, not the picture:
   * "Notifications, 3 unread", never "bell". Required, because there is nothing else to read.
   */
  readonly iconOnly?: boolean | undefined;
  readonly busy?: boolean | undefined;
  /**
   * A machine-readable reason shown under the disabled explanation. It sits on the base rather
   * than on the disabled branch of the union so that `omit` and `merge` can see it: TypeScript
   * cannot read a property off a discriminated union that only one arm declares.
   */
  readonly disabledCode?: string | undefined;
  readonly type?: "button" | "submit" | "reset" | undefined;
  readonly class?: string | undefined;
  readonly onClick?: (event: MouseEvent) => void;
  readonly children?: JSX.Element | undefined;
}

/** A disabled button must carry the reason it is disabled. The type is where that is enforced. */
type Disablement =
  | { readonly disabled?: false; readonly disabledReason?: never }
  | { readonly disabled: true; readonly disabledReason: string };

/** `danger` requires a glyph, because the outline alone is a colour-only distinction. */
type Variance =
  | { readonly variant?: "primary" | "secondary" | "ghost" }
  | { readonly variant: "danger"; readonly icon: IconName };

export type ButtonProps = ButtonBase & Disablement & Variance;

export function Button(props: ButtonProps): JSX.Element {
  const p = merge({ variant: "primary", size: "md", type: "button" } as const, props);
  const inert = (): boolean => p.disabled === true || p.busy === true;

  const button = (
    <button
      {...omit(
        props,
        "variant",
        "size",
        "icon",
        "iconOnly",
        "busy",
        "disabled",
        "disabledReason",
        "disabledCode",
        "class",
        "onClick",
        "children",
      )}
      type={p.type}
      class={[
        "kui-btn",
        "kui-focusable",
        `kui-btn--${p.variant}`,
        `kui-btn--${p.size}`,
        { "kui-btn--icon-only": p.iconOnly === true },
        p.class,
      ]}
      aria-disabled={p.disabled === true ? "true" : undefined}
      aria-busy={p.busy === true ? "true" : undefined}
      onClick={(event) => {
        // `aria-disabled` is a statement to assistive technology; it does not stop the browser
        // dispatching the click. Swallowing it here is what makes the statement true. `busy` is
        // swallowed for the same reason: a second press while the first is in flight is a
        // duplicate mutation, and on this product's actions that matters.
        if (inert()) {
          event.preventDefault();
          event.stopPropagation();
          return;
        }
        p.onClick?.(event);
      }}
    >
      <Show when={p.busy === true} fallback={<Show when={p.icon}>{(name) => <Icon name={name()} />}</Show>}>
        <Spinner />
      </Show>
      {/* An icon-only button's label is its accessible name and nothing else, so it is not in the
          box. It is still in the document — visually hidden text, not an `aria-label` — so that it
          survives translation tooling and shows up in a text search of the page. */}
      <Show when={p.iconOnly !== true} fallback={<span class="kui-visually-hidden">{p.children}</span>}>
        <span class="kui-btn__label">{p.children}</span>
      </Show>
    </button>
  );

  // The tooltip wrapper is always present rather than switched in when the button becomes
  // disabled. Swapping a live DOM node between a `<Show>`'s two branches works, but it moves the
  // element the user may currently be pointing at or has focus in, and permissions arrive
  // asynchronously — so the swap would happen exactly while somebody is looking at the button.
  // The wrapper renders nothing of its own when `disabled` is false.
  return (
    <Tooltip
      content={p.disabledReason ?? ""}
      code={p.disabledCode}
      disabled={p.disabled !== true || p.disabledReason === undefined}
    >
      {button}
    </Tooltip>
  );
}
