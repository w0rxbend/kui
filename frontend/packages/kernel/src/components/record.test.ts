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

  it("rounds a fractional byte count, because rates come through here too", () => {
    // Measured on the shipped product before this was fixed: the cluster dashboard's CONSUME stat
    // card printed `81.2359955010432 B/s` beside a PRODUCTION card reading `1.2 kB/s`, and three
    // rows of the throughput card's data table carried seventeen significant figures each. The
    // whole-byte case below is why the rounding is conditional rather than an unconditional
    // `toFixed(1)`: a 147-byte record is 147 bytes, and `147.0 B` claims a precision nobody has.
    expect(formatBytes(81.2359955010432)).toBe("81.2 B");
    expect(formatBytes(214.77853092686576)).toBe("214.8 B");
    expect(formatBytes(20.149754341786714)).toBe("20.1 B");
    expect(formatBytes(147)).toBe("147 B");
  });

  it("says a small rate is small rather than rounding it to a zero it does not mean", () => {
    // `0.0 B/s` over a cluster that is moving something is the one figure this product is not
    // allowed to draw. A *measured* zero is different and keeps its own spelling.
    expect(formatBytes(0.30493676815166676)).toBe("0.3 B");
    expect(formatBytes(0.04)).toBe("<0.1 B");
    expect(formatBytes(0)).toBe("0 B");
  });

  it("stops at the top unit rather than dividing off the end of the scale", () => {
    // The promotion loop's `unit < units.length - 1` is the only thing keeping `units[unit]` inside
    // the table; without it an exabyte-scale figure divides one step too far, `units[6]` is
    // `undefined`, and the `?? "B"` fallback prints `1.5 B` for a billion gigabytes — a figure that
    // is wrong by eighteen orders of magnitude and reads as perfectly ordinary.
    //
    // The last line is the price of that cap, asserted rather than left to be discovered: `PB` is
    // the ceiling, so the header's "[0, 1000) at every unit below the last one" holds everywhere
    // below it and the last unit keeps counting. This is the line the previous ceiling's header
    // falsified — `TB` was the top until this wave, so the plausible `1.5e15` printed `1500.0 TB`
    // under a header promising `[0, 1000)` unconditionally.
    expect(formatBytes(1_500_000_000_000)).toBe("1.5 TB");
    expect(formatBytes(1_500_000_000_000_000)).toBe("1.5 PB");
    expect(formatBytes(1.5e18)).toBe("1500.0 PB");
  });

  it("spells a petabyte the way `feature-topics` did, because it is now the same function", () => {
    // The list of topics on a cluster used to carry its own copy of this function with its own
    // ladder — `B` through `PB`, promoting at a bare 1000 and printing an unrounded byte count —
    // and `feature-topics/src/index.tsx` re-exported it, so two functions with one name and one
    // meaning were both public surface and disagreed about more than rounding. The copy is gone and
    // its call sites read this one; these are the three figures its own suite pinned, kept here so
    // the merge is asserted rather than assumed.
    expect(formatBytes(4096)).toBe("4.1 kB");
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(128_000_000_000)).toBe("128.0 GB");
  });

  it("never prints a four-digit figure under a three-digit unit", () => {
    // 999.96 is below the promotion threshold and rounds to 1000.0 at one decimal, which is how a
    // `1000.0 B` reached a card that has a `kB` to put it in.
    expect(formatBytes(999.96)).toBe("1.0 kB");
    expect(formatBytes(999)).toBe("999 B");
  });
});
