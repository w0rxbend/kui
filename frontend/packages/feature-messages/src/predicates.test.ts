/**
 * The predicate grammar, and the store the preset row draws from.
 *
 * These are the parts that are plain functions, and the cases below are the ones where being wrong
 * is silent: an expression that compiles and means something else, a link whose predicates read back
 * as different predicates, a saved-filter store that throws and takes the screen with it. The rule
 * that a predicate actually *reaches a browse* is not here — it is not a property of any function in
 * this file, and it is asserted in `route.test.tsx` at the place the product applies it.
 */

import { afterEach, describe, expect, test } from "vitest";

import {
  celFor,
  celString,
  conjunctsOf,
  isEmpty,
  predicateParams,
  predicatesFrom,
  windowOf,
  windowStart,
  type Predicates,
} from "./predicates.js";
import {
  MAX_PRESETS,
  presetsKey,
  readPresets,
  withPreset,
  withoutPreset,
  writePresets,
} from "./presets.js";

const KEY_STARTS: Predicates = { key: { mode: "starts", text: "ord_" } };

describe("what a predicate compiles to", () => {
  test("each field becomes a term about that field, in a fixed order", () => {
    expect(
      conjunctsOf({
        key: { mode: "starts", text: "ord_" },
        value: { mode: "contains", text: "UAH" },
        untilOffset: "200",
        untilTime: 1767225600000,
      }),
    ).toEqual([
      'record.keyAsText.startsWith("ord_")',
      'record.valueAsText.contains("UAH")',
      "record.offset <= 200",
      "record.timestampMs <= 1767225600000",
    ]);
  });

  test("the order is fixed, so the same predicates always mint the same filter id", () => {
    // The service derives the id from `sha256(source)`. A spelling that varied would mean a fresh
    // compile on every browse of the same filter, and a cache that never hits.
    const key = { mode: "equals", text: "a" } as const;
    const value = { mode: "contains", text: "UAH" } as const;
    const one = celFor({ value, key });
    const two = celFor({ key, value });
    expect(one).toBe(two);
  });

  test("an empty box is no predicate at all, not one that matches everything", () => {
    // `record.keyAsText.contains("")` is true of every record, and would look — in the URL, in the
    // chip and in the compiled source — exactly like a filter that is doing something.
    expect(conjunctsOf({ key: { mode: "contains", text: "" } })).toEqual([]);
    expect(isEmpty({ key: { mode: "contains", text: "" } })).toBe(true);
    expect(celFor({ key: { mode: "contains", text: "" } })).toBeUndefined();
  });

  test("a hand-written expression is parenthesised and goes last", () => {
    // `a && b || c` is not the filter somebody who wrote `b || c` meant.
    expect(celFor(KEY_STARTS, "value.amount > 1000 || value.currency == 'UAH'")).toBe(
      'record.keyAsText.startsWith("ord_") && (value.amount > 1000 || value.currency == \'UAH\')',
    );
  });

  test("an offset that is not digits is not put in the expression", () => {
    // An offset is a 64-bit integer carried as a string, and `record.offset <= 1e6` is a comparison
    // the compiler would accept and the service would evaluate against something else.
    expect(conjunctsOf({ untilOffset: "1e6" })).toEqual([]);
    expect(conjunctsOf({ untilOffset: "1000000" })).toEqual(["record.offset <= 1000000"]);
  });

  test("a quote in the text is escaped rather than ending the literal", () => {
    // Without this, a key containing `"` produces an expression that does not compile — and a key
    // containing `" || true || "` produces one that compiles and matches everything.
    expect(celString('he said "no"')).toBe('"he said \\"no\\""');
    expect(celString("back\\slash")).toBe('"back\\\\slash"');
    expect(celString("two\nlines")).toBe('"two\\nlines"');
    expect(celString("\u2028")).toBe('"\\u2028"');
  });
});

