/**
 * The consumer-group list, in a browser, against the quickstart's real coordinators.
 *
 * ## What only a browser can settle here
 *
 * Two things, and both were wrong in the shipped product until this wave.
 *
 * The **coordinator column** printed `broker 1` — the id the wire used to be read for — where the
 * design asks for `broker-1:9092`. A unit test can prove the mapping produces `host:port` from a
 * recorded document; only a run against a live gateway proves the fields the mapping reads are the
 * fields a real coordinator answers with. The quickstart's single broker is reachable as
 * `kafka:9092`, so that is the string this asserts.
 *
 * The **count over the table** was `rows.length`, so it agreed with the table and said nothing
 * about the cluster. This reads the server's own `page.totalItems` over HTTP and then asserts the
 * screen prints that figure, which is the only way round that cannot pass by coincidence: a screen
 * counting its rows and a server counting its groups agree on this cluster, and the assertion is
 * written against the server's number so it is the server's number the screen has to be showing.
 *
 * ## Why page 2 is not asserted here
 *
 * The quickstart seeds three consumer groups and a page holds sixteen, so there is no second page
 * to reach and no honest way to make one in a browser — a consumer group exists because something
 * consumed, not because a test asked for one. "Page 2 asks the server for page 2" is asserted in
 * `packages/feature-consumers/src/consumers.test.tsx`, against the screen mounted with a client
 * that records what it was asked for. What this file can settle is that the control is on the page
 * and states the server's total, which is the half a stub cannot.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/**
 * A seeded group that holds committed offsets, so the per-topic controls on its page have rows.
 *
 * `analytics-indexer` is the quickstart's live one — a container that keeps consuming
 * `analytics.pageviews` — so it is the group whose detail page is populated on a stack that has
 * only just come up, where the two stopped groups are equally valid and less certain to be settled.
 */
const GROUP_WITH_OFFSETS = "analytics-indexer";

/** The server's own count of the cluster's groups, read straight from the gateway. */
async function totalGroups(api: KuiApi): Promise<number> {
  const answer = (await api.get(`/api/v1/clusters/${CLUSTER}/consumer-groups`)) as {
    groups?: { data?: { page?: { totalItems?: number } } };
  };
  const total = answer.groups?.data?.page?.totalItems;
  if (typeof total !== "number") {
    throw new Error(
      "The gateway answered the group list without a page total, so this spec cannot run.",
    );
  }
  return total;
}

