/**
 * The Appearance popover: accent, theme and density, applied the moment they are chosen.
 *
 * ## It writes the same three preferences the settings page writes
 *
 * Not equivalent ones — the same objects. `themePreference`, `accentPreference` and
 * `densityPreference` are module-level singletons in the kernel, each owning one `localStorage`
 * key and one attribute on `<html>`, and both this popover and `pages/SettingsPage.tsx` are handed
 * them rather than reaching for them. Two spellings of one preference is the failure to avoid here:
 * a second key, or a second attribute, would give a product where the popover and the settings page
 * disagree about what the theme is and neither is wrong on its own.
 *
 * They arrive as props for the reason the settings page states: a test that drove the singletons
 * would share `localStorage` with the next suite and would need a browser that has one.
 *
 * ## There is no OK and no Cancel
 *
 * `SCREENS-V4.md` §3.10 draws neither, and that is right rather than an omission. Each control is
 * one attribute on the root element, written on the click; the page you are looking at *is* the
 * preview. A Save would imply a round trip that does not exist and a state — chosen but not
 * applied — that cannot occur.
 *
 * ## Two departures from the drawing, both deliberate
 *
 * **The theme control has three segments, not two.** §7.4 records the conflict: the shipped
 * preference has `auto`, `light` and `dark`, and the drawn control has two segments. Settled here
 * in favour of keeping `auto`, because it is the default and it is the one the other two cannot
 * express — somebody on "follow the system" whose laptop turns dark at sunset expects KUI to turn
 * with it, and a two-segment control has nowhere to say that. The same finding asks that the glyph
 * name the *target* theme rather than the current one; that is refused for the same reason, since
 * a cycle of three has no single target, and the top bar's control keeps naming the mode in words.
 *
 * **The accent options are named, not four bare swatches.** The drawing is a row of colour chips
 * with a tick on the chosen one. A chip carries its meaning in colour alone, which is the one thing
 * `SCREENS.md` §4.0 forbids without exception. The four seeds are also declared on
 * `:root[data-accent="…"]` in `10-tokens.css` and nowhere else, so only the *current* accent is
 * reachable as a token: painting four chips at once would need either a second copy of the four hex
 * values in a shell stylesheet or four new tokens in a file this packet does not own, and the first
 * is the drift that the "no hex outside the token sheet" rule exists to prevent. Named segments
 * need neither, reuse the vocabulary the settings page already uses, and are the version a
 * colour-blind operator can actually operate.
 */
import type { JSX } from "@solidjs/web";
import { SegmentedControl } from "@kui/kernel";
import type { AccentChoice, DensityChoice, RootPreference, ThemeChoice } from "@kui/kernel";

/**
 * The three preferences the popover writes.
 *
 * `RootPreference` and not a narrower structural type: it is the kernel's own name for "a choice
 * that is one attribute on `<html>`", and naming it here is what makes it impossible to pass this
 * component anything but the real thing or a deliberate stand-in built by `createRootPreference`.
 */
export interface AppearancePreferences {
  readonly theme: RootPreference<ThemeChoice>;
  readonly accent: RootPreference<AccentChoice>;
  readonly density: RootPreference<DensityChoice>;
}

export interface AppearancePopoverProps {
  readonly preferences: AppearancePreferences;
  /** For the panel's own dismissal affordance; the caller decides what closing means. */
  readonly onClose?: (() => void) | undefined;
}

const THEMES = [
  /* `auto` first, because it is the default and the right answer for most people. The label says
     what it does rather than what it is called: "Auto" alone is a word nobody can act on. */
  { value: "auto", label: "Auto" },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
] as const satisfies readonly { readonly value: ThemeChoice; readonly label: string }[];

const ACCENTS = [
  { value: "blue", label: "Blue" },
  { value: "teal", label: "Teal" },
  { value: "green", label: "Green" },
  { value: "amber", label: "Amber" },
] as const satisfies readonly { readonly value: AccentChoice; readonly label: string }[];

const DENSITIES = [
  { value: "comfortable", label: "Comfortable" },
  { value: "compact", label: "Compact" },
] as const satisfies readonly { readonly value: DensityChoice; readonly label: string }[];

export function AppearancePopover(props: AppearancePopoverProps): JSX.Element {
  return (
    // `role="dialog"`, as the notifications panel is: it is a panel a control owns, it is dismissed
    // with Escape, and a screen reader has to be told it opened.
    <div
      class="kui-appearance"
      role="dialog"
      aria-label="Appearance"
      data-testid="appearance-popover"
      onKeyDown={(event) => {
        if (event.key === "Escape") props.onClose?.();
      }}
    >
      <h2 class="kui-appearance__title">Appearance</h2>

      <div class="kui-appearance__field">
        {/* The lettered caption is drawn (§3.10) and is decoration: each control below carries its
            own accessible name, and a second one here would be a heading over a group that is
            already named. */}
        <p class="kui-appearance__caption" aria-hidden="true">
          ACCENT
        </p>
        <SegmentedControl
          label="Accent colour"
          segments={ACCENTS}
          value={props.preferences.accent.choice()}
          onChange={(chosen) => props.preferences.accent.select(chosen)}
          size="sm"
          stretch
          testId="appearance-accent"
        />
      </div>

      <div class="kui-appearance__field">
        <p class="kui-appearance__caption" aria-hidden="true">
          THEME
        </p>
        <SegmentedControl
          label="Theme"
          segments={THEMES}
          value={props.preferences.theme.choice()}
          onChange={(chosen) => props.preferences.theme.select(chosen)}
          size="sm"
          stretch
          testId="appearance-theme"
        />
        {/* Worth the line: nobody guesses that "Auto" keeps following the system rather than
            resolving once at load, and it is the default, so it is the one most people are on. */}
        <p class="kui-appearance__help">
          Auto follows the system, including when it changes at sunset.
        </p>
      </div>

      <div class="kui-appearance__field">
        <p class="kui-appearance__caption" aria-hidden="true">
          DENSITY
        </p>
        <SegmentedControl
          label="Density"
          segments={DENSITIES}
          value={props.preferences.density.choice()}
          onChange={(chosen) => props.preferences.density.select(chosen)}
          size="sm"
          stretch
          testId="appearance-density"
        />
      </div>
    </div>
  );
}