describe("a browse is a link", () => {
  test("predicates survive the round trip through an address", () => {
    const predicates: Predicates = {
      key: { mode: "ends", text: "_v2" },
      value: { mode: "equals", text: "CAPTURED" },
      untilOffset: "41284",
      untilTime: 1767225600000,
    };
    const params = new URLSearchParams();
    for (const [name, value] of predicateParams(predicates)) params.append(name, value);
    expect(predicatesFrom(params)).toEqual(predicates);
  });

  test("a mode a link names that this build does not know costs that one setting", () => {
    // The rule the whole URL grammar follows: a value out of a link somebody was sent should cost
    // the recipient that setting rather than the screen.
    const params = new URLSearchParams("key=ord_&keyMode=regex");
    expect(predicatesFrom(params)).toEqual({ key: { mode: "contains", text: "ord_" } });
  });

  test("an empty predicate writes nothing into the address", () => {
    expect(predicateParams({ key: { mode: "contains", text: "" } })).toEqual([]);
  });
});

describe("the quick time windows", () => {
  test("a window chooses a start, measured from the clock it was given", () => {
    const now = Date.parse("2026-09-05T10:00:00Z");
    expect(windowStart("15m", now)).toBe(now - 15 * 60_000);
  });

  test("the chip that set the start stays lit while the page is read", () => {
    // Exact equality would leave the chip somebody just pressed drawn as inactive a second later,
    // which reads as a control that does not work.
    const now = Date.parse("2026-09-05T10:00:00Z");
    expect(windowOf(windowStart("1h", now), now + 8_000)).toBe("1h");
    expect(windowOf(now - 42 * 60_000, now)).toBeUndefined();
  });
});

describe("the preset store", () => {
  afterEach(() => {
    window.localStorage.clear();
  });

  test("a store holding something this build cannot read draws no row", () => {
    // Not a thrown error and not a half-list: §3.12 says an unconfigured preset set draws no row.
    window.localStorage.setItem(presetsKey("c"), "{not json");
    expect(readPresets("c")).toEqual([]);
    const mixed = JSON.stringify([{ nope: 1 }, { name: "Refunds" }]);
    window.localStorage.setItem(presetsKey("c"), mixed);
    expect(readPresets("c").map((one) => one.name)).toEqual(["Refunds"]);
  });

  test("saving under a name that exists replaces it rather than adding a twin", () => {
    // Two chips reading `Refunds` are two chips nobody can choose between.
    const first = withPreset([], { name: "Refunds", predicates: KEY_STARTS });
    const second = withPreset(first, { name: "Refunds", predicates: { untilOffset: "5" } });
    expect(second).toHaveLength(1);
    expect(second[0]?.predicates).toEqual({ untilOffset: "5" });
  });

  test("the list is bounded, newest first", () => {
    let presets = [] as ReturnType<typeof withPreset>;
    for (let i = 0; i < MAX_PRESETS + 3; i += 1) {
      presets = withPreset(presets, { name: `p${String(i)}`, predicates: KEY_STARTS });
    }
    expect(presets).toHaveLength(MAX_PRESETS);
    expect(presets[0]?.name).toBe(`p${String(MAX_PRESETS + 2)}`);
  });

  test("the store keeps the bound too, and not only the builder", () => {
    /*
     * Found by mutation rather than by reading: `writePresets` caps what it stores at
     * {@link MAX_PRESETS} and deleting that cap left all 156 cases in this package green. The
     * bound above is `withPreset`'s, and every call site in the product goes through it — so this
     * is a second defence that nothing could see, on the one function that decides how long the
     * chip row is the next time this browser opens the screen.
     *
     * Written as a direct call for that reason: it is exactly the caller `withPreset` is not.
     */
    const many = Array.from({ length: MAX_PRESETS + 4 }, (_, index) => ({
      name: `p${String(index)}`,
      predicates: KEY_STARTS,
    }));
    writePresets("c", many);
    expect(readPresets("c")).toHaveLength(MAX_PRESETS);
    // The head of the list, so the cap keeps the newest rather than trimming from the wrong end.
    expect(readPresets("c")[0]?.name).toBe("p0");
  });

  test("what is written comes back", () => {
    writePresets("c", [
      { name: "Big tickets", predicates: KEY_STARTS, expression: "record.offset > 0" },
    ]);
    expect(readPresets("c")).toEqual([
      { name: "Big tickets", predicates: KEY_STARTS, expression: "record.offset > 0" },
    ]);
    expect(withoutPreset(readPresets("c"), "Big tickets")).toEqual([]);
  });
});
