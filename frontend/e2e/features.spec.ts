/**
 * The feature screens, each asserted on the distinction it exists to make.
 *
 * Every test here is about a *sentence* as much as a value, because on these screens the wording is
 * the feature: "nothing matched" and "nothing was read" are the same table without the count beside
 * it, and they are opposite conclusions.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

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

    /*
     * Searched for rather than picked off the first page, and that is not tidiness. This file leaves
     * four scratch subjects behind on every run -- `compat-subject`, `compat-check`, `compat-none` and
     * `register` -- because a Confluent-compatible registry has no subject delete in this product, so a
     * unique name is the isolation. Measured on the quickstart: one run took the registry from 1 subject
     * to 5. The list pane asks for 50 rows and the registry answers alphabetically, and every one of
     * those names begins `kui-e2e-`, which sorts before `orders`. So on the fiftieth accumulated scratch
     * subject -- about twelve more runs against a registry nobody tore down -- `orders.avro-value` falls
     * off page one and a click that reads it off the list stops finding it, with nothing wrong with the
     * product. Searching for the subject is what the screen offers a person in that situation, and it
     * makes this case independent of how many times the suite has been run.
     */
    await page.getByLabel("Search subjects").fill("orders.avro-value");
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

/**
 * `SR-005`, driven.
 *
 * The row has stood at REVIEW because only the *display* of a compatibility level had ever been
 * pressed in a browser: the two writes and the check had recorded-response tests and seven stories
 * and no browser evidence at all. These four cases are that evidence, against the quickstart's own
 * Apicurio registry.
 *
 * The global level is changed and changed **back**, in a `finally`, because the first case in this
 * file asserts that the quickstart runs at `NONE` — a suite that leaves the registry somewhere else
 * would fail a sibling case for a reason that has nothing to do with it.
 */
interface LevelDocument {
  readonly level?: string | null;
  readonly inheritedFromGlobal?: boolean;
}

interface VerdictDocument {
  readonly compatible?: boolean;
  readonly messages?: readonly string[];
}

/** A subject name nothing else will collide with. The registry has no delete, so the name is the isolation. */
function scratchSubject(what: string): string {
  return `kui-e2e-${what}-${Date.now()}-value`;
}

const BASE_SCHEMA =
  '{"type":"record","name":"KuiE2eCompat","fields":[{"name":"id","type":"string"}]}';
/** A field with no default: readers of the old schema cannot read this, so BACKWARD refuses it. */
const BREAKING_SCHEMA =
  '{"type":"record","name":"KuiE2eCompat","fields":[{"name":"id","type":"string"},' +
  '{"name":"channel","type":"string"}]}';
/** The same field with a default, which is the change BACKWARD exists to allow. */
const SAFE_SCHEMA =
  '{"type":"record","name":"KuiE2eCompat","fields":[{"name":"id","type":"string"},' +
  '{"name":"channel","type":"string","default":""}]}';

/** Registers a subject over HTTP, so a failure to arrange cannot be read as the failure under test. */
async function seedSubject(api: KuiApi, subject: string): Promise<void> {
  await api.post(
    `/api/v1/clusters/${CLUSTER}/schemas/subjects/${encodeURIComponent(subject)}/versions`,
    { schemaType: "AVRO", definition: BASE_SCHEMA },
  );
}

