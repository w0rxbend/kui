import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createRootPreference } from "@kui/kernel";
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";
import { AppearancePopover, type AppearancePreferences } from "./AppearancePopover.jsx";

/**
 * `SCREENS-V4.md` §3.10, with the two departures the component's own header argues for: three theme
 * segments rather than two, and named accent options rather than four bare colour chips.
 *
 * Every story here writes to its **own** preferences rather than to the application's, built with
 * `storage: null` and a detached root element. Driving the real singletons from a story would
 * repaint Storybook itself and remember the result across a reload, so opening the last story in
 * the file would leave whoever opened it looking at a compact amber product.
 */
const meta = {
  title: "Chrome/AppearancePopover",
  component: AppearancePopover,
  parameters: { layout: "centered" },
} satisfies Meta<typeof AppearancePopover>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Preferences that write nowhere.
 *
 * A fresh `<div>` as the root and no storage: the attributes land on an element nothing is styled
 * against, so choosing "Dark" here changes the control and not the page. That is the right
 * behaviour for a story and exactly the wrong one for the product, which is why the application
 * hands the popover the kernel's singletons instead.
 */
function detached(
  theme: ThemeChoice,
  accent: AccentChoice,
  density: DensityChoice,
): AppearancePreferences {
  const root = document.createElement("div");
  return {
    theme: createRootPreference<ThemeChoice>({
      attribute: "data-theme",
      storageKey: "kui.theme",
      values: ["auto", "light", "dark"],
      fallback: theme,
      attributeValue: (chosen) => (chosen === "auto" ? null : chosen),
      storage: null,
      root,
    }),
    accent: createRootPreference<AccentChoice>({
      attribute: "data-accent",
      storageKey: "kui.accent",
      values: ["blue", "teal", "green", "amber"],
      fallback: accent,
      attributeValue: (chosen) => (chosen === "blue" ? null : chosen),
      storage: null,
      root,
    }),
    density: createRootPreference<DensityChoice>({
      attribute: "data-density",
      storageKey: "kui.density",
      values: ["comfortable", "compact"],
      fallback: density,
      attributeValue: (chosen) => (chosen === "compact" ? "compact" : null),
      storage: null,
      root,
    }),
  };
}

/** The defaults: follow the system, blue, comfortable. What a fresh install opens on. */
export const Defaults: Story = { args: { preferences: detached("auto", "blue", "comfortable") } };

/**
 * Every control moved off its default.
 *
 * Worth looking at because it is the one that shows all three selections at once, and because the
 * chosen segment has to be legible in both themes — a selected segment whose ink is chosen for one
 * palette is the classic way this control fails review.
 */
export const AllChanged: Story = { args: { preferences: detached("dark", "amber", "compact") } };

/**
 * "Light" chosen explicitly, which is not the same preference as "auto, and it is daytime".
 *
 * The third segment is the whole reason the shipped control has three values and the drawing has
 * two (§7.4): a two-segment control cannot say "keep following the system".
 */
export const ExplicitLight: Story = {
  args: { preferences: detached("light", "teal", "comfortable") },
};
