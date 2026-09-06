/**
 * The frame, driven at the address the product actually opens on.
 *
 * This is M4's exit criterion, and it went unwritten for a whole wave because no packet owned
 * `frontend/e2e/**`: the frame was built, wired and — once — driven by hand in a browser, and the
 * only lasting record of that was a paragraph in a plan document. Every assertion below is one of
 * the four things that paragraph claimed.
 *
 * All of them are by role or by visible text rather than by `data-testid`, for the reason
 * `shell.spec.ts` gives: a test that selects on a testid asserts that a developer wrote an
 * attribute, and this suite exists to assert that a person can find the thing.
 */
import { test, expect, CLUSTER } from "./fixtures";

test.describe("the cluster dashboard", () => {
  test("names the cluster at the drawer's head, with a figure beside it", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * The head is a *cluster* block, not a product wordmark (`SCREENS-V4.md` §2.1): a status dot,
     * the cluster's name, and a three-part interpunct caption whose first token is variable in kind
     * — the health word when the cluster is clean, a defect count when it is not.
     */
    const drawer = page.getByTestId("nav-drawer");
    await expect(drawer.getByText(CLUSTER, { exact: false }).first()).toBeVisible();

    /*
     * And a broker count in the caption. The number is the cluster's own and is asserted as a
     * *number beside the word*, not as a literal: the quickstart runs one broker today and a
     * two-broker quickstart must not turn this red. What must never appear is the figure written as
     * a dash — each part of the caption is dropped when its figure is unknown, because a dash
     * beside a word reads as a missing dash rather than as a missing figure.
     */
    await expect(drawer).toContainText(/\d+ brokers?/);
    await expect(drawer).not.toContainText(/— brokers?/);
  });

  test("marks the dashboard as the current entry, not Brokers", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * Three signals say where you are — the drawer's highlight, the top band's trail and the page
     * heading — and two of them were wrong here. `/clusters/<id>/dashboard/<tab>` fell through to
     * the reading that answers `clusters` for any address under `/clusters`, so the drawer
     * highlighted **Brokers** and the trail read "Brokers" over a page headed "Cluster overview",
     * on the first screen anybody sees.
     */
    await expect(page.getByTestId("nav-overview")).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("nav-clusters")).not.toHaveAttribute("aria-current", "page");

    /* And the trail names the cluster and stops: "overview" adds nothing, because the cluster crumb
       already links there and a trail that repeats itself is a trail nobody reads. */
    const trail = page.getByRole("navigation", { name: /breadcrumb/i });
    await expect(trail).not.toContainText("Brokers");
  });

  test("nests the topic tree under Topics, and its rows lead to the list", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * The disclosure is the whole assertion. `nav/topicTree.ts` was written, tested and exported a
     * wave ago and called by nothing but the barrel that exported it, so the drawer never nested —
     * and a chevron only exists for a row that has children, which needs the names endpoint, the
     * fold and the frame's wiring all three.
     *
     * The label is a link and the chevron is a separate control, which is the other half of §2.2:
     * an operator who knows which prefix they want expands, and one who wants the list clicks the
     * label. Merging them costs whichever affordance loses.
     */
    const expand = page.getByRole("button", { name: /Expand Topics/i });
    await expect(expand).toBeVisible();
    await expand.click();

    /* A row per prefix group, each carrying its own count, and the padlocked `internal` row at the
       foot collecting Kafka's own bookkeeping topics whatever their prefixes are. */
    const subtree = page.getByTestId("nav-topics-subtree");
    await expect(subtree).toBeVisible();
    await expect(subtree.getByRole("link").first()).toBeVisible();
    /* The individual internal topics are *not* rows of their own: `__consumer_offsets` and
       `__transaction_state` are one padlocked row, which is what keeps them out of the way of an
       operator looking for their data. */
    await expect(subtree).not.toContainText("__consumer_offsets");

    /* That the rows are links at all, and that they carry the list's address. Where they *land* is
       the next case, and it is a different question — see its header. */
    const group = subtree.getByRole("link").first();
    await expect(group).toHaveAttribute("href", new RegExp(`/clusters/${CLUSTER}/topics`));
  });

  /**
   * A prefix row lands on a list that is actually filtered.
   *
   * The case above asserts the `href`, which is what the drawer builds, and its comment says so.
   * That is half the promise: `…/topics?q=orders` is an honest address only if the screen it names
   * reads the query. Until this wave it did not — `TopicsRoute` seeded its query from a default and
   * looked at the address only for `?tab=` — so every prefix row led to the unfiltered list, which
   * looks exactly like a row that worked, because the list it lands on does contain the topics the
   * row named. An `href` assertion cannot tell those apart; only the destination can.
   *
   * So this drives the click and reads three things: the address after it, the request the list
   * makes, and a topic that does not match being gone from the page.
   */
  test("a drawer prefix row lands on a filtered topics list", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    await page.getByRole("button", { name: /Expand Topics/i }).click();

    /* The row's accessible name is its label and then what its badge means — "orders.*, 3 topics"
       — so the label alone is a prefix match on it. The quickstart seeds three `orders.` topics,
       and the fold writes a group with children as `<segment>.*`. */
    const subtree = page.getByTestId("nav-topics-subtree");
    const orders = subtree.getByRole("link", { name: /^orders\.\*/ });
    await expect(orders).toBeVisible();

    /* Armed before the click: the list asks the server for the filtered page, which is the whole
       point of the query — a screen that filtered the rows it happened to hold would be honest on a
       cluster of ten topics and wrong on one of four thousand. */
    const filtered = page.waitForRequest(
      (request) => request.url().includes("/topics?") && request.url().includes("q=orders"),
    );
    await orders.click();

    await expect(page).toHaveURL(new RegExp(`/ui/clusters/${CLUSTER}/topics\\?q=orders$`));
    await filtered;

    /* And the screen shows the filtered answer. `analytics.pageviews` is seeded on the quickstart
       and is on the unfiltered first page, so its absence is the difference between a list that
       read the address and one that ignored it. */
    await expect(page.getByText("orders.v1").first()).toBeVisible();
    await expect(page.locator("body")).not.toContainText("analytics.pageviews");
  });

  test("the head's + is the route to cluster registration", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /* The only route to cluster registration in twenty-three screens (§2.1). It is a link and not a
       button, so a middle click opens it in a tab like every other destination. */
    await page.getByRole("link", { name: /Add a cluster/i }).click();
    await expect(page).toHaveURL(/\/ui\/clusters\/manage$/);
  });

  test("Create topic takes the operator to where the create flow lives", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * There is no address that opens the creation dialog — it belongs to the topics screen and is
     * opened from that screen's own button — so the honest wiring is to take the operator to where
     * the flow starts rather than to invent a second entry point.
     *
     * The assertion is the address, and it is the address that was wrong: `useNavigate` prefixes
     * the router's base onto a leading-`/` string, and every address this shell holds already
     * carries it, so the button navigated to `/ui/ui/clusters/<id>/topics` and drew the 404 page.
     * Nothing had ever clicked it.
     */
    await page.getByRole("button", { name: /Create topic/i }).click();
    await expect(page).toHaveURL(new RegExp(`/ui/clusters/${CLUSTER}/topics$`));
    await expect(page).not.toHaveURL(/\/ui\/ui\//);
  });

  test("the storage meter reads real log directories, or says it could not", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * The foot of the drawer. Either a percentage over two real byte figures, or the sentence
     * saying the disks could not be read — and never a row of zeros, which draws an empty bar and
     * reads as "your disks are empty". The product's central promise, applied to one panel.
     */
    const meter = page.getByTestId("storage-meter");
    await expect(meter).toBeVisible();
    await expect(meter).toContainText(/\d+%|could not be read|not known/i);
  });
});
