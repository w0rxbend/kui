/**
 * Storybook, configured for SolidJS 2.
 *
 * ## Why this exists at all
 *
 * Every one of this project's worst frontend defects has been in a state nobody looked at: a
 * virtualized table that measured its container once and drew five rows for a twelve-partition
 * topic, a bar that drew zero as a full-width track, a checkbox drawn as nothing, three controls
 * that were perfect in the accessibility tree and invisible to everybody else. None of those is
 * reachable from a running product without arranging a broken cluster first. All of them are one
 * click away here.
 *
 * So the rule for this workspace is that a component gets a story per state — every variant, every
 * size, default and hover and focus and disabled and loading and error, the empty case, and the
 * extreme case: the longest string that will ever appear in it, the largest number, the smallest
 * window. A component whose only story is its happy path is not finished.
 *
 * ## The three things that had to be configured
 *
 * 1. The Solid framework integration, `storybook-solidjs-vite`, which compiles JSX with the same
 *    `vite-plugin-solid` the product build uses. Two copies of the Solid reactive core in one
 *    bundle is the framework's classic failure mode, so the renderer must be the same one.
 * 2. The product's own stylesheet, imported by `preview.ts`, so that a component renders in its
 *    real palette rather than in Storybook's. A component that looks right against a white page and
 *    wrong against #0E1013 has not been checked.
 * 3. The accessibility addon, which runs axe against every story on every render.
 */
import type { StorybookConfig } from "storybook-solidjs-vite";

const config: StorybookConfig = {
  stories: ["../packages/*/src/**/*.stories.tsx"],
  addons: ["@storybook/addon-a11y"],
  framework: {
    name: "storybook-solidjs-vite",
    options: {},
  },
  core: {
    // Nothing about this product phones home, and a component workshop is not the place to make an
    // exception. KUI is developed and installed inside networks where an unexpected outbound
    // request is a finding.
    disableTelemetry: true,
  },
};

export default config;
