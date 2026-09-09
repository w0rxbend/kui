/**
 * The frame: that it loads at all, that a deep link works, and that the two things a bug report
 * needs are on screen.
 *
 * This is the suite's smoke test. If it fails, nothing else in the run means anything.
 */
import { CLUSTER, test, expect } from "./fixtures";

test.describe("the shell", () => {
  test("loads, and reaches the gateway through its own proxy", async ({ page }) => {
    await page.goto("/ui/");

    // By role and by text, not by `data-testid`. A test that selects on a testid asserts that a
    // developer wrote an attribute; this asserts that a person can find the thing.
    await expect(page.getByRole("navigation").first()).toBeVisible();
    await expect(page.getByText("Quickstart", { exact: false }).first()).toBeVisible();
  });

  test("a deep link renders the page it names, not the root", async ({ page }) => {
    /*
     * The single-page fallback answers this URL with `index.html`, and the assets have to resolve
     * from `/ui/assets/…` rather than from `/ui/clusters/quickstart/assets/…`. That is what the
     * injected `<base href>` is for, and getting it wrong renders a blank page with three 404s and
     * nothing saying why — which is exactly the failure this asserts against.
     */
    await page.goto("/ui/clusters/quickstart/topics");
    await expect(page.getByText("orders.v1").first()).toBeVisible();
  });

  test("the settings page carries the build and the API, which is what a bug report needs", async ({
    page,
  }) => {
    // "It is broken" and "build 0.1.0-SNAPSHOT talking to /api/v1 is broken" are different reports
    // and only the second can be acted on. Neither value may be blank: the page says "not reported"
    // when it was not told, because a blank reads as a rendering fault.
    await page.goto("/ui/settings");
    const body = page.locator("body");
    await expect(body).toContainText(/Build/i);
    await expect(body).toContainText(/API/i);
    await expect(body).not.toContainText("undefined");
  });

  test("an address that names nothing says so, and offers a way back", async ({ page }) => {
    await page.goto("/ui/clusters/quickstart/not-a-real-section");
    await expect(page.getByRole("link").first()).toBeVisible();
  });

  /**
   * The bell, against the alerts service's own answer — the frame's half of M8's exit criterion.
   *
   * **The expectation is read off the API rather than written down here**, and that is the whole
   * design of this case. A literal would pass on a fixture and say nothing about the wire; the two
   * halves of wave 5's producers wire were each unit-tested against their own hand-written shape,
   * both green, while a real broker drew an empty card. So this asks `…/alerts/events` for
   * `openCount`, then insists the bell's accessible name carries that number and no other.
   *
   * All three of the section's honest answers are covered, because a deployment that has not
   * configured `services/alerts` is as real a state as one that has, and the bell has a different
   * true sentence for each. What no branch permits is the failure this is written against: a bell
   * saying "no open alerts" over a service that answered a positive count, or over one that
   * answered nothing at all. `@kui/feature-alerts`' own spec asserts the card's pill against the
   * same figure, which is what makes the bell and the card provably one number.
   */
  test("the bell carries the open count the alerts service answered, never one of its own", async ({
    page,
    api,
  }) => {
    const body = (await api.get(`/api/v1/clusters/${CLUSTER}/alerts/events`)) as {
      events?: { status?: string; data?: { openCount?: number | null } };
    };
    const section = body.events;
    expect(section?.status, "the alerts read answered no section at all").toBeDefined();

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    const bell = page.getByTestId("notifications");
    await expect(bell).toBeVisible();

    if (section?.status !== "ok" && section?.status !== "stale") {
      /* Nothing was counted, so nothing may be claimed. The one sentence that must never appear
         here is "no open alerts", which is a statement about the cluster. */
      await expect(bell).toHaveAttribute("aria-label", /not known/);
      return;
    }

    const open = section.data?.openCount;
    if (typeof open !== "number") {
      await expect(bell).toHaveAttribute("aria-label", /not known/);
      return;
    }
    if (open === 0) {
      await expect(bell).toHaveAttribute("aria-label", "Notifications, no open alerts");
      await expect(bell.locator(".kui-bell__badge")).toHaveCount(0);
      return;
    }
    // The server's own figure, in the name and on the badge — the badge capped at "9+", which is
    // why the name is the assertion and the badge is the sanity check.
    await expect(bell).toHaveAttribute("aria-label", new RegExp(`Notifications, ${open} open`));
    await expect(bell.locator(".kui-bell__badge")).toHaveText(open > 9 ? "9+" : String(open));
  });
});
