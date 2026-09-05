/**
 * Run axe over every Storybook story, in both themes, and fail if anything is wrong.
 *
 * ## Why this exists when the a11y addon is already installed
 *
 * The addon checks the story you are looking at. That is the right tool while building a component
 * and the wrong one for keeping a workspace correct: nobody clicks two hundred stories, and the two
 * defects this script found on its first run — a contrast failure on `StatTile`'s "not measured"
 * text, and six paginators producing six identically named landmarks — were both in stories that
 * had been reviewed by eye and passed.
 *
 * It also checks something the addon cannot: **both themes**. The palette is two palettes, contrast
 * is a property of a pair of colours, and a component can be legible in dark and fail in light.
 * Every story here is rendered twice.
 *
 * ## Why `region` is disabled
 *
 * A story root is not inside a `<main>`, so `region` fails for every story in the workspace. That
 * is the harness, not the component — in the product these all render inside the frame's `<main>`.
 * It is the only rule turned off, and turning off a second one needs a reason written here.
 *
 * ## Usage
 *
 *   pnpm storybook            # in one terminal
 *   node scripts/a11y-stories.mjs
 *   node scripts/a11y-stories.mjs 'chrome-|surfaces-'   # only matching story ids
 *
 * `SB` overrides the Storybook origin (default `http://localhost:6017`).
 *
 * Exits non-zero when anything fails, so it can be a gate.
 */
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const require = createRequire(import.meta.url);
const axeSource = readFileSync(require.resolve("axe-core/axe.min.js"), "utf8");

const base = process.env.SB ?? "http://localhost:6017";
const filter = process.argv[2] === undefined ? undefined : new RegExp(process.argv[2]);

/** The harness's own failure, not a component's. See the header. */
const DISABLED_RULES = { region: { enabled: false } };

const index = await fetch(`${base}/index.json`)
  .then((response) => response.json())
  .catch(() => {
    console.error(`Could not reach Storybook at ${base}. Start it with \`pnpm storybook\`.`);
    process.exit(2);
  });

const ids = Object.values(index.entries)
  .filter((entry) => entry.type === "story")
  .map((entry) => entry.id)
  .filter((id) => filter === undefined || filter.test(id));

if (ids.length === 0) {
  console.error("No stories matched.");
  process.exit(2);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

let failures = 0;
for (const theme of ["dark", "light"]) {
  for (const id of ids) {
    // The theme goes through Storybook's `theme` global, not by setting `data-theme` directly.
    // `.storybook/preview.tsx` owns that attribute and rewrites it from the global on every story
    // render, so an attribute set from here is silently reverted — which is exactly what happened
    // on this script's first version: both passes rendered dark and the second was *labelled*
    // light. A check that quietly tests the same thing twice is worse than no check, so the wait
    // below is on the attribute itself.
    const url = `${base}/iframe.html?id=${id}&viewMode=story&globals=theme:${theme}`;
    let themed = false;
    // Two attempts. A story whose feature chunk has not been built yet can take longer than the
    // timeout on its first visit, and that is a slow cache rather than a broken story — retrying
    // the navigation distinguishes the two, where failing immediately just made the run flaky.
    for (let attempt = 0; attempt < 2 && !themed; attempt++) {
      await page.goto(url, { waitUntil: "load" });
      try {
        await page.waitForFunction(
          (expected) => document.documentElement.getAttribute("data-theme") === expected,
          theme,
          { timeout: 10_000 },
        );
        themed = true;
      } catch {
        /* Try once more, from a warm cache. */
      }
    }

    if (!themed) {
      const applied = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
      console.error(`\n✗ ${id}: asked for the ${theme} theme and got ${applied ?? "none"}. Not checking a theme twice.`);
      await browser.close();
      process.exit(2);
    }

    /*
     * Wait for the entry animations to finish before measuring anything.
     *
     * Contrast is computed from what is actually painted, and a dialog or a drawer fades in — so a
     * sweep that ran the instant the story loaded measured *semi-transparent* text against the
     * surface behind it and reported a contrast failure. The tell was that the same element failed
     * with a different ratio on every run: 4.34, then 3.48, then 3.49. A real contrast failure is
     * the same number every time.
     *
     * It also made the count depend on how many dialog stories the workspace happened to contain,
     * which is the worst property a regression check can have — adding a story to a component that
     * was already correct made the number go up.
     *
     * `getAnimations` covers both CSS transitions and animations, and the timeout is a bound rather
     * than a wait: a story with an intentionally infinite animation (a spinner) would otherwise
     * hang the sweep for ever.
     */
    await page
      .waitForFunction(
        () => document.getAnimations().every((animation) => animation.playState !== "running"),
        undefined,
        { timeout: 2_000 },
      )
      .catch(() => {
        /* A story with a looping animation — a spinner — never settles. Measure it anyway. */
      });

    // The a11y addon already puts an axe on `window`, and it runs itself on every story render.
    // Injecting a second copy gives two axes sharing one internal lock, and the sweep dies partway
    // through with "Axe is already running". So: reuse the addon's instance when it is there, and
    // wait for its automatic run to finish rather than racing it.
    const hasAxe = await page.evaluate(() => typeof window.axe === "object");
    if (!hasAxe) await page.addScriptTag({ content: axeSource });

    const result = await page.evaluate(
      async (rules) => {
        const options = { resultTypes: ["violations"], rules };
        for (let attempt = 0; ; attempt++) {
          try {
            return await window.axe.run(document.body, options);
          } catch (error) {
            const busy = String(error).includes("already running");
            if (!busy || attempt >= 20) throw error;
            await new Promise((resolve) => setTimeout(resolve, 100));
          }
        }
      },
      DISABLED_RULES,
    );

    for (const violation of result.violations) {
      failures++;
      console.log(`\n✗ [${theme}] ${id}`);
      console.log(`  ${violation.id} (${violation.impact}): ${violation.help}`);
      for (const node of violation.nodes.slice(0, 3)) {
        console.log(`    ${node.target.join(" ")}`);
        const summary = (node.failureSummary ?? "").split("\n").filter(Boolean).at(-1);
        if (summary !== undefined) console.log(`    ${summary.trim()}`);
      }
    }
  }
}

await browser.close();

const checked = `${ids.length} stories × 2 themes`;
if (failures === 0) {
  console.log(`\n✓ ${checked}: no violations.`);
} else {
  console.log(`\n✗ ${checked}: ${failures} violation${failures === 1 ? "" : "s"}.`);
  process.exit(1);
}
