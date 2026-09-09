/** Server-rendered alert contracts decoded by the browser's shared kernel reader. */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { decodeSection } from "@kui/api";

import { decodeAlertChange, decodeAlertEvent, decodeAlertFeed, type AlertFeed } from "./events.js";

const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..", "..", "..", "..", "..", "..",
  "services", "alerts", "contract", "test", "resources", "golden",
);

function golden(name: string): unknown {
  return JSON.parse(readFileSync(join(GOLDEN, name), "utf8")) as unknown;
}

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

describe("documents rendered by the alerts service", () => {
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
