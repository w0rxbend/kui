import { describe, expect, it } from "vitest";
import { pageWindow } from "./Pagination.jsx";

/**
 * The paginator's window arithmetic.
 *
 * The same reasoning as `window.test.ts`. Every off-by-one a paginator can have lives in this one
 * function, none of them throws, and on screen they look like a missing page button or a window
 * that stops sliding near the end — which nobody notices in a screenshot of page 1 and everybody
 * notices on page 47. Testing it over integers is cheap; reaching it through the DOM would need a
 * list of four hundred topics.
 */
describe("pageWindow", () => {
  it("returns nothing when there are no pages", () => {
    // An empty list. The caller draws no numbered buttons at all rather than a lone "1", which
    // would be a button that goes where the user already is.
    expect(pageWindow(1, 0)).toEqual([]);
    expect(pageWindow(1, -3)).toEqual([]);
  });

  it("never returns more pages than exist", () => {
    expect(pageWindow(1, 1)).toEqual([1]);
    expect(pageWindow(1, 2)).toEqual([1, 2]);
    expect(pageWindow(2, 3)).toEqual([1, 2, 3]);
    expect(pageWindow(1, 5)).toEqual([1, 2, 3, 4, 5]);
  });

  it("centres the window on the current page once there is room", () => {
    expect(pageWindow(10, 20)).toEqual([8, 9, 10, 11, 12]);
    expect(pageWindow(7, 100)).toEqual([5, 6, 7, 8, 9]);
  });

  it("stops sliding at both ends instead of running off them", () => {
    // Near the start the window cannot centre, so it pins to 1 and keeps its full width — the
    // alternative is a shrinking row of buttons that moves under the pointer as you page.
    expect(pageWindow(1, 20)).toEqual([1, 2, 3, 4, 5]);
    expect(pageWindow(2, 20)).toEqual([1, 2, 3, 4, 5]);
    expect(pageWindow(3, 20)).toEqual([1, 2, 3, 4, 5]);
    expect(pageWindow(4, 20)).toEqual([2, 3, 4, 5, 6]);

    expect(pageWindow(20, 20)).toEqual([16, 17, 18, 19, 20]);
    expect(pageWindow(19, 20)).toEqual([16, 17, 18, 19, 20]);
    expect(pageWindow(18, 20)).toEqual([16, 17, 18, 19, 20]);
    expect(pageWindow(17, 20)).toEqual([15, 16, 17, 18, 19]);
  });

  it("survives a current page outside the range, which happens when the list shrinks", () => {
    // The realistic route in: the user is on page 9, someone deletes ninety topics, and the next
    // response has three pages. Clamping rather than returning an empty window is what stops the
    // paginator disappearing at exactly the moment the user needs it to get back.
    expect(pageWindow(9, 3)).toEqual([1, 2, 3]);
    expect(pageWindow(0, 4)).toEqual([1, 2, 3, 4]);
    expect(pageWindow(-5, 4)).toEqual([1, 2, 3, 4]);
    expect(pageWindow(1000, 20)).toEqual([16, 17, 18, 19, 20]);
  });

  it("always contains the current page when the current page exists", () => {
    for (let count = 1; count <= 30; count++) {
      for (let page = 1; page <= count; page++) {
        expect(pageWindow(page, count)).toContain(page);
      }
    }
  });

  it("is always contiguous, ascending, and inside the range", () => {
    for (let count = 1; count <= 30; count++) {
      for (let page = 1; page <= count; page++) {
        const window = pageWindow(page, count);
        expect(window.length).toBe(Math.min(5, count));
        expect(window[0]).toBeGreaterThanOrEqual(1);
        expect(window[window.length - 1]).toBeLessThanOrEqual(count);
        for (let index = 1; index < window.length; index++) {
          expect(window[index]).toBe((window[index - 1] ?? 0) + 1);
        }
      }
    }
  });

  it("honours a different span", () => {
    expect(pageWindow(10, 20, 3)).toEqual([9, 10, 11]);
    expect(pageWindow(1, 20, 1)).toEqual([1]);
    expect(pageWindow(10, 20, 40)).toEqual(Array.from({ length: 20 }, (_, index) => index + 1));
  });
});
