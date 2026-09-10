/**
 * The alerts screen, in a browser, against a stack built from this tree.
 *
 * ## The seeded run is the asserted one
 *
 * Nothing in this product seeds an alert event: one is opened by a rule reading a fact about the
 * cluster — an offline partition, a stuck rebalance, a log directory past its threshold — and there
 * is no endpoint that writes one, which is the design (`W6-01`: *"do not seed an event to make a
 * screen look alive"*). A healthy quickstart therefore has an empty feed, and until wave 7 three of
 * the four cases here answered that by skipping, so the branches that draw a **row** had never been
 * run in a browser at all.
 *
 * The event is seeded by moving the threshold instead of by writing a row, which is the only way
 * this product can produce one honestly: set `diskUsedWarningPercent: 1` in
 * `deployment/quickstart/kui-quickstart.yaml`, restart `kui-quickstart-kui`, and one `storage`
 * event opens against the broker's real log directory. That is how M8's exit criterion was
 * produced, and it is how this suite is meant to be run before it is believed. Revert the file
 * afterwards.
 *
 * ## What still branches, and why that is not a skip
 *
 * A browser suite cannot demand rows without becoming a suite that fails on a working cluster. So
 * every case below reads the gateway's own answer first and then asserts **the sentence that answer
 * earns** — a row when there is a row, and the named sentence for an empty, unevaluated,
 * unconfigured or refused feed when there is not. A branch that asserted nothing would be the
 * failure this wave exists to end: wave 5's `traffic.spec.ts` looped over an empty array, skipped
 * its only assertion, and reported green over two cards that drew nothing.
 *
 * **One case can still skip and it is the last one.** A deployment that runs an alerts service
 * cannot be put into `not_configured` from outside, and the hidden-card rendering is therefore not
 * on this stack's screen at all. It is asserted in `packages/feature-alerts/src/alerts.test.tsx`
 * against the service's own `not_configured` document; here it is honestly unreachable, and it says
 * so in the skip reason rather than pretending to have run.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The feed, as the gateway serves it. The shape is `services/alerts`' `AlertFeedResponse`. */
interface FeedDocument {
  readonly events?: {
    readonly status?: string;
    readonly reason?: string;
    readonly message?: string;
    readonly data?: {
      readonly items?: readonly {
        readonly id?: string;
        readonly title?: string;
        readonly detail?: string;
        readonly severity?: string;
        readonly resolution?: unknown;
      }[];
      readonly openCount?: number;
      readonly total?: number;
      readonly evaluatedAt?: string;
      readonly rules?: readonly { readonly rule?: string }[];
    };
  };
}

async function feed(api: KuiApi): Promise<FeedDocument> {
  return (await api.get(`/api/v1/clusters/${CLUSTER}/alerts/events`)) as FeedDocument;
}

/** One row of the feed, as far as this suite reads it. */
type FeedSection = NonNullable<FeedDocument["events"]>;
type FeedItem = NonNullable<NonNullable<FeedSection["data"]>["items"]>[number];

/** The rows the service is holding on this page, or none when it is not holding a page at all. */
function itemsIn(document: FeedDocument): readonly FeedItem[] {
  const section = document.events;
  return section?.status === "ok" || section?.status === "stale" ? (section.data?.items ?? []) : [];
}

