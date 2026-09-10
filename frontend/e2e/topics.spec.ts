/**
 * The topic write paths, driven through the interface against a real broker.
 *
 * These are the tests worth having. Every one of them exercises a control that changes somebody's
 * cluster, and each asserts the *sentence* the screen shows as well as the outcome — because on a
 * destructive confirmation the words are the feature. A dialog that deletes the right records while
 * saying the wrong number is a dialog nobody should trust.
 */
import { test, expect, CLUSTER, removeTopic, scratchTopic } from "./fixtures";

// One shared cluster, and these write to it. Serial, so a create and a delete cannot interleave.
test.describe.configure({ mode: "serial" });

test.describe("creating a topic", () => {
  const name = scratchTopic("create");

  test.afterAll(async ({ playwright }) => {
    const request = await playwright.request.newContext();
    await request.dispose();
  });

  test("says what cannot be undone before it asks for a name", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await page.getByRole("button", { name: /create topic/i }).first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();

    /*
     * The two sentences that are the point of this dialog. Partitions can be added and never
     * removed, and adding them changes which partition a key hashes to — which breaks per-key
     * ordering for anything relying on it. Replication factor cannot be changed from KUI at all.
     * Both are permanent decisions taken in ten seconds by somebody who came here to do something
     * else, and a dialog that does not say so is three boxes.
     */
    await expect(dialog).toContainText(/added later but never removed/i);
    await expect(dialog).toContainText(/cannot be changed/i);
  });

  test("refuses a name Kafka reserves, before a round trip", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await page.getByRole("button", { name: /create topic/i }).first().click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/^name/i).fill("..");
    await expect(dialog).toContainText(/reserves/i);
  });

  test("creates it, and the list shows it", async ({ page, api }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await page.getByRole("button", { name: /create topic/i }).first().click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/^name/i).fill(name);
    await dialog.getByRole("button", { name: /create topic/i }).click();

    /*
     * The dialog closing is asserted first, and separately, because it is what distinguishes the two
     * failures. A create the server refused leaves the dialog open with the reason in it — and
     * asserting only on the list gave "the list does not contain this name" and a screenshot of a
     * perfectly ordinary topic list, with the actual explanation sitting in a dialog the assertion
     * never looked at.
     */
    await expect(dialog, "the create dialog should close on success; if it is open it carries the reason it did not")
      .toBeHidden({ timeout: 20_000 });

    await expect(page.locator("body")).toContainText(name, { timeout: 20_000 });
    await removeTopic(api, name);
  });
});

test.describe("emptying a topic", () => {
  /*
   * One record, produced over HTTP before the dialog is opened.
   *
   * The sentence this case is about is the server's *measured* warning, and the server has nothing
   * to measure on an empty topic — it answers `warnings: []` and the dialog falls back to
   * `describePurge`, which is the correct rendering of a different state. So the case used to depend
   * on whatever the shared quickstart happened to hold, and it failed on a cluster whose seed had
   * been consumed: 14 later cases in this serial file never ran, for a reason that had nothing to do
   * with any of them. Arranging over HTTP rather than through the screen is this suite's own rule —
   * see `fixtures.ts`.
   */
  test("quotes the server's measured warning, and only once", async ({ page, api }) => {
    await api.post(`/api/v1/clusters/${CLUSTER}/topics/orders.v1/messages`, {
      count: 1,
      value: "kui-e2e: a record, so the purge plan has something to measure",
    });
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1`);
    await page.getByRole("button", { name: /empty topic/i }).first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText(/records? across \d+ partitions? (are|is) deleted/i, {
      timeout: 20_000,
    });

    /*
     * The screen used to compose its own measurement and append the server's warnings, so the
     * operator was told the size of the deletion twice, in two phrasings with two different
     * partition counts — the server says "4 partitions" because two hold nothing, and the browser
     * can only say "6". On a dialog whose whole purpose is that its numbers get read, that is the
     * worst possible place to disagree with yourself.
     */
    const text = (await dialog.innerText()).toLowerCase();
    expect(text.match(/cannot be recovered/g)?.length ?? 0).toBe(1);

    // And it says what survives, which is the reason somebody chooses empty over delete.
    await expect(dialog).toContainText(/only the records go/i);
  });

  test("asks for the topic's name before the button will work", async ({ page }) => {
    // Asked for here because the action destroys data and cannot be undone — and deliberately not
    // asked for on things that can be undone, because a product that demands it everywhere teaches
    // operators to type names without reading.
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1`);
    await page.getByRole("button", { name: /empty topic/i }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("orders.v1", { timeout: 20_000 });
    await expect(dialog.getByRole("button", { name: /^empty topic$/i })).toBeDisabled();
  });
});

