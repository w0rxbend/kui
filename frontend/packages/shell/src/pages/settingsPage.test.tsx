/**
 * The settings page's three controls, and the two that are deliberately not there.
 *
 * ## Why an absence is worth a test
 *
 * Timezone and refresh rate are on the settings screen of every product this one is compared to, and
 * adding either is a five-minute change that looks like an improvement. It is not: nothing in KUI
 * reads either value, so the control would answer "can I change this?" with a yes that is false, and
 * the operator would go on seeing timestamps in the browser's own zone with a setting beside them
 * claiming otherwise. `docs/FEATURE_MATRIX.md` records both as absent rather than as gaps, and this
 * is the assertion that keeps the code and that document agreeing.
 *
 * The rest of the file is the property the page exists for: each control writes to its own
 * preference and to nothing else. The preferences arrive as props precisely so that this is
 * observable without sharing `localStorage` with the next suite.
 */

import { afterEach, describe, expect, it } from "vitest";
import { flush } from "solid-js";
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";

import type { RootPreference } from "@kui/kernel";

import { SettingsPage, type Preference } from "./SettingsPage.jsx";
import { AppearancePopover } from "../chrome/AppearancePopover.jsx";
import { THEME_OPTIONS, appearanceHelp } from "../chrome/appearance.js";
import { mount, type Mounted } from "../chrome/testing.js";

/**
 * Every container this file mounted, torn down after the case whatever the case did.
 *
 * The cases below that predate it dispose by hand at the end of the body, which stops happening the
 * moment one of them fails. `afterEach` runs after a throw.
 */
const mounted: Mounted[] = [];

afterEach(() => {
  for (const each of mounted.splice(0)) each.dispose();
});

const keep = (m: Mounted): Mounted => {
  mounted.push(m);
  return m;
};

/** A preference that records what it was told, so a case can assert who wrote to it. */
function recorder<A extends string>(initial: A): Preference<A> & { readonly written: A[] } {
  const written: A[] = [];
  return { choice: () => initial, select: (chosen) => void written.push(chosen), written };
}

function page() {
  const theme = recorder<ThemeChoice>("auto");
  const accent = recorder<AccentChoice>("blue");
  const density = recorder<DensityChoice>("comfortable");
  const mounted = mount(() => (
    <SettingsPage
      theme={theme}
      accent={accent}
      density={density}
      version="1.4.2+build.7c1f0a3"
      apiBase="https://kui.internal/api/v1"
    />
  ));
  return { ...mounted, theme, accent, density };
}

/**
 * The controls, in document order, paired with the label each one announces.
 *
 * `Select` is a `role="combobox"` button and not a native `<select>` — its own header says why — so
 * the label is reached through `aria-labelledby`, which is also the string a screen reader reads.
 * Asserting the accessible name rather than a nearby text node is what makes this a test of what the
 * page says rather than of how it is nested.
 */
function controls(container: HTMLElement): readonly { readonly label: string; readonly el: HTMLElement }[] {
  return [...container.querySelectorAll('[role="combobox"]')].map((el) => {
    const id = el.getAttribute("aria-labelledby") ?? "";
    return {
      label: container.querySelector(`#${CSS.escape(id)}`)?.textContent?.trim() ?? "",
      el: el as HTMLElement,
    };
  });
}

/** Opens a control and picks the option with this label, the way a pointer does. */
function choose(container: HTMLElement, control: HTMLElement, option: string): void {
  control.click();
  flush();
  for (const item of container.querySelectorAll('[role="option"]')) {
    if (item.textContent?.trim() === option) {
      item.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
      flush();
      return;
    }
  }
  throw new Error(`no option labelled ${option}`);
}

describe("the settings page's controls", () => {
  it("offers exactly three, and they are theme, accent and density", () => {
    const { container, dispose } = page();
    expect(controls(container).map((control) => control.label)).toEqual(["Theme", "Accent", "Density"]);
    dispose();
  });

  it("offers no timezone and no refresh rate, because nothing would read either", () => {
    const { container, dispose } = page();
    const text = (container.textContent ?? "").toLowerCase();
    expect(text).not.toContain("timezone");
    expect(text).not.toContain("time zone");
    expect(text).not.toContain("refresh");
    dispose();
  });

  it("writes each choice to its own preference and to no other", () => {
    const { container, theme, accent, density, dispose } = page();
    const control = controls(container).find((entry) => entry.label === "Accent");
    expect(control).not.toBeUndefined();
    if (control === undefined) return;

    choose(container, control.el, "Teal");

    expect(accent.written).toEqual(["teal"]);
    expect(theme.written).toEqual([]);
    expect(density.written).toEqual([]);
    dispose();
  });

  it("says a fact it was not told rather than leaving a gap", () => {
    const { container, dispose } = mount(() => (
      <SettingsPage
        theme={recorder<ThemeChoice>("dark")}
        accent={recorder<AccentChoice>("teal")}
        density={recorder<DensityChoice>("compact")}
      />
    ));
    // A blank value reads as a rendering fault; "not reported" is itself worth putting in a bug
    // report, which is the only reason this page carries the two facts at all.
    expect([...container.querySelectorAll("dd")].map((dd) => dd.textContent)).toEqual([
      "not reported",
      "not reported",
    ]);
    dispose();
  });
});

