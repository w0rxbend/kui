/**
 * The dashboard's Traffic tab, driven against the deployed product.
 *
 * ## Why almost every assertion here is a comparison with the wire
 *
 * M7's exit criterion was satisfiable for two waves by a service that measured nothing, because
 * every clause of it was a *refusal*: with no exporter anywhere in the repository the endpoint
 * answered `not_configured`, which is exactly what the criterion asked to see. A browser suite can
 * fall into the same hole — "the card says it cannot measure throughput" passes on a deployment
 * that measures it perfectly and on one where the code was never written.
 *
 * So these cases ask the gateway what it says and then assert that the screen says the same thing.
 * On a stack with an exporter that means a chart with real bytes in it; on one without, the
 * sentence. Neither branch is skipped and neither can pass in the other's situation, which is the
 * only shape of this test that can tell a working refusal from a missing feature.
 *
 * Everything is selected by role or by visible text, per `shell.spec.ts`'s rule: a test that selects
 * on a `data-testid` asserts that a developer wrote an attribute, and this suite exists to assert
 * that a person can find the thing.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** One bucket as the metrics service writes it. Every rate is nullable and null means unsampled. */
interface WireBucket {
  readonly startingAt?: string;
  readonly bytesInPerSecond?: number | null;
  readonly bytesOutPerSecond?: number | null;
}

interface WireThroughput {
  readonly throughput: {
    readonly status: string;
    readonly data?: { readonly buckets?: readonly WireBucket[] };
  };
}

/** How many buckets each window holds, from `ThroughputRange.bucketCount`. */
const BUCKETS = { "24h": 288, "7d": 168, "30d": 120 } as const;

const throughput = async (api: KuiApi, range: string): Promise<WireThroughput> =>
  (await api.get(`/api/v1/clusters/${CLUSTER}/metrics/throughput?range=${range}`)) as WireThroughput;

const measured = (bucket: WireBucket): boolean =>
  typeof bucket.bytesInPerSecond === "number" || typeof bucket.bytesOutPerSecond === "number";

