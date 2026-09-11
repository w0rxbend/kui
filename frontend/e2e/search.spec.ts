/**
 * The top bar's search field, against the gateway's own fold.
 *
 * The field has been in the frame since wave 1 and searched nothing: its `onInput` was
 * `() => undefined`, so every state under the box was reachable only in a story. What makes this
 * suite worth running rather than a component test is the half no component test can see — that
 * the request goes to an endpoint that exists, and that the answer a *real* deployment gives is
 * rendered correctly, including the part of it that is missing.
 *
 * ## `partial` is not a failure
 *
 * Both stacks now route all six services — the distributed one gained its `kui-schema` container
 * in wave 3, and before that it routed five and the schema service was not one of them. But the
 * schema service in that stack is configured with no cluster, so it answers `not_configured`
 * rather than subjects, and a stack whose registry is unreachable answers nothing at all. So
 * whether `subjects` comes back with rows still depends on the deployment, and the suite must pass
 * either way — which is why the assertions below are about the *rule* (a service that could not be
 * asked is named) and not a fixed list of headings.
 */
import { test, expect, CLUSTER } from "./fixtures";

/** Types into the box and waits for the debounced request to have been answered. */
async function search(page: import("@playwright/test").Page, query: string): Promise<void> {
  await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
  const input = page.getByRole("combobox", { name: /Search topics, groups, anything/i });
  await input.click();
  await input.fill(query);
}

