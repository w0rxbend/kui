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
 * ## Exit codes, because two very different things used to look the same
 *
 *   0 — every story rendered in both themes and axe found nothing.
 *   1 — axe found violations. They are printed above the summary, one block each.
 *   2 — **the sweep could not run**: Storybook was unreachable, the filter matched no stories, or a
 *       story would not take the theme it was asked for. Nothing is being said about accessibility
 *       in this case, and the message says so in as many words — the previous version printed the
 *       theme failure with the same `✗ <story id>` prefix a violation uses, and two wave-4 packets
 *       read a loaded machine as an a11y regression because of it.
 *
 * A story whose theme lands *after* the last budget expires is **not** exit 2. The attribute is
 * read back before the failure is declared, and when it turns out to be right the story is swept
 * normally with a note saying how long it took — see the block below the retry loop.
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

/**
 * How long to wait for `.storybook/preview.tsx` to stamp `data-theme` on the root, per attempt.
 *
 * A widening budget rather than two attempts at a flat ten seconds. Wave 4 measured the flat wait
 * failing on an arbitrary story — a different one in each of two reports, each passing when run
 * alone, with **no axe violation printed in any run** — under a load average of 21 on 16 cores,
 * while the same sweep was clean twice at a normal load. So it is the story's render losing to the
 * machine rather than anything about the story, and a budget that does not widen under load fails
 * CI for a reason that has nothing to do with accessibility.
 *
 * Three attempts, each a fresh navigation: the second and third also serve the original purpose of
 * the retry, which is a feature chunk that had not been built yet on the first visit and is warm on
 * the second. The totals matter more than the individual numbers — a story that has not themed
 * after seventy seconds of waiting across three loads is not slow, it is broken, and the sweep
 * should say so rather than wait for ever.
 */
const THEME_WAIT_MS = [10_000, 20_000, 40_000];

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
    let waitedMs = 0;
    // See `THEME_WAIT_MS`: each attempt is a fresh navigation on a wider budget, so a slow render
    // under load is told apart from a story that genuinely never applies the theme.
    for (const budget of THEME_WAIT_MS) {
      await page.goto(url, { waitUntil: "load" });
      const startedAt = Date.now();
      try {
        await page.waitForFunction(
          (expected) => document.documentElement.getAttribute("data-theme") === expected,
          theme,
          { timeout: budget },
        );
        themed = true;
      } catch {
        /* Try again, from a warm cache and with more room. */
      }
      waitedMs += Date.now() - startedAt;
      if (themed) break;
    }

    if (!themed) {
      /*
       * Read the attribute back before calling this a failure, because the last budget expiring is
       * not the same fact as the theme never arriving.
       *
       * `waitForFunction` polls, so it can time out in the gap between the attribute landing and
       * the next poll — and under the load this retry exists for, that gap is exactly where a slow
       * render finishes. The previous version went straight to the message below and printed
       * `asked for the dark theme and got dark`, which is self-contradictory in precisely the case
       * the whole retry was written for: two wave-4 packets read a loaded machine as an a11y
       * regression, and this line was the reason the third reader could not tell which it was.
       *
       * When it did arrive, the story is themed and axe below is measuring the right palette, so
       * the sweep continues and says the wait was long rather than failing a CI run over it.
       */
      const applied = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
      const seconds = Math.round(waitedMs / 1000);
      if (applied === theme) {
        console.error(`  ${id}: the ${theme} theme landed after the last budget expired — ${seconds}s`);
        console.error(`  of waiting across ${THEME_WAIT_MS.length} navigations. Checked anyway: the`);
        console.error(`  attribute is right, so what axe measures below is the right palette.`);
      } else {
        // Deliberately not the `✗ <id>` shape a violation is printed with. This story was never
        // checked, so the run has found nothing about it either way, and the two must not read alike.
        console.error(`\nHARNESS FAILURE — nothing was checked here, and this is not a violation.`);
        console.error(`  ${id}: asked for the ${theme} theme and got ${applied ?? "none"}, after`);
        console.error(`  ${THEME_WAIT_MS.length} navigations and ${seconds}s of waiting in total.`);
        console.error(`  A theme that never applied means the sweep would check one theme twice,`);
        console.error(`  so it stops here rather than report a pass it did not earn.`);
        console.error(`  Re-run this story on its own before reporting a defect:`);
        console.error(`      node scripts/a11y-stories.mjs '^${id}$'`);
        await browser.close();
        process.exit(2);
      }
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
