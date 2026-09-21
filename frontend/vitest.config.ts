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
 *
 * ## The third thing: why `testTimeout` is not vitest's default
 *
 * For two waves this suite failed intermittently with `Test timed out in 5000ms` inside an axe case,
 * and the file it landed on moved between runs. It is not axe hanging and it is not an assertion.
 * `findViolations` is a CPU-bound sweep over a mounted tree, vitest's timeout is **wall clock**, and
 * this suite runs 77 jsdom environments in parallel on whatever machine happens to be free — so the
 * budget a case is measured against is a function of how loaded the machine is, and nothing else
 * about the case changes at all.
 *
 * Measured in both directions at wave 8, on a 16-core machine, with the suite between 77 and 82
 * files — it grew under three other packets while the measurements were being taken, which is
 * itself part of the load being described:
 *
 *   - `overview.render.test.tsx`'s *the healthy dashboard > has no accessibility violations*, run
 *     with its own file alone, three times: **385ms, 553ms, 610ms**. The same case in the full run,
 *     three times: **898ms, 4478ms, 881ms** — and **5541ms** in a run where it failed on the 5000ms
 *     default, which is the flake itself, caught.
 *   - `searchShortcut.test.tsx`'s a11y case, in the same three full runs: **336ms, 281ms, 7221ms**.
 *     Twenty-one times its own uncontended cost, in the same suite, on the same machine, minutes
 *     apart. That is the shape of the thing: a case does not get slow, its neighbours do.
 *
 * So the default is the defect: 5000ms is a generous budget for an assertion and a tight one for a
 * CPU-bound sweep measured while 76 other environments are running. Twenty seconds is about three
 * times the worst contended reading above, which leaves headroom for a machine busier than this one
 * while still failing a genuinely hung case inside half a minute. Lowering the worker count was the
 * alternative and it is the wrong fix twice over: it slows every run to make one case's wall clock
 * predictable, and it cannot touch load from outside this process, which is where most of it comes
 * from. The timeout is not there to make slow tests pass — every case above does its own work in
 * under a second — it is there because a wall-clock deadline is the wrong instrument for measuring
 * work done on a shared CPU.
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
    // See the header. Measured, not guessed: three times the worst contended axe reading.
    testTimeout: 20_000,
  },
});
