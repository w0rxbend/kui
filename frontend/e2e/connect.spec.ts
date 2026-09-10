/**
 * Kafka Connect, in a browser, against a stack built from this tree.
 *
 * ## What this suite can assert on the quickstart, and what it cannot
 *
 * `deployment/quickstart/kui-quickstart.yaml` configures **no** `kui.clusters.<n>.connect` entry,
 * so a quickstart has no Connect worker and the connectors section answers `not_configured`. That
 * is the ordinary case for most deployments and it is worth asserting — but a suite made only of
 * "this is absent" assertions proves that a screen can refuse and nothing about whether it works,
 * which house rule 6 forbids and which is how `feature-alerts`' spec came to skip three of its four
 * cases.
 *
 * So every expectation below is **derived from the API's own answer in the same test**, and the
 * suite has three assertions that are about something being present rather than absent:
 *
 *  1. the gateway **routes** the connectors endpoint at all and answers a document with a
 *     `connectors` section in the ADR-034/ADR-039 shape — which is the assertion that would have
 *     caught `services/alerts` shipping complete and unroutable, where every unit suite was green
 *     and the endpoint answered `404 KUI-ROUTE-NOT-FOUND`;
 *  2. the address `/ui/clusters/<id>/connect` **resolves** and draws the feature's own name, so a
 *     bookmark lands somewhere;
 *  3. where a worker *is* configured, the browser draws one card per connector the API named, and
 *     each card's state pill carries the state the API reported.
 *
 * The third is the working half. On a quickstart it does not run, and the test says so with the
 * reason rather than passing quietly. To run it, add a Connect worker to
 * `deployment/quickstart/kui-quickstart.yaml` under `kui.clusters.0.connect` and restart
 * `kui-quickstart-kui`; the branch then fires against whatever that worker is running.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The list, as the gateway serves it. The shape is `services/connect`' connectors response. */
interface ConnectorsDocument {
  readonly connectors?: {
    readonly status?: string;
    readonly reason?: { readonly code?: string; readonly message?: string };
    readonly data?: {
      readonly items?: readonly {
        readonly connect?: string;
        readonly name?: string;
        readonly state?: string;
      }[];
      readonly workers?: readonly { readonly name?: string; readonly status?: string }[];
    };
  };
}

/** The section statuses ADR-039 defines. A document outside this set is a contract break. */
const STATUSES = ["ok", "stale", "unavailable", "not_configured", "forbidden"];

async function connectors(api: KuiApi): Promise<ConnectorsDocument> {
  return (await api.get(`/api/v1/clusters/${CLUSTER}/connect/connectors`)) as ConnectorsDocument;
}

test.describe("Kafka Connect", () => {
  test("the gateway routes the connectors endpoint and answers a document this build reads", async ({
    api,
  }) => {
    const document = await connectors(api);
    const section = document.connectors;

    /*
     * The section key first, and it is not a formality. `services/alerts` reached integration in
     * wave 6 with 142 green cases, its own OpenAPI document, a container and an ADR — and could not
     * be routed, because the one line joining it to the gateway belonged to no packet. A `404` here
     * is what that looks like from a browser, and no unit suite in either language can see it.
     */
    expect(section, "the response carried no 'connectors' section").toBeDefined();
    expect(STATUSES, `the section's status was ${section?.status}`).toContain(section?.status);

    if (section?.status === "ok" || section?.status === "stale") {
      // `items` is an array — the field name the browser reads — and every entry has a name. Never
      // "an empty array because the field is spelled something else": `wire.ts` refuses that
      // document rather than answering an empty list, and this is the same question asked of the
      // real server.
      expect(Array.isArray(section.data?.items)).toBe(true);
      for (const item of section.data?.items ?? []) {
        expect(typeof item.name).toBe("string");
      }
    } else {
      // Every other status carries a reason, because a section that refuses without one leaves the
      // screen with nothing to say beyond "no".
      expect(section?.reason?.code, "a non-ok section carried no reason code").toBeTruthy();
    }
  });

  test("the Connect address resolves and names the feature, whatever is configured", async ({
    page,
  }) => {
    /*
     * A bookmark has to land somewhere. Where no worker is configured ADR-032 hides the *navigation
     * row*, and the address is still a real address — a 404 for a page that exists is the failure
     * this asserts against, and it is the one a hidden nav row makes easy to ship.
     */
    await page.goto(`/ui/clusters/${CLUSTER}/connect`);
    await expect(page.getByText("Kafka Connect").first()).toBeVisible();
    await expect(page.getByText("Sorry, that page does not exist")).toHaveCount(0);
  });

  test("a deployment with no Connect worker says so, and offers nothing to retry", async ({
    page,
    api,
  }) => {
    const section = (await connectors(api)).connectors;
    test.skip(
      section?.status !== "not_configured",
      `this deployment's connectors section answered ${section?.status ?? "nothing"}, so the ` +
        "not-configured rendering is not the one on screen",
    );

    await page.goto(`/ui/clusters/${CLUSTER}/connect`);

    // Nothing is broken, so nothing is red and there is nothing to try again. The sentence names
    // the configuration key, because the next action is editing it.
    const body = page.locator("body");
    await expect(body).toContainText("Connect");
    await expect(page.getByRole("button", { name: "Retry" })).toHaveCount(0);
    await expect(body).not.toContainText("0 connectors");
    await expect(body).not.toContainText("—");
  });

  test("every connector the API named is a card, in the state the API reported", async ({
    page,
    api,
  }) => {
    const section = (await connectors(api)).connectors;
    const items = section?.data?.items ?? [];
    test.skip(
      items.length === 0,
      "this deployment has no Connect worker with connectors on it, so there are no cards to " +
        "compare. Configure kui.clusters.0.connect in deployment/quickstart/kui-quickstart.yaml " +
        "and restart kui-quickstart-kui to run this branch.",
    );

    await page.goto(`/ui/clusters/${CLUSTER}/connect`);

    /*
     * One card per connector, counted from the API's own answer. A loop over an empty array asserts
     * nothing, which is why the skip above is unconditional on the count rather than on a status:
     * wave 5's `traffic.spec.ts` iterated an empty array, skipped its only assertion and reported
     * green over two cards that drew nothing.
     */
    await expect(page.getByTestId("connector")).toHaveCount(items.length);

    for (const item of items) {
      const subject = `${item.connect ?? ""}/${item.name ?? ""}`;
      const card = page.locator(`[data-testid="connector"][data-connector="${subject}"]`);
      await expect(card, `no card for ${subject}`).toHaveCount(1);
      if (item.state !== undefined) {
        // The state the worker reported, in this build's own words for it — never a word KUI chose
        // for a state it did not recognise.
        await expect(card).toContainText(wordFor(item.state));
      }
    }
  });
});

/** The pill's wording for a state, mirroring `@kui/kernel`'s `connectorChip`. */
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
