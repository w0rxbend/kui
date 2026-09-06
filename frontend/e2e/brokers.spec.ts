/**
 * The brokers screen, in a browser, against a real broker.
 *
 * ## What this suite is for
 *
 * Every figure on this screen has a rule attached to it — a percentage that must not appear without
 * a capacity, a count that must not read as zero when nobody measured it, a request that must not
 * happen until a card is opened. The unit suite gates all three against a stub. This one gates them
 * against the server, because the failure this screen shipped with was not a wrong rule: it was a
 * screen that never received the number the rule was about.
 *
 * ## Why the expectations are computed from the API rather than written down
 *
 * A quickstart's under-replicated partition count is `null` until the first partition sweep lands
 * and `0` afterwards, and whether Kafka reports a log directory's filesystem size depends on the
 * broker's version. Both are *legitimate* answers, and the product has a different, correct
 * rendering for each. So this suite asks the gateway what the answer is and asserts that the screen
 * drew the rendering that answer calls for — which is a stronger assertion than either literal
 * would be, and the only one that does not go red for a reason that is nobody's fault.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The `Section` envelope every aggregated answer arrives in (ADR-039). */
interface Section<T> {
  readonly status: string;
  readonly data?: T;
}

interface ClusterDocument {
  readonly cluster?: { readonly summary?: Section<{ readonly underReplicatedPartitionCount?: number | null }> };
}

interface LogDirsDocument {
  readonly logDirs?: Section<readonly { readonly totalBytes?: number | null; readonly usableBytes?: number | null }[]>;
}

/** What the section holds, or `undefined` where it refused. */
function dataOf<T>(section: Section<T> | undefined): T | undefined {
  return section !== undefined && (section.status === "ok" || section.status === "stale")
    ? section.data
    : undefined;
}

test.describe("the brokers screen", () => {
  test("names the broker and its address, from the cluster rather than from a fixture", async ({
    page,
  }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);

    await expect(page.getByRole("heading", { name: "Brokers", exact: true })).toBeVisible();
    // The quickstart runs one broker, advertised as `kafka:9092`. By visible string, not by testid:
    // this asserts that a person can read the broker's address off the card.
    await expect(page.getByText("kafka:9092").first()).toBeVisible();
    await expect(page.locator("body")).not.toContainText("[object Object]");
    await expect(page.locator("body")).not.toContainText("undefined");
  });

  test("the four stat tiles carry figures or say why they do not", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const tiles = page.locator(".kui-brk-tiles");
    await expect(tiles).toBeVisible();
    for (const label of ["ACTIVE CONTROLLER", "TOTAL LEADERS", "DISK USED", "PARTITION SKEW"]) {
      await expect(tiles).toContainText(label);
    }
    /*
     * The rule the whole product rests on, asserted where it is cheapest to break: a tile whose
     * figure could not be read carries a chip saying why, on that tile. A dash on its own is the
     * defect — this is the reading M6's exit criterion asks for, per screen rather than by one
     * sweeping regex over the page.
     */
    const each = tiles.locator(".kui-tile");
    for (let index = 0; index < (await each.count()); index += 1) {
      const tile = each.nth(index);
      if ((await tile.locator(".kui-tile__absent").count()) > 0) {
        await expect(tile.locator(".kui-tile__chip")).toBeVisible();
      }
    }
  });

  test("the voice line reads the count the gateway actually reports", async ({ page, api }) => {
    /*
     * The assertion this file exists for. `underReplicatedPartitionCount` reaches the browser as a
     * number or as `null`, and the two have different sentences: the screen used to print the
     * cheerful one over both, on a prop the route never passed at all.
     */
    const document = (await api.get(`/api/v1/clusters/${CLUSTER}`)) as ClusterDocument;
    const summary = dataOf(document.cluster?.summary);
    const count = summary?.underReplicatedPartitionCount ?? null;

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const voice = page.locator('[data-testid="brokers-head"]');
    await expect(voice).toBeVisible();

    if (count === null) {
      await expect(voice).toContainText(/not claiming there are none/i);
      await expect(voice).not.toContainText(/Zero under-replicated/i);
    } else if (count === 0) {
      await expect(voice).toContainText(/Zero under-replicated partitions/i);
    } else {
      await expect(voice).toContainText(new RegExp(`${count} partitions? (is|are) under-replicated`));
    }
  });

  test("a disk with no capacity on the wire shows a sentence and no percentage", async ({
    page,
    api,
  }) => {
    // Kafka's admin protocol reports a log directory's filesystem size only on brokers new enough
    // to send it, so both branches below are real answers rather than one being a failure.
    const measured = await capacityIsReported(api);

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();

    if (measured) {
      await expect(card).toContainText(/\d+%/);
      await expect(card).not.toContainText(/No disk capacity was reported/i);
    } else {
      await expect(card).toContainText(/No disk capacity was reported/i);
      // Never a `0%` bar: an unmeasurable disk and an empty one mean opposite things.
      await expect(card).not.toContainText(/\d+%/);
    }
  });

  test("a broker's settings arrive when its card is opened, and not before", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();

    // Collapsed, the card answers "is this broker all right?" and has asked the gateway for none of
    // the two hundred settings that answer "why is it behaving like that?".
    await expect(card).not.toContainText("CONFIGURATION");

    const settings = page.waitForResponse(
      (response) => response.url().includes("/configs") && response.status() === 200,
    );
    await card.locator(".kui-brkcard__toggle").click();
    await settings;

    await expect(card).toContainText("CONFIGURATION");
    // Chips, not the "Fetching this broker's settings…" placeholder and not an empty block: an
    // empty configuration block reads as "this broker has no settings", which is never true.
    await expect(card.locator(".kui-config-chip").first()).toBeVisible();
  });
});

/** Whether this deployment's log directories carry a filesystem size to measure against. */
async function capacityIsReported(api: KuiApi): Promise<boolean> {
  const document = (await api.get(`/api/v1/clusters/${CLUSTER}/log-dirs`)) as LogDirsDocument;
  const dirs = dataOf(document.logDirs) ?? [];
  return dirs.some(
    (dir) =>
      typeof dir.totalBytes === "number" &&
      dir.totalBytes > 0 &&
      typeof dir.usableBytes === "number",
  );
}
