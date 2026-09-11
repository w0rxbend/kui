/**
 * ksqlDB, in a browser, against a stack built from this tree.
 *
 * ## This spec reads the service's goldens off disk, and that is not decoration
 *
 * House rule 12 says one packet owns the DTO, the decoder and the golden between them, and that the
 * binding case decodes the encoder's own output. It has a hole in it the shape of `frontend/e2e/`,
 * and that hole is what kept M9 open for a whole wave: `connect.spec.ts` hand-wrote
 * `connectors.data.items` where the service renders
 * `connectors.data.workers[].connectors.data.items`, so its shape assertion **failed** against a
 * correctly routed server and its two positive cases skipped for ever, with every unit suite in
 * both languages green.
 *
 * So nothing here restates a field name. The expected shape comes out of
 * `services/ksql/contract/test/resources/golden/objects-response.json` — the document the service's
 * own encoder rendered — read at run time, and the live answer is compared against it key by key. A
 * spec written that way cannot be written against a wire that does not exist, which is the property
 * `connect.spec.ts` lacked.
 *
 * ## What can be asserted on the quickstart, and what cannot
 *
 * `deployment/quickstart/kui-quickstart.yaml` configures **no** `kui.clusters.<n>.ksql.url`, so a
 * quickstart has no ksqlDB server and the objects section answers `not_configured`. The distributed
 * stack (`deployment/compose/kui-service.yaml`) does configure one, at `http://ksqldb-server:8088`.
 *
 * House rule 6 forbids a suite made entirely of "this is absent" assertions, and this one is not:
 * two of its cases are about something being **present** on any stack —
 *
 *  1. the gateway **routes** the objects endpoint at all and answers a document in the ADR-034 /
 *     ADR-039 shape, which is the assertion that would have caught `services/alerts` shipping
 *     complete and unroutable with every unit suite green and the endpoint answering
 *     `404 KUI-ROUTE-NOT-FOUND`;
 *  2. the address `/ui/clusters/<id>/ksql` **resolves** and draws the feature's own name, so a
 *     bookmark lands somewhere.
 *
 * — and the third and fourth are the two halves of the configured/not-configured fork, each of
 * which runs on exactly one of the two stacks and says which one it is on rather than skipping
 * quietly. The positive half is proved on the compose stack; W8-05's report says so and names the
 * stack.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/**
 * Which cluster to drive.
 *
 * `fixtures.ts` names the quickstart's one cluster and is unowned this wave, so this spec takes an
 * override instead of editing it. It exists because the two stacks this repository ships differ on
 * exactly the fact this suite is about: the quickstart configures **no** ksqlDB, while
 * `deployment/compose/kui-service.yaml` configures `http://ksqldb-server:8088` on clusters whose
 * ids are not `quickstart`. Without the override the positive half of this wire could only ever be
 * run against a stack the suite cannot address, which is house rule 6's failure mode wearing a
 * skip.
 *
 *   KUI_E2E_API=http://localhost:18080 KUI_E2E_UI=http://localhost:18090 \
 *     KUI_E2E_CLUSTER=measured pnpm -C frontend e2e e2e/ksql.spec.ts
 */
const CLUSTER_ID = process.env["KUI_E2E_CLUSTER"] ?? CLUSTER;

/** The section statuses ADR-039 defines. A document outside this set is a contract break. */
const STATUSES = ["ok", "stale", "unavailable", "not_configured", "forbidden"];

/** The ksql contract module's committed documents, three directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "services",
  "ksql",
  "contract",
  "test",
  "resources",
  "golden",
);

/**
 * The shape the service's own encoder renders, read off disk.
 *
 * A throw and not a skip when the file is missing. A spec that quietly passed with no golden behind
 * it would be asserting a shape somebody typed into this file, which is the thing this whole header
 * is about.
 */
function golden(name: string): Record<string, unknown> {
  try {
    return JSON.parse(readFileSync(join(GOLDEN, name), "utf8")) as Record<string, unknown>;
  } catch (cause) {
    throw new Error(
      `${join(GOLDEN, name)} is not readable, so this spec has no rendered example of the ksqlDB ` +
        `wire to compare the live server against and would be asserting a shape typed into ` +
        `e2e/ksql.spec.ts. That is exactly what connect.spec.ts did. (${String(cause)})`,
    );
  }
}

function section(document: unknown, key: string): Record<string, unknown> | undefined {
  const root = document as Record<string, unknown> | null;
  const found = root === null ? undefined : root[key];
  return typeof found === "object" && found !== null
    ? (found as Record<string, unknown>)
    : undefined;
}

async function objects(api: KuiApi): Promise<unknown> {
  return api.get(`/api/v1/clusters/${CLUSTER_ID}/ksql/objects`);
}

