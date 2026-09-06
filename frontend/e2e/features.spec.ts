/**
 * The feature screens, each asserted on the distinction it exists to make.
 *
 * Every test here is about a *sentence* as much as a value, because on these screens the wording is
 * the feature: "nothing matched" and "nothing was read" are the same table without the count beside
 * it, and they are opposite conclusions.
 */
import { test, expect, CLUSTER } from "./fixtures";

interface SubjectsDocument {
  readonly items?: readonly {
    readonly subject: string;
    readonly compatibility?: { readonly inheritedFromGlobal?: boolean };
  }[];
  readonly page?: { readonly totalItems?: number | null };
}


/*
 * The registration case writes to the shared registry and is deliberately *not* marked serial.
 * `playwright.config.ts` already runs this suite with one worker, so nothing runs beside it; the
 * only thing `mode: "serial"` would add is skipping the rest of the file after any failure, which
 * turns one red case into nine unanswered ones. Isolation comes from the scratch subject name
 * instead — a Confluent-compatible registry has no subject delete in this product, so a name
 * nothing else uses is what keeps one run out of the next one's way.
 */
test.describe("the schema registry", () => {
  test("puts the registry's compatibility level where it cannot be missed", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);
    await expect(page.getByText("orders.avro-value").first()).toBeVisible();

    /*
     * The quickstart's registry runs at NONE, which means it checks nothing at all — it will accept
     * a schema that breaks every existing reader. That is the setting somebody switches on during an
     * incident and never switches back, so it is stated in words and not only in a colour.
     */
    await expect(page.locator("body")).toContainText(/accept a schema that breaks existing readers/i);
  });

  test("draws the subject's name, and never the object the wire now sends", async ({ page }) => {
    /*
     * The regression this file caught and nothing else did. `GET …/schemas/subjects` was widened
     * from `items: string[]` to a summary row per subject; the browser's mapping went on handing the
     * list whatever `items` held, and for a day the screen rendered `[object Object]` — in the link
     * text and in its href. `tsc` could not see it because the answer was cast, and the package's
     * own tests could not see it because the recorded fixture still held strings.
     *
     * So this asserts both halves: the name is on the page, and the object is nowhere on it. The
     * second half is what fails when a mapping starts passing rows through, because a row stringifies
     * to something that reads as a value.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);
    const link = page.getByRole("link", { name: /orders\.avro-value/ }).first();
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute("href", /orders\.avro-value/);
    await expect(page.locator("body")).not.toContainText("[object Object]");
  });

  test("says whether a subject's level is its own or the registry's", async ({ page, api }) => {
    /*
     * The distinction the whole feature turns on. A subject either has a compatibility level of its
     * own or follows the registry's global one, and the second group moves — every subject in it, at
     * once — the next time anybody changes the global level. A screen that shows the inherited level
     * as though it were the subject's own tells an operator the global change is safe here, when
     * this is exactly the subject it will move.
     *
     * The expectation is **asked of the gateway** rather than written down, because this assertion
     * used to be a disjunction over the only two strings the code can produce —
     * `/inherited from the registry's global level|set on this subject/` — which passes whichever
     * one is drawn and therefore cannot tell the two apart. That is the entire distinction, so it
     * was a case about the feature that could not fail on the feature.
     */
    const document = (await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/subjects?q=orders.avro-value`,
    )) as SubjectsDocument;
    const row = (document.items ?? []).find((one) => one.subject === "orders.avro-value");
    expect(row, "the quickstart's registry should hold orders.avro-value").toBeDefined();
    const inherited = row?.compatibility?.inheritedFromGlobal === true;

    await page.goto(`/ui/clusters/${CLUSTER}/schemas/orders.avro-value`);
    const pane = page.locator(".kui-subject");
    await expect(pane).toBeVisible();
    if (inherited) {
      await expect(pane).toContainText("inherited from the registry's global level");
      await expect(pane).not.toContainText("set on this subject");
    } else {
      await expect(pane).toContainText("set on this subject");
      await expect(pane).not.toContainText("inherited from the registry's global level");
    }
  });

  test("counts the registry's subjects and not the rows on this page", async ({ page, api }) => {
    // The header's figure is the registry's own total. The page holds fifty rows out of however
    // many the registry has, so the row count dressed up as an inventory is right only on a
    // registry with one page — which is every registry anybody develops against.
    const document = (await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/subjects`,
    )) as SubjectsDocument;
    const total = document.page?.totalItems;
    expect(typeof total, "the gateway should report a subject total").toBe("number");

    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);
    const voice = page.locator(".kui-schema-workspace__voice");
    await expect(voice).toBeVisible();
    await expect(voice).toContainText(new RegExp(`\\b${total}\\s+subjects?\\b`));
    await expect(voice).not.toContainText("did not say how many subjects");
  });

  test("selecting a subject changes the address and keeps the list beside it", async ({ page }) => {
    /*
     * Two panes, not two pages (§3.15). Reading a registry is comparing one subject's level against
     * the next one's, and a subject screen that replaces the list turns every comparison into a
     * navigation. The address carrying the selection is what makes a pasted link open the pane it
     * names rather than the list with instructions attached.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);
    await expect(page.getByText("No subject selected.")).toBeVisible();

    await page.getByRole("link", { name: /orders\.avro-value/ }).first().click();

    await expect(page).toHaveURL(/\/schemas\/orders\.avro-value/);
    await expect(page.getByRole("heading", { name: "Subjects" })).toBeVisible();
    await expect(page.getByText(/schema id/i).first()).toBeVisible();
  });

  test("registers a schema, and the registry's answer is what confirms it", async ({ page, api }) => {
    /*
     * M6's last bullet, driven. Until this wave the gateway served no endpoint that wrote a schema
     * and the control was drawn `aria-disabled` beside a sentence saying so; the sentence was true.
     *
     * The subject is scratch-named, so a failed run leaves nothing behind that the next one has to
     * work around — a schema registry has no delete in this product, so the name is the isolation.
     */
    const subject = `kui-e2e-register-${Date.now()}-value`;
    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);

    const control = page.getByRole("button", { name: /register schema/i });
    await expect(control).toBeVisible();
    // The control is live, which is the half of the bullet that was missing.
    await expect(control).not.toHaveAttribute("aria-disabled", "true");
    await control.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel(/^subject$/i).fill(subject);
    await dialog
      .getByLabel(/^schema$/i)
      .fill('{"type":"record","name":"KuiE2e","fields":[{"name":"id","type":"string"}]}');
    await dialog.getByRole("button", { name: /^register$/i }).click();

    // The confirmation carries the registry's own figures, and labels them: a version and an id are
    // different numbers and only the id is written into a record's header.
    await expect(page.locator(".kui-notice-stack")).toContainText(
      new RegExp(`Registered a schema under ${subject}`),
    );
    await expect(page.locator(".kui-notice-stack")).toContainText(/schema id \d+/);

    // And the registry holds it, asked directly rather than read back off the screen that claimed it.
    const versions = (await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/subjects/${encodeURIComponent(subject)}/versions`,
    )) as { readonly versions?: readonly number[] };
    expect(versions.versions ?? []).toContain(1);
  });

  test("keeps a subject's schema id apart from its version", async ({ page }) => {
    // They are different numbers, and it is the *id* a record's header carries — a record carries no
    // version at all. Conflating them sends somebody looking for "version 5" in a registry whose
    // versions stop at 2.
    await page.goto(`/ui/clusters/${CLUSTER}/schemas/orders.avro-value`);
    await expect(page.getByText(/schema id/i).first()).toBeVisible();
    await expect(page.getByText(/^version$/i).first()).toBeVisible();
  });
});

test.describe("tracking a message across topics", () => {
  test("says how much was read, not only how much matched", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/messages/track`);

    const to = new Date();
    const from = new Date(to.getTime() - 6 * 24 * 60 * 60 * 1000);
    await page.getByPlaceholder("orders.v1, orders.payments.v2").fill("orders.v1");
    await page.getByLabel(/^from$/i).fill(from.toISOString());
    await page.getByLabel(/^to$/i).fill(to.toISOString());
    await page.getByLabel(/^value$/i).fill("order");
    await page.getByRole("button", { name: /^search$/i }).click();

    /*
     * The line this screen exists for. Without it, "nothing matched" and "nothing was read" are the
     * same screen and mean opposite things — the value is not in those topics in that window, versus
     * the window was empty and nothing has been established at all. That is a support engineer
     * closing a ticket correctly or closing it wrongly.
     */
    await expect(page.locator("body")).toContainText(/Read [\d,]+ records?; [\d,]+ matched/i, {
      timeout: 30_000,
    });
  });

  test("refuses a window that ends before it starts, without asking the server", async ({ page }) => {
    // The server answers an inverted window with "nothing matched", which is the one answer this
    // screen must never give wrongly.
    await page.goto(`/ui/clusters/${CLUSTER}/messages/track`);
    await page.getByPlaceholder("orders.v1, orders.payments.v2").fill("orders.v1");
    await page.getByLabel(/^value$/i).fill("4711");
    await page.getByLabel(/^from$/i).fill("2026-09-05T12:00:00Z");
    await page.getByLabel(/^to$/i).fill("2026-09-05T11:00:00Z");

    await expect(page.getByRole("button", { name: /^search$/i })).toBeDisabled();
  });
});

