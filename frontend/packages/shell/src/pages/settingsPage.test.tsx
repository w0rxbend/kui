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

import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import type { AccentChoice, DensityChoice, ThemeChoice } from "@kui/kernel";

import { SettingsPage, type Preference } from "./SettingsPage.jsx";
import { mount } from "../chrome/testing.js";

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
