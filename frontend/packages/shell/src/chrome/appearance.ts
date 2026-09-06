/**
 * The words this product uses for the three appearance preferences, in one place.
 *
 * ## Why a shared table rather than two good ones
 *
 * There are two controls over the same three preferences — the top bar's `AppearancePopover` and
 * `pages/SettingsPage.tsx` — and until this file existed each carried its own copy of the option
 * lists. They had already drifted on the option that matters most: the popover called the default
 * theme "Auto" and the settings page called it "Match the system". Two words for one preference is
 * not a cosmetic difference. An operator who sets "Match the system" on the settings page and then
 * opens the popover sees a control whose selected segment says something else, and the only way to
 * find out whether those are the same setting is to change one and watch the other.
 *
 * So the vocabulary is published from here and consumed by both. A third control added later takes
 * the same table; a rename happens once.
 *
 * ## Why "Auto" won and "Match the system" lost
 *
 * The popover's control is a three-segment `SegmentedControl` at `sm`, stretched across a 240px
 * panel. "Match the system" is sixteen characters in a segment that has room for about eight, so
 * keeping it would have meant either a truncated segment or a popover that no longer fits over the
 * drawer. The sentence it was carrying is not lost: every option may declare {@link help}, and both
 * controls draw it beside the control rather than inside a segment — which is where an explanation
 * belongs anyway, because it is a sentence and a segment is a name.
 */
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";

/**
 * One option of one preference.
 *
 * `help` is the sentence a control may draw beside itself. It is optional because most of these
 * options need none: "Light" means light, and a help line under an option that explains itself is
 * noise that teaches the reader to skip the ones that do not.
 */
export interface AppearanceOption<A extends string> {
  readonly value: A;
  readonly label: string;
  readonly help?: string | undefined;
}

/**
 * `auto` first, because it is the default and the right answer for most people: a laptop that
 * switches to dark at sunset re-themes an open tab with nobody choosing anything.
 *
 * Three values and not the two `SCREENS-V4.md` §3.10 draws. §7.4 records the conflict and
 * `AppearancePopover`'s own header settles it: `auto` is the one value the other two cannot
 * express, and dropping it would take the default away from everybody already on it.
 */
export const THEME_OPTIONS: readonly AppearanceOption<ThemeChoice>[] = [
  {
    value: "auto",
    label: "Auto",
    /* Worth the sentence wherever there is room for one: nobody guesses that "Auto" keeps following
       the system rather than resolving once at load, and it is the value most people are on. */
    help: "Auto follows the system, including when it changes at sunset.",
  },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
];

/**
 * The four accent seeds, named rather than drawn as bare swatches.
 *
 * `AppearancePopover`'s header argues the case: a colour chip carries its meaning in colour alone,
 * which `SCREENS.md` §4.0 forbids without exception, and the four hex values are declared on
 * `:root[data-accent="…"]` in the token sheet and nowhere else — so painting four chips at once
 * would need a second copy of them in a stylesheet.
 */
export const ACCENT_OPTIONS: readonly AppearanceOption<AccentChoice>[] = [
  {
    value: "blue",
    label: "Blue",
    help: "The colour used for the selected item and the primary action.",
  },
  { value: "teal", label: "Teal" },
  { value: "green", label: "Green" },
  { value: "amber", label: "Amber" },
];

/** Comfortable or compact. Density tightens the tables and changes nothing else. */
export const DENSITY_OPTIONS: readonly AppearanceOption<DensityChoice>[] = [
  { value: "comfortable", label: "Comfortable" },
  {
    value: "compact",
    label: "Compact",
    help: "Compact fits more rows on screen by tightening the tables, and changes nothing else.",
  },
];

/**
 * The sentence a control draws beside one of these lists, or `undefined` when none of its options
 * carries one.
 *
 * The *first* help line rather than all of them, and that is the rule the two controls share: a
 * panel of three explanations under a three-segment control is longer than the control and reads as
 * documentation rather than as a hint. The option that needs explaining is the one that carries the
 * sentence, and there is at most one per preference.
 */
export function appearanceHelp<A extends string>(
  options: readonly AppearanceOption<A>[],
): string | undefined {
  return options.find((option) => option.help !== undefined)?.help;
}
