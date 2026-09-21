import { describe, expect, it, vi } from "vitest";

import type { AlertFeed } from "@kui/kernel";

import {
  ALERT_FEED_CACHE_MAX_AGE_MS,
  createAlertFeedCache,
  type AlertCacheDatabase,
} from "./alertCache.js";

function feed(openCount: number): AlertFeed {
  return {
    items: [],
    total: 0,
    openCount,
    unreadCount: openCount,
    lastReadAt: undefined,
    evaluatedAt: "2026-09-21T12:00:00Z",
    rules: [],
  };
}

function memoryDatabase(): AlertCacheDatabase & { readonly values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return {
    values,
    get: (key) => Promise.resolve(values.get(key)),
    put: (record) => {
      values.set(record.key, record);
      return Promise.resolve();
    },
    delete: (key) => {
      values.delete(key);
      return Promise.resolve();
    },
    records: () => Promise.resolve([...values.values()]),
    clear: () => {
      values.clear();
      return Promise.resolve();
    },
  };
}

describe("the browser alert-feed cache", () => {
  it("isolates entries by cluster and principal without exposing either in its key", async () => {
    const database = memoryDatabase();
    let principal = "alice";
    let authorization = "grants-v1";
    const hash = vi.fn(async (material: string) => {
      const scope = JSON.parse(material) as string[];
      if (scope[2] === "bob") return "opaque-b";
      return scope[4] === "grants-v1" ? "opaque-a1" : "opaque-a2";
    });
    const cache = createAlertFeedCache({
      database,
      scope: () => ({
        cluster: "prod/eu",
        principalKind: "user",
        principalName: principal,
        authorization,
      }),
      hash,
      now: () => 1_000,
    });

    await cache.write(feed(3));

    const [key] = database.values.keys();
    expect(key).not.toContain("prod");
    expect(key).not.toContain("alice");
    expect(await cache.read()).toMatchObject({ openCount: 3 });

    authorization = "grants-revoked";
    expect(await cache.read()).toBeUndefined();
    authorization = "grants-v1";
    principal = "bob";
    expect(await cache.read()).toBeUndefined();
    expect(hash).toHaveBeenCalledWith('["v1","user","alice","prod/eu","grants-v1"]');
    expect(hash).toHaveBeenCalledWith('["v1","user","bob","prod/eu","grants-v1"]');
  });

  it("expires old entries and removes them", async () => {
    const database = memoryDatabase();
    let now = 10_000;
    const cache = createAlertFeedCache({
      database,
      scope: () => ({
        cluster: "prod",
        principalKind: "user",
        principalName: "alice",
        authorization: "grants-v1",
      }),
      hash: async () => "one",
      now: () => now,
    });
    await cache.write(feed(4));

    now += ALERT_FEED_CACHE_MAX_AGE_MS + 1;

    expect(await cache.read()).toBeUndefined();
    expect(database.values.size).toBe(0);
  });

  it("keeps only the newest bounded set of entries", async () => {
    const database = memoryDatabase();
    let cluster = "a";
    let now = 1;
    const cache = createAlertFeedCache({
      database,
      scope: () => ({
        cluster,
        principalKind: "user",
        principalName: "alice",
        authorization: "grants-v1",
      }),
      hash: async (material) => `digest-${(JSON.parse(material) as string[])[3]}`,
      now: () => now,
      maxEntries: 2,
    });

    await cache.write(feed(1));
    cluster = "b";
    now += 1;
    await cache.write(feed(2));
    cluster = "c";
    now += 1;
    await cache.write(feed(3));

    expect([...database.values.keys()].sort()).toEqual(["digest-b", "digest-c"]);
  });

  it("ignores malformed records and clears private cached state on request", async () => {
    const database = memoryDatabase();
    const cache = createAlertFeedCache({
      database,
      scope: () => ({
        cluster: "prod",
        principalKind: "user",
        principalName: "alice",
        authorization: "grants-v1",
      }),
      hash: async () => "one",
      now: () => 10,
    });
    database.values.set("one", { key: "one", version: 7, savedAt: 10, feed: feed(99) });

    expect(await cache.read()).toBeUndefined();
    expect(database.values.size).toBe(0);

    await cache.write(feed(5));
    expect(database.values.size).toBe(1);
    await cache.clear();
    expect(database.values.size).toBe(0);
  });

  it("is a no-op until both a cluster and an authenticated principal are known", async () => {
    const database = memoryDatabase();
    const cache = createAlertFeedCache({
      database,
      scope: () => undefined,
      hash: async () => "unused",
    });

    await cache.write(feed(2));

    expect(await cache.read()).toBeUndefined();
    expect(database.values.size).toBe(0);
  });
});
