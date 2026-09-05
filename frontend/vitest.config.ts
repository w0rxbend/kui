/**
 * The unit-test runner.
 *
 * Two things need explaining.
 *
 * `vite-plugin-solid` has to compile the tests as well as the product, because a test renders JSX
 * and Solid's JSX is not a function call that any generic transform would get right. Passing
 * `dev: true` keeps Solid's development-mode diagnostics on, which is deliberate: the warnings for
 * "top-level reactive read in a component body" and "write under an owned scope" are the two
 * mistakes this framework version punishes hardest, and a test run is exactly where we want to hear
 * about them.
 *
 * `jsdom` rather than a real browser. Everything in this suite is markup, accessible names, keyboard
 * behaviour and event handling, none of which needs a layout engine. The things that *do* need one —
 * whether the focus ring is visible, whether a long name truncates rather than wrapping, whether the
 * drawer's foot stays put — are checked by looking at the Storybook stories, because a test that
 * asserted them in jsdom would assert a value jsdom made up.
 */
import { defineConfig } from "vitest/config";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [solid({ dev: true })],
  resolve: {
    // Vitest must load the browser build of Solid, not the server one: the server build renders to
    // a string and has no DOM at all, and the failure mode is a test that "passes" against nothing.
    conditions: ["development", "browser"],
  },
  test: {
    environment: "jsdom",
    include: ["packages/*/src/**/*.test.tsx", "packages/*/src/**/*.test.ts"],
    setupFiles: ["./vitest.setup.ts"],
  },
});
