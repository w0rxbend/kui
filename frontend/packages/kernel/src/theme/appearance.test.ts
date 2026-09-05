import { describe, expect, test } from "vitest";

import {
  createRootPreference,
  createThemePreference,
  type AccentChoice,
  type DensityChoice,
  type PreferenceStorage,
} from "./index.js";

/** A `localStorage` a test owns, so nothing here depends on the browser's real one. */
function memoryStorage(seed: Record<string, string> = {}): PreferenceStorage & {
  contents: Record<string, string>;
} {
  const contents = { ...seed };
  return {
    contents,
    getItem: (key) => contents[key] ?? null,
    setItem: (key, value) => {
      contents[key] = value;
    },
  };
}

/** The browser that refuses storage: Safari in a private window, or enterprise policy. */
const refusingStorage: PreferenceStorage = {
  getItem() {
    throw new DOMException("denied", "SecurityError");
  },
  setItem() {
    throw new DOMException("denied", "SecurityError");
  },
};

function root(): Element {
  return document.createElement("html");
}

describe("theme", () => {
  test("auto removes the attribute, so only the media query decides", () => {
    const element = root();
    element.setAttribute("data-theme", "dark");
    const theme = createThemePreference({
      root: element,
      storage: memoryStorage(),
    });

    theme.select("auto");

    expect(element.hasAttribute("data-theme")).toBe(false);
  });

  test("an explicit choice is written as the attribute the stylesheet matches", () => {
    const element = root();
    const theme = createThemePreference({
      root: element,
      storage: memoryStorage(),
    });

    theme.select("dark");
    expect(element.getAttribute("data-theme")).toBe("dark");

    theme.select("light");
    expect(element.getAttribute("data-theme")).toBe("light");
  });

  test("the choice has changed everywhere by the time the click handler returns", () => {
    // Solid 2 queues signal updates into a microtask, so without the `flush` inside `select` this
    // would read the previous value: the attribute would be right and `choice()` would be a whole
    // microtask behind it, and any control drawing itself from the theme would draw the old one.
    // Asserting it here is what stops that flush being tidied away as redundant.
    const element = root();
    const theme = createThemePreference({
      root: element,
      storage: memoryStorage(),
    });

    theme.select("dark");

    expect(element.getAttribute("data-theme")).toBe("dark");
    expect(theme.choice()).toBe("dark");
    expect(theme.effective()).toBe("dark");
  });

  test("the choice survives a reload", () => {
    const storage = memoryStorage();
    createThemePreference({ root: root(), storage }).select("light");

    const afterReload = createThemePreference({ root: root(), storage });

    expect(afterReload.choice()).toBe("light");
  });

  test("a browser that refuses storage still gets a working switcher", () => {
    const element = root();
    const theme = createThemePreference({
      root: element,
      storage: refusingStorage,
    });

    expect(theme.choice()).toBe("auto");
    theme.select("dark");

    expect(element.getAttribute("data-theme")).toBe("dark");
  });

  test("a stored value this version does not recognise reads as the default", () => {
    // `localStorage` outlives upgrades, so a value written by a later KUI can be read by an
    // earlier one. Starting in the default beats failing to start.
    const storage = memoryStorage({ "kui.theme": "solarized" });

    expect(createThemePreference({ root: root(), storage }).choice()).toBe(
      "auto",
    );
  });

  test("auto follows the operating system, and an explicit choice does not", () => {
    let systemIsDark = false;
    const theme = createThemePreference({
      root: root(),
      storage: memoryStorage(),
      systemPrefersDark: () => systemIsDark,
    });

    expect(theme.effective()).toBe("light");
    systemIsDark = true;
    expect(theme.effective()).toBe("dark");

    theme.select("light");
    expect(theme.effective()).toBe("light");
  });

  test("install applies the remembered choice without anybody clicking anything", () => {
    const element = root();
    const theme = createThemePreference({
      root: element,
      storage: memoryStorage({ "kui.theme": "dark" }),
    });

    expect(element.hasAttribute("data-theme")).toBe(false);
    theme.install();

    expect(element.getAttribute("data-theme")).toBe("dark");
  });
});

describe("accent", () => {
  const accent = (element: Element, storage: PreferenceStorage) =>
    createRootPreference<AccentChoice>({
      attribute: "data-accent",
      storageKey: "kui.accent",
      values: ["blue", "teal", "green", "amber"],
      fallback: "blue",
      attributeValue: (chosen) => (chosen === "blue" ? null : chosen),
      storage,
      root: element,
    });

  test("the default seed writes no attribute, because plain :root already declares it", () => {
    const element = root();
    const preference = accent(element, memoryStorage());

    preference.select("teal");
    expect(element.getAttribute("data-accent")).toBe("teal");

    preference.select("blue");
    expect(element.hasAttribute("data-accent")).toBe(false);
  });
});

describe("density", () => {
  const density = (element: Element, storage: PreferenceStorage) =>
    createRootPreference<DensityChoice>({
      attribute: "data-density",
      storageKey: "kui.density",
      values: ["comfortable", "compact"],
      fallback: "comfortable",
      attributeValue: (chosen) => (chosen === "compact" ? "compact" : null),
      storage,
      root: element,
    });

  test("only compact writes an attribute", () => {
    const element = root();
    const preference = density(element, memoryStorage());

    preference.select("compact");
    expect(element.getAttribute("data-density")).toBe("compact");

    preference.select("comfortable");
    expect(element.hasAttribute("data-density")).toBe(false);
  });

  test("the choice is remembered under its own key", () => {
    const storage = memoryStorage();

    density(root(), storage).select("compact");

    expect(storage.contents["kui.density"]).toBe("compact");
  });
});

describe("the three attributes together", () => {
  test("each preference owns one attribute and leaves the others alone", () => {
    // The three are independent by construction, and the contrast suite depends on that: it checks
    // eight combinations of theme and accent, which is only meaningful if a seed cannot move a
    // surface and a density cannot move a colour.
    const element = root();
    const storage = memoryStorage();

    createThemePreference({ root: element, storage }).select("dark");
    createRootPreference<AccentChoice>({
      attribute: "data-accent",
      storageKey: "kui.accent",
      values: ["blue", "teal", "green", "amber"],
      fallback: "blue",
      attributeValue: (chosen) => (chosen === "blue" ? null : chosen),
      storage,
      root: element,
    }).select("amber");

    expect(element.getAttribute("data-theme")).toBe("dark");
    expect(element.getAttribute("data-accent")).toBe("amber");
  });
});