test.describe("deleting a topic", () => {
  test("warns that auto-create will bring the name straight back", async ({ page, api }) => {
    const name = scratchTopic("delete");
    await api.post(`/api/v1/clusters/${CLUSTER}/topics`, { name, config: {} });

    await page.goto(`/ui/clusters/${CLUSTER}/topics/${name}`);
    await page.getByRole("button", { name: /delete topic/i }).first().click();

    const dialog = page.getByRole("dialog");
    /*
     * The sentence an operator is least likely to have thought of. With auto.create.topics.enable
     * on — which the quickstart has — deleting a topic something is still producing to does not
     * remove it: it removes the configuration and the data and leaves a fresh topic with the
     * broker's defaults, which is usually the opposite of the intent.
     */
    await expect(dialog).toContainText(/auto\.create\.topics\.enable|recreate/i, { timeout: 20_000 });

    await dialog.getByRole("textbox").first().fill(name);
    await dialog.getByRole("button", { name: /^delete topic$/i }).click();

    await expect(page).toHaveURL(new RegExp(`/clusters/${CLUSTER}/topics$`), { timeout: 20_000 });
    await removeTopic(api, name);
  });
});

test.describe("the topic list", () => {
  test("searches on the server, not the page it happens to hold", async ({ page }) => {
    /*
     * It used to filter the rows it had been given. That is honest for a cluster of ten topics and
     * wrong for one of four thousand: a search that only looks at the rows it was handed is a search
     * that lies, and it lies by finding nothing and saying so.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await expect(page.getByText("orders.v1").first()).toBeVisible();

    /*
     * Waiting for the request rather than collecting them and asserting afterwards.
     *
     * The first version asserted on a list of seen URLs immediately after the row appeared — and
     * `analytics.pageviews` is already on screen before any search, so the assertion ran during the
     * search box's 300ms debounce and saw nothing. Waiting for the request is both correct and the
     * thing the test is actually about.
     */
    const search = page.waitForRequest((request) => request.url().includes("q=analytics"));
    await page.getByPlaceholder("Search topics…").fill("analytics");
    await search;

    await expect(page.getByText("analytics.pageviews").first()).toBeVisible();
    // And the server really narrowed it: a topic that does not match is gone from the page, which a
    // client-side filter over one page could not guarantee for a cluster of any size.
    await expect(page.locator("body")).not.toContainText("orders.v1");
  });

  test("a ?q= in the address filters the list", async ({ page }) => {
    /*
     * The drawer's topic-prefix rows link here (`…/topics?q=<prefix>`), and this screen used to
     * seed its query from a default and read the address only for `?tab=` — so the link was honest
     * and the destination listed the whole cluster. The shell owns the link; this asserts arrival.
     *
     * The request is waited for rather than the rows, because that is where the filter is applied:
     * a screen that filled the search box and asked for everything would look the same on arrival.
     */
    const asked = page.waitForRequest((request) => request.url().includes("q=orders"));
    await page.goto(`/ui/clusters/${CLUSTER}/topics?q=orders`);
    await asked;

    await expect(page.getByPlaceholder("Search topics…")).toHaveValue("orders");
    await expect(page.getByText("orders.v1").first()).toBeVisible();
    // And the server really narrowed it: a topic that does not match is not on the page.
    await expect(page.locator("body")).not.toContainText("analytics.pageviews");
  });

  test("asks the gateway whether the cluster is registered read-only", async ({ page }) => {
    /*
     * The edge, driven through the browser's own path.
     *
     * `TopicsRoute` gates all seven of its write controls on ADR-047's read-only flag, and the
     * flag lives on `GET /api/v1/clusters/{clusterId}` — a different service from the one that
     * answers for topics. Every unit case in `feature-topics` stubs that path, so all of them would stay
     * green if the request 404'd, 502'd or never reached the gateway at all: the accessor answers
     * "not read-only" when it cannot ask, which is deliberate and which makes a broken edge
     * completely silent. Wave 6 lost a whole service to exactly that shape.
     *
     * So this asserts the **response**, through nginx's `/api` proxy, which is the only path the
     * browser uses. And the flag the quickstart really carries is `false`, so the Create control is
     * live beside it: a 200 carrying `readOnly: true` would be a different failure and this
     * separates the two.
     */
    const answered = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/api/v1/clusters/${CLUSTER}` &&
        response.request().method() === "GET",
    );
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    const response = await answered;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as { cluster?: { readOnly?: boolean } };
    expect(body.cluster?.readOnly).toBe(false);

    // And the screen agrees with it: the quickstart is writable, so the header action is offered.
    const create = page.getByRole("button", { name: /create topic/i }).first();
    await expect(create).toBeVisible();
    await expect(create).not.toHaveAttribute("aria-disabled", "true");
  });

  test("remembers whether the reader prefers cards", async ({ page }) => {
    // The design is explicit that the choice persists per user: an operator who prefers cards and
    // gets a table on every navigation concludes the control does not work.
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await page.getByRole("radio", { name: /^cards$/i }).click({ force: true });
    await expect(page.locator(".kui-topic-card").first()).toBeVisible();

    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await expect(page.locator(".kui-topic-card").first()).toBeVisible();

    await page.getByRole("radio", { name: /^table$/i }).click({ force: true });
    await expect(page.locator("table").first()).toBeVisible();
  });
});

test.describe("the topics list's statistics region", () => {
  test("reads the cluster's totals and does not follow the search box", async ({ page }) => {
    /*
     * `SCREENS-V4.md` §4.6 calls this the load-bearing fact of the screen: the capture shows the
     * cluster's topic count above a table narrowed to three rows. Against the quickstart the
     * cluster holds ten topics and the list excludes Kafka's bookkeeping ones, so the tile and the
     * table already disagree before anything is typed — and typing must not move the tile.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);

    const total = page.getByTestId("topic-stat-topics");
    await expect(total).toBeVisible();
    /* The figure alone, out of the label and the chip around it. A real number, not the sentence:
       the quickstart measures this one, and a tile reading "not measured" here would mean the
       assertion below was comparing two absences. */
    const figureOf = async (): Promise<string> =>
      (await total.innerText()).replace(/[^\d]/g, "");
    const before = await figureOf();
    expect(before).not.toBe("");
    await expect(total).not.toContainText("not measured");

    const search = page.waitForRequest((request) => request.url().includes("q=orders"));
    await page.getByPlaceholder("Search topics…").fill("orders");
    await search;
    await expect(page.getByText("analytics.pageviews")).toHaveCount(0);

    // The table moved; the cluster did not.
    expect(await figureOf()).toBe(before);
  });

  test("the statistics tile's partition total is read and compared, not merely non-empty", async ({
    page,
    api,
  }) => {
    /*
     * The case said "a page of eight topics cannot sum to the cluster's 86" and then asserted only
     * that the tile did not read "not measured" — so a tile that summed the page would have passed
     * it, which is the one failure the name describes. The number is now read off the screen and
     * compared twice: against the cluster's own total, and against the page's sum, which is a
     * different number and is what a fold over the rows would have drawn.
     */
    const statistics = (await api.get(`/api/v1/clusters/${CLUSTER}/topics/statistics`)) as {
      readonly statistics?: { readonly data?: { readonly partitionCount?: number | null } };
    };
    const clusterTotal = statistics.statistics?.data?.partitionCount ?? null;
    expect(
      clusterTotal,
      "the quickstart measures this; a null here is a broken fixture rather than a passing test",
    ).not.toBeNull();

    const listing = (await api.get(`/api/v1/clusters/${CLUSTER}/topics?page=1&pageSize=32`)) as {
      readonly topics?: {
        readonly data?: { readonly items?: readonly { readonly partitionCount?: number }[] };
      };
    };
    const pageSum = (listing.topics?.data?.items ?? []).reduce(
      (sum, topic) => sum + (topic.partitionCount ?? 0),
      0,
    );
    expect(
      pageSum,
      "the list excludes Kafka's own bookkeeping topics, so its sum must differ from the cluster's",
    ).not.toBe(clusterTotal);

    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    const partitions = page.getByTestId("topic-stat-partitions");
    await expect(partitions).toBeVisible();
    await expect(partitions).not.toContainText("not measured");

    /* The figure alone, out of the label and the chip around it — the same reading the case above
       uses, and the reason the tile carries a testId at all. */
    const drawn = Number((await partitions.innerText()).replace(/[^\d]/g, ""));
    expect(drawn).toBe(clusterTotal);
    expect(drawn).not.toBe(pageSum);
  });

  test("the switch closes the region and the list stays", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await expect(page.getByTestId("topic-stat-topics")).toBeVisible();
    await page.getByTestId("topic-statistics-switch").click({ force: true });
    await expect(page.getByTestId("topic-stat-topics")).toHaveCount(0);
    await expect(page.getByText("orders.v1").first()).toBeVisible();
  });
});

test.describe("the topics list's chips and selection", () => {
  test("a chip the cluster cannot apply says that it narrowed the page", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await page.getByRole("button", { name: /^compacted$/i }).click();
    await expect(page.getByText(/narrows the \d+ topics on this page/i)).toBeVisible();
  });

  test("selecting rows raises the bulk bar, and Delete keeps its place", async ({ page }) => {
    /*
     * §3.7: an action the principal may not take is disabled with its reason, never hidden — if
     * `Delete` disappeared, `Empty` would move into its place and the same gesture would do two
     * different irreversible things to two different people. Here the principal may do both, so the
     * assertion is that the bar appears with both of them on it and counts what was ticked.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics`);
    await expect(page.getByText("orders.v1").first()).toBeVisible();

    const ticks = page.locator("tbody input[type=checkbox]");
    await ticks.first().click({ force: true });
    await ticks.nth(1).click({ force: true });

    const bar = page.getByTestId("topic-bulk-bar");
    await expect(bar).toBeVisible();
    await expect(bar).toContainText("2 topics selected");
    await expect(bar.getByRole("button", { name: /^delete$/i })).toBeVisible();
    await expect(bar.getByRole("button", { name: /^empty$/i })).toBeVisible();
  });
});