test.describe("the Traffic tab", () => {
  test("is reachable from the tab strip and marked when it is open", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * A tab, not a navigation destination (§3.1): the drawer's selected row stays on Dashboard and
     * only the address's last segment moves. The strip was two segments before this wave and the
     * third one is here because the endpoint behind it began answering — the design's own rule is
     * that a tab whose data is not collected does not get drawn.
     */
    await page.getByRole("link", { name: "Traffic" }).click();
    await expect(page).toHaveURL(new RegExp(`/ui/clusters/${CLUSTER}/dashboard/traffic$`));
    await expect(page.getByRole("link", { name: "Traffic" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("nav-overview")).toHaveAttribute("aria-current", "page");
  });

  test("carries the same stat cards as Overview, and its own last row", async ({ page }) => {
    const cards = ["BROKERS ONLINE", "TOPICS", "PARTITIONS IN SYNC", "PRODUCTION", "CONSUMER LAG"];

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    for (const label of cards) await expect(page.getByText(label, { exact: true })).toBeVisible();

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);
    // §4.2: the tab changes the voice line, the last row and the address, and nothing else.
    for (const label of cards) await expect(page.getByText(label, { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Cluster overview" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Broker health" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Partition health" })).toBeVisible();

    // And the last row is this tab's own.
    for (const title of ["Top producers · client.id", "Message size distribution", "Request handlers"]) {
      await expect(page.getByRole("heading", { name: title })).toBeVisible();
    }
    // `Storage by broker` belongs to the other two tabs.
    await expect(page.getByRole("heading", { name: "Storage by broker" })).toHaveCount(0);
  });

  test("draws what the endpoint actually answered, and never a zero for a gap", async ({ page, api }) => {
    const wire = await throughput(api, "24h");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-throughput"]');
    await expect(page.getByRole("heading", { name: "Throughput" })).toBeVisible();

    if (wire.throughput.status === "ok" || wire.throughput.status === "stale") {
      const buckets = wire.throughput.data?.buckets ?? [];

      /*
       * The axis is the window and not the sample count. A range's bucket count is constant whatever
       * was sampled — `ThroughputRange.bucketCount` — so a quiet hour draws the same axis as a busy
       * one, and this is the browser end of that: the chart's hidden data table has one row per
       * bucket, and there are as many buckets as the range holds.
       */
      expect(buckets.length).toBe(BUCKETS["24h"]);
      await expect(card.locator("table tbody tr")).toHaveCount(buckets.length);

      /*
       * The rule this whole card exists for. A bucket the exporter did not answer for prints the em
       * dash; one that was measured prints its rate, including a measured zero. They must never be
       * the same cell, because as bars they are already the same picture.
       */
      const gap = buckets.findIndex((bucket) => !measured(bucket));
      if (gap >= 0) {
        const cells = card.locator("table tbody tr").nth(gap).locator("td");
        await expect(cells.first()).toHaveText("—");
        await expect(cells.first()).not.toHaveText(/^0 /);

        /* And the same fact in words and in ink, because the bars cannot carry it: the caption
           counts the unsampled steps and the strip under the axis paints them. A reader looking at
           the picture has to be able to see the difference the hidden table spells out. */
        const absent = buckets.filter((bucket) => !measured(bucket)).length;
        await expect(card).toContainText(`${absent} of the ${buckets.length}`);
        await expect(card).toContainText("were never sampled");
        await expect(
          card.locator(".kui-throughput__coverage-run--absent"),
        ).not.toHaveCount(0);
      }

      /* M7's positive half: something on this deployment really is measured. Asserted against the
         wire rather than as a literal, so a quickstart with a different exporter still passes. */
      const anyMeasured = buckets.some(measured);
      if (anyMeasured) {
        const row = buckets.findIndex(measured);
        await expect(card.locator("table tbody tr").nth(row).locator("td").first()).toContainText("/s");
        /* And the legend carries the current rate, which is where §3.1 puts it — the reason the
           plot is allowed no y-axis at all. By list item rather than by text, because "produce" is
           also a column heading in the chart's hidden data table and the two would collide. */
        await expect(card.getByRole("listitem").filter({ hasText: "produce" })).toContainText("/s");
      } else {
        // A source KUI can reach and has sampled nothing from: the axis is drawn and the plot says
        // why it is empty, which is a different picture from the not-configured one below.
        await expect(card).toContainText("Nothing has been sampled in this window yet");
      }
    } else if (wire.throughput.status === "not_configured") {
      /*
       * The refusal, and the reason it is not enough on its own. This branch is the one M7's old
       * criterion could be satisfied by, so what it asserts is not merely that a sentence appears —
       * it is that no axis, no table and no figure appear beside it. An empty plot with a labelled
       * time axis is a claim that the quantity is measured and merely absent right now, and it sends
       * somebody to find an exporter that was never configured.
       */
      await expect(card).toContainText("No metrics source is configured for it");
      await expect(card.locator("table")).toHaveCount(0);
      await expect(card.locator('[role="img"]')).toHaveCount(0);
      await expect(card).not.toContainText("0 B/s");
      // Never a bare dash where a sentence belongs, either.
      await expect(card).not.toContainText(/^\s*—\s*$/);
    } else {
      // An exporter that is configured and not answering: a failure with the code somebody quotes.
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }

    /* Whatever the status, the three cards wave 5 fills keep saying what they cannot measure. None
       of them may borrow a figure from something the browser happens to hold. */
    await expect(page.locator('[data-testid="panel-top-producers"]')).toContainText(
      "does not record which clients are producing",
    );
    await expect(page.locator('[data-testid="panel-request-handlers"]')).toContainText(
      "does not record request-handler idle time",
    );
  });

  test("puts the chosen window in the address, so a colleague can be sent one", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    /*
     * The selector is a group of real radios, so this is how a person chooses one. The assertion is
     * the address: a range held in component state would draw the same chart and make every link
     * anybody pasted land on the default window, which is the failure the URL is here to prevent.
     */
    /* The label, not the input. `RangeSelector` clips its radios rather than hiding them — clipping
       keeps them in the accessibility tree and in the tab order, which is the whole arrangement —
       so the drawn segment is the label, and the label is what a pointer lands on. */
    await page
      .getByRole("radiogroup", { name: "Throughput range" })
      .getByText("7d", { exact: true })
      .click();
    await expect(page).toHaveURL(/[?&]range=7d/);
    await expect(page.getByRole("radio", { name: "7d" })).toBeChecked();
  });

  test("opens on the window the address names, and asks the server for that one", async ({ page, api }) => {
    const wire = await throughput(api, "30d");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic?range=30d`);

    await expect(page.getByRole("radio", { name: "30d" })).toBeChecked();
    if (wire.throughput.status === "ok" || wire.throughput.status === "stale") {
      // Read as well as written: a selector that only wrote to the address would show `30d` over a
      // day of data. 120 rows is the thirty-day window at its own six-hour step.
      const rows = page.locator('[data-testid="panel-throughput"] table tbody tr');
      await expect(rows).toHaveCount(BUCKETS["30d"]);
      expect(wire.throughput.data?.buckets?.length).toBe(BUCKETS["30d"]);
    }
  });

  test("says the same thing about a cluster nobody configured a source for", async ({ page, api }) => {
    /*
     * The other half of M7's criterion, and the half that has to keep working: a second cluster with
     * no `kui.metrics.sources` entry answers `not_configured` on the same deployment, and the screen
     * draws the sentence rather than an empty axis. Skipped rather than failed when the deployment
     * has only one cluster, because the assertion is about a second cluster and not about how many
     * clusters a quickstart happens to run.
     */
    const clusters = (await api.get("/api/v1/clusters")) as {
      readonly clusters?: { readonly data?: readonly { readonly cluster?: { readonly id?: string } }[] };
    };
    const other = (clusters.clusters?.data ?? [])
      .map((entry) => entry.cluster?.id)
      .find((id): id is string => id !== undefined && id !== CLUSTER);
    test.skip(other === undefined, "this deployment runs one cluster; there is no second one to ask");
    if (other === undefined) return;

    const wire = (await api.get(
      `/api/v1/clusters/${other}/metrics/throughput?range=24h`,
    )) as WireThroughput;
    await page.goto(`/ui/clusters/${other}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-throughput"]');
    if (wire.throughput.status === "not_configured") {
      await expect(card).toContainText("No metrics source is configured for it");
      await expect(card.locator("table")).toHaveCount(0);
    } else {
      await expect(card.locator("table tbody tr")).toHaveCount(BUCKETS["24h"]);
    }
  });
});
