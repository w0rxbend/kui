/**
 * The alerts screen, in a browser, against a stack built from this tree.
 *
 * ## What this suite asserts and what it deliberately cannot
 *
 * M8's exit criterion asks for a seeded event reaching the screen. Nothing in this product seeds
 * one: an alert event is opened by a rule reading a fact about the cluster — an offline partition,
 * a stuck rebalance, a log directory past its threshold — and there is no endpoint that writes one,
 * which is the design (`W6-01`: *"do not seed an event to make a screen look alive"*). A healthy
 * quickstart therefore has an empty feed, and a case that demanded rows would be a case that fails
 * on a working cluster.
 *
 * So every expectation below is **derived from the API's own answer in the same test**. The
 * browser's card is compared against the document the gateway served a moment earlier, which is the
 * one comparison that catches the failure this wave exists to end: wave 5's `traffic.spec.ts` read
 * `data.readings ?? []` from a document that spells it something else, iterated an empty array,
 * skipped its only assertion and reported green over two cards that drew nothing. A loop over an
 * empty array asserts nothing, so each case below either asserts against a non-empty set or asserts
 * the *sentence* an empty one earns.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The feed, as the gateway serves it. The shape is `services/alerts`' `AlertFeedResponse`. */
interface FeedDocument {
  readonly events?: {
    readonly status?: string;
    readonly reason?: string;
    readonly message?: string;
    readonly data?: {
      readonly items?: readonly { readonly id?: string; readonly title?: string }[];
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
    test.skip(
      section?.status !== "ok" && section?.status !== "stale",
      `the alerts service answered ${section?.status ?? "nothing"}; there is no count to compare`,
    );

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    await expect(page.getByRole("heading", { name: "Alerts", level: 1 })).toBeVisible();

    const card = page.getByTestId("alerts-feed");
    await expect(card).toBeVisible();

    const open = section?.data?.openCount;
    const rows = section?.data?.items ?? [];
    const pill = page.getByTestId("alerts-open-count");

    if (section?.data?.evaluatedAt === undefined) {
      /* A cluster the rules have never run on. The pill says so rather than drawing a zero, which
         is the whole reason `evaluatedAt` is on the wire. */
      await expect(pill).toContainText("Not evaluated yet");
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

  test("says which rules ran, and never reports nothing found for one that could not look", async ({
    page,
    api,
  }) => {
    const document = await feed(api);
    const rules = document.events?.data?.rules ?? [];
    test.skip(rules.length === 0, "the feed carried no rule reports to compare against");

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);

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
  });

  test("a deployment with no alerts service draws no card rather than an empty one", async ({
    page,
    api,
  }) => {
    const section = (await feed(api)).events;
    test.skip(
      section?.status !== "not_configured",
      "this deployment runs an alerts service, so the hidden rendering is not the one on screen",
    );

    await page.goto(`/ui/clusters/${CLUSTER}/alerts`);
    // ADR-032: hidden, not empty. The heading is still there — a bookmark has to land somewhere —
    // and the card is not, because a card headed "Alerts & events" over a deployment that runs no
    // alerts service sends an operator hunting for an outage that does not exist.
    await expect(page.getByTestId("alerts-feed")).toHaveCount(0);
  });
});
