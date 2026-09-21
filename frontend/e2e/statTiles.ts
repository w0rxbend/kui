/**
 * The cluster dashboard's stat row, written down **once** for the whole browser suite.
 *
 * ## Why this file exists
 *
 * Wave 10's closer counted **four hand-written rosters of the same six tiles** in this repository:
 * `Overview.tsx`'s `STAT_ORDER`, `overview.render.test.tsx`, `shell.spec.ts` (by `data-testid`) and
 * `traffic.spec.ts` (by the label each tile prints). Two of the four were in this directory, in two
 * different vocabularies, describing one row — so a seventh tile, or a renamed sixth, needed two
 * separate edits here before either browser case would notice, and `traffic.spec.ts`'s copy could
 * not have noticed at all. This is those two, merged: one list, both vocabularies, in the only
 * place a spec has to look.
 *
 * It is a plain module and not a spec: `playwright.config.ts` collects `e2e/**` by Playwright's
 * default `testMatch`, so a helper imported from a `*.spec.ts` would register that file's tests a
 * second time under the importer's name. That is why this is not simply an export from
 * `shell.spec.ts`.
 *
 * ## Why the roster is written out and not read off the page
 *
 * Both halves of the rule are needed and neither is enough:
 *
 * - **Written out**, because the defect this guards against is a row that draws *fewer* tiles than
 *   it should. A locator that collects whatever is on the page and then asserts about each of them
 *   passes over an empty row, which is the shape of vacuous coverage wave 9 removed from
 *   `traffic.spec.ts` and wave 10 from `/ui/`.
 * - **Counted too**, because a list checked one entry at a time cannot see a *seventh* tile. Before
 *   wave 11 the only case in the repository that could was `overview.render.test.tsx`'s, in jsdom.
 *   `.kui-stat` is the class every tile carries; comparing its count to this list's length is the
 *   cheapest thing that makes an addition visible, and it is what turns a roster into a census.
 */
import { expect } from "@playwright/test";
import type { Page } from "@playwright/test";

/**
 * The six tiles, in the order the row draws them, each in both vocabularies.
 *
 * `testId` is what `shell.spec.ts` addresses a tile by and has been stable since `M01`; `label` is
 * the word the tile prints above its figure, which is what `traffic.spec.ts` asserts because the
 * question there is whether the *same row* is on a second tab, seen the way a person sees it.
 */
export const STAT_TILES = [
  { testId: "stat-brokers", label: "BROKERS ONLINE" },
  { testId: "stat-topics", label: "TOPICS" },
  { testId: "stat-in-sync", label: "PARTITIONS IN SYNC" },
  { testId: "stat-production", label: "PRODUCTION" },
  { testId: "stat-consume", label: "CONSUME" },
  { testId: "stat-lag", label: "CONSUMER LAG" },
] as const;

/**
 * The row is exactly this row: these tiles, and no others.
 *
 * Two assertions and they fail on opposite defects — a missing tile is a `getByTestId` that resolves
 * to nothing, and an added one is a `.kui-stat` count that no longer matches the list above. A
 * seventh tile shipped without a decision is the second, and until wave 11 no browser case made it.
 */
export async function theStatRowIsExactlyTheSixTiles(page: Page): Promise<void> {
  for (const tile of STAT_TILES) {
    await expect(page.getByTestId(tile.testId), `${tile.testId} is not drawn at all`).toBeVisible();
  }
  await expect(
    page.locator(".kui-stat"),
    "the stat row drew a tile this suite has never heard of; add it to STAT_TILES in " +
      "e2e/statTiles.ts, or find out who put it there",
  ).toHaveCount(STAT_TILES.length);
}

/**
 * Every stat tile carries either a figure or a sentence — never its own label and nothing else.
 *
 * The label is subtracted rather than matched around, because the assertion has to hold for a
 * measured `3`, for an em dash with a title, and for a paragraph explaining that nobody asked. What
 * it must not hold for is the state that shipped, where the only text in the tile was the word
 * printed above the space the figure was supposed to occupy.
 *
 * `expect.poll` rather than a single read: a tile on a cold page is legitimately a skeleton for a
 * moment, and the claim being made is that it stops being one — not that it never was.
 */
export async function eachTileSaysSomething(page: Page): Promise<void> {
  await theStatRowIsExactlyTheSixTiles(page);
  for (const { testId } of STAT_TILES) {
    const tile = page.getByTestId(testId);
    await expect
      .poll(
        async () => {
          const label = await tile.locator(".kui-stat__label").innerText();
          return (await tile.innerText()).replace(label, "").trim();
        },
        { message: `${testId} drew its label and nothing else — no figure and no sentence` },
      )
      .not.toBe("");
  }
}
