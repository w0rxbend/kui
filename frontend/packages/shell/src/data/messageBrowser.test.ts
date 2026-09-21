import { afterEach, describe, expect, it, vi } from "vitest";

import { err, ok, type KuiApiClient } from "@kui/api";
import type { PreferenceStorage } from "@kui/kernel";

import {
  MESSAGE_BROWSER_SAVE_DEBOUNCE_MS,
  createMessageBrowserSync,
} from "./messageBrowser.js";

const scope = (principalName = "alice") => ({
  cluster: "prod",
  principalKind: "user",
  principalName,
});

const cacheKey = async (selected: ReturnType<typeof scope>): Promise<string> =>
  `${selected.cluster}.${selected.principalName}`;

function memoryStorage(initial: Record<string, string> = {}): PreferenceStorage {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
}

const pages = { pageSize: 100, mode: "pages" } as const;
const infinite = { pageSize: 250, mode: "infinite" } as const;

afterEach(() => {
  vi.useRealTimers();
});

describe("message browser settings persistence", () => {
  it("hydrates the selected cluster from the backend", async () => {
    const get = vi.fn().mockResolvedValue(ok(infinite));
    const sync = createMessageBrowserSync({
      api: { get } as unknown as KuiApiClient,
      storage: memoryStorage(),
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.waitFor(() => expect(sync.status().kind).toBe("saved"));

    expect(get).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/messages", {
      params: { path: { clusterId: "prod" } },
    });
    expect(sync.preferences.pageSize.choice()).toBe(250);
    expect(sync.preferences.mode.choice()).toBe("infinite");
  });

  it("applies immediately, coalesces changes and marks the browser cache clean", async () => {
    vi.useFakeTimers();
    const storage = memoryStorage();
    const put = vi.fn().mockResolvedValue(ok(infinite));
    const sync = createMessageBrowserSync({
      api: {
        get: vi.fn().mockResolvedValue(ok(pages)),
        put,
      } as unknown as KuiApiClient,
      storage,
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.advanceTimersByTimeAsync(0);
    sync.preferences.pageSize.select(250);
    sync.preferences.mode.select("infinite");
    await vi.advanceTimersByTimeAsync(0);

    expect(sync.preferences.pageSize.choice()).toBe(250);
    expect(sync.status().kind).toBe("saving");
    expect(put).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(MESSAGE_BROWSER_SAVE_DEBOUNCE_MS);
    expect(put).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/messages", {
      params: { path: { clusterId: "prod" } },
      body: infinite,
    });
    expect(sync.status().kind).toBe("saved");
    expect(storage.getItem("kui.message-browser.prod.alice")).toContain('"dirty":false');
  });

  it("retries a dirty browser value instead of replacing it with a remote default", async () => {
    vi.useFakeTimers();
    const storage = memoryStorage({
      "kui.message-browser.prod.alice": JSON.stringify({ settings: infinite, dirty: true }),
    });
    const get = vi.fn().mockResolvedValue(ok(pages));
    const put = vi.fn().mockResolvedValue(err({ kind: "unreachable", cause: "offline" }));
    const sync = createMessageBrowserSync({
      api: { get, put } as unknown as KuiApiClient,
      storage,
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);

    expect(get).not.toHaveBeenCalled();
    expect(put).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/messages", {
      params: { path: { clusterId: "prod" } },
      body: infinite,
    });
    expect(sync.status()).toMatchObject({ kind: "local-only" });
    expect(storage.getItem("kui.message-browser.prod.alice")).toContain('"dirty":true');
  });

  it("ignores malformed remote and cached values", async () => {
    const storage = memoryStorage({
      "kui.message-browser.prod.alice": JSON.stringify({
        settings: { pageSize: 999, mode: "stream" },
        dirty: false,
      }),
    });
    const sync = createMessageBrowserSync({
      api: {
        get: vi.fn().mockResolvedValue(ok({ pageSize: 0, mode: "stream" })),
      } as unknown as KuiApiClient,
      storage,
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.waitFor(() => expect(sync.status().kind).toBe("local-only"));

    expect(sync.preferences.pageSize.choice()).toBe(100);
    expect(sync.preferences.mode.choice()).toBe("pages");
  });
});
