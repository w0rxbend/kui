import { createSignal } from "solid-js";

import {
  createRootPreference,
  type PreferenceStorage,
  type RootPreference,
} from "./rootPreference.js";

/**
 * What the user asked for, which is not the same as what is displayed.
 *
 * `auto` is a third state and not a synonym for one of the other two. Somebody on `auto` whose
 * laptop switches to dark in the evening expects KUI to switch with it; somebody who picked
 * `light` expects KUI to stay light at midnight.
 */
export type ThemeChoice = "auto" | "light" | "dark";

/** The theme actually on screen, with `auto` already resolved. Never `auto`. */
export type EffectiveTheme = "light" | "dark";

/**
 * Which accent the product is painted in.
 *
 * Four interchangeable seed palettes, offered as a control rather than fixed at build time because
 * offering them is nearly free: the neutral ramp — every surface, every text colour, every status
 * colour — is identical under all four, so a seed redefines five custom properties and repaints.
 * `blue` is the design's default and is what plain `:root` declares, so a page that has never
 * touched this preference carries no `data-accent` attribute at all.
 */
export type AccentChoice = "blue" | "teal" | "green" | "amber";

/**
 * How much vertical air a table row has.
 *
 * Density is a switch, not a theme. `compact` moves the padding inside a table row from 15px to
 * 9px and changes nothing else — not the type size, not the gaps between sections, not the height
 * of a control. That restraint is the point: an operator scanning thousands of topics wants more
 * rows on screen, and shrinking everything else would only make the interface harder to hit.
 */
export type DensityChoice = "comfortable" | "compact";

const THEME_VALUES: readonly ThemeChoice[] = ["auto", "light", "dark"];
const ACCENT_VALUES: readonly AccentChoice[] = [
  "blue",
  "teal",
  "green",
  "amber",
];
const DENSITY_VALUES: readonly DensityChoice[] = ["comfortable", "compact"];

const DARK_QUERY = "(prefers-color-scheme: dark)";

/**
 * The theme preference, plus the one thing theme has that the other two do not: a resolved answer.
 */
export interface ThemePreference extends RootPreference<ThemeChoice> {
  /**
   * The theme on screen, with `auto` resolved against the operating system. Never `auto`, so a
   * component that needs to know whether it is currently dark reads this and not `choice`.
   */
  readonly effective: () => EffectiveTheme;
}

export interface ThemePreferenceOptions {
  /** Where the choice is remembered. Defaults to `localStorage`; `null` does not persist. */
  readonly storage?: PreferenceStorage | null;
  /** The element the attribute is written on. Defaults to `<html>`. */
  readonly root?: Element;
  /** Whether the operating system is currently asking for a dark interface. */
  readonly systemPrefersDark?: () => boolean;
}

/**
 * Builds the theme preference against a given root, storage and system-preference source.
 *
 * ## How three states become two palettes
 *
 * The stylesheet declares light on `:root`, dark under `@media (prefers-color-scheme: dark)` for
 * the system preference, and dark again under `:root[data-theme="dark"]` for an explicit choice.
 * All this has to do is keep `data-theme` in step:
 *
 *   - `auto` removes the attribute, so only the media query decides;
 *   - `light` writes `data-theme="light"`, which the media query's `:root:not([data-theme="light"])`
 *     guard excludes, so dark cannot win;
 *   - `dark` writes `data-theme="dark"`, written last in the file and therefore winning over a
 *     system set to light.
 *
 * A contrast test asserts that the two dark palettes stay identical, because a user who picks dark
 * by hand must not see a different product from one whose laptop picked it.
 *
 * It takes its collaborators as arguments so that a test can supply its own storage (to prove a
 * choice survives a reload, and that a browser with storage disabled still works) and its own
 * answer for the system preference (to prove `auto` follows it). `themePreference` below is the
 * application's one instance, wired to the real browser.
 */
export function createThemePreference(
  options: ThemePreferenceOptions = {},
): ThemePreference {
  const systemPrefersDark =
    options.systemPrefersDark ?? watchSystemDarkPreference();

  const preference = createRootPreference<ThemeChoice>({
    ...(options.storage === undefined ? {} : { storage: options.storage }),
    ...(options.root === undefined ? {} : { root: options.root }),
    attribute: "data-theme",
    storageKey: "kui.theme",
    values: THEME_VALUES,
    fallback: "auto",
    // `auto` must remove the attribute rather than write "auto": the media query matches on the
    // attribute's *absence*, so leaving a value behind would pin the theme.
    attributeValue: (chosen) => (chosen === "auto" ? null : chosen),
  });

  return {
    ...preference,
    effective(): EffectiveTheme {
      const chosen = preference.choice();
      if (chosen !== "auto") return chosen;
      return systemPrefersDark() ? "dark" : "light";
    },
  };
}

/**
 * Tracks the operating system's dark-mode preference and its changes.
 *
 * `matchMedia` gives both the current answer and an event when it changes, which is what makes
 * `auto` genuinely automatic: a laptop switching to dark at sunset re-themes an open tab without a
 * reload.
 *
 * Its absence is not a reason to fail to start. jsdom does not implement it, some embedded
 * browsers do not either, and a hardened configuration can have the method and refuse the query.
 * In all three cases the honest answer is "the system is not asking for dark", so `auto` behaves
 * as light and the two explicit choices still work.
 */
export function watchSystemDarkPreference(): () => boolean {
  let query: MediaQueryList | undefined;
  try {
    query =
      typeof matchMedia === "undefined" ? undefined : matchMedia(DARK_QUERY);
  } catch {
    query = undefined;
  }

  if (query === undefined) return () => false;

  const list = query;
  const [prefersDark, setPrefersDark] = createSignal(list.matches);
  // A listener, not an effect: it is called by the browser rather than by the reactive graph, so
  // the write is allowed and lands where a write belongs.
  list.addEventListener("change", () => setPrefersDark(list.matches));
  return prefersDark;
}

/** The application's theme, wired to the real browser. */
export const themePreference: ThemePreference = createThemePreference();

/** The application's accent, wired to the real browser. */
export const accentPreference: RootPreference<AccentChoice> =
  createRootPreference<AccentChoice>({
    attribute: "data-accent",
    storageKey: "kui.accent",
    values: ACCENT_VALUES,
    fallback: "blue",
    // Blue is what plain `:root` already declares, so the default writes no attribute at all.
    attributeValue: (chosen) => (chosen === "blue" ? null : chosen),
  });

/** The application's density, wired to the real browser. */
export const densityPreference: RootPreference<DensityChoice> =
  createRootPreference<DensityChoice>({
    attribute: "data-density",
    storageKey: "kui.density",
    values: DENSITY_VALUES,
    fallback: "comfortable",
    // Comfortable is what plain `:root` declares, so only compact writes an attribute.
    attributeValue: (chosen) => (chosen === "compact" ? "compact" : null),
  });

/**
 * Puts all three attributes on `<html>`. Called once by the shell, before the first paint.
 *
 * Doing it in one call is deliberate: the three preferences are read from storage when this module
 * is first imported, and if only some of them were installed the page would come up with, say, a
 * remembered accent and a forgotten density, which is the kind of half-applied state nobody
 * reports as a bug because it looks like they mis-remembered what they chose.
 */
export function installAppearance(): void {
  themePreference.install();
  accentPreference.install();
  densityPreference.install();
}
