import { createSignal, onSettled } from "solid-js";

/**
 * Whether the operator has asked for compact rows.
 *
 * ## Why this reads the DOM instead of a store
 *
 * Density is one of the three axes written onto `<html>` as a data attribute (`10-tokens.css`),
 * alongside theme and accent. The stylesheet reads it from there, so the arithmetic reads it from
 * there too: one source, and no way for the CSS and the JavaScript to hold different opinions
 * about how tall a row is. That is the same reasoning as the row-height custom property in
 * `VirtualizedTable`, applied one level up.
 *
 * ## Why it is observed and not sampled
 *
 * The attribute changes while the page is up — the operator flips the switch in Settings, or, in
 * Storybook, in the toolbar. A value read once at mount would leave every table already on screen
 * at the old height while the stylesheet moved to the new one, which in a *windowed* table is not
 * a cosmetic difference: the arithmetic would be computing a window for 48px rows that the CSS is
 * drawing at 36px, and the bottom third of the list would be blank.
 *
 * A `MutationObserver` is used rather than a custom event because the attribute is the contract.
 * Anything that sets it — the Settings switch, a test, the Storybook toolbar, a person typing in
 * the developer console — is picked up, and nothing has to remember to announce itself.
 */
export function createIsCompact(): () => boolean {
  const read = (): boolean =>
    typeof document !== "undefined" &&
    document.documentElement.getAttribute("data-density") === "compact";

  const [isCompact, setIsCompact] = createSignal(read());

  onSettled(() => {
    if (typeof MutationObserver === "undefined") return undefined;
    const observer = new MutationObserver(() => setIsCompact(read()));
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-density"],
    });
    // Re-read on connect as well as on change: the attribute may have been set between the
    // component body running and this callback, and a first read that was already stale would not
    // be corrected until somebody happened to change it again.
    setIsCompact(read());
    return () => observer.disconnect();
  });

  return isCompact;
}

/**
 * How much shorter a compact row is.
 *
 * The design says compact moves the row's vertical padding from 15px to 9px and nothing else — not
 * the type size, not the control heights, because shrinking those makes an interface harder to
 * hit rather than denser. Six pixels off each of the two edges is twelve off the row.
 *
 * In an ordinary table that is purely a stylesheet change (`--kui-density-row-padding-y`). In a
 * windowed table it cannot be, because the window arithmetic is done from the row height: a
 * stylesheet that shortened the rows without telling the arithmetic would leave the component
 * rendering a screenful for a viewport that now holds a third more. So the switch moves both
 * numbers, from here.
 */
export const COMPACT_ROW_SAVING_PX = 12;
