import { describe, expect, it } from "vitest";

import { DEFAULT_EVENT_NAME, EMPTY_PARSER_STATE, feed, type RawSseEvent } from "./parser.js";

/**
 * The exact bytes `libs/http` writes, so client and server are provably compatible.
 *
 * Task HTTP-004 pins this document byte for byte on the server side, in
 * `libs/http/test/src/kui/http/sse/SseSuite.scala`. Reproducing it here rather than describing it
 * means the two halves cannot drift: a change to the server's format that nobody mirrored fails this
 * suite. It carried across from the Scala.js frontend unchanged — the bytes are the contract, and
 * the language the parser is written in has nothing to do with them.
 */
const GOLDEN_STREAM =
  "event: heartbeat\ndata: {}\n\n" +
  'event: done\nid: eyJ2IjoxfQ.abc\ndata: {"reason":"exhausted","cursor":"eyJ2IjoxfQ.abc"}\n\n';

/** Feeds a whole document at once. */
function parse(document: string): readonly RawSseEvent[] {
  return feed(EMPTY_PARSER_STATE, document).events;
}

describe("the SSE parser", () => {
  it("parses the golden wire format from HTTP-004", () => {
    expect(parse(GOLDEN_STREAM)).toEqual([
      { name: "heartbeat", data: "{}", id: undefined },
      {
        name: "done",
        data: '{"reason":"exhausted","cursor":"eyJ2IjoxfQ.abc"}',
        id: "eyJ2IjoxfQ.abc",
      },
    ]);
  });

  it("handles multi-line data and comments", () => {
    const document =
      ": this is a keep-alive comment\nevent: row\ndata: first\ndata: second\ndata: third\n\n";
    expect(parse(document)).toEqual([{ name: "row", data: "first\nsecond\nthird", id: undefined }]);
  });

  it("ignores unknown fields", () => {
    // A stream may grow a field. An older browser must skip it, not fail: that is what lets the
    // server add one without a coordinated release.
    expect(parse("event: row\nsomethingNew: 42\ndata: payload\n\n")).toEqual([
      { name: "row", data: "payload", id: undefined },
    ]);
  });

  it("handles CRLF and LF alike, and holds back a trailing bare CR", () => {
    const lf = parse("event: row\ndata: payload\n\n");
    const crlf = parse("event: row\r\ndata: payload\r\n\r\n");
    // A bare `\r` at the very end of the input is held back, not dispatched: it might yet turn out
    // to be the first half of a `\r\n`. So the carriage-return-only document needs one more
    // character before its last event can be delivered, which is what a real stream provides.
    const held = feed(EMPTY_PARSER_STATE, "event: row\rdata: payload\r\r");
    expect(held.events).toEqual([]);
    const cr = feed(held.state, "event: next\r").events;

    expect(crlf).toEqual(lf);
    expect(cr).toEqual(lf);
  });

  it("emits nothing for an incomplete trailing event", () => {
    // The blank line is what ends an event. Emitting on the last `data:` line would deliver half a
    // JSON document to the decoder and report a corrupt payload for a stream that was fine.
    const first = feed(EMPTY_PARSER_STATE, 'event: row\ndata: {"half":');
    expect(first.events).toEqual([]);

    expect(feed(first.state, " true}\n\n").events).toEqual([
      { name: "row", data: '{"half": true}', id: undefined },
    ]);
  });

  it("calls an event with no name 'message'", () => {
    expect(parse("data: bare\n\n")).toEqual([
      { name: DEFAULT_EVENT_NAME, data: "bare", id: undefined },
    ]);
  });

  it("emits nothing for a block with no data, and does not leak its name into the next event", () => {
    expect(parse("event: ping\n\nevent: row\ndata: payload\n\n")).toEqual([
      { name: "row", data: "payload", id: undefined },
    ]);
  });

  it("keeps the stream id until the server sends a new one", () => {
    // `id` is a property of the stream, not of one event: it is the resume cursor (ADR-026), and
    // resetting it per event would make a reconnect restart from the beginning.
    expect(parse("id: one\ndata: a\n\ndata: b\n\nid: two\ndata: c\n\n")).toEqual([
      { name: "message", data: "a", id: "one" },
      { name: "message", data: "b", id: "one" },
      { name: "message", data: "c", id: "two" },
    ]);
  });

  it("strips exactly one space after the colon", () => {
    // The format strips one space and no more. Eating the rest would change the bytes the JSON
    // decoder sees, which matters for a payload a server chose to indent.
    expect(parse("data:  two spaces\n\n")).toEqual([
      { name: "message", data: " two spaces", id: undefined },
    ]);
    expect(parse("data:none\n\n")).toEqual([{ name: "message", data: "none", id: undefined }]);
    expect(parse("data\n\n")).toEqual([{ name: "message", data: "", id: undefined }]);
  });

  it("records a retry line without producing an event", () => {
    const fed = feed(EMPTY_PARSER_STATE, "retry: 4000\n");
    expect(fed.events).toEqual([]);
    expect(fed.state.retry).toBe(4000);
  });

  it("ignores an id containing a NUL rather than truncating it", () => {
    expect(parse("id: bad\u0000cursor\ndata: payload\n\n")).toEqual([
      { name: "message", data: "payload", id: undefined },
    ]);
  });

  it("waits for the partner of a carriage return at the end of a chunk", () => {
    // The one genuinely dangerous split: `\r` alone ends a line, but `\r\n` is one terminator.
    // Acting on the `\r` before its partner arrives invents a blank line, which in this format
    // means "dispatch" — a phantom event.
    const first = feed(EMPTY_PARSER_STATE, "data: payload\r");
    expect(first.events).toEqual([]);
    expect(feed(first.state, "\n\r\n").events).toEqual([
      { name: "message", data: "payload", id: undefined },
    ]);
  });
});