describe("one vocabulary for the appearance preferences", () => {
  /**
   * The rule, asserted where both controls draw it rather than on the constant they share.
   *
   * There are two controls over the same three preferences — this page and the top bar's
   * `AppearancePopover` — and each used to carry its own option table. They had drifted on the one
   * option that matters: the popover called the default theme "Auto" and this page called it "Match
   * the system". An operator who set "Match the system" here and then opened the popover saw a
   * control whose selected segment said something else, and the only way to find out whether those
   * were the same setting was to change one and watch the other.
   *
   * Asserting `THEME_OPTIONS[0].label === THEME_OPTIONS[0].label` would pass over two components
   * that had each gone back to a table of their own, which is the state this case exists to catch.
   * So it mounts both and compares what each *renders* for the preference the browser is actually
   * on. The comparison is to the other screen, not to a string written here, because the label is
   * allowed to change — what is not allowed is for it to change in one place.
   */
  const settingsShowsForTheme = (chosen: ThemeChoice): string => {
    const { container } = keep(
      mount(() => (
        <SettingsPage
          theme={recorder<ThemeChoice>(chosen)}
          accent={recorder<AccentChoice>("blue")}
          density={recorder<DensityChoice>("comfortable")}
        />
      )),
    );
    const theme = controls(container).find((entry) => entry.label === "Theme");
    return theme?.el.textContent?.trim() ?? "";
  };

  /**
   * A `RootPreference` that holds a value and paints nothing.
   *
   * The real one writes `localStorage` and an attribute on `<html>`, both of which would be shared
   * with the next suite. Neither is what this case is about: it is about the word the control
   * draws for the value it is on.
   */
  const held = <A extends string>(chosen: A): RootPreference<A> => ({
    choice: () => chosen,
    select: () => undefined,
    install: () => undefined,
  });

  const popoverShowsForTheme = (chosen: ThemeChoice): string => {
    const { container } = keep(
      mount(() => (
        <AppearancePopover
          preferences={{
            theme: held<ThemeChoice>(chosen),
            accent: held<AccentChoice>("blue"),
            density: held<DensityChoice>("comfortable"),
          }}
        />
      )),
    );
    const checked = container.querySelector('[data-testid="appearance-theme"] input:checked');
    return checked?.closest("label")?.textContent?.trim() ?? "";
  };

  it("names the default theme the same way on this page and in the popover", () => {
    const here = settingsShowsForTheme("auto");
    expect(here).not.toBe("");
    expect(here).toBe(popoverShowsForTheme("auto"));
  });

  it("names the other two the same way as well, so the agreement is not one lucky string", () => {
    expect(settingsShowsForTheme("light")).toBe(popoverShowsForTheme("light"));
    expect(settingsShowsForTheme("dark")).toBe(popoverShowsForTheme("dark"));
  });

  it("explains the default beside the control, in the words the popover uses", () => {
    // The sentence "Match the system" used to carry moved into `help` when the label shortened, and
    // it is drawn here rather than inside a segment because it is a sentence and a segment is a
    // name. If it stopped being drawn, `auto` would be a two-word label with nothing explaining
    // that it keeps following the system rather than resolving once at load.
    const { container } = keep(
      mount(() => (
        <SettingsPage
          theme={recorder<ThemeChoice>("auto")}
          accent={recorder<AccentChoice>("blue")}
          density={recorder<DensityChoice>("comfortable")}
        />
      )),
    );
    const help = [...container.querySelectorAll(".kui-settings__help")].map((el) =>
      el.textContent?.trim(),
    );
    expect(help).toContain(appearanceHelp(THEME_OPTIONS));
    expect(appearanceHelp(THEME_OPTIONS)).not.toBeUndefined();
  });
});
