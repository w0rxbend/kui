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

    await expect(page.locator("body")).toContainText(name, { timeout: 20_000 });
    await removeTopic(api, name);
  });
});

test.describe("emptying a topic", () => {
  test("quotes the server's measured warning, and only once", async ({ page }) => {
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
