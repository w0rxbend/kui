import { describe, expect, it } from "vitest";
import { endIndex, MAX_SCROLL_HEIGHT_PX, slice, trailingHeightPx } from "./window.js";

/**
 * The window arithmetic.
 *
 * Every off-by-one a virtualizer can have lives in `slice`, and not one of them throws. They show
 * up on screen as a flickering row, a blank strip at the bottom of a fast scroll, or a scrollbar
 * that lies about how much list there is — none of which a screenshot catches and all of which a
 * test over five integers catches. That is the whole reason this function has no DOM in it.
 */
describe("slice", () => {
  const rowHeight = 48;
  const viewport = 480; // exactly ten rows

  it("renders nothing, and does not divide by zero, when there is nothing to render", () => {
    expect(slice(0, viewport, rowHeight, 3, 0)).toEqual({
      firstIndex: 0,
      count: 0,
      offsetPx: 0,
      totalHeightPx: 0,
    });
    // A viewport with no height is a tab that is hidden, not an error.
    expect(slice(0, 0, rowHeight, 3, 100).count).toBe(0);
    expect(slice(0, viewport, 0, 3, 100).count).toBe(0);
  });

  it("claims the whole list's height, so the scrollbar is honest", () => {
    expect(slice(0, viewport, rowHeight, 3, 10_000).totalHeightPx).toBe(480_000);
  });

  it("includes the partly visible row at the bottom", () => {
    // A viewport 481px tall over 48px rows shows ten whole rows and a sliver of an eleventh.
    // Rounding down here is the off-by-one that draws a blank strip along the bottom edge.
    const cut = slice(0, 481, rowHeight, 0, 100);
    expect(endIndex(cut)).toBe(11);
  });

  it("adds overscan on both sides, and clamps it at the ends of the list", () => {
    const middle = slice(rowHeight * 50, viewport, rowHeight, 3, 100);
    expect(middle.firstIndex).toBe(47);
    expect(endIndex(middle)).toBe(63);

    // At the top there is nothing above to overscan into, and a negative first index would slice
    // from the end of the array.
    expect(slice(0, viewport, rowHeight, 3, 100).firstIndex).toBe(0);
    // At the bottom the window must not run past the list.
    const bottom = slice(rowHeight * 100, viewport, rowHeight, 3, 100);
    expect(endIndex(bottom)).toBe(100);
  });

  it("puts the rendered rows exactly where they would have been", () => {
    const cut = slice(rowHeight * 50, viewport, rowHeight, 3, 100);
    expect(cut.offsetPx).toBe(cut.firstIndex * rowHeight);
  });

  it("adds up: the two spacers and the rendered rows fill the claimed height", () => {
    for (const scrollTop of [0, 137, 4800, 47_000]) {
      const cut = slice(scrollTop, viewport, rowHeight, 3, 1000);
      expect(cut.offsetPx + cut.count * rowHeight + trailingHeightPx(cut, rowHeight)).toBe(
        cut.totalHeightPx,
      );
    }
  });

  it("shows the last screenful when the list shrinks under a scrolled viewport", () => {
    // A live tail dropped old records while the reader was near the bottom, so the container is
    // holding a scroll position that no longer exists. "None" would draw a blank area under a
    // scrollbar that says there is content there; the last screenful is the honest answer.
    const cut = slice(999_999, viewport, rowHeight, 3, 20);
    expect(cut.count).toBeGreaterThan(0);
    expect(endIndex(cut)).toBe(20);
  });

  it("treats a negative scroll position as the top", () => {
    // Elastic overscroll on a trackpad reports a negative scrollTop for a few frames.
    expect(slice(-300, viewport, rowHeight, 3, 100).firstIndex).toBe(0);
  });

  it("never claims a height a layout engine will refuse", () => {
    const cut = slice(0, viewport, rowHeight, 3, 100_000_000);
    expect(cut.totalHeightPx).toBe(MAX_SCROLL_HEIGHT_PX);
    // And the trailing spacer never goes negative, which a browser would silently read as zero.
    expect(trailingHeightPx(cut, rowHeight)).toBeGreaterThanOrEqual(0);
  });

  it("draws every row of a short list in a tall box, with nothing left over", () => {
    // The twelve-partition topic. Twelve rows fit in 480px twice over, so all twelve are rendered
    // and both spacers are empty. Rendering five here is the defect this component was fixed for.
    const cut = slice(0, viewport, rowHeight, 3, 12);
    expect(cut.count).toBe(12);
    expect(cut.offsetPx).toBe(0);
    expect(trailingHeightPx(cut, rowHeight)).toBe(0);
  });

  it("renders a full window at compact density from the same call", () => {
    // The same 480px box holds ten comfortable rows and thirteen compact ones. If the stylesheet
    // moved to 36px and the arithmetic stayed at 48, the bottom of the list would be blank.
    expect(slice(0, viewport, 48, 0, 100).count).toBe(10);
    expect(slice(0, viewport, 36, 0, 100).count).toBe(14);
  });
});