test.describe("the alerts screen", () => {
  test("the API's own feed is a document this build understands", async ({ api }) => {
    const document = await feed(api);
    const section = document.events;

    /*
     * The section key first, because it is the one thing both halves of this wire had to agree on
     * before anything else could: `ROADMAP.md`'s M8 exit criterion is written in `jq` against
     * `.events.status`, and a document that carries the section under any other key is the failure
     * that put "the metrics source named no producers" over a source that had named five.
     */
    expect(section, "the response carried no 'events' section").toBeDefined();
    expect(
      ["ok", "stale", "unavailable", "not_configured", "forbidden"],
      `the section's status was ${section?.status}`,
    ).toContain(section?.status);

    if (section?.status === "ok" || section?.status === "stale") {
      // `items` is an array — the field name the browser reads — and the counts are numbers or
      // absent. Never "an empty array because the name was wrong".
      expect(Array.isArray(section.data?.items)).toBe(true);
      if (section.data?.openCount !== undefined) {
        expect(typeof section.data.openCount).toBe("number");
      }
    }
  });

  test("the card's count is the API's own figure, and never the rows on the page", async ({
    page,
    api,
  }) => {
    const document = await feed(api);
    const section = document.events;

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    await expect(page.getByRole("heading", { name: "Alerts", level: 1 })).toBeVisible();

    const card = page.getByTestId("alerts-feed");
    const pill = page.getByTestId("alerts-open-count");

    /*
     * A read this principal may not make, or a service that is not answering. No skip: these are
     * states the screen has sentences for, and the sentence is what is asserted. `not_configured`
     * is the last case in this file and draws no card at all.
     */
    if (section?.status === "forbidden") {
      await expect(card).toContainText("do not have permission to read this cluster's alerts");
      await expect(card).not.toContainText("Retry");
      return;
    }
    if (section?.status === "unavailable" || section?.status === "unreadable") {
      await expect(card).toContainText(section.message ?? "did not answer");
      await expect(pill).toHaveCount(0);
      return;
    }
    if (section?.status === "not_configured") {
      await expect(card).toHaveCount(0);
      return;
    }

    await expect(card).toBeVisible();
    const open = section?.data?.openCount;
    const rows = section?.data?.items ?? [];

    if (section?.data?.evaluatedAt === undefined) {
      /* A cluster the rules have never run on. The pill says so rather than drawing a zero, which
         is the whole reason `evaluatedAt` is on the wire — and the voice line above it agrees. */
      await expect(pill).toContainText("Not evaluated yet");
      await expect(page.locator(".kui-page-head__voice")).toContainText(
        "KUI has not run its alert rules on this cluster yet.",
      );
    } else if (open === undefined) {
      await expect(pill).toHaveCount(0);
      await expect(card).toContainText("did not say how many events are open");
    } else if (rows.length === 0) {
      // An answered, empty, evaluated feed: a sentence naming the source, and no count beside it.
      await expect(pill).toHaveCount(0);
      await expect(card).toContainText("is holding no events for this cluster");
    } else if (open === 0) {
      await expect(pill).toContainText("None open");
    } else {
      // The figure, exactly as the API wrote it — not the number of rows the page happens to hold.
      await expect(pill).toContainText(`${open} open`);
      await expect(page.getByTestId("alert-row")).toHaveCount(rows.length);
    }

    // Whatever branch ran: never a bare zero and never an em dash where a sentence belongs.
    await expect(card).not.toContainText("0 open");
    await expect(card).not.toContainText("—");
  });

  /**
   * The seeded event, drawn.
   *
   * This is the branch the old suite skipped past. With `diskUsedWarningPercent: 1` the disk-usage
   * rule opens one `storage` event against a real log directory, and everything below is read off
   * the API's own document rather than written here: the title the service composed, the count it
   * counted, and the acknowledgement control that only an *open* row may carry.
   *
   * On a healthy cluster the same case asserts what an empty page earns, so it never passes by
   * looping over nothing.
   */
  test("a page holding events draws each of them, and offers acknowledgement only on open ones", async ({
    page,
    api,
  }) => {
    const document = await feed(api);
    const section = document.events;
    const rows = itemsIn(document);

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    const card = page.getByTestId("alerts-feed");

    if (section?.status !== "ok" && section?.status !== "stale") {
      // Nothing was answered, so there is no page to draw. The card's own sentence for that state
      // is the previous case's; here the assertion is simply that no row was invented.
      await expect(page.getByTestId("alert-row")).toHaveCount(0);
      return;
    }

    if (rows.length === 0) {
      await expect(card).toContainText(
        section.data?.evaluatedAt === undefined
          ? "so an empty feed says nothing about it"
          : "is holding no events for this cluster",
      );
      await expect(page.getByTestId("alert-row")).toHaveCount(0);
      return;
    }

    await expect(page.getByTestId("alert-row")).toHaveCount(rows.length);
    for (const event of rows) {
      // The service's own title on the row the service gave that id to. A card that drew rows in
      // the wrong order, or drew one row five times, fails here.
      const row = page.locator(`[data-testid="alert-row"][data-event="${String(event.id)}"]`);
      await expect(row).toHaveCount(1);
      await expect(row).toContainText(String(event.title));
      if (event.detail !== undefined) await expect(row).toContainText(event.detail);
      // "KUI noticed", not "opened": the age is KUI's own observation, and the row says so.
      await expect(row).toContainText("KUI noticed");

      const acknowledge = row.getByRole("button", { name: /Acknowledge/ });
      if (event.resolution === undefined || event.resolution === null) {
        // An open row offers the control — enabled for a principal who may acknowledge, disabled
        // with a reason for one who may not. Never absent, and never enabled with no wiring behind
        // it: what is asserted is that the operator is told which of the two they are looking at.
        await expect(acknowledge).toHaveCount(1);
        const disabled = await acknowledge.getAttribute("aria-disabled");
        if (disabled === "true") {
          await expect(acknowledge).toHaveAttribute("aria-describedby", /.+/);
        }
      } else {
        // A resolved row has nothing left to acknowledge, and offering the control would put a
        // write in front of somebody that the service answers 409 KUI-INVALID-STATE to.
        await expect(acknowledge).toHaveCount(0);
      }
    }
  });

  test("says which rules ran, and never reports nothing found for one that could not look", async ({
    page,
    api,
  }) => {
    const document = await feed(api);
    const rules = document.events?.data?.rules ?? [];

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    await expect(page.getByRole("heading", { name: "Alerts", level: 1 })).toBeVisible();

    if (rules.length === 0) {
      /*
       * No rule reports, so no panel at all — never an empty table under the heading "What KUI
       * checked", which would read as "KUI checked nothing" over a document that simply did not
       * carry the section.
       */
      await expect(page.getByTestId("alerts-rules")).toHaveCount(0);
      await expect(page.getByTestId("alert-rule")).toHaveCount(0);
      return;
    }

    /*
     * One row per rule, and the count is the API's. This is the assertion that would have caught a
     * feed drawn without its rule reports: an empty feed looks identical whether four rules ran and
     * found nothing or one of them has been refused by an ACL all afternoon.
     */
    await expect(page.getByTestId("alert-rule")).toHaveCount(rules.length);
    for (const rule of rules) {
      if (rule.rule === undefined) continue;
      await expect(page.getByTestId("alerts-rules")).toContainText(rule.rule);
    }
    // And no rendered zero in the panel whose whole argument is against them: a rule that skipped
    // nothing says nothing about skipping.
    await expect(page.getByTestId("alerts-rules")).not.toContainText("0 subjects were skipped");
  });

  test("a deployment with no alerts service draws no card rather than an empty one", async ({
    page,
    api,
  }) => {
    const section = (await feed(api)).events;
    /*
     * The one skip left in this file, and it is honest rather than convenient: `not_configured` is
     * decided by whether this deployment runs an alerts service, and there is no way to put a stack
     * that runs one into that state from outside it. The rendering is asserted in
     * `packages/feature-alerts/src/alerts.test.tsx` — "a deployment with no alerts service draws no
     * Alerts row at all rather than an empty one" — over the service's own `not_configured`
     * document. This case exists so that a deployment which *is* configured that way is covered
     * when somebody runs the suite against one.
     */
    test.skip(
      section?.status !== "not_configured",
      `this deployment answered ${section?.status ?? "nothing"}, so the hidden rendering is not ` +
        `the one on screen; the same rule is asserted in alerts.test.tsx over the service's own ` +
        `document`,
    );

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    // ADR-032: hidden, not empty. The heading is still there — a bookmark has to land somewhere —
    // and the card is not, because a card headed "Alerts & events" over a deployment that runs no
    // alerts service sends an operator hunting for an outage that does not exist.
    await expect(page.getByRole("heading", { name: "Alerts", level: 1 })).toBeVisible();
    await expect(page.getByTestId("alerts-feed")).toHaveCount(0);
  });
});