test.describe("compatibility, written from the screen", () => {
  test("changes the registry's global level, and says what NONE means when NONE is set", async ({
    page,
    api,
  }) => {
    /*
     * Both writes in one case, because the *restore* is the second write and running it as a
     * separate test would leave the registry changed if this one failed.
     *
     * The two toasts are deliberately different events: every level but `NONE` narrows what the
     * registry will accept, and `NONE` turns the checking off — a green "done" for that would be
     * the product agreeing with a decision it should be reporting.
     */
    const before = ((await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/compatibility`,
    )) as LevelDocument).level;
    expect(typeof before, "the gateway should report a global compatibility level").toBe("string");

    await page.goto(`/ui/clusters/${CLUSTER}/schemas`);
    const globalRow = page.locator(".kui-schemas__global");
    await expect(globalRow).toBeVisible();

    try {
      // The Select opens on `BACKWARD`, so Save alone writes it.
      await globalRow.getByRole("button", { name: "Change" }).click();
      await globalRow.getByRole("button", { name: "Save" }).click();

      await expect(page.locator(".kui-notice-stack")).toContainText(
        "Compatibility for every inheriting subject set to BACKWARD",
      );
      // Asked of the registry rather than read back off the screen that claimed it.
      const written = ((await api.get(
        `/api/v1/clusters/${CLUSTER}/schemas/compatibility`,
      )) as LevelDocument).level;
      expect(written).toBe("BACKWARD");
      // And the screen re-read it: the pill is the registry's answer, not the value that was typed.
      await expect(globalRow).toContainText("BACKWARD");
      await expect(globalRow).not.toContainText("accept a schema that breaks existing readers");
    } finally {
      await globalRow.getByRole("button", { name: "Change" }).click();
      await globalRow.getByRole("combobox", { name: "Compatibility level" }).click();
      await page.getByRole("option", { name: String(before), exact: true }).click();
      await globalRow.getByRole("button", { name: "Save" }).click();
      await expect(page.locator(".kui-notice-stack")).toContainText(
        `Compatibility for every inheriting subject set to ${String(before)}`,
      );
    }

    const restored = ((await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/compatibility`,
    )) as LevelDocument).level;
    expect(restored).toBe(before);

    if (restored === "NONE") {
      // The warning, not a cheerful success: from here the registry accepts a schema that breaks
      // every existing reader, and the toast is the only place that is said as it happens.
      await expect(page.locator(".kui-notice-stack")).toContainText(/breaks every existing reader/i);
      await expect(globalRow).toContainText("accept a schema that breaks existing readers");
    }
  });

  test("sets one subject's level, and the screen says it is the subject's own", async ({
    page,
    api,
  }) => {
    /*
     * The second write, and the distinction the feature turns on. The quickstart's registry reports
     * every subject at the global `NONE`, so a subject that reads `BACKWARD, set on this subject`
     * afterwards is the write having landed — and the sentence beside the pill is what tells an
     * operator that changing the *global* level will no longer move this one.
     */
    const subject = scratchSubject("compat-subject");
    await seedSubject(api, subject);

    await page.goto(`/ui/clusters/${CLUSTER}/schemas/${encodeURIComponent(subject)}`);
    const pane = page.locator(".kui-subject");
    await expect(pane).toBeVisible();

    await pane.getByRole("combobox", { name: "Compatibility level" }).click();
    await page.getByRole("option", { name: "BACKWARD", exact: true }).click();

    await expect(page.locator(".kui-notice-stack")).toContainText(
      `Compatibility for ${subject} set to BACKWARD`,
    );

    const level = (await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/subjects/${encodeURIComponent(subject)}/compatibility`,
    )) as LevelDocument;
    expect(level.level).toBe("BACKWARD");
    expect(level.inheritedFromGlobal).toBe(false);

    await expect(pane.locator(".kui-subject__compat")).toContainText("BACKWARD");
    await expect(pane).toContainText("set on this subject");
    await expect(pane).not.toContainText("inherited from the registry's global level");
  });

  test("asks the registry whether a schema would be accepted, and draws the answer it gave", async ({
    page,
    api,
  }) => {
    /*
     * The check, driven, on the deployment where its hardest rendering is the ordinary one.
     *
     * Apicurio's Confluent-compatible API words its explanation under a key KUI's registry client
     * does not read, so **both** verdicts arrive with `messages: []` — measured here, not assumed:
     * a refusal is `{"compatible": false, "messages": []}` and an acceptance is
     * `{"compatible": true, "messages": []}`. The same empty list therefore has to render as two
     * opposite sentences, decided by the verdict and by nothing else, and "refused, and the
     * registry gave no reason" must never look like "the registry raised nothing against it".
     */
    const subject = scratchSubject("compat-check");
    await seedSubject(api, subject);

    await page.goto(`/ui/clusters/${CLUSTER}/schemas/${encodeURIComponent(subject)}`);
    const pane = page.locator(".kui-subject");
    await expect(pane).toBeVisible();

    // A level of NONE would make the registry answer "compatible" for anything, which is why the
    // control refuses to run there — so the check needs a level that decides something first.
    await pane.getByRole("combobox", { name: "Compatibility level" }).click();
    await page.getByRole("option", { name: "BACKWARD", exact: true }).click();
    await expect(pane.locator(".kui-subject__compat")).toContainText("BACKWARD");

    const panel = page.locator(".kui-schema-check");
    await expect(panel).toBeVisible();
    // The panel says on itself that it writes nothing; that promise is the reason anybody presses it.
    await expect(panel).toContainText(/Nothing is registered and nothing is changed/i);

    const editor = panel.locator("textarea");
    const button = panel.getByRole("button", { name: "Check compatibility" });

    /** What the registry itself says, so the expectation is not a guess about this registry. */
    const asked = async (definition: string): Promise<VerdictDocument> =>
      (await api.post(
        `/api/v1/clusters/${CLUSTER}/schemas/subjects/${encodeURIComponent(subject)}` +
          `/versions/latest/compatibility`,
        { schemaType: "AVRO", definition },
      )) as VerdictDocument;

    const refused = await asked(BREAKING_SCHEMA);
    expect(
      refused.compatible,
      "a required field with no default should be refused under BACKWARD",
    ).toBe(false);

    await editor.fill(BREAKING_SCHEMA);
    await button.click();
    const verdict = page.locator('[data-testid="compatibility-verdict"]');
    await expect(verdict).toBeVisible();
    await expect(verdict).toContainText("Would be refused");
    if ((refused.messages ?? []).length === 0) {
      await expect(verdict).toContainText(/gave no reason/i);
      await expect(verdict).not.toContainText(/raised nothing against it/i);
    } else {
      await expect(verdict).toContainText(String((refused.messages ?? [])[0]));
    }

    const accepted = await asked(SAFE_SCHEMA);
    expect(accepted.compatible, "the same field with a default should be accepted").toBe(true);

    await editor.fill(SAFE_SCHEMA);
    await button.click();
    await expect(verdict).toContainText("Would be accepted");
    await expect(verdict).toContainText(/raised nothing against it/i);
    await expect(verdict).not.toContainText(/gave no reason/i);

    // Nothing was registered: the panel's promise, asked of the registry rather than believed.
    const versions = (await api.get(
      `/api/v1/clusters/${CLUSTER}/schemas/subjects/${encodeURIComponent(subject)}/versions`,
    )) as { readonly versions?: readonly number[] };
    expect(versions.versions ?? []).toEqual([1]);
  });

  test("refuses the check under NONE, and says why rather than hiding the control", async ({
    page,
    api,
  }) => {
    /*
     * The refusal beside the capability. Under `NONE` the registry answers "compatible" for every
     * schema, including one that breaks every reader — the recorded documents show the two verdicts
     * are byte for byte identical — so a green pill there would be evidence for a change that is
     * about to break production. The control is disabled rather than removed, because a missing
     * control teaches an operator the product cannot do the thing.
     */
    const subject = scratchSubject("compat-none");
    await seedSubject(api, subject);

    await page.goto(`/ui/clusters/${CLUSTER}/schemas/${encodeURIComponent(subject)}`);
    const pane = page.locator(".kui-subject");
    await expect(pane).toBeVisible();
    await expect(pane.locator(".kui-subject__compat")).toContainText("NONE");

    const panel = page.locator(".kui-schema-check");
    await expect(panel).toContainText(/compatibility level is NONE/i);
    const button = panel.getByRole("button", { name: "Check compatibility" });
    await expect(button).toHaveAttribute("aria-disabled", "true");
    await button.focus();
    await expect(page.locator('[role="tooltip"]')).toContainText(/not an answer worth having/i);
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
