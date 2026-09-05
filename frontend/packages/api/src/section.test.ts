import { describe, expect, test } from "vitest";

import { decodeSection, sectionData } from "./section.js";
import { decodeEnvelope, isRetryable, userMessage } from "./errors.js";
import { ErrorCodes } from "./constants.generated.js";

describe("sections of an aggregated response", () => {
  test("an ok section carries its data", () => {
    const section = decodeSection<readonly string[]>({
      status: "ok",
      data: ["a"],
      fetchedAt: "2026-09-05T10:00:00.000Z",
    });
    expect(section.status).toBe("ok");
    expect(sectionData(section)).toEqual(["a"]);
  });

  test("a stale section still carries data, and says why it is stale", () => {
    const section = decodeSection<readonly string[]>({
      status: "stale",
      data: ["a"],
      fetchedAt: "2026-09-05T10:00:00.000Z",
      reason: "upstream_timeout",
      message: "the broker did not answer",
    });
    expect(sectionData(section)).toEqual(["a"]);
    if (section.status === "stale") {
      expect(section.reason.code).toBe("upstream_timeout");
      expect(section.reason.message).toBe("the broker did not answer");
    }
  });

  test("the three empty states carry no data and are not failures", () => {
    for (const status of ["unavailable", "forbidden", "not_configured"] as const) {
      const section = decodeSection<readonly string[]>({ status, reason: "circuit_open" });
      expect(section.status).toBe(status);
      expect(sectionData(section)).toBeUndefined();
    }
  });

  test("a status this build has never heard of is unreadable, not a thrown error", () => {
    // A newer server inventing a sixth status must not blank the page. The navigation renders
    // `unreadable` exactly as `unavailable`, with the reason shown (ADR-032).
    const section = decodeSection<readonly string[]>({ status: "quantum" });
    expect(section.status).toBe("unreadable");
  });

  test("an ok section with no data is unreadable rather than an ok section with undefined in it", () => {
    const section = decodeSection<readonly string[]>({ status: "ok", fetchedAt: "" });
    expect(section.status).toBe("unreadable");
  });

  test("something that is not a section at all is unreadable", () => {
    expect(decodeSection<readonly string[]>(null).status).toBe("unreadable");
    expect(decodeSection<readonly string[]>("<html>").status).toBe("unreadable");
  });
});

describe("the error envelope", () => {
  test("a complete envelope decodes to every field the interface branches on", () => {
    const error = decodeEnvelope({
      code: ErrorCodes.GroupNotEmpty,
      message: "the group still has members",
      details: [{ field: "groupId", restrictions: ["must be empty"] }],
      correlationId: "cid-9",
      timestamp: "2026-09-05T10:00:00.000Z",
      retryable: false,
    });

    expect(error.kind).toBe("envelope");
    if (error.kind === "envelope") {
      expect(error.code).toBe(ErrorCodes.GroupNotEmpty);
      expect(error.details[0]?.field).toBe("groupId");
      expect(isRetryable(error)).toBe(false);
    }
  });

  test("an envelope with no code is a decoding failure, because there is nothing to branch on", () => {
    expect(decodeEnvelope({ message: "something" }).kind).toBe("decoding");
  });

  test("an envelope missing its correlation id still decodes, degraded", () => {
    // The user needs to be told what went wrong. An empty support reference beats a blank screen.
    const error = decodeEnvelope({ code: ErrorCodes.Internal, message: "boom" });
    expect(error.kind).toBe("envelope");
    if (error.kind === "envelope") expect(error.correlationId).toBe("");
  });

  test("an upstream failure is reworded, and every other message is the server's own", () => {
    // The defect: `kafka answered with status 502` reached the screen verbatim. No Kafka broker
    // speaks HTTP, so there was no 502 to go and look for, and the real problem was never stated.
    const upstream = decodeEnvelope({
      code: ErrorCodes.UpstreamUnavailable,
      message: "kafka answered with status 502",
      correlationId: "c",
    });
    expect(userMessage(upstream)).not.toContain("502");

    const business = decodeEnvelope({
      code: ErrorCodes.TopicNotFound,
      message: "topic 'orders' does not exist",
      correlationId: "c",
    });
    expect(userMessage(business)).toBe("topic 'orders' does not exist");
  });

  test("a code from a newer server decodes and keeps its message", () => {
    const future = decodeEnvelope({
      code: "KUI-SOMETHING-NEW-IN-M9",
      message: "a thing happened",
      correlationId: "c",
    });
    expect(future.kind).toBe("envelope");
    expect(userMessage(future)).toBe("a thing happened");
  });
});
