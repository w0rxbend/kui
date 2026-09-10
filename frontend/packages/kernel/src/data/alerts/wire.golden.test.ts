/**
 * The documents the **alerts service's own encoder** renders, decoded by the browser that reads
 * them.
 *
 * ## Why this file exists, and why it enumerates rather than lists
 *
 * House rule 12: two sides of one wire are one packet, and the binding case decodes the encoder's
 * own output. `services/alerts/contract` renders the documents in
 * `services/alerts/contract/test/resources/golden/` from `AlertDtos.scala` and a Scala suite
 * asserts they are exactly what the encoders produce; this suite reads **the same files off disk**
 * and
 * pushes them through `./events.ts`, which is what the bell, the dashboard card and the alerts
 * screen all decode with. One document, two languages. That is the shape that closed M7 for
 * `services/metrics` and its ten goldens, and `../../../../shell/src/overview/wire.golden.test.ts`
 * is the same file for the metrics wire.
 *
 * The version of this suite that shipped in wave 6 named five files by hand. That is a list, and a
 * list is exactly what the golden mechanism exists to remove: a sixth document rendered by the
 * service is decoded by nobody, silently, and the drift it was written to catch goes uncaught. So
 * the directory is **read**. Every `.json` in it is classified by its own shape and decoded, an
 * empty or missing directory is a failure rather than a vacuous pass, and a document whose shape
 * this build does not recognise fails by name with the keys it carried — which is what a renamed
 * envelope looks like from here.
 *
 * The named cases below stay, because "it decodes" is not the assertion: wave 5's producers wire
 * decoded successfully into an empty array and a card announced it. What is checked is the values
 * that reach a screen.
 *
 * ## Why the files and not a copy of them
 *
 * A copy is a third literal, and a third literal is how both sides of a wire end up green and
 * disagreeing. `vitest` runs in node and can read the repository, so the artefact these cases
 * assert against is the artefact the Scala suite asserts against.
 */

import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { decodeSection, type Section } from "@kui/api";

import {
  decodeAlertChange,
  decodeAlertEvent,
  decodeAlertFeed,
  type AlertFeed,
} from "./events.js";
import { ALERTS_EVENT_NAME } from "./store.js";

/** The alerts contract module's committed documents, six directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..", "..", "..", "..", "..", "..",
  "services", "alerts", "contract", "test", "resources", "golden",
);

/**
 * Every document the service currently commits.
 *
 * A missing directory is reported as the failure it is rather than as an empty sweep — a suite that
 * quietly passes when its fixtures have moved is the failure mode this whole file is about.
 */
function documents(): readonly string[] {
  let entries: readonly string[];
  try {
    entries = readdirSync(GOLDEN);
  } catch (cause) {
    throw new Error(
      `The alerts goldens are not where this suite expects them (${GOLDEN}). They are committed ` +
        `by services/alerts/contract; if they moved, this suite moves with them.`,
      { cause },
    );
  }
  return entries.filter((entry) => entry.endsWith(".json")).sort();
}

/** One committed document, parsed. A missing file is a failure and never a skip. */
function golden(name: string): unknown {
  const path = join(GOLDEN, name);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (cause) {
    throw new Error(`${name} is not at ${path}; see the note in documents().`, { cause });
  }
  return JSON.parse(text) as unknown;
}

/** The feed inside an `AlertFeedResponse`, refusing every state that is not a readable document. */
function feed(name: string): AlertFeed {
  const body = golden(name) as Record<string, unknown>;
  const section = decodeSection<unknown>(body["events"]);
  if (section.status !== "ok" && section.status !== "stale") {
    throw new Error(`${name} carried ${section.status}`);
  }
  const decoded = decodeAlertFeed(section.data);
  if (!decoded.ok) throw new Error(decoded.cause);
  return decoded.value;
}

/**
 * What one document is, judged from the document and not from its file name.
 *
 * A file name is a convention and conventions are not checked; the envelope is the contract. Every
 * shape the alerts wire has is here, and a document matching none of them is the interesting case —
 * either the service grew a response this build cannot read, or one it can read was renamed.
 */
type Shape = "feed-response" | "stream-frame" | "change-frame" | "acknowledgement";