test.describe("ksqlDB", () => {
  test("the gateway routes the objects endpoint and answers what the service renders", async ({
    api,
  }) => {
    const live = await objects(api);
    const expected = golden("objects-response.json");

    /*
     * The section key comes from the golden and not from a literal here. `services/alerts` reached
     * integration in wave 6 with 142 green cases, its own OpenAPI document, a container and an ADR
     * — and could not be routed, because the one line joining it to the gateway belonged to no
     * packet's Owns. A 404 here is what that looks like from a browser, and no unit suite in either
     * language can see it.
     */
    const key = Object.keys(expected)[0];
    expect(key, "the golden carries no section key").toBeDefined();
    const answered = section(live, key ?? "objects");
    expect(answered, `the response carried no '${key}' section`).toBeDefined();
    expect(STATUSES, `the section's status was ${String(answered?.["status"])}`).toContain(
      answered?.["status"],
    );

    if (answered?.["status"] === "ok" || answered?.["status"] === "stale") {
      /*
       * Every key the golden's payload has, present on the live one. Derived from the rendered
       * document rather than listed, so a field the service adds or renames moves this assertion
       * without anybody editing it — and a field this build reads that the server has stopped
       * sending fails it.
       */
      const shape = section(expected[key ?? "objects"], "data") ?? {};
      const data = answered["data"] as Record<string, unknown> | undefined;
      expect(data, "an ok section carried no data").toBeDefined();
      for (const field of Object.keys(shape)) {
        expect(data, `the live answer has no '${field}', which the golden renders`).toHaveProperty(
          field,
        );
      }
      // `items` is an array — the field name the browser reads — and every entry carries the two
      // fields every kind has. Never "an empty array because the field is spelled something else":
      // `wire.ts` refuses that document rather than answering an empty list.
      expect(Array.isArray(data?.["items"])).toBe(true);
      for (const item of (data?.["items"] ?? []) as readonly Record<string, unknown>[]) {
        expect(typeof item["name"]).toBe("string");
        expect(typeof item["kind"]).toBe("string");
      }
    } else {
      // Every other status carries a reason, because a section that refuses without one leaves the
      // screen with nothing to say beyond "no".
      expect(answered?.["reason"], "a non-ok section carried no reason code").toBeTruthy();
    }
  });

  test("the ksqlDB address resolves and names the feature, whatever is configured", async ({
    page,
  }) => {
    /*
     * A bookmark has to land somewhere. Where no server is configured ADR-032 hides the *navigation
     * row*, and the address is still a real address — a 404 for a page that exists is the failure
     * this asserts against, and it is the one a hidden nav row makes easy to ship.
     */
    await page.goto(`/ui/clusters/${CLUSTER_ID}/ksql`);
    await expect(page.getByText("ksqlDB").first()).toBeVisible();
    await expect(page.getByText("Sorry, that page does not exist")).toHaveCount(0);
  });

  test("a deployment with no ksqlDB says which key to set, and offers nothing to retry", async ({
    page,
    api,
  }) => {
    const answered = section(await objects(api), "objects");
    test.skip(
      answered?.["status"] !== "not_configured",
      `this deployment's ksqlDB section answered ` +
        `${String(answered?.["status"] ?? "nothing")}, so ` +
        "the not-configured rendering is not the one on screen. The quickstart configures no " +
        "kui.clusters.<n>.ksql.url and the compose stack configures one, so exactly one of this " +
        "case and the next runs on any given stack.",
    );

    await page.goto(`/ui/clusters/${CLUSTER_ID}/ksql`);

    const body = page.locator("body");
    // Nothing is broken, so nothing is red and there is nothing to try again. The sentence
    // names the configuration key, because the next action is editing it.
    await expect(body).toContainText("kui.clusters.<n>.ksql.url");
    await expect(page.getByRole("button", { name: "Retry" })).toHaveCount(0);
    // And no editor over a server that does not exist: a Run control that can only fail is worse
    // than no control.
    await expect(page.locator("#kui-ksql-editor")).toHaveCount(0);
    // Never a count of nothing, and never a bare dash where a sentence belongs.
    await expect(body).not.toContainText("0 streams");
  });

  test("every stream and table the server named is a row, with its kind beside it", async ({
    page,
    api,
  }) => {
    const answered = section(await objects(api), "objects");
    const data = answered?.["data"] as Record<string, unknown> | undefined;
    const items = ((data?.["items"] ?? []) as readonly Record<string, unknown>[]).filter(
      (item) => item["kind"] === "stream" || item["kind"] === "table",
    );
    test.skip(
      items.length === 0,
      "this deployment's ksqlDB named no stream or table, so there are no rows to compare. The " +
        "compose stack (deployment/compose/kui-service.yaml) configures ksqldb-server:8088; run " +
        "this spec against that stack, or add a ksql block to " +
        "deployment/quickstart/kui-quickstart.yaml and restart kui-quickstart-kui.",
    );

    await page.goto(`/ui/clusters/${CLUSTER_ID}/ksql`);

    /*
     * One row per object, counted from the API's own answer. A loop over an empty array asserts
     * nothing, which is why the skip above is on the count rather than on a status: wave 5's
     * `traffic.spec.ts` iterated an empty array, skipped its only assertion and reported green over
     * two cards that drew nothing.
     */
    const rows = page.locator(".kui-ksql__object");
    await expect(rows).toHaveCount(items.length);

    for (const item of items) {
      const row = rows.filter({ hasText: String(item["name"]) }).first();
      await expect(row, `no row for ${String(item["name"])}`).toBeVisible();
      // A stream and a table mean different things — an unbounded log against the current value per
      // key — so the word is on the row and not only a glyph.
      await expect(row).toContainText(String(item["kind"]));
    }
  });
});
