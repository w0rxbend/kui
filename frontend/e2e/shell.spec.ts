/**
 * The frame: that it loads at all, that a deep link works, and that the two things a bug report
 * needs are on screen.
 *
 * This is the suite's smoke test. If it fails, nothing else in the run means anything.
 */
import type { Page } from "@playwright/test";

import { CLUSTER, test, expect, type KuiApi } from "./fixtures";

test.describe("the shell", () => {
  /**
   * The smoke test, and it changed this wave for a reason worth writing down.
   *
   * It used to be `getByText("Quickstart")`, which worked because the deployment registered exactly
   * **one** cluster: `soleClusterChoice` selects a sole cluster on arrival, so the drawer head drew
   * its name and the string was on the page. With a second cluster registered
   * (`deployment/quickstart/kui-quickstart.yaml`, W9-01) nothing is auto-selected — deliberately,
   * because choosing for the operator is how somebody ends up acting on the wrong cluster — and
   * `/ui/` draws "no cluster" at the head. The old assertion was therefore measuring *how many
   * clusters the deployment happened to have* and calling it "the gateway answered".
   *
   * So the assertion is the roster: **every cluster the gateway names has a control in the frame**,
   * counted from `/api/v1/clusters` rather than written here. That is the same fact the old line
   * was reaching for — the browser's request went through nginx to the gateway and came back — and
   * it is true on a deployment with one cluster, with two, or with ten.
   *
   * By role and by accessible name throughout, not by `data-testid`: a test selecting on a testid
   * asserts that a developer wrote an attribute, and the rail's tiles show a single letter, so the
   * accessible name is what a person on a keyboard or a screen reader actually has to find.
   */
  test("loads, and reaches the gateway through its own proxy", async ({ page, api }) => {
    const registered = await clusters(api);
    expect(registered.length, "the gateway named no clusters at all").toBeGreaterThan(0);

    await page.goto("/ui/");

    await expect(page.getByRole("navigation").first()).toBeVisible();
    const rail = page.getByRole("list", { name: "Environments" });
    await expect(rail.getByRole("button")).toHaveCount(registered.length);
    for (const entry of registered) {
      await expect(
        rail.getByRole("button", { name: new RegExp(quoted(entry.name)) }),
        `${entry.id} is registered and the frame offers no way to reach it`,
      ).toHaveCount(1);
    }

    /*
     * And the page under the frame says something.
     *
     * This is the half the rewrite above lost. The rail is chrome: it is drawn from the capability
     * roster, which arrives before any cluster is chosen, so every assertion above is green over a
     * body that is drawing nothing at all — which is exactly what shipped. Measured on this stack
     * before the repair: six stat tiles carrying a label apiece, no figure, no sentence, held after
     * `networkidle` plus fifteen seconds, under the voice line *"Asking the cluster how it is."*
     * while the only requests in flight were `/auth/me`, `/auth/settings` and
     * `/capabilities/stream`. Nothing was being asked.
     *
     * The two assertions below are deliberately about *whether the screen speaks* rather than about
     * what it says, because what it says differs legitimately between deployments: with one cluster
     * registered `soleClusterChoice` selects it and the tiles carry figures; with two, nothing is
     * selected and each tile says so. Both are honest. A label alone is not, and neither is a claim
     * to be asking when nothing has been asked.
     */
    await page.waitForLoadState("networkidle");
    await eachTileSaysSomething(page);
    await expect(
      page.locator('[data-testid="overview-header"] .kui-page-head__voice'),
      "the voice line claims a request is in flight on a page that has made none",
    ).not.toHaveText(/^Asking the cluster/);
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

  /**
   * The bell, against the alerts service's own answer — the frame's half of M8's exit criterion.
   *
   * **The expectation is read off the API rather than written down here**, and that is the whole
   * design of this case. A literal would pass on a fixture and say nothing about the wire; the two
   * halves of wave 5's producers wire were each unit-tested against their own hand-written shape,
   * both green, while a real broker drew an empty card. So this asks `…/alerts/events` for
   * `openCount`, then insists the bell's accessible name carries that number and no other.
   *
   * All three of the section's honest answers are covered, because a deployment that has not
   * configured `services/alerts` is as real a state as one that has, and the bell has a different
   * true sentence for each. What no branch permits is the failure this is written against: a bell
   * saying "no open alerts" over a service that answered a positive count, or over one that
   * answered nothing at all. `@kui/feature-alerts`' own spec asserts the card's pill against the
   * same figure, which is what makes the bell and the card provably one number.
   */
  test("the bell carries the open count the alerts service answered, never one of its own", async ({
    page,
    api,
  }) => {
    const body = (await api.get(`/api/v1/clusters/${CLUSTER}/alerts/events`)) as {
      events?: { status?: string; data?: { openCount?: number | null } };
    };
    const section = body.events;
    expect(section?.status, "the alerts read answered no section at all").toBeDefined();

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    const bell = page.getByTestId("notifications");
    await expect(bell).toBeVisible();

    if (section?.status !== "ok" && section?.status !== "stale") {
      /* Nothing was counted, so nothing may be claimed. The one sentence that must never appear
         here is "no open alerts", which is a statement about the cluster. */
      await expect(bell).toHaveAttribute("aria-label", /not known/);
      return;
    }

    const open = section.data?.openCount;
    if (typeof open !== "number") {
      await expect(bell).toHaveAttribute("aria-label", /not known/);
      return;
    }
    if (open === 0) {
      await expect(bell).toHaveAttribute("aria-label", "Notifications, no open alerts");
      await expect(bell.locator(".kui-bell__badge")).toHaveCount(0);
      return;
    }
    // The server's own figure, in the name and on the badge — the badge capped at "9+", which is
    // why the name is the assertion and the badge is the sanity check.
    await expect(bell).toHaveAttribute("aria-label", new RegExp(`Notifications, ${open} open`));
    await expect(bell.locator(".kui-bell__badge")).toHaveText(open > 9 ? "9+" : String(open));
  });

  /**
   * The other half of M8, and the half no gate in this repository has ever covered: the **stream**.
   *
   * `services/alerts` publishes a change frame when an event is acknowledged, the gateway relays it
   * as ADR-035 SSE, and the kernel store takes the count off the frame and re-reads the feed. Every
   * one of those three has unit cases. What none of them can establish is that they are joined:
   * `ContractRouting.derive` decodes and re-encodes JSON and therefore cannot carry a stream at
   * all, so the relay is hand-written and separate — and a contract entry with a green suite looks
   * exactly like a routed stream, which is how wave 6 shipped one that answered 404 through the
   * gateway with every suite passing.
   *
   * So the assertion is a browser watching a number change **with no navigation**: the page is
   * stamped before the acknowledgement and the stamp is checked afterwards, because a bell that
   * came back right after a reload proves only that the *feed* is readable, which the case above
   * already covers.
   *
   * Acknowledging closes the event (`InMemoryAlertStore.acknowledge` resolves it as
   * `Acknowledged`), so the open count falls by one. It is done over the API rather than through
   * `@kui/feature-alerts`' own control, so that a failure here is a failure of the stream and not
   * of a button in another package.
   *
   * **Skipped, with the reason said out loud, on a deployment with nothing open.** The seeded event
   * comes from setting `diskUsedWarningPercent: 1` in the quickstart configuration; with the
   * shipped threshold the feed is legitimately empty and there is nothing to acknowledge. A silent
   * skip is what let three of `alerts.spec.ts`'s four cases pass while asserting nothing.
   */
  test("acknowledging an open event moves the bell, with no reload", async ({ page, api }) => {
    const body = (await api.get(`/api/v1/clusters/${CLUSTER}/alerts/events`)) as {
      events?: {
        status?: string;
        data?: {
          openCount?: number | null;
          items?: readonly { id?: string; resolution?: unknown }[];
        };
      };
    };
    const section = body.events;
    const open = section?.data?.openCount;
    const unresolved = (section?.data?.items ?? []).filter(
      (row) =>
        typeof row.id === "string" && (row.resolution === null || row.resolution === undefined),
    );

    test.skip(
      (section?.status !== "ok" && section?.status !== "stale") ||
        typeof open !== "number" ||
        open < 1 ||
        unresolved.length === 0,
      "this deployment's alerts feed holds no open event, so there is nothing to acknowledge; " +
        "seed one with diskUsedWarningPercent: 1 in the quickstart configuration",
    );

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    const bell = page.getByTestId("notifications");
    await expect(bell).toHaveAttribute("aria-label", new RegExp(`Notifications, ${open} open`));

    /* The stamp. A reload replaces the window, so this value not surviving is exactly the thing
       that would make the assertion below meaningless. */
    await page.evaluate(() => {
      (window as unknown as Record<string, unknown>)["__kuiStreamWitness"] = "before";
    });

    const eventId = unresolved[0]!.id!;
    await api.post(
      `/api/v1/clusters/${CLUSTER}/alerts/events/${encodeURIComponent(eventId)}/acknowledgement`,
    );

    const after = (open as number) - 1;
    /* `toHaveAttribute` retries, which is what lets this wait for the frame rather than sleep for
       it. If the relay is not routed the bell simply never moves and this fails on its timeout —
       the symptom the 404 stream had, seen from the browser. */
    await expect(bell).toHaveAttribute(
      "aria-label",
      after === 0
        ? "Notifications, no open alerts"
        : new RegExp(`Notifications, ${after} open`),
    );

    expect(
      await page.evaluate(
        () => (window as unknown as Record<string, unknown>)["__kuiStreamWitness"],
      ),
      "the page reloaded, so this proves the feed is readable and says nothing about the stream",
    ).toBe("before");
  });

  /**
   * The notifications panel, opened (`M06`, `M21`) — the frame's half of two captures, and a panel
   * no browser case had ever opened before W8-07's screen census counted them.
   *
   * The bell's *count* is asserted twice above. The panel underneath it was not asserted anywhere
   * outside jsdom, and it is the half that can be empty for four different reasons: fetching,
   * refused, not configured, and a genuinely quiet cluster. §4.16 puts it in the frame rather than
   * on a page — it is drawn over the dashboard in `M06` and over ksqlDB in `M21` — which is why it
   * is driven here rather than from either screen's spec.
   *
   * Every branch asserts the words for the state the API is actually in, and one rule spans all of
   * them: **no branch may draw a blank panel**. A panel with nothing in it and nothing to say is
   * indistinguishable from a panel that failed to render, which is the misreading the whole
   * component exists to prevent.
   */
  test("the bell opens a panel that says something in every state it can be in", async ({
    page,
    api,
  }) => {
    const body = (await api.get(`/api/v1/clusters/${CLUSTER}/alerts/events`)) as {
      events?: { status?: string; data?: { items?: readonly unknown[] } };
    };
    const section = body.events;

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    await page.getByTestId("notifications").click();

    const panel = page.getByTestId("notification-panel");
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("heading", { name: "Notifications" })).toBeVisible();

    if (section?.status === "ok" || section?.status === "stale") {
      const rows = section.data?.items ?? [];
      if (rows.length === 0) {
        /* Words, not a blank panel: the case this component exists to get right. */
        await expect(panel).toContainText("Nothing to report");
      } else {
        await expect(panel.locator(".kui-notices__item")).toHaveCount(rows.length);
        /* The control that makes the bell's mark meaningful. Present only where there is something
           to mark, which is why it is asserted inside this branch and not above it. */
        await expect(panel.getByRole("button", { name: "Mark all read" })).toBeVisible();
      }
    } else {
      /* Refused, unavailable or not configured. Each has its own sentence and none of them is the
         empty-cluster sentence, which would be a statement about the cluster. */
      await expect(panel).not.toContainText("Nothing to report");
      await expect(panel.locator(".kui-notices__state p")).toBeVisible();
    }

    /* Whatever branch ran: something legible is in it. A panel whose only content is its own
       heading is the rendering fault this asserts against. */
    const text = ((await panel.textContent()) ?? "").replace("Notifications", "").trim();
    const why = "the notifications panel drew its heading and nothing else";
    expect(text.length, why).toBeGreaterThan(0);
  });

  /**
   * The Appearance popover (`M22`) and the light theme it writes (`M02`) — two of the twenty-three
   * screens, and until W8-07's census counted them, two that no browser case had ever opened.
   *
   * They are one case because they are one mechanism seen from two ends. `chrome.test.tsx` asserts
   * in jsdom that choosing `light` writes `data-theme="light"` on the root element; what it cannot
   * assert is that the popover a person clicks is wired to that preference at all, through a real
   * `TopBar`, a real anchor and the kernel's own singleton. §3.10's rule is the second half and is
   * equally invisible to a unit case: **no chip carries its meaning in colour alone** — every
   * segment is a named radio, which is why the accent row is four words rather than four swatches.
   *
   * `M22` is drawn over the ksqlDB screen and `M02` over the dashboard. The popover belongs to the
   * frame rather than to either page (§4.16 makes the same point about the notifications panel), so
   * it is driven here, from the address the product opens on.
   */
  test("the appearance popover names every choice in words, and Light repaints the frame", async ({
    page,
  }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    const control = page.getByTestId("appearance-control");
    await expect(control).toHaveAttribute("aria-expanded", "false");
    await control.click();

    const popover = page.getByRole("dialog", { name: "Appearance" });
    await expect(popover).toBeVisible();
    await expect(control).toHaveAttribute("aria-expanded", "true");

    /* Three preferences, each a named group, and every option a word. A swatch-only accent row
       would satisfy "the popover opened" and fail every colour-blind operator. */
    for (const group of ["Accent colour", "Theme", "Density"]) {
      await expect(popover.getByRole("radiogroup", { name: group })).toBeVisible();
    }
    const options = ["Blue", "Teal", "Green", "Amber", "Auto", "Light", "Dark"];
    for (const option of [...options, "Comfortable", "Compact"]) {
      await expect(popover.getByRole("radio", { name: option })).toHaveCount(1);
    }

    /* And "Auto" is explained. Nobody guesses that it keeps following the system rather than
       resolving once at load, and it is the value most operators are on. */
    await expect(popover).toContainText(/Auto follows the system/i);

    await popover.getByRole("radio", { name: "Light", exact: true }).check();
    /* The attribute, not a colour: `10-tokens.css` redefines the palette under
       `:root[data-theme="light"]`, so this is the one thing the whole light theme hangs from, and
       it is what `M02` is a picture of. */
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");

    await popover.getByRole("radio", { name: "Dark", exact: true }).check();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

    /* Escape closes it and returns focus to the glyph, which is the rule that keeps a keyboard user
       from being dropped at the top of the document. */
    await page.keyboard.press("Escape");
    await expect(popover).toBeHidden();
    await expect(control).toBeFocused();
  });

  /**
   * `M08`, and it is the last of the twenty-three screens that had never been drawn by a browser.
   *
   * Not because anything was hard: **no case had ever switched cluster, because the quickstart
   * registered one cluster and there was nothing to switch to.** The control, its accessible names,
   * and `environmentSwitch`'s decision all have unit cases; what none of them can establish is the
   * thing the capture is a picture of — an operator pressing a second environment and the whole
   * frame arriving on it, with a receipt naming where they now are.
   *
   * `deployment/quickstart/kui-quickstart.yaml` registers the second cluster (W9-01).
   *
   * ## The control is the rail, and it is not a menu
   *
   * `WAVE-09.md` describes this case as opening *"the cluster selector's menu"*, and there is a
   * `ClusterSelector` in `shell/src/chrome/` that has exactly such a menu — a listbox, 214 lines,
   * eight stories, eight cases in `chrome.test.tsx`'s own `describe` for it, and an export from
   * `shell/src/index.ts`. **Nothing in the product renders it**, which a browser measures in one
   * line: `[data-testid="cluster-selector-trigger"]` resolves to zero elements on every screen of
   * a running stack. The shipped switch is `EnvRail`: one always-visible tile per environment in
   * the 48px column, because a dropdown hides the one fact an operator needs in peripheral vision,
   * which is which cluster they are about to break (`EnvRail`'s own header says so). So this case
   * presses a tile. Reported as a finding rather than repaired here: the component is not this
   * packet's, and deleting it is a decision rather than a chore.
   *
   * **This fails rather than skips on a one-cluster deployment**, which is house rule 6 and is the
   * same choice `connect.spec.ts` made about a Connect worker: a suite that quietly skips its only
   * uncovered screen is how that screen stayed uncovered for four waves. The four skips this suite
   * carries are about a *service* a deployment may legitimately not run; a second registered
   * cluster is a line of configuration this wave's own stack ships.
   *
   * Nothing about the second cluster is written down here. Its id and its name are read off
   * `/api/v1/clusters`, so the case is about the frame following a switch and not about the word
   * `staging-eu-01` — which is `deployment/`'s to choose and this file's to discover.
   */
  test("switching cluster names where you have arrived, and takes the frame with it", async ({
    page,
    api,
  }) => {
    const registered = await clusters(api);
    const other = registered.find((entry) => entry.id !== CLUSTER);
    expect(
      other,
      "this deployment registers one cluster, so the environment rail has one tile and there is " +
        "nothing to switch to, and M08 cannot be drawn. The quickstart registers a second " +
        "profile — see deployment/quickstart/kui-quickstart.yaml, kui.clusters",
    ).toBeDefined();
    if (other === undefined) return;

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * One tile per registered cluster, counted from the gateway's own roster rather than from a
     * literal — a rail that drew only the current environment would have looked exactly like a
     * working one on the deployment this suite ran against for four waves.
     *
     * And each tile is asserted to carry the environment's **full name** in its accessible name.
     * That is `EnvRail`'s own stated rule and it is load-bearing rather than decorative: the tile
     * shows one letter, so `prod-kyiv-01` and `prod-eu-02` draw the identical tile, and the
     * accessible name is the only thing that tells a keyboard or screen-reader user which is
     * which.
     */
    const tiles = page.getByRole("list", { name: "Environments" }).getByRole("button");
    await expect(tiles).toHaveCount(registered.length);
    for (const entry of registered) {
      const tile = page.getByTestId(`env-tile-${entry.id}`);
      await expect(tile, `no rail tile for ${entry.id}`).toHaveCount(1);
      await expect(tile).toHaveAttribute("aria-label", new RegExp(quoted(entry.name)));
    }
    await expect(page.getByTestId(`env-tile-${CLUSTER}`)).toHaveAttribute("aria-current", "true");

    await page.getByTestId(`env-tile-${other.id}`).click();

    /*
     * The toast, first, because it is the only thing here on a six-second timer
     * (`DEFAULT_DURATION_MS`) and because it is what `M08` is a capture of.
     *
     * Two assertions and they are different in kind. The **name** is the one only a real second
     * cluster can supply and it is read off the API above — a rail tile shows an initial, so the
     * toast is the only place the cluster somebody has just switched to is named in full (§3.11).
     * The **verb** is the shell's sentence, and it is asserted case-insensitively here because
     * `shell.test.tsx` pins the wording by literal in the package that owns it; what this case is
     * responsible for is that a receipt was raised at all, over a real switch, in a browser.
     */
    const notices = page.locator(".kui-notice-stack");
    await expect(notices).toContainText(other.name);
    await expect(notices).toContainText(/switched to/i);

    /*
     * And the frame followed. Three readings of the same fact, because each of them can fail on its
     * own: the address (a switch made from a cluster-scoped page has to rewrite it, or the frame
     * and the page describe two different clusters), the rail's current tile, and the drawer head —
     * §2.1's cluster block, which is the head of every screen and would otherwise go on naming the
     * cluster nobody is looking at.
     */
    await expect(page).toHaveURL((url) => url.pathname.startsWith(`/ui/clusters/${other.id}/`));
    await expect(page.getByTestId(`env-tile-${other.id}`)).toHaveAttribute("aria-current", "true");
    await expect(page.getByTestId(`env-tile-${CLUSTER}`)).not.toHaveAttribute(
      "aria-current",
      "true",
    );
    await expect(page.getByTestId("nav-drawer")).toContainText(other.name);
  });
});

/**
 * The six stat tiles of the cluster dashboard, by the testid each one has carried since `M01`.
 *
 * Written out rather than counted off `.kui-stat`, because the defect this guards against is a row
 * that draws **fewer** tiles than it should: a locator that collects whatever is on the page and
 * then asserts about each of them passes over an empty row, which is the shape of vacuous coverage
 * this wave exists to remove.
 */
const STAT_TILES = [
  "stat-brokers",
  "stat-topics",
  "stat-in-sync",
  "stat-production",
  "stat-consume",
  "stat-lag",
] as const;

/**
 * Every stat tile carries either a figure or a sentence — never its own label and nothing else.
 *
 * The label is subtracted rather than matched around, because the assertion has to hold for a
 * measured `3`, for an em dash with a title, and for a paragraph explaining that nobody asked. What
 * it must not hold for is the state that shipped, where the only text in the tile was the word
 * printed above the space the figure was supposed to occupy.
 *
 * `expect.poll` rather than a single read: a tile on a cold page is legitimately a skeleton for a
 * moment, and the claim being made is that it stops being one — not that it never was.
 */
async function eachTileSaysSomething(page: Page): Promise<void> {
  for (const testId of STAT_TILES) {
    const tile = page.getByTestId(testId);
    await expect(tile, `${testId} is not drawn at all`).toBeVisible();
    await expect
      .poll(
        async () => {
          const label = await tile.locator(".kui-stat__label").innerText();
          return (await tile.innerText()).replace(label, "").trim();
        },
        { message: `${testId} drew its label and nothing else — no figure and no sentence` },
      )
      .not.toBe("");
  }
}

/**
 * A cluster's own name, made safe to put inside a `RegExp`.
 *
 * The names come from a YAML file an operator writes, and `Quickstart (local)` — which is the one
 * this stack ships — is two capture groups and an empty alternation to a regular-expression engine.
 * A name is matched here rather than compared whole because it sits inside a longer accessible
 * label; escaping is what keeps that from becoming a match against something else entirely.
 */
function quoted(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Every cluster the gateway has registered, in the order it lists them. */
async function clusters(api: KuiApi): Promise<readonly { id: string; name: string }[]> {
  const document = (await api.get("/api/v1/clusters")) as {
    readonly clusters?: {
      readonly data?: readonly {
        readonly cluster?: { readonly id?: string; readonly name?: string };
      }[];
    };
  };
  return (document.clusters?.data ?? [])
    .map((entry) => entry.cluster)
    .filter((one): one is { id: string; name: string } =>
      typeof one?.id === "string" && typeof one.name === "string",
    );
}