test.describe("consumer groups", () => {
  test("a group's page shows what it is reading", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups`);
    await page.getByRole("link", { name: /analytics-indexer/ }).first().click();
    await expect(page.getByText("analytics.pageviews").first()).toBeVisible();
  });

  test("resetting offsets is refused while the group has members, and says why", async ({ page }) => {
    /*
     * Kafka genuinely cannot move a live group's offsets. The refusal is the one failure on this
     * screen an operator can act on themselves — by stopping the consumers — so it is shown as the
     * server words it rather than as a generic "could not reset".
     */
    await page.goto(`/ui/clusters/${CLUSTER}/consumer-groups/analytics-indexer`);
    await page.getByRole("button", { name: /reset offsets/i }).first().click();
    await page.getByRole("button", { name: /preview the plan/i }).first().click();

    await expect(page.locator("body")).toContainText(/stop its consumers/i, { timeout: 30_000 });
  });
});

test.describe("a topic's settings", () => {
  test("shows what was set on this topic apart from what it inherits", async ({ page }) => {
    /*
     * Kafka reports thirty-three keys for an ordinary topic and three of them hold a value somebody
     * chose. Those three are the entire reason anybody opens this tab — "why is this topic behaving
     * differently" is answered by them and by nothing else — so the rest are behind a switch.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/topics/orders.v1?tab=settings`);
    await expect(page.getByText(/set on this topic/i).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator("body")).not.toContainText("compression.gzip.level");

    await page.getByLabel(/show inherited settings/i).click({ force: true });
    await expect(page.getByText("compression.gzip.level").first()).toBeVisible();
  });
});