function shapeOf(document: unknown): Shape | undefined {
  if (typeof document !== "object" || document === null) return undefined;
  const candidate = document as Record<string, unknown>;
  if ("events" in candidate) return "feed-response";
  // Before the acknowledgement, which also has an `event` key — one holds an SSE event *name* and
  // the other an alert event object, and the `data` beside it is what tells them apart.
  if ("event" in candidate && "data" in candidate) return "stream-frame";
  if ("event" in candidate && "openCount" in candidate) return "acknowledgement";
  if ("cluster" in candidate && "at" in candidate) return "change-frame";
  return undefined;
}

/** Reads one document all the way through `./events.ts`, or explains what stopped it. */
function decodeDocument(name: string, document: unknown): void {
  switch (shapeOf(document)) {
    case "feed-response": {
      const body = document as Record<string, unknown>;
      const section: Section<unknown> = decodeSection<unknown>(body["events"]);
      // `unreadable` is the one status a committed document may never carry: it means this build
      // could not read the envelope the service wrote, which is the drift itself.
      expect(section.status, `${name}: the 'events' section`).not.toBe("unreadable");
      if (section.status !== "ok" && section.status !== "stale") return;
      const decoded = decodeAlertFeed(section.data);
      expect(decoded.ok ? true : decoded.cause, `${name}: the feed`).toBe(true);
      return;
    }
    case "stream-frame": {
      const body = document as Record<string, unknown>;
      // The event *name* is half the wire and the only half nothing used to compare. `@kui/api`'s
      // generated `SseEventNames` does not carry `alerts` — the generator writes the shared names
      // by hand — so `ALERTS_EVENT_NAME` is a literal in this package, and a rename of
      // `AlertChangeDto.EventName` on the server would leave the browser subscribed to a name the
      // gateway never sends: a bell that simply never moves, with every suite green.
      expect(body["event"], `${name}: the SSE event name`).toBe(ALERTS_EVENT_NAME);
      const change = decodeAlertChange(JSON.stringify(body["data"]));
      expect(change.ok ? true : change.cause, `${name}: the frame payload`).toBe(true);
      return;
    }
    case "acknowledgement": {
      const body = document as Record<string, unknown>;
      const event = decodeAlertEvent(body["event"]);
      expect(event.ok ? true : event.cause, `${name}: the acknowledged event`).toBe(true);
      expect(typeof body["openCount"], `${name}: openCount`).toBe("number");
      return;
    }
    case "change-frame": {
      // Through `JSON.stringify` because that is how a frame reaches the decoder in production: the
      // text of one `data:` line, parsed by `decodeAlertChange` itself.
      const change = decodeAlertChange(JSON.stringify(document));
      expect(change.ok ? true : change.cause, `${name}: the change frame`).toBe(true);
      return;
    }
    default: {
      const keys =
        typeof document === "object" && document !== null
          ? Object.keys(document as Record<string, unknown>).join(", ")
          : String(document);
      throw new Error(
        `${name} is not a shape @kui/kernel can read. Its keys are ` +
          `${keys === "" ? "(none)" : keys}. The alerts wire is AlertFeedResponse, ` +
          `AcknowledgementDto, AlertChangeDto and the named SSE frame that wraps one; a new one ` +
          `needs a reader in ./events.ts before the service can render a document for it.`,
      );
    }
  }
}

