import { describe, expect, it } from "vitest";
import { createEffect, createRoot, flush, type Accessor } from "solid-js";

import { createQueryCache, queryKey, type QueryState } from "./cache.js";

/**
 * A fetch whose answers are handed out by the test, one at a time.
 *
 * Every behaviour in the cache is about *when* a request happens and *which* answer wins, so the
 * suite has to control both. Real promises with real timers would turn each of these into a flaky
 * test about scheduling.
 */
function stubFetch() {
  const calls: string[] = [];
  const pending: Array<{ key: string; settle: (value: unknown) => void }> = [];

  return {
    calls,
    pending,
    fetch: (key: string): Promise<{ ok: true; value: string } | { ok: false; error: never }> => {
      calls.push(key);
      return new Promise((resolve) => {
        pending.push({ key, settle: resolve as (value: unknown) => void });
      });
    },
    /** Answers the nth outstanding request (0 is the oldest) and lets the microtask queue drain. */
    async answer(index: number, value: string): Promise<void> {
      const call = pending[index];
      if (call === undefined) throw new Error(`no outstanding request at ${index}`);
      call.settle({ ok: true, value });
      await Promise.resolve();
      flush();
    },
    async fail(index: number, message: string): Promise<void> {
      const call = pending[index];
      if (call === undefined) throw new Error(`no outstanding request at ${index}`);
      call.settle({ ok: false, error: { kind: "unreachable", cause: message } });
      await Promise.resolve();
      flush();
    },
  };
}

/** Watches a key the way a component does, and hands the test the states it saw. */
function watching<A>(
  watch: (key: string) => Accessor<QueryState<A>>,
  key: string,
): { readonly seen: QueryState<A>[]; readonly stop: () => void } {
  const seen: QueryState<A>[] = [];
  let stop = (): void => {};
  createRoot((dispose) => {
    stop = dispose;
    createEffect(
      () => watch(key)(),
      (state) => {
        seen.push(state);
      },
    );
  });
  flush();
  return { seen, stop };
}

describe("the query cache", () => {
  it("asks the server once however many components want the same key", () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    const first = watching(cache.watch.bind(cache), "clusters");
    const second = watching(cache.watch.bind(cache), "clusters");

    expect(server.calls).toEqual(["clusters"]);
    first.stop();
    second.stop();
  });

  it("fetches nothing until something watches", () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    cache.watch("clusters");
    cache.peek("clusters");

    expect(server.calls).toEqual([]);
  });

  it("keeps the last good answer when a refetch fails, and says the picture is stale", async () => {
    let clock = 1000;
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch, now: () => clock });

    const first = watching(cache.watch.bind(cache), "topics");
    await server.answer(0, "the good answer");
    expect(first.seen.at(-1)).toMatchObject({ lastGood: "the good answer", stale: false });
    first.stop();

    clock += 31_000;
    const second = watching(cache.watch.bind(cache), "topics");
    await server.fail(1, "the gateway went away");

    const state = second.seen.at(-1);
    // The three things ADR-032's stale rule needs at once: the old numbers, when they arrived, and
    // the current failure that explains why they are not moving.
    expect(state?.lastGood).toBe("the good answer");
    expect(state?.lastGoodAt).toBe(1000);
    expect(state?.stale).toBe(true);
    expect(state?.outcome?.ok).toBe(false);
    second.stop();
  });

  it("holds a failure for a few seconds so a struggling endpoint is not hit by the whole page", async () => {
    let clock = 0;
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch, now: () => clock });

    const first = watching(cache.watch.bind(cache), "brokers");
    await server.fail(0, "unreachable");
    first.stop();

    clock += 4000;
    watching(cache.watch.bind(cache), "brokers").stop();
    expect(server.calls).toHaveLength(1);

    clock += 2000;
    watching(cache.watch.bind(cache), "brokers").stop();
    expect(server.calls).toHaveLength(2);
  });

  it("trusts a success for thirty seconds", async () => {
    let clock = 0;
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch, now: () => clock });

    watching(cache.watch.bind(cache), "info").stop();
    await server.answer(0, "0.1.0");

    clock += 29_000;
    watching(cache.watch.bind(cache), "info").stop();
    expect(server.calls).toHaveLength(1);

    clock += 2000;
    watching(cache.watch.bind(cache), "info").stop();
    expect(server.calls).toHaveLength(2);
  });

  it("drops the answer to a superseded request rather than letting it overwrite a newer one", async () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    const watcher = watching(cache.watch.bind(cache), "topics");
    cache.invalidate("topics");
    expect(server.calls).toHaveLength(2);

    // The *newer* request answers first, and then the slow original arrives.
    await server.answer(1, "fresh");
    await server.answer(0, "stale, and a whole request behind");

    expect(watcher.seen.at(-1)?.lastGood).toBe("fresh");
    watcher.stop();
  });

  it("refetches a watched key when it is invalidated and leaves an unwatched one alone", async () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    const watched = watching(cache.watch.bind(cache), "cluster|a|topics");
    await server.answer(0, "one topic");

    const abandoned = watching(cache.watch.bind(cache), "cluster|b|topics");
    await server.answer(1, "another topic");
    abandoned.stop();

    cache.invalidateWhere((key) => key.startsWith("cluster|"));

    // Both were invalidated; only the one on screen was asked again.
    expect(server.calls).toEqual(["cluster|a|topics", "cluster|b|topics", "cluster|a|topics"]);
    watched.stop();
  });

  it("stops refreshing a key nobody is looking at any more", () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    watching(cache.watch.bind(cache), "topics").stop();
    cache.invalidate("topics");

    expect(server.calls).toHaveLength(1);
  });

  it("accepts a value a mutation already returned, without asking again", () => {
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch });

    cache.set("topic|orders", "the topic the create call answered with");
    const watcher = watching(cache.watch.bind(cache), "topic|orders");

    expect(server.calls).toEqual([]);
    expect(watcher.seen.at(-1)?.lastGood).toBe("the topic the create call answered with");
    watcher.stop();
  });

  it("evicts unwatched entries when it is over its bound, and never a watched one", async () => {
    let clock = 0;
    const server = stubFetch();
    const cache = createQueryCache<string>({ fetch: server.fetch, maxEntries: 2, now: () => clock });

    const kept = watching(cache.watch.bind(cache), "on-screen");
    await server.answer(0, "visible");

    clock += 1;
    watching(cache.watch.bind(cache), "old").stop();
    await server.answer(1, "older answer");

    clock += 1;
    watching(cache.watch.bind(cache), "newer").stop();
    await server.answer(2, "newer answer");

    clock += 1;
    watching(cache.watch.bind(cache), "newest").stop();

    expect(cache.size()).toBe(2);
    // The one on screen survived a bound it broke; the oldest unwatched answer is the one that went.
    expect(cache.peek("on-screen").lastGood).toBe("visible");
    expect(cache.peek("old").lastGood).toBeUndefined();
    kept.stop();
  });

  it("builds a key a prefix test can match", () => {
    // The separator is a character Kafka's own vocabulary excludes — a cluster id is a lowercase
    // slug, a topic name is [a-zA-Z0-9._-], a group id likewise — so a key part can never contain
    // one, and `invalidateWhere(key => key.startsWith("cluster|a|"))` means what it says.
    expect(queryKey("cluster", "a", "topics")).toBe("cluster|a|topics");
    expect(queryKey("topic", "orders", 3)).toBe("topic|orders|3");
  });
});
