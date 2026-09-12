/**
 * The brokers screen, in a browser, against a real broker.
 *
 * ## What this suite is for
 *
 * Every figure on this screen has a rule attached to it — a percentage that must not appear without
 * a capacity, a count that must not read as zero when nobody measured it, a request that must not
 * happen until a card is opened. The unit suite gates all three against a stub. This one gates them
 * against the server, because the failure this screen shipped with was not a wrong rule: it was a
 * screen that never received the number the rule was about.
 *
 * ## Why the expectations are computed from the API rather than written down
 *
 * A quickstart's under-replicated partition count is `null` until the first partition sweep lands
 * and `0` afterwards, and whether Kafka reports a log directory's filesystem size depends on the
 * broker's version. Both are *legitimate* answers, and the product has a different, correct
 * rendering for each. So this suite asks the gateway what the answer is and asserts that the screen
 * drew the rendering that answer calls for — which is a stronger assertion than either literal
 * would be, and the only one that does not go red for a reason that is nobody's fault.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** The `Section` envelope every aggregated answer arrives in (ADR-039). */
interface Section<T> {
  readonly status: string;
  readonly data?: T;
}

interface ClusterDocument {
  readonly cluster?: {
    readonly summary?: Section<{
      readonly underReplicatedPartitionCount?: number | null;
      readonly version?: string | null;
      readonly controllerKind?: string | null;
    }>;
  };
}

interface BrokersDocument {
  readonly brokers?: Section<
    readonly { readonly leaderSkewPercent?: number | null; readonly leaderCount?: number | null }[]
  >;
}

interface LogDirsDocument {
  readonly logDirs?: Section<readonly { readonly totalBytes?: number | null; readonly usableBytes?: number | null }[]>;
}

/** What the section holds, or `undefined` where it refused. */
function dataOf<T>(section: Section<T> | undefined): T | undefined {
  return section !== undefined && (section.status === "ok" || section.status === "stale")
    ? section.data
    : undefined;
}