describe("documents rendered by the alerts service", () => {
  it("decodes every committed golden, and there are goldens to decode", () => {
    const files = documents();
    // The vacuous-pass guard, in two halves. A `for` over an empty roster is green and says
    // nothing, which is the exact failure the metrics goldens were introduced to end — so the
    // roster must be non-empty, **and** the count of documents this case actually pushed through
    // `./events.ts` must equal it. Without the second half the cheapest way to neuter this suite is
    // to delete the loop, which leaves every other case here passing.
    expect(files.length, `no .json documents under ${GOLDEN}`).toBeGreaterThan(0);
    let decoded = 0;
    for (const name of files) {
      decodeDocument(name, golden(name));
      decoded += 1;
    }
    expect(decoded, "every committed document was read").toBe(files.length);
  });

  it("still carries every document the M8 screens are drawn from", () => {
    // Enumeration catches a document nobody reads; this catches a document nobody renders any
    // more. The cases below assert values out of these by name, and a deletion would otherwise
    // take its case with it and leave the suite green. It is `arrayContaining` and not an equality
    // on purpose: a seventh golden is `services/alerts`' to add and is decoded by the case above
    // without anybody editing a list here.
    expect(documents()).toEqual(
      expect.arrayContaining([
        "alerts-acknowledgement.json",
        "alerts-change.json",
        "alerts-feed-blind-rule.json",
        "alerts-feed-response.json",
        "alerts-feed-unevaluated.json",
        "alerts-stream-frame.json",
      ]),
    );
  });

  it("subscribes to the event name the service publishes under", () => {
    // House rule 12 on the half of the alerts wire that is not a document body. The kernel opens
    // its listener on `ALERTS_EVENT_NAME`; the service renders the frame under
    // `AlertChangeDto.EventName`; this is the only place in the repository the two meet.
    const frame = golden("alerts-stream-frame.json") as Record<string, unknown>;
    expect(frame["event"]).toBe(ALERTS_EVENT_NAME);
    expect(decodeAlertChange(JSON.stringify(frame["data"]))).toEqual({
      ok: true,
      value: { cluster: "prod-eu", openCount: 1, at: "2026-09-03T10:11:12Z" },
    });
  });

  it("decodes the feed, counts, resolutions and rule measurements", () => {
    const value = feed("alerts-feed-response.json");
    expect(value.items.map((event) => event.id)).toEqual([
      "offline-partitions.cluster.1756890672000",
      "disk-usage.broker-1-.var.lib.kafka.1756887072000",
    ]);
    expect(value.openCount).toBe(1);
    expect(value.unreadCount).toBe(1);
    expect(value.items[1]?.resolution).toEqual({
      at: "2026-09-03T10:11:12Z",
      kind: "acknowledged",
      by: "ada",
    });
    expect(value.rules.map((rule) => [rule.rule, rule.openEvents, rule.unmeasuredSubjects])).toEqual([
      ["offline-partitions", 1, 0],
      ["under-replicated-partitions", 0, 0],
      ["stuck-rebalance", 0, 0],
      ["disk-usage", 0, 1],
    ]);
  });

  it("carries the severity and tone the service derived, and derives neither itself", () => {
    // The four words travel because three screens must not be able to map them differently. This is
    // the assertion that the golden actually carries both halves of both pairs — a service that
    // stopped sending `tone` would otherwise only be caught by a refusal, and only if `tone` were
    // dropped rather than emptied.
    const value = feed("alerts-feed-response.json");
    const first = value.items[0];
    expect(first?.severity.length ?? 0).toBeGreaterThan(0);
    expect(first?.tone.length ?? 0).toBeGreaterThan(0);
    expect(first?.category).toBeDefined();
    expect(first?.glyph).toBeDefined();
  });

  it("keeps an unavailable rule distinct from a measured zero", () => {
    const value = feed("alerts-feed-blind-rule.json");
    expect(value.rules[0]).toMatchObject({
      rule: "offline-partitions",
      status: "unavailable",
      openEvents: null,
      reason: "kafka-admin could not be reached",
    });
    expect(value.rules[1]).toMatchObject({ status: "ok", openEvents: 0 });
  });

  it("keeps zero beside a missing evaluation timestamp as unevaluated", () => {
    const value = feed("alerts-feed-unevaluated.json");
    expect(value.openCount).toBe(0);
    expect(value.evaluatedAt).toBeUndefined();
    expect(value.rules[0]?.reason).toBe("KUI has not finished reading this cluster yet");
  });

  it("decodes the stream frame and acknowledgement event with the same vocabulary", () => {
    const change = decodeAlertChange(JSON.stringify(golden("alerts-change.json")));
    expect(change).toEqual({
      ok: true,
      value: { cluster: "prod-eu", openCount: 1, at: "2026-09-03T10:11:12Z" },
    });

    const acknowledgement = golden("alerts-acknowledgement.json") as Record<string, unknown>;
    const event = decodeAlertEvent(acknowledgement["event"]);
    expect(event.ok && event.value.resolution?.by).toBe("ada");
    expect(acknowledgement["openCount"]).toBe(1);
  });
});