test.describe("consumer groups", () => {
  test("prints the coordinator's address and not a broker id", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);

    const table = page.getByRole("table", { name: /consumer groups on this cluster/i });
    await expect(table).toBeVisible();
    // The quickstart's one broker, as the coordinator reports itself.
    await expect(table).toContainText("kafka:9092");
    // The shape the column used to hold: a broker id with a space in front of the number, which
    // reads like an address and is nowhere anybody can point a tool.
    await expect(table).not.toContainText(/broker \d/);
  });

  test("the count over the table is the server's, not the number of rows drawn", async ({
    page,
    api,
  }) => {
    const total = await totalGroups(api);
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);

    const heading = page.getByTestId("consumer-groups-head");
    await expect(heading).toBeVisible();
    // The sentence names the cluster's figure. On a cluster with more groups than a page holds
    // this differs from the rows on screen; here it is asserted against the number the *server*
    // gave, so a screen that counted its own rows would have to agree with the server by accident.
    await expect(heading).toContainText(`${total} group`);
    // And it is a count of groups, not a count of something the screen could not measure. The
    // product never prints a total it was not given: it says so in words instead.
    await expect(heading).not.toContainText("unstated total");
  });

  test("offers paging, and says which rows of how many are on screen", async ({ page, api }) => {
    const total = await totalGroups(api);
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);

    const paging = page.getByRole("navigation", { name: "Consumer group pages" });
    await expect(paging).toBeVisible();
    await expect(paging).toContainText(`of ${total}`);
    // One page of three groups: there is nowhere forward to go, and the control says so by
    // disabling the step rather than by hiding it.
    await expect(paging.getByRole("button", { name: "Next page" })).toBeDisabled();
  });

  test("the lag poll asks only about the groups on the page", async ({ page }) => {
    /*
     * CG-006's whole saving, and the half of it that is only true over the wire.
     *
     * `GET …/consumer-groups/lag` answers cluster-wide when `group` is absent, and this screen
     * draws one page. Asked unscoped, a cluster with more groups than fit answers about groups the
     * browser has no row for — and the merge refuses those, so every poll fell back to fetching the
     * whole list again. Nothing on screen changed, no figure was wrong, and the optimisation was
     * simply off. The quickstart has three groups and one page, so a unit test is what proves the
     * merge's behaviour; what this proves is that the request the browser actually sends carries
     * the scope, with the parameter spelled the way the gateway accepts it.
     */
    const poll = page.waitForRequest(
      (request) => request.url().includes("/consumer-groups/lag"),
      { timeout: 30_000 },
    );
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);
    const table = page.getByRole("table", { name: /consumer groups on this cluster/i });
    await expect(table).toBeVisible();

    const asked = new URL((await poll).url()).searchParams.getAll("group");
    expect(asked.length).toBeGreaterThan(0);

    // The same names the table is drawing, so this cannot pass against a scope built from anything
    // other than the rows on screen.
    const drawn = await page.locator(".kui-cg-name__link").allTextContents();
    expect([...asked].sort()).toEqual([...drawn].sort());
  });

  test("a group's name opens the group, which is where its offsets can be reset", async ({
    page,
  }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);

    const first = page.locator(".kui-cg-name__link").first();
    const name = (await first.textContent()) ?? "";
    expect(name).not.toBe("");
    await first.click();

    // The detail page, by its heading rather than by an address: every row's link resolved back to
    // the list before wave 2, and a URL assertion would have passed while it did.
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /reset offsets/i })).toBeVisible();
  });

  test("offers forgetting the offsets on each topic the group holds them on", async ({
    page,
    api,
  }) => {
    /*
     * `CG-005`, and the half only a browser settles: the control is on the shipped screen, and the
     * topics it names are the coordinator's rather than a fixture's. The row that shipped for two
     * waves said the control was there; the port to `feature-consumers` did not carry it across
     * and nothing looked.
     *
     * It is **not pressed here.** Forgetting a seeded group's offsets removes the group — a group
     * is nothing but its committed offsets — and the quickstart's three groups are shared state
     * that this file's own count assertions and three other specs read. KUI cannot create a
     * consumer group either: `…/offsets/plan` answers `KUI-GROUP-NOT-FOUND` for a group that does
     * not exist, checked against the running stack, so there is no scratch group to do it to. The
     * destructive half — the request, the receipt and the two sentences its figure chooses
     * between — is driven at the route in `src/groupRoute.test.tsx`.
     */
    const detail = (await api.get(
      `/api/v1/clusters/${CLUSTER}/consumer-groups/${encodeURIComponent(GROUP_WITH_OFFSETS)}`,
    )) as { topics?: { topic: string; partitions?: unknown[] }[] };
    const held = detail.topics ?? [];
    expect(held.length).toBeGreaterThan(0);

    await page.goto(
      `/ui/clusters/${CLUSTER}/consumer-groups/${encodeURIComponent(GROUP_WITH_OFFSETS)}`,
    );

    const section = page.getByTestId("group-forget-offsets");
    await expect(section).toBeVisible({ timeout: 20_000 });
    // One row per topic the coordinator says this group holds a position on, and the count beside
    // each is that topic's partitions — the figure the receipt after a click is read against.
    await expect(section.locator("li")).toHaveCount(held.length);
    for (const topic of held) {
      const partitions = topic.partitions?.length ?? 0;
      expect(partitions).toBeGreaterThan(0);
      const row = section.locator("li", { hasText: topic.topic });
      await expect(row).toContainText(
        partitions === 1 ? "1 partition held" : `${partitions} partitions held`,
      );
      await expect(row.getByRole("button", { name: /forget offsets/i })).toBeEnabled();
    }
  });
});
