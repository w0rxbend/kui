/**
 * The rules the smart filter and the resend enforce before a request is made.
 *
 * The recorded-document suite next door checks that what the server sends is read correctly. This
 * one checks the decisions taken on this side: what is refused before it is sent, which of the four
 * readings a tally gets, and — the one that matters most — that a record the operator points at
 * survives the trip into the preview's request with the fields a filter can see intact.
 */
import { describe, expect, it } from "vitest";
import type { KafkaRecord } from "@kui/kernel";

import { MAX_FILTER_SOURCE_BYTES, filterProblem, verdictOf } from "./filters.js";
import {
  MAX_RESEND_RECORDS,
  draftSize,
  rangeSize,
  readingOf,
  resendDraftProblem,
} from "./resend.js";
import { toDto, toRecord } from "./wire.js";

describe("what the filter editor refuses to send", () => {
  it("will not send an empty expression", () => {
    expect(filterProblem("")).toBeDefined();
    expect(filterProblem("   ")).toBeDefined();
  });

  it("measures the length in bytes rather than characters", () => {
    /* The service's limit is bytes. An expression of 5 000 two-byte characters is under the
     * character count and over the real one, and would be refused by the server after passing an
     * editor that counted `.length`. */
    const wide = "é".repeat(MAX_FILTER_SOURCE_BYTES / 2 + 1);
    expect(wide.length).toBeLessThan(MAX_FILTER_SOURCE_BYTES);
    expect(filterProblem(wide)).toContain("bytes");
  });

  it("does not try to judge the grammar", () => {
    /* Deliberate. CEL's grammar is the server's judgement, and a second, worse compiler here would
     * refuse expressions the real one accepts — with no way for the operator to settle it. */
    expect(filterProblem("this is not( CEL at all")).toBeUndefined();
  });
});

describe("the three verdicts", () => {
  it("reads an empty error string as no error", () => {
    // Belt and braces on the drift: `""` is not a failure anybody could act on, and treating it as
    // one would put an empty red panel on screen.
    expect(verdictOf({ matched: true, error: "" })).toEqual({ kind: "matched" });
    expect(verdictOf({ matched: false, error: "" })).toEqual({ kind: "no-match" });
  });

  it("prefers the failure over the match flag", () => {
    /* The server sets `matched: false` whenever it sets `error`, so this pair should not arise —
     * but if it ever does, a client that read `matched` first would report a match for an
     * expression that threw. Wrong in the safe direction, by construction. */
    expect(verdictOf({ matched: true, error: "boom" })).toEqual({ kind: "failed", reason: "boom" });
  });
});

describe("a record on its way into the preview", () => {
  const round = (record: KafkaRecord) => toDto(record);

  it("keeps every field a filter expression can read", () => {
    const record: KafkaRecord = {
      offset: "18442901",
      partition: 3,
      key: "ord_9f21ac",
      timestamp: "2026-09-05T10:00:08.000Z",
      timestampType: "CreateTime",
      headers: [{ name: "content-type", value: "application/json" }],
      value: { kind: "json", text: '{"status":"CAPTURED"}' },
    };

    const dto = round(record);
    // The eight fields CEL is given, and nothing else has to survive.
    expect(dto.partition).toBe(3);
    expect(dto.offset).toBe(18_442_901);
    expect(dto.timestamp).toBe("2026-09-05T10:00:08.000Z");
    expect(dto.key.text).toBe("ord_9f21ac");
    expect(dto.value.text).toBe('{"status":"CAPTURED"}');
    expect(dto.headers).toEqual({ "content-type": "application/json" });
  });

  it("keeps a null key apart from an empty one", () => {
    /* On a compacted topic the null key *is* the deletion. Collapsing the two would make a preview
     * of `record.keyAsText == ""` answer matched for a record whose key is genuinely absent — an
     * answer about a record that is not the one the operator pointed at. */
    const absent = round({
      offset: "1",
      partition: 0,
      key: null,
      timestamp: "2026-09-05T10:00:00Z",
      headers: [],
      value: { kind: "tombstone" },
    });
    const empty = round({
      offset: "2",
      partition: 0,
      key: "",
      timestamp: "2026-09-05T10:00:00Z",
      headers: [],
      value: { kind: "text", text: "" },
    });

    expect(absent.key.kind).toBe("null");
    expect(empty.key.kind).toBe("string");
    expect(absent.key.kind).not.toBe(empty.key.kind);
  });

  it("keeps a tombstone apart from an empty value", () => {
    const tombstone = round({
      offset: "1",
      partition: 0,
      key: "k",
      timestamp: "2026-09-05T10:00:00Z",
      headers: [],
      value: { kind: "tombstone" },
    });
    const empty = round({
      offset: "2",
      partition: 0,
      key: "k",
      timestamp: "2026-09-05T10:00:00Z",
      headers: [],
      value: { kind: "text", text: "" },
    });

    expect(tombstone.value.kind).toBe("null");
    expect(empty.value.kind).toBe("string");
  });

  it("survives a round trip through the row mapping", () => {
    /* The property that matters at the call site: the preview is given a record that came off the
     * wire, through `toRecord`, onto the screen, and back through `toDto`. The fields a filter can
     * read have to be the same at both ends or the preview answers about a different record. */
    const wire = {
      partition: 7,
      offset: 4242,
      timestamp: "2026-09-05T09:59:00Z",
      timestampType: "LogAppendTime",
      key: { kind: "string", text: "ord_b8e044", serde: "String", properties: {} },
      value: { kind: "json", text: '{"amount":89.99}', serde: "String", properties: {} },
      headers: { "correlation-id": "c_4242" },
      keySize: 10,
      valueSize: 16,
      headersSize: 20,
      deserializeErrors: [],
    };

    const back = toDto(toRecord(wire));
    expect(back.partition).toBe(wire.partition);
    expect(back.offset).toBe(wire.offset);
    expect(back.key.text).toBe("ord_b8e044");
    expect(back.value.text).toBe('{"amount":89.99}');
    expect(back.headers).toEqual({ "correlation-id": "c_4242" });
    expect(back.timestampType).toBe("LogAppendTime");
  });

  it("declares the real size of a payload it is not holding the text of", () => {
    /* A value too large to preview has no text on this side. Sending an empty string with a size of
     * nought would tell the filter the record is empty; the size is what says otherwise. */
    const dto = round({
      offset: "1",
      partition: 0,
      key: "k",
      timestamp: "2026-09-05T10:00:00Z",
      headers: [],
      value: { kind: "large", bytes: 900_000 },
    });
    expect(dto.value.text).toBe("");
    expect(dto.valueSize).toBe(900_000);
  });
});

