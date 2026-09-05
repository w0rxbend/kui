/**
 * A media query as a signal.
 *
 * SPEC §7.5 gives three breakpoints and says what changes at each. Two of those changes cannot be
 * made in CSS: below 900px the consumer table drops its COORDINATOR and TOPICS columns, and a
 * column that is `display: none` is still in the document, still read out by a screen reader, and
 * still counted by `aria-colcount`. Dropping it properly means not rendering it, and that is a
 * decision in TypeScript.
 *
 * ## The two mistakes this exists to not make twice
 *
 * **Sampling once at mount.** The same defect as the virtualized table's: `matchMedia(...).matches`
 * read once tells you about the window as it was, and a window that is then resized — or that had
 * not been laid out at mount — leaves the component drawing for a width that is gone. The listener
 * is attached for the lifetime of the component and removed in `onCleanup`.
 *
 * **Assuming `matchMedia` is there.** It is absent in jsdom's older shims and in server rendering.
 * The fallback is the wide branch: a table with all its columns is the honest default, and a
 * narrow-window optimisation that fails open costs a horizontal scrollbar rather than data.
 */

import { createSignal, onSettled, type Accessor } from "solid-js";

/**
 * `true` while the query matches. Reactive, and correct across a resize.
 *
 * The initial value is read synchronously where `matchMedia` exists, so the first paint is already
 * right; the listener is what keeps it right afterwards. Solid 2 batches writes onto a microtask,
 * so a resize handler's write is visible on the next flush and not before — which is fine here,
 * because nothing reads this back within the same tick.
 */
export function createMediaQuery(query: string): Accessor<boolean> {
  const supported = typeof window !== "undefined" && typeof window.matchMedia === "function";
  const list = supported ? window.matchMedia(query) : null;
  const [matches, setMatches] = createSignal(list?.matches ?? false);

  onSettled(() => {
    if (list === null) return undefined;
    const listener = (event: MediaQueryListEvent): void => {
      setMatches(event.matches);
    };
    // Re-read on attach as well as on change: between the synchronous read above and this point,
    // the layout may have settled at a different width.
    setMatches(list.matches);
    list.addEventListener("change", listener);
    // Returned, not registered with `onCleanup`. Solid 2 refuses `onCleanup` inside `onSettled`
    // (`CLEANUP_IN_FORBIDDEN_SCOPE`) and the refusal is not a warning: it throws, halts the
    // reactive system, and every later update is dropped. The listener must come back as the
    // return value instead.
    return () => list.removeEventListener("change", listener);
  });

  return matches;
}

/** The breakpoint SPEC §7.5 puts the drawer overlay and the column dropping behind. */
export const NARROW_QUERY = "(max-width: 899px)";
