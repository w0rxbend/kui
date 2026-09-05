/**
 * The icon set, re-exported.
 *
 * The glyphs themselves live one directory up, in `src/icon.tsx`, because they are shared with
 * every part of the kernel — controls, lists, cards and charts — and one keyed record is what makes
 * an icon name a type error rather than a blank square. This file exists so that a component in
 * `components/` can write `./Icon.jsx` and get the same set: there is one icon module in this
 * package, and this is a door onto it, not a second one.
 *
 * `Spinner` is re-exported too, because a caller that wants a busy glyph reaches for it in the
 * same breath as an icon. It is *defined* in `Spinner.tsx` rather than in the icon set, because it
 * is the one glyph that moves and the reduced-motion rule that suppresses it belongs beside it.
 */
export { Icon, iconNames, type IconName, type IconProps } from "../icon.jsx";
export { Spinner, type SpinnerProps } from "./Spinner.jsx";
