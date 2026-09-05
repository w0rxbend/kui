import { describe, expect, it } from "vitest";
import { apiFailure, figure, fromSection, valueOf } from "./fetched.js";

/**
 * The six states a screen can be in, and the two mappings that produce them.
 *
 * These moved here from `feature-clusters` when the union did: three more features are about to map
 * sections the same way, and a copy per feature is a copy that can quietly lose a case.
 *
 * The cases worth reading are the ones asserting that two states stay *apart*. `forbidden` and
 * `failed` both render "you cannot see this list", and only one of them has a retry that could
 * work; `not-configured` renders the same again and means nothing is broken at all.
 */
describe("fromSection", () => {
  const id = <T,>(value: T): T => value;

  it("maps every status to its own state", () => {
    expect(fromSection({ status: "ok", data: 1, fetchedAt: "" }, id)).toEqual({ kind: "ready", value: 1 });
    expect(fromSection({ status: "forbidden" }, id)).toEqual({ kind: "forbidden" });
    expect(fromSection({ status: "not_configured" }, id)).toEqual({ kind: "not-configured" });
    expect(fromSection({ status: "unavailable", reason: { code: "X" } }, id).kind).toBe("failed");
    // A section this build does not recognise is reported, never dropped.
    expect(fromSection({ status: "unreadable", reason: { code: "X" } }, id).kind).toBe("failed");
  });

  it("keeps stale data and says why it is stale", () => {
    const state = fromSection(
      { status: "stale", data: [1, 2], fetchedAt: "", reason: { code: "SCRAPE_FAILED", message: "Last scrape failed." } },
      id,
    );
    expect(state).toEqual({ kind: "stale", value: [1, 2], reason: "Last scrape failed." });
  });

  it("still explains a failure the server gave no message for", () => {
    // A reason code with no sentence is common — the server has one and the browser must still put
    // words on screen rather than an empty panel.
    const state = fromSection({ status: "unavailable", reason: { code: "UPSTREAM_UNAVAILABLE" } }, id);
    if (state.kind !== "failed") throw new Error("expected failed");
    expect(state.message.length).toBeGreaterThan(0);
    expect(state.code).toBe("UPSTREAM_UNAVAILABLE");
  });

  it("applies the mapping only where there is data", () => {
    let called = 0;
    const counting = <T,>(value: T): T => {
      called += 1;
      return value;
    };
    fromSection({ status: "forbidden" }, counting);
    fromSection({ status: "not_configured" }, counting);
    fromSection({ status: "unavailable", reason: { code: "X" } }, counting);
    expect(called).toBe(0);
  });
});

describe("apiFailure", () => {
  it("uses the envelope's own code and message", () => {
    const state = apiFailure({
      kind: "envelope",
      code: "TOPIC_NOT_FOUND",
      message: "No such topic.",
      details: [],
      correlationId: "abc",
      retryable: false,
    });
    expect(state).toEqual({ kind: "failed", message: "No such topic.", code: "TOPIC_NOT_FOUND" });
  });

  it("puts a sentence on screen for the three failures that carry no message", () => {
    // Only `envelope` has a `message`. `error.message` on the other three is `undefined`, and a
    // screen rendering the word "undefined" at the moment the server is unreachable is the exact
    // failure this function exists to prevent.
    for (const error of [
      { kind: "unreachable", cause: "ECONNREFUSED" },
      { kind: "timeout" },
      { kind: "decoding", cause: "bad json" },
    ] as const) {
      const state = apiFailure(error);
      expect(state.kind).toBe("failed");
      if (state.kind !== "failed") continue;
      expect(state.message.length).toBeGreaterThan(0);
      expect(state.message).not.toContain("undefined");
      expect(state.code).toBe(error.kind.toUpperCase());
    }
  });
});

describe("valueOf", () => {
  it("yields stale data rather than the fallback", () => {
    expect(valueOf({ kind: "stale", value: [1], reason: "old" }, [])).toEqual([1]);
    expect(valueOf({ kind: "ready", value: [2] }, [])).toEqual([2]);
  });

  it("falls back for every state that has no data", () => {
    expect(valueOf({ kind: "loading" }, ["fallback"])).toEqual(["fallback"]);
    expect(valueOf({ kind: "forbidden" }, ["fallback"])).toEqual(["fallback"]);
    expect(valueOf({ kind: "not-configured" }, ["fallback"])).toEqual(["fallback"]);
    expect(valueOf({ kind: "failed", message: "m", code: "c" }, ["fallback"])).toEqual(["fallback"]);
  });
});

describe("figure", () => {
  it("keeps zero and loses nothing else", () => {
    // The single most important line in any mapping layer: `0` is a claim, and it is the most
    // reassuring possible rendering of "we do not know".
    expect(figure(0)).toBe(0);
    expect(figure(42)).toBe(42);
    expect(figure(null)).toBeNull();
    expect(figure(undefined)).toBeNull();
  });
});
