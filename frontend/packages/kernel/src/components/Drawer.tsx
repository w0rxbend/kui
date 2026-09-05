/**
 * The drawer: a second surface beside the thing you were looking at.
 *
 * ## How it differs from a dialog
 *
 * Only in shape and purpose. A dialog interrupts to ask a question and goes away; a drawer is a
 * place to work — compose a message, edit a configuration — and stays open while you do. All the
 * modal behaviour is identical and is shared through `overlay.ts`: the veil, the focus trap,
 * `Escape`, and focus going back to whatever opened it.
 *
 * ## The one rule this component is built around
 *
 * **A drawer must not rebuild while somebody is typing in it.**
 *
 * This is a defect this project shipped. It looks like this: the operator opens the produce
 * drawer, starts typing a JSON payload, and the list of available serdes — which was still being
 * fetched when the drawer opened — arrives. If the form is inside a boundary whose condition is
 * "have the serdes arrived", the boundary swaps its content, the textarea is a *new* element, and
 * the payload is gone. Nothing errors. The operator retypes it, and the second time they are angry.
 *
 * Three consequences, and this component enforces the first two:
 *
 *   1. **The drawer takes no `state` prop.** There is no `loading`, no `unavailable`, no
 *      `<Loading>` around the body. If a drawer offered a whole-body loading state, somebody would
 *      use it, and the form would be inside it. Async regions belong around the *selects*, added
 *      by the caller, never around the editors.
 *   2. **The body is rendered once and updated in place.** `props.children` is placed directly, not
 *      behind a `<Show>` that depends on anything that changes after opening.
 *   3. (For the caller) the editor's value lives in a store the async updates do not replace
 *      wholesale. Solid's fine-grained reactivity gives you this for free *if* the form stays
 *      outside the boundary that re-renders; putting it inside is how the defect comes back.
 *
 * ## Clicking the veil does not close it
 *
 * The default is the opposite of a dialog's, and for the same reason the rule above exists: a
 * drawer usually holds something somebody typed. Throwing away a composed payload because a click
 * landed forty pixels to the left is the worst thing this surface can do. `Escape` and the close
 * button remain, and both are deliberate acts.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Portal } from "@solidjs/web";
import { Icon } from "./Icon.jsx";
import { modalBehaviour, scrimClickHandler } from "./overlay.js";

export interface DrawerProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  /** One sentence under the title. Becomes the drawer's accessible description. */
  readonly description?: string | undefined;
  /** Which edge it slides from. Right is the default and is what the produce drawer uses. */
  readonly side?: "right" | "left" | undefined;
  /** Any CSS length. Defaults to the 560px the design draws. */
  readonly width?: string | undefined;
  /** The footer: the primary action and Cancel. Stays visible while the body scrolls. */
  readonly footer?: JSX.Element | undefined;
  /**
   * A failure from the last submission. Rendered above the footer, and the drawer **stays open**
   * with everything the operator composed still in it.
   */
  readonly error?: { readonly message: string; readonly code?: string | undefined } | undefined;
  /**
   * Opt back in to closing on a click outside. Only for a drawer that holds nothing the operator
   * typed — a read-only detail panel.
   */
  readonly closeOnScrimClick?: boolean | undefined;
  readonly initialFocus?: (() => HTMLElement | null | undefined) | undefined;
  readonly testId?: string | undefined;
  readonly children?: JSX.Element;
}

let sequence = 0;
function nextId(prefix: string): string {
  sequence += 1;
  return `${prefix}-${sequence}`;
}

export function Drawer(props: DrawerProps): JSX.Element {
  const titleId = nextId("kui-sheet-title");
  const descriptionId = nextId("kui-sheet-desc");


  // Held so the veil handler can tell "clicked the veil" from "clicked the surface".
  let scrim: HTMLElement | undefined;
  return (
    <Show when={props.open}>
      <Portal mount={document.body}>
        <div
          class="kui-sheet-scrim"
          ref={(element: HTMLElement) => (scrim = element)}
          onClick={scrimClickHandler(() => scrim, {
            onClose: () => props.onClose(),
            // Note the default: unlike a dialog, a click outside is ignored unless asked for.
            closeOnScrimClick: props.closeOnScrimClick === true,
          })}
        >
          <div
            class={["kui-sheet", `kui-sheet--${props.side ?? "right"}`]}
            style={props.width === undefined ? undefined : { width: props.width }}
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            aria-describedby={props.description === undefined ? undefined : descriptionId}
            data-testid={props.testId}
            ref={modalBehaviour({
              onClose: () => props.onClose(),
              ...(props.initialFocus !== undefined ? { initialFocus: props.initialFocus } : {}),
            })}
          >
            {/* A `div`, not a `<header>`. A `<header>` that is not inside an article, aside, main, nav
                or section is a *banner* landmark, and a page with two banner landmarks — the
                application's top bar and this one — is a page whose landmark navigation is
                broken. The same argument applies to the actions row below. */}
            <div class="kui-sheet__header">
              <div class="kui-sheet__heading">
                <h2 class="kui-sheet__title" id={titleId}>
                  {props.title}
                </h2>
                <Show when={props.description}>
                  {(description) => (
                    <p class="kui-sheet__description" id={descriptionId}>
                      {description()}
                    </p>
                  )}
                </Show>
              </div>
              <button
                type="button"
                class="kui-sheet__close kui-focusable"
                aria-label={`Close ${props.title}`}
                onClick={() => props.onClose()}
              >
                <Icon name="close" />
              </button>
            </div>

            {/*
             * The body scrolls inside its own box — `overflow: auto` with `min-height: 0` in the
             * stylesheet — so the header and the footer stay put. `min-height: 0` is not optional:
             * a flex child will not shrink below its content unless told it may, and a scroller
             * that cannot shrink never scrolls, it just grows and pushes the footer off screen.
             *
             * `props.children` goes in directly. Nothing conditional wraps it. See the note at the
             * top of this file.
             */}
            <div class="kui-sheet__body">{props.children}</div>

            <Show when={props.error}>
              {(error) => (
                <p class="kui-sheet__error" role="alert">
                  <Icon name="error" class="kui-sheet__error-glyph" />
                  <span>{error().message}</span>
                  <Show when={error().code}>{(code) => <code class="kui-sheet__error-code">{code()}</code>}</Show>
                </p>
              )}
            </Show>

            <Show when={props.footer}>{(footer) => <div class="kui-sheet__footer">{footer()}</div>}</Show>
          </div>
        </div>
      </Portal>
    </Show>
  );
}
