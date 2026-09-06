import { describe, expect, it } from "vitest";
import { createEffect, createRoot, createSignal, flush } from "solid-js";

import type { Fetched } from "../fetched.js";
import { createQueryRegistry, useQuery, type QueryRegistry } from "./useQuery.js";

/**
 * A loader whose answers the test hands out one at a time.
 *
 * Every behaviour here is about *when* a request happens and *which* answer wins, so the suite has
 * to control both. Real timers would turn each of these into a flaky test about scheduling.
 */
function stubLoader() {
  const calls: string[] = [];
  const pending: Array<(answer: Fetched<string>) => void> = [];

  return {
    calls,
    load: (key: string): Promise<Fetched<string>> => {
      calls.push(key);
      return new Promise((resolve) => {
        pending.push(resolve);
      });
    },
    /** Answers the nth outstanding request (0 is the oldest) and lets the microtask queue drain. */
    async settle(index: number, answer: Fetched<string>): Promise<void> {
      const resolve = pending[index];
      if (resolve === undefined) throw new Error(`no outstanding request at ${index}`);
      resolve(answer);
      // The registry wraps the loader in an `async` function, so the answer reaches the cache a
      // couple of microtasks after it is resolved. Draining generously rather than counting ticks.
      for (let tick = 0; tick < 6; tick += 1) await Promise.resolve();
      flush();
    },
  };
}

/** Reads a query the way a component does, and hands the test every state it saw. */
function reading(
  registry: QueryRegistry,
  key: () => string | undefined,
  load: (key: string) => Promise<Fetched<string>>,
): { readonly seen: Fetched<string>[]; readonly reload: () => void; readonly stop: () => void } {
  const seen: Fetched<string>[] = [];
  let reload = (): void => {};
  let stop = (): void => {};
  createRoot((dispose) => {
    stop = dispose;
    const query = useQuery<string>({ key, load, registry });
    reload = query.reload;
    createEffect(
      () => query.state(),
      (state) => {
        seen.push(state);
      },
    );
  });
  flush();
  return { seen, reload, stop };
}

const ready = (value: string): Fetched<string> => ({ kind: "ready", value });

describe("useQuery", () => {
  it("asks once however many components want the same key", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    const header = reading(registry, () => "clusters", server.load);
    const table = reading(registry, () => "clusters", server.load);

    expect(server.calls).toEqual(["clusters"]);

    // And one answer reaches both, at the same moment — three requests would have put three
    // different pictures of the same cluster on one screen.
    await server.settle(0, ready("kafka-prod"));
    expect(header.seen.at(-1)).toEqual({ kind: "ready", value: "kafka-prod" });
    expect(table.seen.at(-1)).toEqual({ kind: "ready", value: "kafka-prod" });

    header.stop();
    table.stop();
  });

  it("fetches nothing until something reads the state", () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    createRoot(() => {
      useQuery<string>({ key: () => "topics", load: server.load, registry });
    });
    flush();

    expect(server.calls).toEqual([]);
  });

  it("keeps the last good value when a refetch fails, and says the picture is stale", async () => {
    let clock = 1000;
    const registry = createQueryRegistry({ now: () => clock });
    const server = stubLoader();

    const page = reading(registry, () => "topics", server.load);
    await server.settle(0, ready("128 topics"));
    expect(page.seen.at(-1)).toEqual({ kind: "ready", value: "128 topics" });

    clock += 31_000;
    page.reload();
    await server.settle(1, { kind: "failed", message: "The gateway went away.", code: "TIMEOUT" });

    // The whole reason this hook exists: the six hand-rolled copies would each have gone back to
    // `loading` and then to `failed`, blanking a card that was showing a real figure.
    expect(page.seen.at(-1)).toEqual({
      kind: "stale",
      value: "128 topics",
      reason: "The gateway went away.",
    });
    expect(page.seen.some((state) => state.kind === "failed")).toBe(false);
    page.stop();
  });

  it("reports a first failure as a failure, because there is nothing to keep", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    const page = reading(registry, () => "brokers", server.load);
    await server.settle(0, { kind: "failed", message: "Nothing answered.", code: "UNREACHABLE" });

    expect(page.seen.at(-1)).toEqual({
      kind: "failed",
      message: "Nothing answered.",
      code: "UNREACHABLE",
    });
    page.stop();
  });

  it("carries a refusal through the cache instead of flattening it to a failure", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    const forbidden = reading(registry, () => "acls", server.load);
    await server.settle(0, { kind: "forbidden" });
    // A retry button here is a button that cannot work, which is why `forbidden` may never arrive
    // at a screen as `failed`.
    expect(forbidden.seen.at(-1)).toEqual({ kind: "forbidden" });
    forbidden.stop();

    const missing = reading(registry, () => "schemas", server.load);
    await server.settle(1, { kind: "not-configured" });
    expect(missing.seen.at(-1)).toEqual({ kind: "not-configured" });
    missing.stop();
  });

  it("does not dress a refusal up as stale data the principal may no longer see", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    const page = reading(registry, () => "acls", server.load);
    await server.settle(0, ready("12 rules"));
    page.reload();
    await server.settle(1, { kind: "forbidden" });

    expect(page.seen.at(-1)).toEqual({ kind: "forbidden" });
    page.stop();
  });

  it("releases the entry when the last reader goes away", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();

    const header = reading(registry, () => "clusters", server.load);
    const table = reading(registry, () => "clusters", server.load);
    await server.settle(0, ready("kafka-prod"));
    expect(registry.bound()).toBe(1);

    header.stop();
    // One reader left: the key is still being watched, so it is still being kept fresh.
    expect(registry.bound()).toBe(1);

    table.stop();
    expect(registry.bound()).toBe(0);

    // And nothing is refreshed on behalf of a page the user has left: this is what stops a tab
    // that has been open all day from polling for every screen it ever visited.
    registry.invalidate("clusters");
    expect(server.calls).toEqual(["clusters"]);
  });

  it("asks nothing while the key is undefined, and asks as soon as it is known", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();
    const [clusterId, setClusterId] = createSignal<string | undefined>(undefined);

    // A screen whose cluster id has not been resolved yet must not fetch `topics|undefined`, which
    // is a key the server would answer for and nothing on screen would ever want again.
    const page = reading(registry, clusterId, server.load);
    expect(server.calls).toEqual([]);
    expect(page.seen.at(-1)).toEqual({ kind: "loading" });

    setClusterId("kafka-prod");
    flush();
    expect(server.calls).toEqual(["kafka-prod"]);

    await server.settle(0, ready("kafka-prod"));
    expect(page.seen.at(-1)).toEqual({ kind: "ready", value: "kafka-prod" });
    page.stop();
  });

  it("switches to the new key when the key changes, and lets the old one go", async () => {
    const registry = createQueryRegistry();
    const server = stubLoader();
    const [key, setKey] = createSignal("topics|a");

    const page = reading(registry, key, server.load);
    await server.settle(0, ready("cluster a"));

    setKey("topics|b");
    flush();
    await server.settle(1, ready("cluster b"));

    expect(server.calls).toEqual(["topics|a", "topics|b"]);
    expect(page.seen.at(-1)).toEqual({ kind: "ready", value: "cluster b" });
    // Only the new key is still bound: browsing from one cluster to the next must not leave the
    // first one being kept fresh behind the page the user has left.
    expect(registry.bound()).toBe(1);
    page.stop();
    expect(registry.bound()).toBe(0);
  });
});
