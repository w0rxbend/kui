/**
 * The behaviour every modal surface shares: the dialog and the drawer.
 *
 * ## Why a dialog and a drawer share one module
 *
 * They are the same object with two shapes. Both take the whole window's attention, both put a
 * veil over the page, both trap the keyboard, both close on `Escape`, and both give focus back to
 * whatever opened them. The only differences are where they sit and what they are for — a dialog
 * interrupts to ask a question, a drawer opens a second surface beside the thing you were looking
 * at — and neither of those differences is behavioural. Written twice, the two copies drift, and
 * the half that drifts is always the focus restoration, because nothing on screen shows it is
 * broken.
 *
 * ## What "trapped" has to mean
 *
 * Three things, and all three are load-bearing:
 *
 *   1. **Focus starts inside.** A dialog that opens with focus still on the button behind it means
 *      a keyboard user's next `Tab` goes to the page, not to the dialog, and a screen-reader user
 *      is told nothing has happened.
 *   2. **Focus cannot leave by `Tab`.** Tabbing past the last control wraps to the first;
 *      shift-tabbing before the first wraps to the last. Otherwise the user walks out into a page
 *      they cannot see and cannot get back from.
 *   3. **Focus goes home on close.** Back to the element that opened the surface. Losing it drops
 *      the user at the top of the document, which after closing a confirmation on row 400 of a
 *      table is a small catastrophe.
 *
 * ## What this module deliberately does not do
 *
 * It does not use the native `<dialog>` element. `showModal()` gives the trap and the top layer
 * for free, and it also gives an unstyleable `::backdrop` in some engines, a `cancel` event whose
 * default has to be prevented to keep `Escape` under our control, and — the reason that decides it
 * — no way to render the surface *inside* the reactive tree while keeping it in the top layer, so
 * a Solid `<Show>` around a `<dialog>` ends up fighting an imperative open/close API for
 * ownership of the same boolean. Two sources of truth for "is this open" is exactly the bug class
 * this whole file exists to avoid.
 */
import { onSettled } from "solid-js";

/**
 * Everything the browser will put focus on. `:not([tabindex="-1"])` matters: a programmatically
 * focusable element (a listbox option, a row) is reachable by script but must not be a `Tab` stop.
 */
const FOCUSABLE =
  'a[href], area[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), ' +
  "select:not([disabled]), textarea:not([disabled]), iframe, object, embed, " +
  '[contenteditable="true"], [tabindex]:not([tabindex="-1"])';

/**
 * The focusable descendants of `root`, in document order, skipping anything that is not really
 * there.
 *
 * The visibility test is `checkVisibility()` where the engine has it, and the two attributes
 * otherwise. It is deliberately **not** `offsetParent !== null`, which is the obvious-looking
 * check and is wrong twice: `offsetParent` is null for any `position: fixed` element — which every
 * modal surface in this product is — and it is null for *everything* in jsdom, which has no layout
 * engine at all, so the trap would silently find no stops in every test that exercises it.
 */
export function focusableWithin(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) => {
    if (element.hasAttribute("hidden")) return false;
    if (element.getAttribute("aria-hidden") === "true") return false;
    const check = (element as { checkVisibility?: () => boolean }).checkVisibility;
    return typeof check === "function" ? check.call(element) : true;
  });
}

export interface ModalBehaviourOptions {
  /** Called for `Escape`, for a click on the veil, and for the close button. */
  readonly onClose: () => void;
  /**
   * Where focus lands when the surface opens.
   *
   * The default is **the surface itself**, which a screen reader reads as the dialog's name and
   * description before anything else. Focusing the first control instead would be the obvious
   * choice and is worse: the reader hears "Close button" and has to go looking for what it is
   * they have been asked.
   *
   * Pass this whenever there is a control the operator obviously wants — the name field in a
   * create form — and pass it for every destructive confirmation, where focus belongs on
   * **Cancel**: a stray `Enter` left over from the keystroke that opened the dialog must not be
   * the keystroke that confirms it.
   */
  readonly initialFocus?: () => HTMLElement | null | undefined;
  /** Set false to let a click on the veil do nothing — for a form with unsaved input. */
  readonly closeOnScrimClick?: boolean;
}

/**
 * Install modal behaviour on a surface. Returns a `ref` callback to put on it.
 *
 * **Call this from a component body, not from inside a handler or another `onSettled`.** The
 * lifecycle is registered here, at call time, and Solid 2 refuses a cleanup returned from an
 * unowned scope with `SETTLED_CLEANUP_UNOWNED` — which is a good error, because a modal whose
 * cleanup was dropped is a page left permanently unscrollable with a focus trap still running.
 *
 * The element itself arrives later, through the returned ref. That is why the setup is inside
 * `onSettled` rather than inside the ref callback: at the moment the ref fires, the surface's
 * children are not in the document yet, so "the first focusable element" does not exist.
 */
export function modalBehaviour(options: ModalBehaviourOptions) {
  let surface: HTMLElement | undefined;

  // Captured now, before anything moves focus. `document.activeElement` is `<body>` when nothing
  // was focused, and focusing `<body>` on the way out is the same as focusing nothing.
  const opener = document.activeElement;

  onSettled(() => {
    if (surface === undefined) return;
    const element = surface;

    const target = options.initialFocus?.() ?? element;
    if (target === element && !element.hasAttribute("tabindex")) {
      // The surface has to be focusable to receive focus at all. `-1` gives it that without
      // putting it in the tab order, so it is a destination and never a stop.
      element.setAttribute("tabindex", "-1");
    }
    target.focus();

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault();
        // `stopPropagation` so a dialog opened from inside a drawer closes only itself.
        event.stopPropagation();
        options.onClose();
        return;
      }
      if (event.key !== "Tab") return;

      const stops = focusableWithin(element);
      if (stops.length === 0) {
        event.preventDefault();
        element.focus();
        return;
      }
      const first = stops[0] as HTMLElement;
      const last = stops[stops.length - 1] as HTMLElement;
      const active = document.activeElement;

      if (event.shiftKey && (active === first || active === element)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    // On `document`, not on the surface. `Escape` has to work even when focus is momentarily
    // somewhere the trap did not put it — on the veil after a click, or on `<body>` after a
    // control inside the surface was removed while focused. A listener bound to the surface only
    // hears keys pressed inside it, and the symptom is a dialog that ignores `Escape` about one
    // time in twenty with nothing in the code looking wrong.
    document.addEventListener("keydown", onKeyDown);

    // The page behind must not scroll under the surface. Restored to whatever it was rather than
    // to "", because a nested overlay would otherwise unlock the page when the inner one closes
    // and leave the outer one floating over a scrolling document.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });

  return (element: HTMLElement): void => {
    surface = element;
  };
}

/**
 * The click handler for the veil.
 *
 * Only a click whose target *is* the veil closes anything. A click that landed on the surface and
 * bubbled out — or a text selection dragged from an input out past the edge, which is a thing
 * people do — must not close the surface and throw away what they typed.
 *
 * The veil is identified by the element passed in rather than by `event.currentTarget`. Solid
 * delegates most events from the document root, and a delegated handler's `currentTarget` is not
 * reliably the element the handler was written on; comparing against it silently never matches,
 * and the symptom is a veil that cannot be clicked with nothing in the code looking wrong.
 */
export function scrimClickHandler(scrim: () => HTMLElement | undefined, options: ModalBehaviourOptions) {
  return (event: MouseEvent): void => {
    if (options.closeOnScrimClick === false) return;
    if (event.target === scrim()) options.onClose();
  };
}
