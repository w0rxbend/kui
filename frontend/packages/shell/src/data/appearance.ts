import { createSignal } from "solid-js";

import {
  userMessage,
  type ApiError,
  type KuiApiClient,
  type components,
} from "@kui/api";
import type {
  AccentChoice,
  DensityChoice,
  PreferenceStorage,
  RootPreference,
  ThemeChoice,
} from "@kui/kernel";

import { opaqueBrowserScopeKey } from "./alertCache.js";

const SETTINGS_PATH = "/api/v1/clusters/{clusterId}/settings/ui";
const CACHE_PREFIX = "kui.appearance.";
export const APPEARANCE_SAVE_DEBOUNCE_MS = 250;

type UiAppearanceDto = components["schemas"]["UiAppearanceDto"];

export interface AppearanceSnapshot {
  readonly theme: ThemeChoice;
  readonly accent: AccentChoice;
  readonly density: DensityChoice;
}

interface CachedAppearance {
  readonly appearance: AppearanceSnapshot;
  readonly dirty: boolean;
}

export type AppearanceSyncStatus =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "saving" }
  | { readonly kind: "saved" }
  | { readonly kind: "local-only"; readonly message: string };

export interface AppearancePreferences {
  readonly theme: RootPreference<ThemeChoice>;
  readonly accent: RootPreference<AccentChoice>;
  readonly density: RootPreference<DensityChoice>;
}

export interface AppearanceScope {
  readonly cluster: string;
  readonly principalKind: string;
  readonly principalName: string;
}

export interface AppearanceSync {
  readonly preferences: AppearancePreferences;
  readonly status: () => AppearanceSyncStatus;
  readonly selectScope: (scope: AppearanceScope | undefined) => void;
  readonly dispose: () => void;
}

export interface AppearanceSyncOptions {
  readonly api: KuiApiClient;
  readonly preferences: AppearancePreferences;
  readonly storage?: PreferenceStorage | undefined;
  readonly cacheKey?: ((scope: AppearanceScope) => Promise<string | undefined>) | undefined;
  readonly debounceMs?: number | undefined;
}

/**
 * Keeps instant, browser-cached appearance controls in step with the current principal's durable
 * cluster settings. The local copy paints first; the server copy reconciles afterwards.
 */