describe("chunking", () => {
  /**
   * A reproducible pseudo-random source.
   *
   * The Scala suite drove this property with ScalaCheck, whose seed changes from run to run. A
   * deterministic generator is the better trade for the one test that proves the two halves of the
   * product agree on the wire format: a failure here is the same failure on every machine and in CI,
   * rather than something that appeared once and cannot be reproduced.
   */
  function splits(seed: number): () => number {
    let state = seed >>> 0;
    return () => {
      // xorshift32, chosen because it is four lines and needs no dependency.
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      return state;
    };
  }

  it("gives the same answer however the network splits the bytes", () => {
    const expected = parse(GOLDEN_STREAM);
    const next = splits(20260905);

    for (let attempt = 0; attempt < 2000; attempt += 1) {
      let rest = GOLDEN_STREAM;
      let state = EMPTY_PARSER_STATE;
      const collected: RawSseEvent[] = [];

      while (rest.length > 0) {
        // One to eight characters at a time: small enough to land inside the `\r\n` of a line
        // terminator, inside a field name, and between a `data:` line and the blank line after it.
        const size = (next() % 8) + 1;
        const fed = feed(state, rest.slice(0, size));
        rest = rest.slice(size);
        state = fed.state;
        collected.push(...fed.events);
      }

      expect(collected).toEqual(expected);
    }
  });

  it("holds an event back until its blank line arrives, whatever the chunking", () => {
    // The complement of the property above: it is not enough that the events come out right in the
    // end, nothing may come out early. A `data:` line that dispatched before its terminator would
    // hand the decoder half a JSON document.
    const upToLastNewline = GOLDEN_STREAM.slice(0, GOLDEN_STREAM.length - 1);
    let state = EMPTY_PARSER_STATE;
    const collected: RawSseEvent[] = [];
    for (const character of upToLastNewline) {
      const fed = feed(state, character);
      state = fed.state;
      collected.push(...fed.events);
    }

    expect(collected).toHaveLength(1);
    expect(feed(state, "\n").events).toHaveLength(1);
  });
});
