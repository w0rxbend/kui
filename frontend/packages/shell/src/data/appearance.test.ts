import { afterEach, describe, expect, it, vi } from "vitest";

import { err, ok, type KuiApiClient } from "@kui/api";
import type {
  AccentChoice,
  DensityChoice,
  PreferenceStorage,
  RootPreference,
  ThemeChoice,
} from "@kui/kernel";

import { APPEARANCE_SAVE_DEBOUNCE_MS, createAppearanceSync } from "./appearance.js";

const scope = (principalName = "alice") => ({
  cluster: "prod",
  principalKind: "user",
  principalName,
});

const cacheKey = async (selected: ReturnType<typeof scope>): Promise<string> =>
  `${selected.cluster}.${selected.principalName}`;

function preference<A extends string>(initial: A): RootPreference<A> {
  let value = initial;
  return {
    choice: () => value,
    select: (next) => {
      value = next;
    },
    install: () => undefined,
  };
}

function memoryStorage(initial: Record<string, string> = {}): PreferenceStorage {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
}

const light = { theme: "light", accent: "blue", density: "comfortable" } as const;
const dark = { theme: "dark", accent: "teal", density: "compact" } as const;

afterEach(() => {
  vi.useRealTimers();
});

describe("appearance persistence", () => {
  it("hydrates the selected cluster from the backend", async () => {
    const get = vi.fn().mockResolvedValue(ok(dark));
    const sync = createAppearanceSync({
      api: { get } as unknown as KuiApiClient,
      preferences: {
        theme: preference<ThemeChoice>("auto"),
        accent: preference<AccentChoice>("blue"),
        density: preference<DensityChoice>("comfortable"),
      },
      storage: memoryStorage(),
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.waitFor(() => expect(sync.status().kind).toBe("saved"));

    expect(get).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/ui", {
      params: { path: { clusterId: "prod" } },
    });
    expect(sync.preferences.theme.choice()).toBe("dark");
    expect(sync.preferences.accent.choice()).toBe("teal");
    expect(sync.preferences.density.choice()).toBe("compact");
  });

  it("applies immediately, coalesces clicks, and marks the cached value clean after save", async () => {
    vi.useFakeTimers();
    const storage = memoryStorage();
    const put = vi.fn().mockResolvedValue(ok(dark));
    const sync = createAppearanceSync({
      api: {
        get: vi.fn().mockResolvedValue(ok(light)),
        put,
      } as unknown as KuiApiClient,
      preferences: {
        theme: preference<ThemeChoice>("auto"),
        accent: preference<AccentChoice>("blue"),
        density: preference<DensityChoice>("comfortable"),
      },
      storage,
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.advanceTimersByTimeAsync(0);
    sync.preferences.theme.select("dark");
    sync.preferences.accent.select("teal");
    sync.preferences.density.select("compact");
    await vi.advanceTimersByTimeAsync(0);

    expect(sync.preferences.theme.choice()).toBe("dark");
    expect(sync.status().kind).toBe("saving");
    expect(put).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(APPEARANCE_SAVE_DEBOUNCE_MS);
    expect(put).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/ui", {
      params: { path: { clusterId: "prod" } },
      body: dark,
    });
    expect(sync.status().kind).toBe("saved");
    expect(storage.getItem("kui.appearance.prod.alice")).toContain('"dirty":false');
  });

  it("retries an unsynced browser value after reload instead of overwriting it", async () => {
    vi.useFakeTimers();
    const storage = memoryStorage({
      "kui.appearance.prod.alice": JSON.stringify({ appearance: dark, dirty: true }),
    });
    const get = vi.fn().mockResolvedValue(ok(light));
    const put = vi.fn().mockResolvedValue(
      err({ kind: "unreachable", cause: "offline" }),
    );
    const sync = createAppearanceSync({
      api: { get, put } as unknown as KuiApiClient,
      preferences: {
        theme: preference<ThemeChoice>("auto"),
        accent: preference<AccentChoice>("blue"),
        density: preference<DensityChoice>("comfortable"),
      },
      storage,
      cacheKey,
    });

    sync.selectScope(scope());
    await vi.advanceTimersByTimeAsync(0);
    expect(sync.preferences.theme.choice()).toBe("dark");
    await vi.advanceTimersByTimeAsync(0);

    expect(get).not.toHaveBeenCalled();
    expect(put).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/ui", {
      params: { path: { clusterId: "prod" } },
      body: dark,
    });
    expect(sync.status()).toMatchObject({ kind: "local-only" });
    expect(storage.getItem("kui.appearance.prod.alice")).toContain('"dirty":true');
  });

  it("does not reuse or upload another principal's dirty snapshot", async () => {
    vi.useFakeTimers();
    const storage = memoryStorage({
      "kui.appearance.prod.alice": JSON.stringify({ appearance: dark, dirty: true }),
    });
    const get = vi.fn().mockResolvedValue(ok(light));
    const put = vi.fn().mockResolvedValue(ok(dark));
    const sync = createAppearanceSync({
      api: { get, put } as unknown as KuiApiClient,
      preferences: {
        theme: preference<ThemeChoice>("auto"),
        accent: preference<AccentChoice>("blue"),
        density: preference<DensityChoice>("comfortable"),
      },
      storage,
      cacheKey,
    });

    sync.selectScope(scope("bob"));
    await vi.advanceTimersByTimeAsync(0);

    expect(put).not.toHaveBeenCalled();
    expect(get).toHaveBeenCalledWith("/api/v1/clusters/{clusterId}/settings/ui", {
      params: { path: { clusterId: "prod" } },
    });
    expect(sync.preferences.theme.choice()).toBe("light");
  });
});
