/**
 * Kafka Connect, in a browser, against a stack built from this tree.
 *
 * ## Why this file was rewritten, and what it had been asserting
 *
 * The first version of this suite hand-wrote the wire it was about to check:
 *
 *     interface ConnectorsDocument { connectors?: { data?: { items?: […] } } }
 *
 * `services/connect` renders `connectors.data.workers[].connectors.data.items` — a `Section` per
 * configured Connect cluster, because one worker being down costs one row rather than the screen.
 * The outer key matched, so the spec compiled, ran, and asserted against a shape the product has
 * never produced: `Array.isArray(section.data?.items)` **failed** against a correctly routed
 * server, and the two positive cases guarded themselves with `test.skip(items.length === 0, …)`,
 * which was `0` on every deployment that will ever exist. Both skip messages blamed
 * `deployment/quickstart/kui-quickstart.yaml` for not configuring a worker, which was a false
 * explanation for a spec that could not have seen one.
 *
 * The package under it had been corrected away from exactly that shape mid-wave, by
 * `wire.golden.test.ts`, which decodes the service's own rendered documents off disk. That suite
 * does not cover `e2e/`, and this file is the hole it left: **house rule 12 has an `e2e/`-shaped
 * hole in it**, and one spec file is what kept M9 open with the service built, routed, imaged and
 * answering real documents.
 *
 * ## So the shape is read off disk, from the service's own encoder
 *
 * `services/connect/contract/test/resources/golden/connectors-response.json` is rendered by
 * `ConnectorsDto`'s own encoder and committed. The readers below — `workersIn`, `connectorsIn` —
 * are applied to **that file first**, in the first test, and only then to the live server. A reader
 * aimed at the wrong path finds nothing in the golden and the case fails before it ever reaches the
 * gateway; a live server that disagrees with its own golden fails a line later. Neither failure can
 * be mistaken for "this deployment has nothing deployed", which is the failure this file shipped.
 *
 * ## Every case here runs, and none of them skips
 *
 * House rule 6. That costs something and it is worth saying: this suite now **requires** a Connect
 * worker with at least one connector on it, and says so by failing rather than by skipping when
 * there is not one. `deployment/quickstart/kui-quickstart.yaml` configures the worker and the
 * quickstart deploys a connector onto it (W8-10); a stack without either is a stack on which M9's
 * browser evidence does not exist, and a green run over three skips is how that went unnoticed for
 * two waves.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The connect contract module's committed document, three directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "services",
  "connect",
  "contract",
  "test",
  "resources",
  "golden",
  "connectors-response.json",
);

/** The section statuses ADR-039 defines. A document outside this set is a contract break. */
const STATUSES = ["ok", "stale", "unavailable", "not_configured", "forbidden"];

type Json = Record<string, unknown>;

function asRecord(value: unknown): Json | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Json)
    : undefined;
}

function asArray(value: unknown): readonly unknown[] {
  return Array.isArray(value) ? (value as readonly unknown[]) : [];
}

/** The document's outer `connectors` section. */
function sectionIn(document: unknown): Json | undefined {
  return asRecord(asRecord(document)?.["connectors"]);
}

/** One Connect cluster's row per configured worker: `connectors.data.workers[]`. */
function workersIn(document: unknown): readonly Json[] {
  const data = asRecord(sectionIn(document)?.["data"]);
  return asArray(data?.["workers"])
    .map(asRecord)
    .filter((worker): worker is Json => worker !== undefined);
}

/** Every connector, flattened: `connectors.data.workers[].connectors.data.items[]`. */
function connectorsIn(document: unknown): readonly Json[] {
  return workersIn(document).flatMap((worker) => {
    const data = asRecord(asRecord(worker["connectors"])?.["data"]);
    return asArray(data?.["items"])
      .map(asRecord)
      .filter((item): item is Json => item !== undefined);
  });
}

function goldenDocument(): unknown {
  return JSON.parse(readFileSync(GOLDEN, "utf8")) as unknown;
}

async function connectors(api: KuiApi): Promise<unknown> {
  return await api.get(`/api/v1/clusters/${CLUSTER}/connect/connectors`);
}

/** How a connector is addressed on screen and in a permission question: cluster and name. */
function subjectOf(connector: Json): string {
  return `${String(connector["connect"] ?? "")}/${String(connector["name"] ?? "")}`;
}

