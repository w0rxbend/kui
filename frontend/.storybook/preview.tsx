/**
 * What every story is rendered inside.
 *
 * ## Three toolbars, because the palette has three axes
 *
 * `data-theme`, `data-accent` and `data-density` are attributes on `<html>`, and they compose: any
 * theme, with any accent, at either density. The design screenshots are one combination of the
 * eight — dark, blue, compact — and it would be easy to build the whole product against that one
 * and discover the other seven at release. So all three are switchable from the toolbar, and the
 * switch writes exactly the attribute the product writes, through the same mechanism.
 *
 * The theme toolbar has three positions and not two, for the same reason the product's theme
 * control does: "light" and "light because the operating system is light" are different states, and
 * only one of them follows the machine at sunset. "auto" is represented by the *absence* of the
 * attribute, which is what the token file's `prefers-color-scheme` block keys off.
 */
import type { Preview } from "storybook-solidjs-vite";

// The product's real stylesheet: tokens, reset, kernel primitives, screens. Reached by relative
// path rather than as a package import for the same reason `index.css` reaches the feature
// stylesheets that way — there is no code dependency here, only one build artifact assembled from
// files on disk.
import "../packages/kernel/styles/index.css";

/** Writes the three attributes the palette is selected by, exactly as the product writes them. */
function applyAppearance(theme: string, accent: string, density: string): void {
  const root = document.documentElement;
  if (theme === "auto") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", theme);

  if (accent === "blue") root.removeAttribute("data-accent");
  else root.setAttribute("data-accent", accent);

  if (density === "comfortable") root.removeAttribute("data-density");
  else root.setAttribute("data-density", density);

  // The story canvas is transparent by default, so without this a dark-theme component is drawn on
  // Storybook's white. That is not a cosmetic complaint: contrast, the weight of a border and
  // whether a card is visible at all are all judged against the page ground.
  document.body.style.background = "var(--kui-color-surface)";
  document.body.style.color = "var(--kui-color-text)";
}

const preview: Preview = {
  parameters: {
    controls: { matchers: { color: /(background|color)$/i, date: /Date$/i } },
    a11y: {
      // Report violations rather than failing the story: a story exists to be looked at, and a
      // story that refuses to render because of a colour-contrast finding hides the very thing that
      // needs judging. The findings are in the panel, and the tests in `*.test.tsx` are where axe
      // is allowed to fail a build.
      test: "todo",
    },
    layout: "centered",
  },
  globalTypes: {
    theme: {
      description: "data-theme on <html>",
      toolbar: {
        title: "Theme",
        icon: "circlehollow",
        items: [
          { value: "dark", title: "Dark" },
          { value: "light", title: "Light" },
          { value: "auto", title: "Follows system" },
        ],
        dynamicTitle: true,
      },
    },
    accent: {
      description: "data-accent on <html>",
      toolbar: {
        title: "Accent",
        icon: "paintbrush",
        items: [
          { value: "blue", title: "Blue" },
          { value: "teal", title: "Teal" },
          { value: "green", title: "Green" },
          { value: "amber", title: "Amber" },
        ],
        dynamicTitle: true,
      },
    },
    density: {
      description: "data-density on <html>",
      toolbar: {
        title: "Density",
        icon: "component",
        items: [
          { value: "comfortable", title: "Comfortable" },
          { value: "compact", title: "Compact" },
        ],
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: {
    // Dark, blue and comfortable: the design screenshots are dark and blue, and the *default*
    // product is comfortable. The screenshots were taken at compact, which is why that is one
    // toolbar click away and not the starting point — build the default, check the screenshots.
    theme: "dark",
    accent: "blue",
    density: "comfortable",
  },
  decorators: [
    (Story, context) => {
      applyAppearance(
        String(context.globals["theme"] ?? "dark"),
        String(context.globals["accent"] ?? "blue"),
        String(context.globals["density"] ?? "comfortable"),
      );
      return Story();
    },
  ],
};

export default preview;
