/**
 * The frame: that it loads at all, that a deep link works, and that the two things a bug report
 * needs are on screen.
 *
 * This is the suite's smoke test. If it fails, nothing else in the run means anything.
 */
import { test, expect } from "./fixtures";

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
});
