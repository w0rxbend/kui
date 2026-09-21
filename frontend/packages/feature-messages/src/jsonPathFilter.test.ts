import { describe, expect, it } from "vitest";

import {
  compileJsonPathFilter,
  type JsonPathFilterInput,
  type JsonPathFilterResult,
} from "./jsonPathFilter.js";

const base: JsonPathFilterInput = {
  target: "value",
  path: "$.status",
  operator: "==",
  value: "CAPTURED",
};

function sourceOf(input: JsonPathFilterInput): string {
  const result = compileJsonPathFilter(input);
  expect(result).toMatchObject({ ok: true });
  if (!result.ok) throw new Error(result.error);
  return result.source;
}

function refusalOf(input: unknown): string {
  const result = compileJsonPathFilter(input as JsonPathFilterInput);
  expect(result).toMatchObject({ ok: false });
  if (result.ok) throw new Error(`expected a refusal, received ${result.source}`);
  return result.error;
}

describe("JSONPath-style field filters", () => {
  it("addresses the selected root with $", () => {
    expect(sourceOf({ ...base, target: "key", path: "$", value: null })).toBe(
      "record.key == null",
    );
    expect(sourceOf(base)).toBe('record.value.status == "CAPTURED"');
  });

  it("compiles dot identifiers, numeric indexes and quoted object keys", () => {
    expect(
      sourceOf({
        ...base,
        path: "$.customer.addresses[0]['postal-code']",
        operator: "!=",
        value: "00-001",
      }),
    ).toBe('record.value.customer.addresses[0]["postal-code"] != "00-001"');
    expect(sourceOf({ ...base, path: "$.in['']" })).toBe(
      'record.value["in"][""] == "CAPTURED"',
    );
  });

  it("decodes quoted-key escapes and safely re-encodes the key for CEL", () => {
    expect(
      sourceOf({
        ...base,
        path: String.raw`$['customer\'s']["line\nkey"]["\u0069d"]`,
        value: true,
      }),
    ).toBe('record.value["customer\'s"]["line\\nkey"]["id"] == true');
  });

  it("emits each supported typed value without turning it into source text", () => {
    expect(sourceOf({ ...base, operator: "==", value: 'paid" || true' })).toBe(
      'record.value.status == "paid\\\" || true"',
    );
    expect(sourceOf({ ...base, operator: ">=", value: 12.5 })).toBe(
      "record.value.status >= 12.5",
    );
    expect(sourceOf({ ...base, operator: "!=", value: false })).toBe(
      "record.value.status != false",
    );
    expect(sourceOf({ ...base, operator: "==", value: null })).toBe(
      "record.value.status == null",
    );
  });

  it("compiles contains as a CEL method call with an escaped string", () => {
    expect(
      sourceOf({
        target: "key",
        path: '$["display name"]',
        operator: "contains",
        value: 'Ada \\"Countess\\"',
      }),
    ).toBe('record.key["display name"].contains("Ada \\\\\\\"Countess\\\\\\\"")');
  });

  it("keeps path content inside one quoted CEL key", () => {
    const source = sourceOf({
      ...base,
      path: String.raw`$["x\"] || true || record.value[\"y"]`,
      value: "safe",
    });

    expect(source).toBe(
      'record.value["x\\\"] || true || record.value[\\\"y"] == "safe"',
    );
  });
});

describe("unsupported or malformed paths", () => {
  const rejected = [
    "",
    "status",
    "$status",
    "$.",
    "$..status",
    "$.*",
    "$[*]",
    "$[?(@.price > 10)]",
    "$[]",
    "$[-1]",
    "$[1.2]",
    "$[01]",
    "$[9223372036854775808]",
    "$[\"unterminated]",
    String.raw`$["bad\q"]`,
    String.raw`$["bad\u12x4"]`,
    "$.postal-code",
    "$.status trailing",
  ];

  it.each(rejected)("rejects %j and consumes no valid prefix", (path) => {
    expect(refusalOf({ ...base, path })).toMatch(/path/i);
  });

  it("rejects runtime values outside the closed target and operator sets", () => {
    expect(refusalOf({ ...base, target: "headers" })).toMatch(/target/i);
    expect(refusalOf({ ...base, operator: "matches" })).toMatch(/operator/i);
  });

  it("rejects values that cannot be represented faithfully in CEL", () => {
    for (const value of [Number.NaN, Number.POSITIVE_INFINITY, 9_007_199_254_740_992, {}, undefined]) {
      expect(refusalOf({ ...base, value })).toMatch(/value/i);
    }
  });

  it("requires a string value for contains", () => {
    expect(refusalOf({ ...base, operator: "contains", value: 12 })).toMatch(/contains/i);
  });

  it("always returns a discriminated result instead of throwing on unknown input", () => {
    const result: JsonPathFilterResult = compileJsonPathFilter(null as never);
    expect(result).toMatchObject({ ok: false });
  });
});