test.describe("cross-entity search", () => {
  test("answers across topics, groups and subjects from one query", async ({ page, api }) => {
    /* Asked of the API first, so that a screen showing nothing can be told apart from a gateway
       answering nothing. A test that only looked at the screen would report the same failure for
       both, and they are opposite problems. */
    const answer = (await api.get("/api/v1/search?q=orders&limit=10")) as {
      results?: {
        topics?: readonly unknown[];
        groups?: readonly unknown[];
        subjects?: readonly unknown[];
      };
      partial?: readonly string[];
    };
    expect(answer.results).toBeDefined();
    expect(Array.isArray(answer.results?.topics)).toBe(true);

    await search(page, "orders");

    /* The quickstart seeds `orders.v1`, so a search for `orders` finds at least one topic and the
       overlay draws it under a heading. */
    await expect(page.getByRole("listbox", { name: /Search results/i })).toBeVisible();
    await expect(page.getByRole("option").first()).toBeVisible();
    await expect(page.getByTestId("search")).toContainText("orders");
  });

  test("a result leads to the object it names", async ({ page }) => {
    await search(page, "orders");
    const first = page.getByRole("option").first();
    await expect(first).toBeVisible();
    /* Built through the router's typed proxy like every other address in this product, so it
       carries the deployment's mount prefix and resolves rather than 404ing. */
    await expect(first).toHaveAttribute("href", /^\/ui\/clusters\//);
  });

  /**
   * The click, and the reason it needs a browser to prove.
   *
   * A pointer press on a result focuses the link, which blurs the input; the `click` only exists
   * once the button comes back up. Closing the overlay on the blur therefore takes the row out
   * from under the cursor between the two halves of one press, and every result in the panel
   * becomes unclickable while looking perfectly normal — no error, no console line, nothing on the
   * screen to point at. `SearchField.RESULT_CLICK_GRACE_MS` is what stops it, and only a real
   * browser sequences the press the way a person does.
   *
   * The case above asserts the row's address; this one asserts that the address is reachable by
   * clicking, which is a different question and the one an operator asks.
   */
  test("a click on a search result lands before the panel closes", async ({ page }) => {
    await search(page, "orders");
    const first = page.getByRole("option").first();
    await expect(first).toBeVisible();
    const href = await first.getAttribute("href");
    expect(href).toBeTruthy();

    await first.click();
    await expect(page).toHaveURL(new RegExp(`${href!.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`));
  });

  test("says which service could not be asked instead of returning fewer results silently", async ({
    page,
    api,
  }) => {
    const answer = (await api.get("/api/v1/search?q=orders&limit=10")) as {
      partial?: readonly string[];
    };
    const partial = answer.partial ?? [];

    await search(page, "orders");
    const overlay = page.getByTestId("search");

    if (partial.length === 0) {
      /* Everybody answered. There must then be no "not searched" line at all — a permanently
         present caveat is a caveat nobody reads, and it would make the case below meaningless. */
      await expect(overlay).not.toContainText(/Not searched/i);
      return;
    }

    /* Somebody did not. The line names it in words rather than by its service id, and says the
       difference that matters: results from it are missing, which is not the same as empty. An
       operator who searched for a subject on a stack with no registry has to be told that. */
    await expect(overlay).toContainText(/Not searched/i);
    await expect(overlay).toContainText(/missing, not empty/i);
  });

  /**
   * Three renderings of "no rows", and which one is honest depends on who was asked.
   *
   * This case asserted `Nothing matches` flatly until wave 9, and it passed for four waves because
   * the deployment registered **one** cluster and that cluster had a schema registry, so `partial`
   * was always empty. With a second cluster registered and no registry on it
   * (`deployment/quickstart/kui-quickstart.yaml`, W9-01), `/api/v1/search` — which is global rather
   * than cluster-scoped — answers `partial: ["schema"]` on **every** query, and the overlay stops
   * saying `Nothing matches`. That is `searchStatus` doing exactly what it was written to do:
   * "Nothing matches" over a search that never reached the registry is a false negative, and a
   * false negative in a search box is indistinguishable from an absence.
   *
   * So the branch is on the wire, like the case above it, and each arm asserts the *other* arm's
   * sentence is absent — a rendering that drew both would be telling the operator two different
   * things about one answer. What is common to both, and is what the case is named for, is that
   * neither arm calls it a failure: the operator's next action after "nothing matched" is to search
   * for something else, and after "not answering" it is to retry, and drawing the second over the
   * first sends them to look at a gateway that is working.
   */
  test("says so in words when nothing matches, and never calls it a failure", async ({
    page,
    api,
  }) => {
    const query = "zzz-nothing-matches-this-zzz";
    const answer = (await api.get(`/api/v1/search?q=${query}`)) as {
      partial?: readonly string[];
    };
    const partial = answer.partial ?? [];

    await search(page, query);
    const overlay = page.getByTestId("search");

    if (partial.length === 0) {
      await expect(overlay).toContainText(/Nothing matches/i);
      await expect(overlay).not.toContainText(/Not searched/i);
    } else {
      /*
       * Said out loud, because a green run over this arm is not coverage of the other one.
       *
       * `/api/v1/search` is global and this deployment registers a cluster with no schema registry,
       * so **every** query answers `partial: ["schema"]` and the arm above has never executed here
       * — measured on `?q=orders` and on this query. W9-03's verifier replaced the empty-state
       * sentence in the served bundle with a bare `0` and this file stayed 6 passed. The case below
       * is what pins that sentence; this annotation is what stops the reader of a green run from
       * believing this case did.
       */
      test.info().annotations.push({
        type: "arm not exercised",
        description:
          `this deployment answered partial: [${partial.join(", ")}], so the "everybody answered ` +
          `and nothing matched" arm did not run; it is driven by the case below`,
      });
      await expect(overlay).toContainText(/Not searched/i);
      await expect(overlay).toContainText(/missing, not empty/i);
      await expect(overlay).not.toContainText(/Nothing matches/i);
    }
    await expect(overlay).not.toContainText(/not answering/i);
  });

  /**
   * The empty state, reached the only way this deployment can reach it.
   *
   * The case above branches on the wire and the wire here has one value: `staging-eu-01` has no
   * schema registry, `/api/v1/search` is global rather than cluster-scoped, so every query — a
   * matching one and this deliberately unmatchable one alike — comes back `partial: ["schema"]`.
   * `searchStatus` is then right to refuse the empty rendering, because "Nothing matches" over a
   * search that never reached the registry is a false negative and a false negative in a search box
   * is indistinguishable from an absence. The consequence is that the *honest* empty state — the
   * one an operator sees on a healthy deployment when they mistype a topic name — is drawn by no
   * browser on this stack, and nothing here pinned it.
   *
   * So one field of the answer is replaced on the wire, and only one. The results are the gateway's
   * own: the query really is answered by the real search endpoint over the real cluster and really
   * does match nothing. What the intercept changes is `partial`, which is a fact about **which
   * services this deployment runs** and not a fact about the rendering under test — the same
   * deployment shape difference the case above has to branch on. Nothing else is faked, and the
   * case fails if the gateway stops answering or starts matching this query.
   *
   * This is a second case rather than a third arm of the first one because the two ask different
   * questions: the one above asks what this deployment draws, and this one asks what the empty
   * state is. Both are `searchStatus`'s contract and only one of them can be true at a time.
   */
  test("draws the empty state when everybody answered and nothing matched", async ({ page }) => {
    const query = "zzz-nothing-matches-this-zzz";
    let served = 0;

    /* A predicate rather than a glob: Playwright's URL globs treat `?` as a wildcard, and a pattern
       that matched more than this one path would rewrite answers this case is not about. */
    await page.route(
      (url) => url.pathname === "/api/v1/search",
      async (route) => {
        const response = await route.fetch();
        const body = (await response.json()) as {
          results?: {
            topics?: readonly unknown[];
            groups?: readonly unknown[];
            subjects?: readonly unknown[];
          };
        };
        const hits =
          (body.results?.topics?.length ?? 0) +
          (body.results?.groups?.length ?? 0) +
          (body.results?.subjects?.length ?? 0);
        expect(
          hits,
          `the gateway matched ${hits} rows for ${query}, so the empty state is not the honest ` +
            `rendering of this answer and this case would be asserting a sentence over results`,
        ).toBe(0);
        served += 1;
        await route.fulfill({ response, json: { ...body, partial: [] } });
      },
    );

    await search(page, query);
    const overlay = page.getByTestId("search");

    /* The sentence, in the field's own words and with the query quoted back — an empty panel and a
       bare `0` are the two renderings this replaces, and both of them read as a broken box. */
    await expect(overlay).toContainText(/Nothing matches/i);
    await expect(overlay).toContainText(query);

    /* And neither of the other two sentences: an answer that reached everybody has nothing to
       caveat, and nothing failed. Drawing either over this one tells the operator two different
       things about one answer. */
    await expect(overlay).not.toContainText(/Not searched/i);
    await expect(overlay).not.toContainText(/not answering/i);

    expect(
      served,
      "the search endpoint was never called through the browser, so the assertions above ran " +
        "against a panel this case did not put there",
    ).toBeGreaterThan(0);
  });

  test("bounds the query at the box, at the length the endpoint accepts", async ({ page, api }) => {
    /*
     * The wire half of the same rule. A 201-character `q` is a `KUI-VALIDATION` 400 naming the
     * field, and the overlay's only failure rendering says the search is not answering — a sentence
     * that sends somebody to look at a gateway that is working. So the box carries the endpoint's
     * own maximum as a `maxlength` and the case is unreachable from the screen; this asserts that
     * the maximum the box enforces is the one the server actually has.
     */
    /* The box's half, which this case claimed to assert and did not: `SEARCH_MAX_LENGTH` reaches
       the field through one line of `App.tsx`, and raising it left every unit case green. */
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    const input = page.getByRole("combobox", { name: /Search topics, groups, anything/i });
    await expect(input).toHaveAttribute("maxlength", "200");

    /* And the server's, from both sides of the boundary, so the two numbers are pinned to each
       other rather than each to itself. */
    const accepted = await api.raw.get(`/api/v1/search?q=${"a".repeat(200)}`);
    expect(accepted.status()).toBe(200);
    const response = await api.raw.get(`/api/v1/search?q=${"a".repeat(201)}`);
    expect(response.status()).toBe(400);
    const body = (await response.json()) as {
      code?: string;
      details?: readonly { field?: string }[];
    };
    expect(body.code).toBe("KUI-VALIDATION");
    expect(body.details?.[0]?.field).toBe("q");
  });
});
