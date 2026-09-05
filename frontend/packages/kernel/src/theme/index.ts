/**
 * Theme, accent and density: the three attributes on `<html>` that select among the palettes and
 * measurements declared in `styles/10-tokens.css`. No colour or length is computed here.
 */

export {
  createRootPreference,
  type PreferenceStorage,
  type RootPreference,
  type RootPreferenceOptions,
} from "./rootPreference.js";

export {
  accentPreference,
  createThemePreference,
  densityPreference,
  installAppearance,
  themePreference,
  watchSystemDarkPreference,
  type AccentChoice,
  type DensityChoice,
  type EffectiveTheme,
  type ThemeChoice,
  type ThemePreference,
  type ThemePreferenceOptions,
} from "./appearance.js";