test.describe("Kafka Connect", () => {
  test("the gateway answers a document shaped like the service's own golden", async ({ api }) => {
    /*
     * The readers first, against the committed document. This is the half that makes the rest of
     * the file honest: if `connectorsIn` walked `data.items` — the shape this spec used to assert —
     * it would find nothing here, and the case would fail on a file in this repository rather than
     * blaming a deployment.
     */
    const golden = goldenDocument();
    expect(workersIn(golden).length, `${GOLDEN} names no Connect clusters`).toBeGreaterThan(0);
    expect(connectorsIn(golden).length, `${GOLDEN} names no connectors`).toBeGreaterThan(0);
    expect(
      asRecord(sectionIn(golden)?.["data"])?.["items"],
      "the service's own document carries no flat 'items' list and never has",
    ).toBeUndefined();

    /*
     * And now the server. The section key first, and it is not a formality: `services/alerts`
     * reached integration in wave 6 with 142 green cases, its own OpenAPI document, a container and
     * an ADR — and could not be routed, because the one line joining it to the gateway belonged to
     * no packet. A `404` here is what that looks like from a browser, and no unit suite in either
     * language can see it.
     */
    const document = await connectors(api);
    const section = sectionIn(document);
    expect(section, "the response carried no 'connectors' section").toBeDefined();
    expect(STATUSES, `the section's status was ${String(section?.["status"])}`).toContain(
      section?.["status"],
    );
    expect(
      section?.["status"],
      "this deployment's connectors section did not answer with a list, so none of the browser " +
        "evidence M9 needs can be gathered from it. kui.clusters.0.connect must name a worker " +
        "that is running — see deployment/quickstart/kui-quickstart.yaml",
    ).toBe("ok");

    // Every configured worker carries its own name and its own section, which is the shape of the
    // whole response: the browser turns each into its own row and one down worker costs one row.
    const workers = workersIn(document);
    expect(workers.length, "the section named no Connect clusters").toBeGreaterThan(0);
    for (const worker of workers) {
      expect(typeof worker["connect"]).toBe("string");
      const inner = asRecord(worker["connectors"]);
      expect(STATUSES, `worker ${String(worker["connect"])} answered with an unknown status`)
        .toContain(inner?.["status"]);
    }

    // The field set, taken from the golden rather than written down here a second time. A rename
    // on either side — a service that stops sending `runningTasks`, a golden regenerated from a
    // widened DTO — is a disagreement this catches, and it is the one `wire.golden.test.ts` cannot
    // see because it reads committed files on both sides.
    const expected = Object.keys(connectorsIn(golden)[0] ?? {});
    expect(expected.length).toBeGreaterThan(0);
    for (const connector of connectorsIn(document)) {
      expect(
        Object.keys(connector),
        `${subjectOf(connector)} is missing fields the service's golden carries`,
      ).toEqual(expect.arrayContaining(expected));
    }
  });

  test("the Connect address resolves and names the feature", async ({ page }) => {
    /*
     * A bookmark has to land somewhere. Where no worker is configured ADR-032 hides the
     * *navigation row*, and the address is still a real address — a 404 for a page that exists is
     * the failure this asserts against, and it is the one a hidden nav row makes easy to ship.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/connect`);
    await expect(page.getByText("Kafka Connect").first()).toBeVisible();
    await expect(page.getByText("Sorry, that page does not exist")).toHaveCount(0);
  });

  test("every connector the API named is a card, in the state the API reported", async ({
    page,
    api,
  }) => {
    const document = await connectors(api);
    const items = connectorsIn(document);
    expect(
      items.length,
      "no Connect worker on this stack is running a connector, so there are no cards to compare " +
        "and this suite proves nothing about the working half of the screen. The quickstart " +
        "deploys one onto its Connect worker (W8-10); a stack without one cannot close M9.",
    ).toBeGreaterThan(0);

    await page.goto(`/ui/clusters/${CLUSTER}/connect`);

    /*
     * One card per connector, counted from the API's own answer rather than from a number written
     * here. Wave 5's `traffic.spec.ts` iterated an empty array, skipped its only assertion and
     * reported green over two cards that drew nothing; the count above is what stops that.
     */
    await expect(page.getByTestId("connector")).toHaveCount(items.length);

    for (const item of items) {
      const subject = subjectOf(item);
      const card = page.locator(`[data-testid="connector"][data-connector="${subject}"]`);
      await expect(card, `no card for ${subject}`).toHaveCount(1);
      // The state the worker reported, in this build's own words for it — never a word KUI chose
      // for a state it did not recognise.
      await expect(card).toContainText(wordFor(String(item["state"] ?? "")));
    }

    // And the page's own voice line counts the same rows. It is the only figure on this screen the
    // browser computes, and §4.14 draws it as `4 connectors · 1 failed and sulking`.
    const noun = items.length === 1 ? "1 connector" : `${items.length} connectors`;
    await expect(page.getByTestId("connect-header")).toContainText(noun);
  });

  test("each card carries the service's task figures and no throughput at all", async ({
    page,
    api,
  }) => {
    const items = connectorsIn(await connectors(api));
    expect(items.length, "no connector on this stack to read task figures from").toBeGreaterThan(0);

    await page.goto(`/ui/clusters/${CLUSTER}/connect`);

    for (const item of items) {
      const subject = subjectOf(item);
      const card = page.locator(`[data-testid="connector"][data-connector="${subject}"]`);
      /*
       * `runningTasks` and `taskCount` are computed once by `services/connect` and travel, so that
       * the card, a drawer row and the voice line show one number rather than three derivations of
       * it — `RESTARTING` is not running and that rule lives in the domain. This asserts the
       * service's own two figures reached the screen unchanged.
       */
      const sentence = `${String(item["runningTasks"])} of ${String(item["taskCount"])} tasks`;
      await expect(card.getByTestId("connector-tasks"), subject).toContainText(sentence);
    }

    /*
     * The product's central promise, in a real browser: the Connect REST API publishes no
     * per-connector record rate (ADR-054 §6), so the card says so in words. A `0 msg/s` here would
     * be a measured zero on a paused connector and a fabrication on every other one.
     */
    await expect(page.getByTestId("connect-list")).toContainText("throughput not measured");
    await expect(page.getByTestId("connect-list")).not.toContainText("msg/s");
  });
});

/**
 * The pill's wording for a state, mirroring `@kui/kernel`'s `connectorChip`.
 *
 * Written out rather than imported: `e2e/` has no project reference to the packages — Playwright
 * compiles these files on their own — so the kernel's own mapping is not reachable from here. It is
 * five words and the suite that owns them is `packages/kernel`'s; what this file is responsible for
 * is the *wire*, which it reads off disk instead of transcribing.
 */
function wordFor(state: string): string {
  switch (state.toUpperCase()) {
    case "RUNNING":
      return "running";
    case "FAILED":
      return "failed";
    case "PAUSED":
      return "paused";
    case "UNASSIGNED":
      return "unassigned";
    default:
      return "state not reported";
  }
}