describe("a resend range", () => {
  it("is half-open, so from 0 until 3 is three records", () => {
    expect(rangeSize({ partition: 0, from: "0", until: "3" })).toBe(3);
  });

  it("has no size while it is still being typed", () => {
    // Not zero. "Nobody has finished typing this" and "this range holds no records" are different
    // facts, and the dialog draws them differently.
    expect(rangeSize({ partition: 0, from: "", until: "3" })).toBeUndefined();
    expect(rangeSize({ partition: 0, from: "0", until: "" })).toBeUndefined();
    expect(draftSize({ toTopic: "t", ranges: [{ partition: 0, from: "0", until: "" }] })).toBeUndefined();
  });

  it("has no size when it runs backwards", () => {
    expect(rangeSize({ partition: 0, from: "9", until: "2" })).toBeUndefined();
  });

  it("adds up across partitions", () => {
    expect(
      draftSize({
        toTopic: "t",
        ranges: [
          { partition: 0, from: "0", until: "10" },
          { partition: 1, from: "5", until: "15" },
        ],
      }),
    ).toBe(20);
  });
});

describe("what the resend dialog refuses to send", () => {
  const draft = (ranges: { partition: number; from: string; until: string }[]) => ({
    toTopic: "orders.replay",
    ranges,
  });

  it("needs a destination", () => {
    expect(resendDraftProblem({ toTopic: "", ranges: [{ partition: 0, from: "0", until: "1" }] })).toBeDefined();
  });

  it("needs at least one range, in the server's own words", () => {
    expect(resendDraftProblem(draft([]))).toContain("nothing to copy");
  });

  it("refuses a range that names no records, and explains the half-open convention", () => {
    /* `from` equal to `until` is a range of nothing. The server would accept it and answer
     * `read: 0, written: 0` — indistinguishable from the retention case, which is a real problem
     * worth reading. Refusing it here keeps that panel meaning one thing. */
    const problem = resendDraftProblem(draft([{ partition: 0, from: "5", until: "5" }]));
    expect(problem).toBeDefined();
    expect(problem).toContain("until");
  });

  it("refuses more than the cap the server enforces", () => {
    const problem = resendDraftProblem(draft([{ partition: 0, from: "0", until: String(MAX_RESEND_RECORDS + 1) }]));
    expect(problem).toContain("10,000");
  });

  it("counts the cap across every range, not one at a time", () => {
    // Two ranges of 6 000 are 12 000 records and one request. Checking each range alone would let
    // this through to a refusal the operator would find puzzling, having seen both numbers pass.
    const problem = resendDraftProblem(
      draft([
        { partition: 0, from: "0", until: "6000" },
        { partition: 1, from: "0", until: "6000" },
      ]),
    );
    expect(problem).toContain("12,000");
  });

  it("passes a range that is within every rule", () => {
    expect(resendDraftProblem(draft([{ partition: 0, from: "0", until: "3" }]))).toBeUndefined();
  });
});

describe("how a finished resend is read", () => {
  it("calls a full copy complete", () => {
    expect(readingOf({ toTopic: "t", read: 3, written: 3, requested: 3 })).toEqual({ kind: "complete" });
  });

  it("never calls a copy of nothing complete", () => {
    /* The state the whole feature turns on: a 200 with two zeroes and no error. It has to be its
     * own reading, because every other reading renders as some flavour of "it worked". */
    expect(readingOf({ toTopic: "t", read: 0, written: 0, requested: 10 })).toEqual({
      kind: "nothing",
      requested: 10,
    });
  });

  it("still reports nothing-copied when the client could not count what was asked for", () => {
    expect(readingOf({ toTopic: "t", read: 0, written: 0 })).toEqual({ kind: "nothing" });
  });

  it("puts a lost write ahead of a short read", () => {
    /* A tally can be both — fewer records in the log *and* a producer that stopped. The copy that
     * lost records mid-flight is the worse fact and the one that needs acting on, because running
     * it again duplicates whatever did land. */
    expect(readingOf({ toTopic: "t", read: 6, written: 2, requested: 10 })).toEqual({
      kind: "partial",
      lost: 4,
    });
  });

  it("reports a short read when the source had fewer records than the range named", () => {
    expect(readingOf({ toTopic: "t", read: 6, written: 6, requested: 10 })).toEqual({
      kind: "short",
      missing: 4,
    });
  });

  it("does not invent a shortfall when nothing said how many were asked for", () => {
    expect(readingOf({ toTopic: "t", read: 6, written: 6 })).toEqual({ kind: "complete" });
  });
});
