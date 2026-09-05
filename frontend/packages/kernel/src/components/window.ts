/**
 * The arithmetic behind a windowed list, with no DOM and no reactivity in it.
 *
 * ## Why this is a plain module and not part of the component
 *
 * Every off-by-one a virtualizer can have lives in this one function, and none of them throws.
 * They appear on screen as a flickering row, a blank strip along the bottom of a fast scroll, or a
 * scrollbar that lies about how much list there is. A screenshot cannot catch any of those; a test
 * over five integers catches all of them. So the five integers are the whole interface — no
 * element, no signal, no component.
 *
 * ## The model
 *
 * Every row is the same height. The list is therefore `total * rowHeight` pixels tall, and which
 * rows are on screen is division.
 *
 * That fixed height is a deliberate trade. A virtualizer that *measured* its rows could show rows
 * of different heights, but then every scroll event would have to read layout back out of the
 * browser, and its cost would depend on what happened to be in the rows. A fixed height makes the
 * cost constant and, more to the point, predictable: a virtualizer that is fast in a test and slow
 * on the one cluster that matters is not a virtualizer.
 *
 * Ported from `frontend/ui-kernel/src/kui/ui/kernel/component/Window.scala`, whose behaviour this
 * reproduces exactly, including the clamping and the ceiling division.
 */

/** Which rows to render, where to put them, and how tall to claim the list is. */
export interface Slice {
  /** The index, in the whole list, of the first row to render. */
  readonly firstIndex: number;
  /** How many rows to render from there. Never more than `total`. */
  readonly count: number;
  /**
   * How far down the full list the first rendered row starts: `firstIndex * rowHeight`. The
   * rendered rows are pushed down by this much, so each one sits exactly where it would have been
   * if every row were in the document.
   */
  readonly offsetPx: number;
  /**
   * The height the scroll container is told its content has. This is what makes the scrollbar
   * honest: it is the length of the whole list, not of the window.
   */
  readonly totalHeightPx: number;
}

/** One past the last rendered index — the exclusive end, which is what every loop actually wants. */
export function endIndex(slice: Slice): number {
  return slice.firstIndex + slice.count;
}

/**
 * The height of everything below the window, which the trailing spacer has to take up.
 *
 * Computed here rather than at the call site because it is the one place the three numbers can
 * disagree, and a negative height silently collapses to zero in the browser rather than erroring.
 */
export function trailingHeightPx(slice: Slice, rowHeight: number): number {
  return Math.max(0, slice.totalHeightPx - slice.offsetPx - slice.count * rowHeight);
}

/**
 * The full list's height, clamped.
 *
 * Ten million rows at forty-eight pixels is four hundred and eighty million, which a browser will
 * take. A number large enough to lose integer precision, or one a layout engine refuses, gives a
 * container with no height at all — which reads as "there is no content" rather than as an error.
 * Clamping to a value browsers still lay out gives a scrollbar that is merely wrong about how far
 * past the end of the world the list goes, which is the better of the two failures.
 */
function contentHeightPx(rowHeight: number, total: number): number {
  if (rowHeight <= 0 || total <= 0) return 0;
  return Math.min(rowHeight * total, MAX_SCROLL_HEIGHT_PX);
}

/**
 * The tallest content height this component will claim.
 *
 * Browsers cap the height of a scrollable box somewhere between 1.5e7 and 3.3e7 CSS pixels
 * depending on the engine, and past the cap the scrollbar silently stops mapping to the content.
 * Sixteen million is under every engine's cap, and at a 48px row it is 350,000 rows — far past the
 * point where anyone is scrolling rather than filtering.
 */
export const MAX_SCROLL_HEIGHT_PX = 16_000_000;

/** Nothing to render: an empty list, a zero row height, or a viewport with no height because its
 * tab is hidden. Rendering nothing is correct in all three, and none of them divides by zero. */
const nothing: Slice = { firstIndex: 0, count: 0, offsetPx: 0, totalHeightPx: 0 };

/**
 * The rows to render for a given scroll position.
 *
 * `scrollTop` is **clamped** to the furthest the container can actually be scrolled before
 * anything else is computed. A browser will not scroll past the end, but a list that shrinks under
 * a scrolled viewport — a live tail that drops old records, a filter that suddenly matches fewer —
 * leaves the container holding a scroll position that no longer exists. The honest answer to
 * "which rows are visible at a position past the end" is "the last screenful", not "none": "none"
 * renders a blank area under a scrollbar that says there is content there.
 *
 * @param overscan extra rows above and below the viewport, so a fast scroll reveals a row that is
 *   already in the document instead of a gap that is filled a frame later.
 */
export function slice(
  scrollTop: number,
  viewportHeight: number,
  rowHeight: number,
  overscan: number,
  total: number,
): Slice {
  if (rowHeight <= 0 || total <= 0 || viewportHeight <= 0) {
    return { ...nothing, totalHeightPx: contentHeightPx(rowHeight, total) };
  }

  const safeOverscan = Math.max(0, Math.trunc(overscan));
  const totalHeightPx = contentHeightPx(rowHeight, total);
  const maxScrollTop = Math.max(0, totalHeightPx - viewportHeight);
  const top = Math.min(Math.max(0, scrollTop), maxScrollTop);

  const firstVisible = Math.floor(top / rowHeight);
  // Exclusive, and rounded *up*: a viewport whose bottom edge falls inside a row still shows part
  // of that row, and leaving it out is the off-by-one that draws a blank strip along the bottom.
  const endVisible = Math.ceil((top + viewportHeight) / rowHeight);

  const firstIndex = Math.max(0, firstVisible - safeOverscan);
  const lastIndex = Math.min(total, endVisible + safeOverscan);

  return {
    firstIndex,
    count: Math.max(0, lastIndex - firstIndex),
    offsetPx: firstIndex * rowHeight,
    totalHeightPx,
  };
}
