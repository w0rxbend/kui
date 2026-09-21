/**
 * How KUI prints a quantity, asserted where the quantity lands.
 *
 * ## Why this file did not exist until now
 *
 * `numbers.ts` had no test file at all. Wave 4's census measured what that cost: replacing
 * `share`'s whole body with `return value / of` left the workspace green, and so did replacing
 * `formatDelta`'s signed format with `formatCount`. Its twin one directory over —
 * `charts/format.ts`'s `fraction()`, which states the same denominator rule in the same words —
 * has a test file beside it and caught its own mutation. Two modules stated one rule and only one
 * of them was checked.
 *
 * That is the shape of five of the six rules this packet closes, and it is worth naming: **four of
 * the six are teardown** — what happens on the way out, reached only by close, stop and unmount
 * sequences that no test drives — and **two are formatting helpers whose twin in another module
 * has a test file and is gated**. Neither is laziness. A helper gets a test when it is exported
 * beside one, and a teardown path gets a test when somebody writes the sequence that reaches it.
 *
 * ## Why the share case renders a bar
 *
 * `share`'s only product caller passes the result straight into `MagnitudeBar`'s `fraction`
 * (`feature-clusters/src/BrokerDetail.tsx:172`), and the defect the guard exists for is a *width*:
 * a zero denominator makes `4212 / 0` Infinity, `MagnitudeBar.percentage` clamps that to `1`, and
 * the stylesheet paints a full bar for a quantity nothing measured. Asserting the returned number
 * on its own would miss half the rule — `0 / 0` is NaN, and `percentage` catches NaN by itself —
 * so the case is written at the seam the product composes, with the two inputs only `share` can
 * stop.
 */
import { describe, expect, it } from "vitest";

import { MagnitudeBar } from "./components/MagnitudeBar.jsx";
import { mount } from "./components/testing.js";
import { formatCount, formatDelta, formatRate, share, MISSING } from "./numbers.js";

/** The width `MagnitudeBar` asked the stylesheet for: where an unguarded share becomes visible. */
function fillWidth(container: HTMLElement): string {
  return (container.querySelector(".kui-magnitude__fill") as HTMLElement).style.width;
}

describe("share", () => {
  it("an unknown share draws an empty bar and not a full one", () => {
    // A broker whose largest log directory could not be read leaves the denominator at zero, and
    // `4212 / 0` is Infinity: it fails every comparison, so `Math.min`/`Math.max` pass it through
    // and the row nothing could be measured against draws as the biggest one on the screen.
    const noDenominator = mount(() => <MagnitudeBar inline value="" fraction={share(4_212, 0)} />);
    expect(fillWidth(noDenominator.container)).toBe("0%");
    noDenominator.dispose();

    // The other half of the same guard, and the half `MagnitudeBar` cannot make for itself: its
    // own clamp catches NaN and nothing else, so an infinite numerator arrives as a full track.
    const noValue = mount(() => (
      <MagnitudeBar inline value="" fraction={share(Number.POSITIVE_INFINITY, 100)} />
    ));
    expect(fillWidth(noValue.container)).toBe("0%");
    noValue.dispose();
  });

  it("a share of a negative quantity draws an empty bar", () => {
    // Both sides negative is the case the guard is written as `of <= 0` rather than `of === 0`
    // for, and the arithmetic is what makes it dangerous rather than merely odd: `-4212 / -100`
    // is `42.12`, which clamps to a **full** track. A denominator that came back negative is a
    // figure nothing measured — a byte total read as a difference, a capacity from a broker that
    // answered garbage — and a full bar is the loudest possible claim to make about it.
    const bothNegative = mount(() => (
      <MagnitudeBar inline value="" fraction={share(-4_212, -100)} />
    ));
    expect(fillWidth(bothNegative.container)).toBe("0%");
    bothNegative.dispose();

    // And with only the denominator negative, where the quotient is negative and `MagnitudeBar`'s
    // own clamp would have caught it: the two halves are asserted separately so that neither can
    // stand in for the other.
    const negativeDenominator = mount(() => (
      <MagnitudeBar inline value="" fraction={share(4_212, -100)} />
    ));
    expect(fillWidth(negativeDenominator.container)).toBe("0%");
    negativeDenominator.dispose();
  });

  it("draws a share that is real, and clamps one that is out of range", () => {
    // The refusal above is only honest beside this: the guard must not be a helper that always
    // answers zero.
    const quarter = mount(() => <MagnitudeBar inline value="" fraction={share(25, 100)} />);
    expect(fillWidth(quarter.container)).toBe("25%");
    quarter.dispose();

    expect(share(25, 100)).toBe(0.25);
    // A stale denominator gives a full bar rather than one painting outside its own track, and a
    // negative numerator gives an empty one rather than a bar drawn backwards.
    expect(share(150, 100)).toBe(1);
    expect(share(-5, 100)).toBe(0);
  });
});

describe("formatDelta", () => {
  it("a positive delta carries its sign", () => {
    // The offset-reset preview decides exactly one thing: whether this rewinds 4,212 records or
    // skips them. A `+` dropped because the number is positive makes the two rows identical, and
    // the preview is then a table of numbers with no direction in it at all.
    expect(formatDelta(4_212)).toBe("+4,212");
    expect(formatDelta(-4_212)).toBe("-4,212");
    expect(formatDelta(4_212)).not.toBe(formatDelta(-4_212));

    // Zero is neither direction, so it carries no sign: `+0` would be a claim about a movement
    // that did not happen.
    expect(formatDelta(0)).toBe("0");
    // A delta that could not be computed is the em dash. "No value" and "a value of nothing" are
    // different facts about a group, which is the whole reason `MISSING` exists.
    expect(formatDelta(Number.NaN)).toBe(MISSING);
  });
});

describe("the other two formatters", () => {
  it("groups thousands in one pinned locale, and refuses a number it does not have", () => {
    // Pinned to `en-US` rather than the browser's: a locale that groups with spaces makes an
    // offset ambiguous the moment it is pasted into a shell.
    expect(formatCount(4_212)).toBe("4,212");
    expect(formatCount(42_120)).toBe("42,120");
    expect(formatCount(Number.NaN)).toBe(MISSING);
    expect(formatCount(Number.POSITIVE_INFINITY)).toBe(MISSING);
  });

  it("keeps a rate's sign and its one decimal place", () => {
    // A negative rate is committed offsets moving backwards — somebody else's reset, seen from
    // this screen — and noticing it is most of the value of printing it.
    expect(formatRate(-4.26)).toBe("-4.3");
    expect(formatRate(1_204.7)).toBe("1,205");
    expect(formatRate(Number.NaN)).toBe(MISSING);
  });
});