test.describe("the brokers screen", () => {
  test("names the broker and its address, from the cluster rather than from a fixture", async ({
    page,
  }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);

    /*
     * Scoped to the page head, and that scope is the whole of what wave 6 recorded as a flake.
     * `BrokerList` draws its `PageHeader` and, while the broker list is still in flight, a loading
     * `Card` also titled "Brokers" — so an unscoped `getByRole("heading", { name: "Brokers" })`
     * resolves to two elements for as long as the answer takes, and Playwright's strict mode throws
     * on that immediately rather than polling until one of them goes. Whether this case sampled
     * that window depended on whether the brokers query beat the route's chunk, which is why it
     * passed 9/9 alone and failed in the full run, where `alerts.spec.ts` is the only file that
     * precedes it. A longer timeout cannot help: the violation is raised, not retried.
     */
    const head = page.locator('[data-testid="brokers-head"]');
    await expect(head.getByRole("heading", { name: "Brokers", exact: true })).toBeVisible();
    // The quickstart runs one broker, advertised as `kafka:9092`. By visible string, not by testid:
    // this asserts that a person can read the broker's address off the card.
    await expect(page.getByText("kafka:9092").first()).toBeVisible();
    await expect(page.locator("body")).not.toContainText("[object Object]");
    await expect(page.locator("body")).not.toContainText("undefined");
  });

  test("carries one heading named Brokers once the list has landed, and two while it has not", async ({
    page,
  }) => {
    /*
     * The case wave 6 recorded as a flake and nobody root-caused, written so that it cannot flake:
     * the answer is held open rather than raced against. While the brokers are in flight the screen
     * legitimately draws two headings named "Brokers" -- the `PageHeader` and the loading `Card` --
     * and once the answer lands the loading card goes, leaving one. The case above asserted the
     * screen's title with an unscoped role locator, which is only unambiguous in the second of
     * those two states, so which state the run sampled decided whether it passed. Assert both
     * states here, and the case above stays scoped to the head.
     */
    await page.route(`**/api/v1/clusters/${CLUSTER}/brokers`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 4_000));
      await route.continue();
    });
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);

    const titles = page.getByRole("heading", { name: "Brokers", exact: true });
    const head = page.locator('[data-testid="brokers-head"]');
    // In flight: the page head still names the screen exactly once, whatever else is on the page.
    await expect(head.getByRole("heading", { name: "Brokers", exact: true })).toHaveCount(1);
    await expect(page.locator('[data-testid="brokers-loading"]')).toBeVisible();
    await expect(titles).toHaveCount(2);

    // Landed: the loading card is gone and the screen names itself once.
    await expect(page.locator('[data-testid="brokers-loading"]')).toHaveCount(0, { timeout: 20_000 });
    await expect(titles).toHaveCount(1);
  });

  test("the four stat tiles carry figures or say why they do not", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const tiles = page.locator(".kui-brk-tiles");
    await expect(tiles).toBeVisible();
    for (const label of ["ACTIVE CONTROLLER", "TOTAL LEADERS", "DISK USED", "PARTITION SKEW"]) {
      await expect(tiles).toContainText(label);
    }
    /*
     * The rule the whole product rests on, asserted where it is cheapest to break: a tile whose
     * figure could not be read carries a chip saying why, on that tile. A dash on its own is the
     * defect — this is the reading M6's exit criterion asks for, per screen rather than by one
     * sweeping regex over the page.
     */
    const each = tiles.locator(".kui-tile");
    for (let index = 0; index < (await each.count()); index += 1) {
      const tile = each.nth(index);
      if ((await tile.locator(".kui-tile__absent").count()) > 0) {
        await expect(tile.locator(".kui-tile__chip")).toBeVisible();
      }
    }
  });

  test("the voice line reads the count the gateway actually reports", async ({ page, api }) => {
    /*
     * The assertion this file exists for. `underReplicatedPartitionCount` reaches the browser as a
     * number or as `null`, and the two have different sentences: the screen used to print the
     * cheerful one over both, on a prop the route never passed at all.
     */
    const document = (await api.get(`/api/v1/clusters/${CLUSTER}`)) as ClusterDocument;
    const summary = dataOf(document.cluster?.summary);
    const count = summary?.underReplicatedPartitionCount ?? null;

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const voice = page.locator('[data-testid="brokers-head"]');
    await expect(voice).toBeVisible();

    if (count === null) {
      await expect(voice).toContainText(/not claiming there are none/i);
      await expect(voice).not.toContainText(/Zero under-replicated/i);
    } else if (count === 0) {
      await expect(voice).toContainText(/Zero under-replicated partitions/i);
    } else {
      await expect(voice).toContainText(new RegExp(`${count} partitions? (is|are) under-replicated`));
    }
  });

  test("a disk with no capacity on the wire shows a sentence and no percentage", async ({
    page,
    api,
  }) => {
    // Kafka's admin protocol reports a log directory's filesystem size only on brokers new enough
    // to send it, so both branches below are real answers rather than one being a failure.
    const measured = await capacityIsReported(api);

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();

    if (measured) {
      await expect(card).toContainText(/\d+%/);
      await expect(card).not.toContainText(/No disk capacity was reported/i);
    } else {
      await expect(card).toContainText(/No disk capacity was reported/i);
      // Never a `0%` bar: an unmeasurable disk and an empty one mean opposite things.
      await expect(card).not.toContainText(/\d+%/);
    }
  });

  test("says nothing about the cluster, and draws no zero, while the brokers are in flight", async ({
    page,
  }) => {
    /*
     * The rendering this screen shipped with, in the browser that shipped it. The route has fed
     * `BrokerList` a `loading` prop since wave 3 and nothing read it, so a slow answer produced four
     * claims about a request that had not come back — the cluster is not answering, its last check
     * was N seconds ago, it leads 0 partitions, it reported no brokers — two of which contradict
     * each other, and a bare zero where this product's central rule demands a sentence.
     *
     * The delay is imposed rather than waited for: against a local stack the answer arrives in
     * milliseconds, so the state that was wrong for two waves is invisible unless it is held open.
     */
    await page.route(`**/api/v1/clusters/${CLUSTER}/brokers`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 4_000));
      await route.continue();
    });

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const head = page.locator('[data-testid="brokers-head"]');
    await expect(head).toContainText(/Reading this cluster's brokers/);
    await expect(head).not.toContainText(/is not answering/);
    await expect(head).not.toContainText(/Last successful check/);

    // Not the empty state, and not a figure anywhere in the tiles.
    await expect(page.locator("body")).not.toContainText("reported no brokers");
    await expect(page.locator('[data-testid="brokers-pending"]')).toBeVisible();
    await expect(page.locator(".kui-brk-tiles .kui-tile__value")).toHaveCount(0);
    await expect(page.locator(".kui-brk-tiles .kui-tile__absent")).toHaveCount(0);

    // And once it lands, the page is the ordinary one.
    await expect(page.locator(".kui-brkcard").first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('[data-testid="brokers-pending"]')).toHaveCount(0);
  });

  test("tags an expanded card with the version the cluster's own summary reports", async ({
    page,
    api,
  }) => {
    /*
     * `v4.3 · KRaft` is two fields from the cluster scrape, and both could be deleted with every
     * suite green — the tag has no source on the broker DTO, so a card drawing nothing looks
     * exactly like a cluster that did not report a version. Asking the gateway which of the two
     * this deployment is settles it.
     */
    const document = (await api.get(`/api/v1/clusters/${CLUSTER}`)) as ClusterDocument;
    const summary = dataOf(document.cluster?.summary);

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();
    await card.locator(".kui-brkcard__toggle").click();

    if (typeof summary?.version === "string") {
      const version = summary.version.startsWith("v") ? summary.version : `v${summary.version}`;
      await expect(card).toContainText(version);
      if (typeof summary.controllerKind === "string") {
        const kind = summary.controllerKind.toLowerCase() === "kraft" ? "KRaft" : summary.controllerKind;
        await expect(card).toContainText(`${version} · ${kind}`);
      }
    } else {
      // No tag at all where the scrape did not report one: "unknown version" on every card is a row
      // of noise, and the card already says what it does not know about its own figures.
      await expect(card).not.toContainText(/^v\d/);
    }
  });

  test("says a broker's share of the leaderships, or says it was not measured", async ({
    page,
    api,
  }) => {
    /*
     * `leaderSkewPercent` began arriving from the cluster service on 2026-09-06 and no screen drew
     * it, which made it a field no test could be wrong about. It is a *signed* deviation from an
     * even share, so the card says it in a sentence: `-25%` beside the word LEADERS reads as a
     * negative partition count. Both branches are legitimate answers — a cluster whose topic sweep
     * produced no census reports none — so the gateway decides which one this deployment gives.
     */
    const document = (await api.get(`/api/v1/clusters/${CLUSTER}/brokers`)) as BrokersDocument;
    const skew = (dataOf(document.brokers) ?? [])[0]?.leaderSkewPercent ?? null;

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();
    await card.locator(".kui-brkcard__toggle").click();

    const said = card.locator('[data-testid$="-skew"]');
    await expect(said).toBeVisible();
    if (skew === null) {
      await expect(said).toContainText(/did not report this broker's share of the leaderships/);
    } else if (Math.round(skew) === 0) {
      await expect(said).toContainText("Leads an even share");
    } else {
      await expect(said).toContainText(
        new RegExp(`Leads ${Math.abs(Math.round(skew))}% (more|fewer) partitions`),
      );
    }
  });

  test("TOTAL LEADERS is the cluster's own count, or a sentence saying it was not read", async ({
    page,
    api,
  }) => {
    /*
     * Closed by mutation, in the unit suite and here: deleting the `leaderPartitions === null` guard
     * from `totalLeaders` left all 147 cases in `feature-clusters` green, and the tile then adds an
     * unreadable broker in as `0` and prints a total that is quietly short.
     *
     * Both branches are legitimate answers — Kafka does not report a per-broker leader count on
     * every cluster, and a single-broker cluster is the usual place it is `null` — so the gateway
     * decides which one this deployment gives, and the assertion is that the screen drew the
     * rendering that answer calls for.
     */
    const document = (await api.get(`/api/v1/clusters/${CLUSTER}/brokers`)) as BrokersDocument;
    const brokers = dataOf(document.brokers) ?? [];
    expect(brokers.length, "the quickstart should report at least one broker").toBeGreaterThan(0);
    const counts = brokers.map((broker) => broker.leaderCount ?? null);
    const anyUnread = counts.some((count) => count === null);

    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const tile = page.locator(".kui-brk-tiles .kui-tile").filter({ hasText: "TOTAL LEADERS" });
    await expect(tile).toBeVisible();

    if (anyUnread) {
      // No figure at all rather than a sum with a zero in it, and a chip saying which silence this
      // is: these brokers answered `describeCluster`, so "no broker answered" would send an
      // operator looking for an outage that is not happening.
      await expect(tile.locator(".kui-tile__value")).toHaveCount(0);
      await expect(tile.locator(".kui-tile__absent")).toBeVisible();
      await expect(tile).toContainText("this cluster does not report leader counts");
    } else {
      const total = counts.reduce((sum: number, count) => sum + (count ?? 0), 0);
      await expect(tile.locator(".kui-tile__value")).toContainText(total.toLocaleString("en-US"));
      await expect(tile).not.toContainText("does not report leader counts");
    }

    // And the same rule one level down, on the card: a figure where the wire carried one, an em
    // dash with a reason where it did not — never a zero standing in for either.
    const card = page.locator(".kui-brkcard").first();
    const leaders = card.locator(".kui-brkcard__figure").filter({ hasText: "LEADERS" }).first();
    if (counts[0] === null) {
      await expect(leaders).toContainText("—");
      await expect(leaders.locator("[title]")).toHaveAttribute(
        "title",
        "The leader count could not be read",
      );
    } else {
      await expect(leaders).toContainText(Number(counts[0]).toLocaleString("en-US"));
    }
  });

  test("a broker's settings arrive when its card is opened, and not before", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    const card = page.locator(".kui-brkcard").first();
    await expect(card).toBeVisible();

    // Collapsed, the card answers "is this broker all right?" and has asked the gateway for none of
    // the two hundred settings that answer "why is it behaving like that?".
    await expect(card).not.toContainText("CONFIGURATION");

    const settings = page.waitForResponse(
      (response) => response.url().includes("/configs") && response.status() === 200,
    );
    await card.locator(".kui-brkcard__toggle").click();
    await settings;

    await expect(card).toContainText("CONFIGURATION");
    // Chips, not the "Fetching this broker's settings…" placeholder and not an empty block: an
    // empty configuration block reads as "this broker has no settings", which is never true.
    await expect(card.locator(".kui-config-chip").first()).toBeVisible();
  });

  /**
   * `M09` — the brokers screen of the **second** cluster, reached by changing cluster.
   *
   * The ten cases above cover this screen; every one of them drives `quickstart`. What none of them
   * could establish until this wave is that the screen is a screen *of a cluster* rather than a
   * screen of the deployment — the quickstart registered one cluster, so every reading on this page
   * had exactly one possible source and a route that ignored its `clusterId` altogether would have
   * drawn the same picture. `deployment/quickstart/kui-quickstart.yaml` now registers a second
   * profile (W9-01), and this asks the second one the same questions.
   *
   * The two clusters may point at one broker — a second *registered profile* is what the screens
   * need and it is the cheap shape — so the figures on the two screens can legitimately be
   * identical. That is why nothing here compares the two clusters' numbers to each other. What it
   * compares is **this** screen against **this** cluster's own endpoints: the voice line against
   * `/clusters/<other>`'s summary, the cards against `/clusters/<other>/brokers`. A route pinned to
   * the first cluster's document reddens on the identity assertions whatever the figures say.
   *
   * Fails rather than skips on a one-cluster deployment, for the reason `shell.spec.ts`'s `M08`
   * case does.
   */
  test("follows a cluster change into the second cluster's brokers screen", async ({
    page,
    api,
  }) => {
    const other = await secondCluster(api);
    expect(
      other,
      "this deployment registers one cluster, so there is no second brokers screen to draw and " +
        "M09's second-cluster clause cannot be proved. See deployment/quickstart/" +
        "kui-quickstart.yaml, kui.clusters",
    ).toBeDefined();
    if (other === undefined) return;

    /* Start on the first cluster's brokers screen, so the change is a change and not an arrival.
       The environment rail's tile is the shipped switch — see `shell.spec.ts`'s `M08` case for why
       it is a tile and not the dropdown the plan describes. */
    await page.goto(`/ui/clusters/${CLUSTER}/brokers`);
    await expect(page.locator(".kui-brkcard").first()).toBeVisible();

    await page.getByTestId(`env-tile-${other.id}`).click();

    /*
     * A switch made from a cluster-scoped page rewrites the address, and that is the behaviour
     * being pinned: leaving the reader on `/clusters/<first>/brokers` while the frame said
     * `<second>` would be the frame and the page describing two different clusters — which is the
     * exact failure `setRouteCluster(undefined)` in `App.tsx` exists to prevent, and which no
     * deployment in this repository could ever exhibit until there were two clusters to confuse.
     */
    await expect(page).toHaveURL((url) => url.pathname.startsWith(`/ui/clusters/${other.id}/`));

    /*
     * And now the screen itself, opened at the second cluster's own address rather than clicked to
     * through the cluster list. That is deliberate: the list's rows are a different screen with
     * their own cases, and routing this one through them would make a slow or changed list redden
     * a case about brokers. What the switch above establishes is that the change propagated; what
     * follows establishes that this screen is the second cluster's.
     */

    /*
     * The wire, which is the only thing on this page that differs between the two clusters.
     *
     * Everything below reads the screen, and reading the screen cannot decide this question on this
     * deployment: both registered profiles point at the same `kafka:9092`, so their summaries and
     * their broker lists are identical documents and every figure below agrees with whichever
     * cluster was asked. Measured in wave 9 (`docs/plan/verification/W9-03.md` F1) and reproduced
     * here before this assertion was written: pinning all three of this route's loaders to
     * `quickstart` while leaving their reactive keys alone left this file **11 passed**, with the
     * browser issuing `GET /api/v1/clusters/quickstart/brokers` on
     * `/ui/clusters/staging-eu-01/brokers`. The identity assertions this case's header claims
     * redden on that mutation are driven by the route parameter, not by the answer.
     *
     * **And waiting for the right request does not close it either**, which is why this is a
     * ledger and not a `waitForRequest`. Measured on this stack, with every `/api/` request on this
     * navigation printed: the frame asks for this cluster whatever the route does — the drawer's
     * tree reads `/clusters/<id>/brokers`, `/topics`, `/consumer-groups` and `/schemas/subjects`,
     * and the drawer foot's storage meter reads `/clusters/<id>/log-dirs` — so
     * `/api/v1/clusters/staging-eu-01/brokers` is on the wire under the mutation too, three times,
     * and a positive observation of it passes while the cards on screen are the first cluster's.
     * The three requests the mutation actually moves are the route's own, and what distinguishes
     * them from the frame's is not that they happened but **whose id they carry**.
     *
     * So: every cluster-scoped request this navigation makes must name this cluster. The buffer is
     * emptied on the main frame's own navigation request, so nothing the previous screen left in
     * flight is counted, and the assertion is read after the screen has finished drawing.
     */
    const foreign: string[] = [];
    page.on("request", (request) => {
      if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
        foreign.length = 0;
        return;
      }
      const path = new URL(request.url()).pathname;
      const named = /^\/api\/v1\/clusters\/([^/]+)(?:\/|$)/.exec(path);
      if (named !== null && named[1] !== other.id) foreign.push(path);
    });

    await page.goto(`/ui/clusters/${other.id}/brokers`);

    const document = (await api.get(`/api/v1/clusters/${other.id}`)) as ClusterDocument;
    const summary = dataOf(document.cluster?.summary);
    const count = summary?.underReplicatedPartitionCount ?? null;

    const voice = page.locator('[data-testid="brokers-head"]');
    await expect(voice).toBeVisible();
    if (count === null) {
      await expect(voice).toContainText(/not claiming there are none/i);
    } else if (count === 0) {
      await expect(voice).toContainText(/Zero under-replicated partitions/i);
    } else {
      await expect(voice).toContainText(
        new RegExp(`${count} partitions? (is|are) under-replicated`),
      );
    }

    /* One card per broker the *second* cluster's own list reports. */
    const brokers = (await api.get(`/api/v1/clusters/${other.id}/brokers`)) as BrokersDocument;
    const rows = dataOf(brokers.brokers) ?? [];
    expect(rows.length, `${other.id} reports no brokers, so there is no screen to draw`)
      .toBeGreaterThan(0);
    await expect(page.locator(".kui-brkcard")).toHaveCount(rows.length);

    /* The frame, which is the half `M09` is a capture of: §2.1's cluster block at the drawer head
       names the cluster whose brokers are on screen, and the rail's current tile agrees with it. */
    await expect(page.getByTestId("nav-drawer")).toContainText(other.name);
    await expect(page.getByTestId(`env-tile-${other.id}`)).toHaveAttribute("aria-current", "true");

    /*
     * And the ledger, read last, because by now the screen has drawn everything it is going to ask
     * for. On a clean stack this list is empty; under the mutation described above it holds
     * `/api/v1/clusters/quickstart/brokers`, `/api/v1/clusters/quickstart` and
     * `/api/v1/clusters/quickstart/log-dirs` — the route's three loaders, and nothing else moves.
     */
    expect(
      foreign,
      "this screen asked another cluster's endpoints, so the figures above are that cluster's " +
        "and agree only because both profiles point at one broker",
    ).toEqual([]);
  });
});

