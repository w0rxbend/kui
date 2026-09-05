import { describe, expect, it } from "vitest";
import {
  formatBytes,
  formatOffset,
  prettyValue,
  previewValue,
  recordKey,
  relativeTime,
} from "./record.js";

describe("recordKey", () => {
  it("is partition and offset together, not the offset alone", () => {
    // Offsets restart at zero in every partition, so a list merged across twelve partitions has
    // twelve records numbered 0. Keying on the offset alone made eleven of them share one element,
    // and eleven rows drew the twelfth's contents.
    expect(recordKey({ partition: 0, offset: "0" } as never)).not.toBe(
      recordKey({ partition: 1, offset: "0" } as never),
    );
  });
});

describe("formatOffset", () => {
  it("groups digits so a column of offsets can be compared at a glance", () => {
    expect(formatOffset("18442901")).toBe("18,442,901");
    expect(formatOffset("999")).toBe("999");
    expect(formatOffset("1000")).toBe("1,000");
    expect(formatOffset("0")).toBe("0");
  });

  it("keeps every digit of an offset past 2^53", () => {
    // This is why an offset is a string. As a number, 9223372036854775806 rounds to
    // 9223372036854776000 and the last digits on screen are simply wrong.
    expect(formatOffset("9223372036854775806")).toBe("9,223,372,036,854,775,806");
  });

  it("leaves anything that is not a run of digits alone", () => {
    expect(formatOffset("latest")).toBe("latest");
  });
});

describe("relativeTime", () => {
  const now = Date.parse("2026-03-14T12:00:00Z");
  const ago = (seconds: number) => new Date(now - seconds * 1000).toISOString();

  it("reads in the largest unit that is still a whole number", () => {
    expect(relativeTime(ago(2), now)).toBe("2s ago");
    expect(relativeTime(ago(59), now)).toBe("59s ago");
    expect(relativeTime(ago(60), now)).toBe("1m ago");
    expect(relativeTime(ago(3599), now)).toBe("59m ago");
    expect(relativeTime(ago(3600), now)).toBe("1h ago");
    expect(relativeTime(ago(86_399), now)).toBe("23h ago");
    expect(relativeTime(ago(86_400), now)).toBe("1d ago");
  });

  it("says so, rather than complaining, when a producer's clock is ahead", () => {
    // Common enough that treating it as a fault would cry wolf, and visible enough to be a hint
    // that a clock is wrong somewhere.
    expect(relativeTime(ago(-3), now)).toBe("in 3s");
  });

  it("does not render NaN when the wire sends something unparseable", () => {
    expect(relativeTime("not a timestamp", now)).toBe("unknown time");
  });
});

describe("previewValue", () => {
  it("collapses whitespace, so a pretty-printed payload does not preview as a single brace", () => {
    expect(previewValue({ kind: "json", text: '{\n  "a": 1\n}' })).toBe('{ "a": 1 }');
  });

  it("says what each of the five situations is, and never returns an empty string", () => {
    // An empty preview is indistinguishable from a record holding the empty string, which is how
    // three of these were shipped as blank rows.
    expect(previewValue({ kind: "tombstone" })).toBe("null");
    expect(previewValue({ kind: "large", bytes: 4_200_000 })).toBe("4.2 MB — open to view");
    expect(previewValue({ kind: "undecodable", reason: "Avro schema 42 not found" })).toBe(
      "could not deserialize (Avro schema 42 not found)",
    );
    for (const value of [
      { kind: "tombstone" },
      { kind: "large", bytes: 0 },
      { kind: "undecodable", reason: "x" },
      { kind: "text", text: "" },
    ] as const) {
      if (value.kind !== "text") expect(previewValue(value)).not.toBe("");
    }
  });
});

describe("prettyValue", () => {
  it("indents JSON", () => {
    expect(prettyValue({ kind: "json", text: '{"a":1}' })).toBe('{\n  "a": 1\n}');
  });

  it("shows a payload that claimed to be JSON and is not, exactly as it arrived", () => {
    // The bytes are what the operator is trying to look at. A component that hides them because it
    // could not format them has removed the only evidence.
    expect(prettyValue({ kind: "json", text: "{not json" })).toBe("{not json");
  });

  it("offers the raw bytes when the deserializer failed", () => {
    expect(prettyValue({ kind: "undecodable", reason: "no schema", hex: "00 01" })).toBe("00 01");
    // And falls back to the reason when there are no bytes to show, rather than to nothing.
    expect(prettyValue({ kind: "undecodable", reason: "no schema" })).toBe("no schema");
  });
});

describe("formatBytes", () => {
  it("is readable at every scale a Kafka payload reaches", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(999)).toBe("999 B");
    expect(formatBytes(1000)).toBe("1.0 kB");
    expect(formatBytes(4_200_000)).toBe("4.2 MB");
    expect(formatBytes(1_500_000_000)).toBe("1.5 GB");
  });

  it("does not render a negative size", () => {
    expect(formatBytes(-1)).toBe("0 B");
  });
});