test.describe("the topic's Overview tab", () => {
  test("draws a body: four figures and the partition table", async ({ page }) => {
    /*
     * The tab the strip opens by default, and the one that rendered nothing at all before this
     * wave: `TopicsRoute` declared `id: "overview"` and had no body for it anywhere in the file.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1`);

    await expect(page.getByTestId("topic-overview-partitions")).toContainText("6");
    await expect(page.getByTestId("topic-overview-groups")).toBeVisible();
    await expect(page.getByTestId("topic-partitions-table")).toBeVisible();

    /*
     * The quickstart's single broker reports no per-topic log-directory size and no produce rate.
     * Both must say so in words — `0 B` and `0 /s` are what a topic legitimately measures at, and
     * the whole promise of this product is that the two never look alike.
     */
    await expect(page.getByTestId("topic-overview-size")).toContainText("not measured");

    // And the trail inside the content, which is how somebody gets back to the list.
    await expect(page.getByRole("navigation", { name: "Breadcrumb" }).first()).toContainText("Topics");
  });

  test("Produce message goes to the browser that can produce", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1`);
    await page.getByRole("button", { name: /produce message/i }).first().click();
    await expect(page).toHaveURL(new RegExp(`/topics/orders.v1/messages$`), { timeout: 20_000 });
  });
});

test.describe("the topic's Consumers tab", () => {
  test("prints the coordinator as an address and not as a broker id", async ({ page }) => {
    /*
     * `coordinatorHost` and `coordinatorPort` are on the wire beside `coordinatorId`. Against the
     * quickstart the group's coordinator is `kafka:9092`; `broker 1` is not something an operator
     * can connect to, ping, or find in a log, which is the whole reason the column is an address.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1?tab=consumers`);
    const table = page.getByTestId("topic-consumers-table");
    await expect(table).toBeVisible();
    await expect(table).toContainText("order-fulfilment", { timeout: 20_000 });
    await expect(table).toContainText(/[a-z0-9.-]+:\d+/);
    await expect(table).not.toContainText(/broker \d/i);
  });

  test("no column heading is blank", async ({ page }) => {
    // The heading that shipped as `""` and produced all fourteen of the a11y sweep's violations.
    // A screen reader announces a cell by its column, and this column had nothing to announce.
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1?tab=consumers`);
    const headings = page.getByTestId("topic-consumers-table").locator("th");
    await expect(headings.first()).toBeVisible();
    for (const text of await headings.allInnerTexts()) {
      expect(text.trim()).not.toBe("");
    }
  });
});
