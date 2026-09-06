/**
 * The message browser, driven against a real broker.
 *
 * Three of the four things this screen was rebuilt for are only true end to end, and each of them
 * was false in the shipped product for a reason no component test could see:
 *
 *   - **The partition count is the topic's.** The route held `createSignal(0)` and handed the zero
 *     to three children, so the copy dialog told every operator the source topic has 0 partitions.
 *     The assertion below reads the figure from the API and then reads it off the screen, so it
 *     cannot pass against a hard-coded anything.
 *   - **A typed predicate reaches the request.** The key and value predicates have no query
 *     parameter — they compile to one CEL expression that the screen registers with the message
 *     service, and the browse quotes the id it was given. Two hops, both over the network.
 *   - **The address is the browse.** It was written with `window.history.replaceState`, which the
 *     router never hears about, so every control on the bar changed the URL and left the browse
 *     reading whatever the page was opened with.
 *
 * Everything here reads except the last case, which publishes one record into a topic it created.
 */
import { test, expect, CLUSTER, removeTopic, scratchTopic } from "./fixtures";

// It shares the cluster with the other specs, and the last case writes to it.
test.describe.configure({ mode: "serial" });

const TOPIC = "orders.v1";

/** The topic's own partition count, straight from the gateway. The screen has to agree with this. */
async function partitionCount(api: { get: (path: string) => Promise<unknown> }): Promise<number> {
  const answer = (await api.get(
    `/api/v1/clusters/${CLUSTER}/topics/${encodeURIComponent(TOPIC)}`,
  )) as { topic?: { data?: { row?: { partitionCount?: number } } } };
  const count = answer.topic?.data?.row?.partitionCount;
  if (typeof count !== "number") {
    throw new Error(
      `the gateway did not describe ${TOPIC}; there is nothing for the screen to agree with`,
    );
  }
  return count;
}

test.describe("the partition count", () => {
  test("the selector names the topic's real one, and never `all 0`", async ({ page, api }) => {
    const count = await partitionCount(api);

    await page.goto(`/ui/clusters/${CLUSTER}/topics/${TOPIC}/messages`);
    const picker = page.locator(".kui-partition-picker__trigger");
    await expect(picker).toBeVisible();

    /*
     * `all N` with the broker's own N. The zero this replaced was not a rounding error: it made the
     * menu empty as well, so the control offered no partition to choose and read as a topic that
     * has none.
     */
    await expect(picker).toContainText(`all ${count}`, { timeout: 20_000 });
    await expect(picker).not.toContainText("all 0");
  });

  test("the copy dialog quotes it, in a sentence", async ({ page, api }) => {
    const count = await partitionCount(api);

    await page.goto(`/ui/clusters/${CLUSTER}/topics/${TOPIC}/messages`);
    await page.getByRole("button", { name: /copy records out/i }).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    /*
     * The sentence is the feature. A dialog that copies the right records while saying the wrong
     * number is a dialog nobody should trust — and this one said "has 0 partitions" beside a
     * control that copies records between topics.
     */
    await expect(dialog).toContainText(`${TOPIC} has ${count} partitions.`, { timeout: 20_000 });
    await expect(dialog).not.toContainText("has 0 partitions");
  });
});

test.describe("the typed predicates", () => {
  test("a key predicate and a value predicate reach the request separately", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/topics/${TOPIC}/messages`);

    await page.getByLabel(/key predicate/i).fill("ord_");
    await page.getByLabel(/value predicate/i).fill("UAH");

    /*
     * The address first, because it is the cheap half and it is the half that was broken: the
     * previous writer used `window.history.replaceState`, which the router does not hear, so the
     * URL and the browse disagreed from the first keystroke.
     */
    await expect(page).toHaveURL(/key=ord_/, { timeout: 10_000 });
    await expect(page).toHaveURL(/value=UAH/);

    /*
     * Then the two requests Read makes. Waiting for them rather than collecting afterwards, for the
     * reason `topics.spec.ts` gives about the search box: an assertion that runs between the click
     * and the request sees nothing and blames the feature.
     */
    const registration = page.waitForRequest(
      (request) => request.url().includes("/messages/filters") && request.method() === "POST",
    );
    const browse = page.waitForRequest((request) => request.url().includes("/messages/stream"));

    await page.getByRole("button", { name: /^read$/i }).first().click();

    const registered = await registration;
    const source = String((registered.postDataJSON() as { source?: unknown }).source ?? "");
    // Two terms over two variables. `q`, the one substring parameter the endpoint has, is matched
    // against the whole decoded record and could not tell a key predicate from a value one.
    expect(source).toContain('record.keyAsText.contains("ord_")');
    expect(source).toContain('record.valueAsText.contains("UAH")');

    const url = new URL((await browse).url());
    // The id and the source travel together: a replica that never saw the registration compiles the
    // source beside it, and a browse carrying only the source is dropped in silence.
    expect(url.searchParams.get("filterId")).toBeTruthy();
    expect(url.searchParams.get("filterSource")).toContain("record.keyAsText");
    expect(url.searchParams.get("filterSource")).toContain("record.valueAsText");
  });

  test("a browse with no predicate registers nothing", async ({ page }) => {
    // The common browse must not have acquired a round trip. A filter endpoint call here would be
    // the screen registering the empty expression, which is a compile nobody asked for.
    await page.goto(`/ui/clusters/${CLUSTER}/topics/${TOPIC}/messages`);

    const filterCalls: string[] = [];
    page.on("request", (request) => {
      if (request.url().includes("/messages/filters")) filterCalls.push(request.url());
    });

    const browse = page.waitForRequest((request) => request.url().includes("/messages/stream"));
    await page.getByRole("button", { name: /^read$/i }).first().click();
    await browse;

    expect(filterCalls).toEqual([]);
  });
});

test.describe("writing", () => {
  test("a produce that succeeds raises a toast naming where the record landed", async ({
    page,
    api,
  }) => {
    const name = scratchTopic("produce");
    await api.post(`/api/v1/clusters/${CLUSTER}/topics`, { name, config: {} });

    await page.goto(`/ui/clusters/${CLUSTER}/topics/${name}/messages`);
    await page.getByRole("button", { name: /produce message/i }).click();

    const drawer = page.getByRole("dialog");
    await expect(drawer).toBeVisible();
    await drawer.getByLabel("Value", { exact: true }).first().fill('{"from":"the e2e suite"}');
    await drawer.getByRole("button", { name: /^produce record$/i }).click();

    /*
     * The toast, and not the drawer's own receipt. A produce cannot be undone and the drawer is
     * dismissible; the confirmation that survives closing it is the thing an operator has afterwards
     * to say the write happened. It quotes a position rather than saying "sent", because a position
     * is something they can go and look at.
     */
    const toast = page.locator(".kui-notice").first();
    await expect(toast).toBeVisible({ timeout: 20_000 });
    await expect(toast).toContainText(/published/i);
    await expect(toast).toContainText(/partition \d+, offset \d+/i);

    await removeTopic(api, name);
  });
});
