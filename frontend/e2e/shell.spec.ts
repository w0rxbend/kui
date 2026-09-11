/**
 * The frame: that it loads at all, that a deep link works, and that the two things a bug report
 * needs are on screen.
 *
 * This is the suite's smoke test. If it fails, nothing else in the run means anything.
 */
import { CLUSTER, test, expect } from "./fixtures";

test.describe("the shell", () => {
  test("loads, and reaches the gateway through its own proxy", async ({ page }) => {
    await page.goto("/ui/");

    // By role and by text, not by `data-testid`. A test that selects on a testid asserts that a
    // developer wrote an attribute; this asserts that a person can find the thing.
    await expect(page.getByRole("navigation").first()).toBeVisible();
    await expect(page.getByText("Quickstart", { exact: false }).first()).toBeVisible();
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
});