export function createAppearanceSync(options: AppearanceSyncOptions): AppearanceSync {
  const [status, setStatus] = createSignal<AppearanceSyncStatus>({ kind: "idle" });
  const debounceMs = options.debounceMs ?? APPEARANCE_SAVE_DEBOUNCE_MS;
  const cacheKeyOf =
    options.cacheKey ??
    ((scope: AppearanceScope) =>
      opaqueBrowserScopeKey("appearance-v1", [
        scope.principalKind,
        scope.principalName,
        scope.cluster,
      ]));
  let selectedCluster: string | undefined;
  let selectedCacheKey: string | undefined;
  let episode = 0;
  let revision = 0;
  let saveTimer: ReturnType<typeof setTimeout> | undefined;

  const snapshot = (): AppearanceSnapshot => ({
    theme: options.preferences.theme.choice(),
    accent: options.preferences.accent.choice(),
    density: options.preferences.density.choice(),
  });

  const apply = (appearance: AppearanceSnapshot): void => {
    options.preferences.theme.select(appearance.theme);
    options.preferences.accent.select(appearance.accent);
    options.preferences.density.select(appearance.density);
  };

  const persist = async (
    cluster: string,
    cacheKey: string | undefined,
    appearance: AppearanceSnapshot,
    expectedEpisode: number,
    expectedRevision: number,
  ): Promise<void> => {
    const answer = await options.api.put(SETTINGS_PATH, {
      params: { path: { clusterId: cluster } },
      body: appearance,
    });
    if (
      expectedEpisode !== episode ||
      expectedRevision !== revision ||
      selectedCluster !== cluster
    )
      return;

    if (answer.ok) {
      const saved = decodeAppearance(answer.value);
      if (saved === undefined) {
        setLocalOnly(cacheKey, appearance, decodingError());
        return;
      }
      apply(saved);
      writeCache(options.storage, cacheKey, { appearance: saved, dirty: false });
      setStatus({ kind: "saved" });
    } else {
      setLocalOnly(cacheKey, appearance, answer.error);
    }
  };

  const scheduleSave = (cluster: string, delay = debounceMs): void => {
    if (saveTimer !== undefined) clearTimeout(saveTimer);
    const expectedEpisode = episode;
    const expectedRevision = revision;
    const appearance = snapshot();
    const cacheKey = selectedCacheKey;
    setStatus({ kind: "saving" });
    saveTimer = setTimeout(() => {
      saveTimer = undefined;
      void persist(cluster, cacheKey, appearance, expectedEpisode, expectedRevision);
    }, delay);
  };

  const changed = <A extends string>(preference: RootPreference<A>, chosen: A): void => {
    preference.select(chosen);
    const cluster = selectedCluster;
    if (cluster === undefined) return;
    revision += 1;
    writeCache(options.storage, selectedCacheKey, { appearance: snapshot(), dirty: true });
    scheduleSave(cluster);
  };

  const wrap = <A extends string>(preference: RootPreference<A>): RootPreference<A> => ({
    choice: preference.choice,
    install: preference.install,
    select: (chosen) => changed(preference, chosen),
  });

  const setLocalOnly = (
    cacheKey: string | undefined,
    appearance: AppearanceSnapshot,
    error: ApiError,
  ): void => {
    writeCache(options.storage, cacheKey, { appearance, dirty: true });
    setStatus({
      kind: "local-only",
      message: `${userMessage(error)} Your appearance remains saved in this browser.`,
    });
  };

  const selectScope = (scope: AppearanceScope | undefined): void => {
    const cluster = scope?.cluster;
    selectedCluster = undefined;
    selectedCacheKey = undefined;
    episode += 1;
    revision += 1;
    if (saveTimer !== undefined) {
      clearTimeout(saveTimer);
      saveTimer = undefined;
    }
    if (scope === undefined || cluster === undefined) {
      setStatus({ kind: "idle" });
      return;
    }

    const expectedEpisode = episode;
    const expectedRevision = revision;
    selectedCluster = cluster;
    setStatus({ kind: "loading" });
    void cacheKeyOf(scope)
      .catch(() => undefined)
      .then((cacheKey) => {
        if (expectedEpisode !== episode || expectedRevision !== revision) return;
        selectedCacheKey = cacheKey;
        const cached = readCache(options.storage, cacheKey);
        if (cached !== undefined) apply(cached.appearance);

        if (cached?.dirty === true) {
          scheduleSave(cluster, 0);
          return;
        }

        void options.api
          .get(SETTINGS_PATH, { params: { path: { clusterId: cluster } } })
          .then((answer) => {
            if (
              expectedEpisode !== episode ||
              expectedRevision !== revision ||
              selectedCluster !== cluster
            )
              return;
            if (!answer.ok) {
              setLocalOnly(cacheKey, snapshot(), answer.error);
              return;
            }
            const remote = decodeAppearance(answer.value);
            if (remote === undefined) {
              setLocalOnly(cacheKey, snapshot(), decodingError());
              return;
            }
            apply(remote);
            writeCache(options.storage, cacheKey, { appearance: remote, dirty: false });
            setStatus({ kind: "saved" });
          });
      });
  };

  return {
    preferences: {
      theme: wrap(options.preferences.theme),
      accent: wrap(options.preferences.accent),
      density: wrap(options.preferences.density),
    },
    status,
    selectScope,
    dispose(): void {
      episode += 1;
      if (saveTimer !== undefined) clearTimeout(saveTimer);
    },
  };
}

function decodeAppearance(value: UiAppearanceDto): AppearanceSnapshot | undefined {
  const theme = oneOf(value.theme, ["auto", "light", "dark"] as const);
  const accent = oneOf(value.accent, ["blue", "teal", "green", "amber"] as const);
  const density = oneOf(value.density, ["comfortable", "compact"] as const);
  return theme === undefined || accent === undefined || density === undefined
    ? undefined
    : { theme, accent, density };
}

function oneOf<A extends string>(value: string, allowed: readonly A[]): A | undefined {
  return allowed.find((candidate) => candidate === value);
}

function readCache(
  storage: PreferenceStorage | undefined,
  cacheKey: string | undefined,
): CachedAppearance | undefined {
  if (storage === undefined || cacheKey === undefined) return undefined;
  try {
    const raw = storage.getItem(`${CACHE_PREFIX}${cacheKey}`);
    if (raw === null) return undefined;
    const parsed = JSON.parse(raw) as unknown;
    if (typeof parsed !== "object" || parsed === null) return undefined;
    const record = parsed as Record<string, unknown>;
    const appearance = decodeUnknownAppearance(record.appearance);
    return appearance === undefined || typeof record.dirty !== "boolean"
      ? undefined
      : { appearance, dirty: record.dirty };
  } catch {
    return undefined;
  }
}

function writeCache(
  storage: PreferenceStorage | undefined,
  cacheKey: string | undefined,
  cached: CachedAppearance,
): void {
  if (storage === undefined || cacheKey === undefined) return;
  try {
    storage.setItem(`${CACHE_PREFIX}${cacheKey}`, JSON.stringify(cached));
  } catch {
    // The page still changes immediately; only cross-reload caching is unavailable.
  }
}

function decodeUnknownAppearance(value: unknown): AppearanceSnapshot | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const record = value as Record<string, unknown>;
  if (
    typeof record.theme !== "string" ||
    typeof record.accent !== "string" ||
    typeof record.density !== "string"
  )
    return undefined;
  return decodeAppearance({
    theme: record.theme,
    accent: record.accent,
    density: record.density,
  });
}

function decodingError(): ApiError {
  return { kind: "decoding", cause: "invalid appearance settings" };
}
