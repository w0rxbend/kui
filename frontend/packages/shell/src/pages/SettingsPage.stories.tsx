import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";
import { SettingsPage } from "./SettingsPage.jsx";

/**
 * The four preferences, and the two facts a bug report needs.
 *
 * It reads nothing from any service, which is the point: it is one of two screens that has to keep
 * working when everything behind KUI is down. `NothingReported` is the story that matters — a value
 * the shell was not told is the words "not reported", never a blank, because a blank reads as a
 * rendering fault where the absence is itself worth putting in the report.
 *
 * The controls in `Interactive` are wired to local signals, so the selects actually move. They do
 * not repaint Storybook, because the story holds the choice rather than the kernel's singleton — the
 * page takes its preferences as props precisely so this is possible.
 */
const meta: Meta<typeof SettingsPage> = {
  title: "Screens/Settings",
  component: SettingsPage,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const fixed = <A extends string>(value: A) => ({ choice: () => value, select: () => undefined });

export const Default: Story = {
  args: {
    theme: fixed<ThemeChoice>("auto"),
    accent: fixed<AccentChoice>("blue"),
    density: fixed<DensityChoice>("comfortable"),
    version: "1.4.2+build.7c1f0a3",
    apiBase: "https://kui.internal/api/v1",
  },
};

/** A shell that was not told its build. Says so, rather than leaving a gap. */
export const NothingReported: Story = {
  args: {
    theme: fixed<ThemeChoice>("dark"),
    accent: fixed<AccentChoice>("teal"),
    density: fixed<DensityChoice>("compact"),
  },
};

export const Interactive: Story = {
  render: () => {
    const [theme, setTheme] = createSignal<ThemeChoice>("auto");
    const [accent, setAccent] = createSignal<AccentChoice>("blue");
    const [density, setDensity] = createSignal<DensityChoice>("comfortable");
    return (
      <SettingsPage
        theme={{ choice: theme, select: setTheme }}
        accent={{ choice: accent, select: setAccent }}
        density={{ choice: density, select: setDensity }}
        version="1.4.2+build.7c1f0a3"
        apiBase="https://kui.internal/api/v1"
      />
    );
  },
};
