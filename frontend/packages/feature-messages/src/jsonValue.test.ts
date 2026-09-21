import { describe, expect, it } from "vitest";

import { analyzeJsonValue, formatJsonValue } from "./jsonValue.js";

describe("JSON values written from the produce drawer", () => {
  it("detects valid JSON and formats it across readable lines", () => {
    const source = '{"order":{"id":42,"paid":true},"tags":["new",null]}';

    expect(analyzeJsonValue(source)).toMatchObject({ kind: "valid-json" });
    expect(formatJsonValue(source, "pretty")).toBe(
      '{\n  "order": {\n    "id": 42,\n    "paid": true\n  },\n  "tags": [\n    "new",\n    null\n  ]\n}',
    );
  });

  it("reports the line and column of malformed JSON", () => {
    const analysis = analyzeJsonValue('{\n  "order": 42,\n  "paid": tru\n}');

    expect(analysis.kind).toBe("invalid-json");
    if (analysis.kind !== "invalid-json") return;
    expect(analysis.message).toMatch(/line 3, column \d+/i);
  });

  it("leaves ordinary text alone instead of calling it broken JSON", () => {
    expect(analyzeJsonValue("result=success")).toEqual({ kind: "plain-text" });
    expect(formatJsonValue("result=success", "compact")).toBe("result=success");
  });

  it("minifies whitespace without rounding large integer lexemes or changing escaped strings", () => {
    const source = '{\n  "id": 9007199254740993,\n  "label": "a \\\"quoted\\\" value"\n}';

    expect(formatJsonValue(source, "compact")).toBe(
      '{"id":9007199254740993,"label":"a \\\"quoted\\\" value"}',
    );
  });
});