/**
 * A cluster this deployment registers that is not the one every other case here drives.
 *
 * Read off the gateway rather than written down, so this file learns the second cluster's id and
 * name from the deployment instead of from `deployment/quickstart/kui-quickstart.yaml` by hand —
 * the same rule `connect.spec.ts` follows for the Connect wire.
 */
async function secondCluster(api: KuiApi): Promise<{ id: string; name: string } | undefined> {
  const registered = (await api.get("/api/v1/clusters")) as {
    readonly clusters?: {
      readonly data?: readonly {
        readonly cluster?: { readonly id?: string; readonly name?: string };
      }[];
    };
  };
  return (registered.clusters?.data ?? [])
    .map((entry) => entry.cluster)
    .find(
      (one): one is { id: string; name: string } =>
        typeof one?.id === "string" && one.id !== CLUSTER && typeof one.name === "string",
    );
}

/** Whether this deployment's log directories carry a filesystem size to measure against. */
async function capacityIsReported(api: KuiApi): Promise<boolean> {
  const document = (await api.get(`/api/v1/clusters/${CLUSTER}/log-dirs`)) as LogDirsDocument;
  const dirs = dataOf(document.logDirs) ?? [];
  return dirs.some(
    (dir) =>
      typeof dir.totalBytes === "number" &&
      dir.totalBytes > 0 &&
      typeof dir.usableBytes === "number",
  );
}
