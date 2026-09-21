/**
 * The alerts feed: the document it decodes, the two words §3.9 separated, and the count it must not
 * compute.
 *
 * ## Why every case here starts from a document, and goes through the store
 *
 * The files in `src/documents/` are whole responses in the shape `services/alerts` sends — one
 * `Section` under `events`, wrapping `{items, total, openCount, unreadCount, lastReadAt,
 * evaluatedAt, rules}` with `{id, severity, tone, category, glyph, openedAt, lastSeenAt, title,
 * detail, resolution}` rows — and every case decodes one. Wave 5 put a hand-written literal on each
 * side of the metrics wire, both sides passed, and two cards drew nothing against a real broker for
 * a whole milestone. A literal typed beside an assertion asserts that the author typed the same
 * thing twice.
 *
 * They go through `@kui/kernel`'s `createAlerts` — see `fixtures.tsx` — because that is the store
 * the shell builds and therefore the only thing that decides what this screen is handed in
 * production. Until wave 7 these cases went through a `feedSection` of this package's own that no
 * product code called: a second reader of one wire, checked by the cases that were meant to be
 * checking the first one.
 *
 * ## What these documents are, and are not
 *
 * Their **shape** is the encoder's: `services/alerts/contract/test/resources/golden/*.json` are
 * rendered by `AlertDtos`' own `Encoder`s and asserted by a Scala suite, and these files were
 * rewritten to match them field for field, explicit `null`s included. `alertsGolden.test.tsx` then
 * draws the screen over those golden files directly, so the shape is bound rather than believed.
 *
 * Their **content** is this packet's, for the seven states the three goldens do not cover — a
 * second page of a long feed, an unrecognised vocabulary, a dark rule. That half is disclosed here
 * rather than in a report nobody reads next wave.
 */
import { describe, expect, it } from "vitest";
import { flush } from "solid-js";

import { mount, findViolations, describeViolations } from "./testing.js";
import { FromDocument, feedState, pageOf } from "./fixtures.jsx";
import {
  AlertsFeed,
  RuleReports,
  acknowledgedBy,
  categoryWords,
  filterEvents,
  glyphOf,
  isOpen,
  openPill,
  pageCaption,
  ruleRefusal,
  severityWords,
  toneOf,
  NOT_EVALUATED,
  NO_EVENTS,
  NO_MATCHES,
  NO_OPEN_COUNT,
} from "./index.jsx";
import openDocument from "./documents/events-open.json" with { type: "json" };
import pagedDocument from "./documents/events-paged.json" with { type: "json" };
import emptyDocument from "./documents/events-empty.json" with { type: "json" };
import neverEvaluatedDocument from "./documents/events-never-evaluated.json" with { type: "json" };
import notConfiguredDocument from "./documents/events-not-configured.json" with { type: "json" };
import unavailableDocument from "./documents/events-unavailable.json" with { type: "json" };
import unknownDocument from "./documents/events-unknown-vocabulary.json" with { type: "json" };
import darkRuleDocument from "./documents/events-dark-rule.json" with { type: "json" };
import staleRuleDocument from "./documents/events-stale-rule.json" with { type: "json" };

/** A clock the cases hold still, so a relative age is a fact rather than a race. */
const NOW = new Date("2026-03-04T09:41:00Z");

function rowsIn(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="alert-row"]')];
}

function rowFor(container: HTMLElement, id: string): HTMLElement | undefined {
  return rowsIn(container).find((row) => row.dataset["event"] === id);
}

function pillIn(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>('[data-testid="alerts-open-count"]');
}

function toneOfRow(row: HTMLElement | undefined): string | null | undefined {
  return row?.querySelector(".kui-alerts__mark")?.getAttribute("data-tone");
}

function iconOfRow(row: HTMLElement | undefined): string | null | undefined {
  return row?.querySelector("[data-icon]")?.getAttribute("data-icon");
}

function ruleRow(container: HTMLElement, rule: string): HTMLElement | undefined {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]')].find(
    (candidate) => candidate.dataset["rule"] === rule,
  );
}

describe("the alerts wire", () => {
  it("decodes a whole events document into the page the screen draws", async () => {
    const page = await pageOf(openDocument);
    expect(page.items).toHaveLength(5);
    expect(page.openCount).toBe(3);
    expect(page.total).toBe(5);
    expect(page.unreadCount).toBe(2);
    expect(page.evaluatedAt).toBe("2026-03-04T09:40:00Z");

    const first = page.items[0];
    expect(first?.id).toBe("evt-1");
    expect(first?.title).toBe("2 partitions are offline");
    expect(first?.detail).toBe("orders.payments.v2 p3, p7");
    expect(first?.severity).toBe("critical");
    expect(first?.tone).toBe("danger");
    expect(first?.category).toBe("partition");
    expect(first?.glyph).toBe("partition");
    expect(first?.openedAt).toBe("2026-03-04T09:28:00Z");
    expect(first?.resolution).toBeUndefined();
  });

  it("reads a resolution as the two facts it is, and never as a boolean", async () => {
    const page = await pageOf(openDocument);
    const cleared = page.items.find((event) => event.id === "evt-4");
    const acknowledged = page.items.find((event) => event.id === "evt-5");

    // `cleared` is the cluster saying the condition stopped; `acknowledged` is a person saying they
    // know about it. A feed that collapsed them answers "is it fixed?" with "somebody looked".
    expect(cleared?.resolution?.kind).toBe("cleared");
    expect(cleared?.resolution?.by).toBeUndefined();
    expect(acknowledgedBy(cleared!)).toBeUndefined();
    expect(acknowledged?.resolution?.kind).toBe("acknowledged");
    expect(acknowledgedBy(acknowledged!)).toBe("ops@example.test");
    expect(isOpen(cleared!)).toBe(false);
    expect(page.items.filter(isOpen)).toHaveLength(3);
  });

  it("does not name the acknowledger of an event the cluster cleared by itself", async () => {
    /* Filed by W7-A3. The case above pins `acknowledgedBy` on the golden's `cleared` row — whose
       `by` is `null` — so `event.resolution?.by` and the real rule answer the same thing for it,
       and dropping the `kind === "acknowledged"` test left all 61 cases green.

       The two facts are the ones `AlertResolution`'s own comment keeps apart: `cleared` is the
       cluster saying the condition stopped, `acknowledged` is a person saying they know about it.
       `AlertResolutionDto` carries a `by` on either, so a row cleared while a principal was
       recorded against it would have read as "acknowledged by ops@example.test" on a screen where
       nobody acknowledged anything — the product answering "is it fixed?" with "somebody looked at
       it", which is the one confusion this pair of words exists to prevent. */
    const clearedWithPrincipal = structuredClone(openDocument) as {
      events: { data: { items: { id: string; resolution?: Record<string, unknown> | null }[] } };
    };
    const cleared = clearedWithPrincipal.events.data.items.find((one) => one.id === "evt-4");
    expect(cleared?.resolution?.["kind"]).toBe("cleared");
    if (cleared !== undefined) {
      cleared.resolution = { at: "2026-03-04T07:06:00Z", kind: "cleared", by: "ops@example.test" };
    }

    const page = await pageOf(clearedWithPrincipal);
    const row = page.items.find((event) => event.id === "evt-4");
    expect(row?.resolution?.by).toBe("ops@example.test");
    expect(acknowledgedBy(row!)).toBeUndefined();

    // And the acknowledged row still names its principal, so the guard declines a word rather than
    // the feature.
    expect(acknowledgedBy(page.items.find((event) => event.id === "evt-5")!)).toBe(
      "ops@example.test",
    );
  });

  it("hands a document it cannot read to the screen as a failure, never as an empty feed", async () => {
    /*
     * The exact shape wave 5's metrics wire failed in: the section decodes, the payload does not,
     * and an empty array is the most convincing possible way to say nothing is wrong. It is also
     * the shape this feed would arrive in if the service renamed `items` — which is why the
     * refusal, and not a default, is what this build does with it.
     */
    const renamed = {
      events: { status: "ok", fetchedAt: "2026-03-04T09:41:00.000Z", data: { rows: [] } },
    };
    const failed = await feedState(renamed);
    expect(failed.kind).toBe("failed");
    if (failed.kind === "failed") {
      // The keys the document *did* carry, so a rename is legible the first time somebody looks.
      expect(failed.message).toContain("no 'items' array");
      expect(failed.message).toContain("rows");
    }
    expect((await feedState({ alerts: {} })).kind).toBe("failed");
    expect((await feedState(null)).kind).toBe("failed");
  });

  it("carries the section's own status through to the screen's state untouched", async () => {
    // `not_configured` is not a failure and must never arrive as one; ADR-032.
    expect((await feedState(notConfiguredDocument)).kind).toBe("not-configured");

    const unavailable = await feedState(unavailableDocument);
    expect(unavailable.kind).toBe("failed");
    if (unavailable.kind === "failed") {
      /*
       * `UPSTREAM_UNAVAILABLE` and not `KUI-UPSTREAM-UNAVAILABLE`. A `Section.Unavailable`
       * carries a `ReasonCode`, whose wire spelling has no `KUI-` prefix — that prefix belongs to
       * `ErrorCode`, which travels in an envelope and not in a section. This fixture asserted the
       * envelope's spelling until wave 7, over a document the encoder cannot produce.
       */
      expect(unavailable.code).toBe("UPSTREAM_UNAVAILABLE");
      expect(unavailable.message).toBe("The alerts service did not answer.");
    }
  });

  it("refuses a feed containing a row it cannot date rather than dating it now", async () => {
    const state = await feedState({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: {
          openCount: 1,
          evaluatedAt: "2026-03-04T09:40:00Z",
          items: [
            { id: "evt-x", title: "No opened-at", severity: "warning", openedAt: "not a date" },
            { id: "evt-y", title: "No id at all", severity: "warning" },
          ],
        },
      },
    });
    // A partial page would silently under-report its rows while retaining the server's count.
    expect(state.kind).toBe("failed");
  });

  it("requires a resolution time and never invents how it closed", async () => {
    const page = await pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: {
          openCount: 0,
          evaluatedAt: "2026-03-04T09:40:00Z",
          items: [
            {
              id: "evt-1",
              title: "1 partition was offline",
              severity: "critical",
              tone: "success",
              category: "partition",
              glyph: "partition",
              openedAt: "2026-03-04T09:28:00Z",
              resolution: { kind: "acknowledged" },
            },
          ],
        },
      },
    });
    // Drawn as **open**, which is the safe direction: a row marked resolved on a resolution that
    // cannot say when the condition stopped is the screen asserting the incident is over.
    expect(page.items[0]?.resolution).toBeUndefined();
    expect(page.items.filter(isOpen)).toHaveLength(1);

    /* And the other half of the same rule. `cleared` and `acknowledged` are drawn as different
       sentences, so a resolution with a time and no kind cannot be drawn as either — and guessing
       one would put a name on a closure nobody made, or take one off a closure somebody did. */
    const noKind = await pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: {
          openCount: 0,
          evaluatedAt: "2026-03-04T09:40:00Z",
          items: [
            {
              id: "evt-1",
              title: "1 partition was offline",
              severity: "critical",
              tone: "success",
              category: "partition",
              glyph: "partition",
              openedAt: "2026-03-04T09:28:00Z",
              resolution: { at: "2026-03-04T09:30:00Z" },
            },
          ],
        },
      },
    });
    expect(noKind.items[0]?.resolution).toEqual({
      at: "2026-03-04T09:30:00Z",
      kind: undefined,
      by: undefined,
    });
  });

  it("refuses a count that is not a finite number rather than printing what it is", async () => {
    /*
     * `1e999` is a legal JSON number and `JSON.parse` answers `Infinity` for it — as would a
     * misconfigured encoder writing an overflowed counter. Drawn, it reads "Infinity open"; folded
     * into arithmetic it reads "NaN open". Both are worse than the card saying it does not know.
     */
    const page = await pageOf(
      JSON.parse(
        '{"events":{"status":"ok","fetchedAt":"2026-03-04T09:41:00.000Z","data":' +
          '{"items":[],"openCount":1e999,"evaluatedAt":"2026-03-04T09:40:00Z"}}}',
      ),
    );
    expect(page.openCount).toBeNull();
  });

  it("keeps every count absent rather than zero when the document did not carry it", async () => {
    const page = await pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: { items: [], evaluatedAt: "2026-03-04T09:40:00Z" },
      },
    });
    expect(page.openCount).toBeNull();
    expect(page.total).toBeNull();
    expect(page.unreadCount).toBeNull();
  });
});

describe("severity, category and the two words they choose", () => {
  it("an event's severity chooses its tone and its category chooses its glyph", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    expect(rowsIn(container)).toHaveLength(5);
    // The one-third-width layout by default; §3.8's full-width one is a modifier and not a second
    // component, so the class is asserted here and the appearance is reviewed in the two stories.
    expect(container.querySelector(".kui-alerts--wide")).toBeNull();

    /*
     * §3.9's correction, asserted in the one arrangement that proves it: two rows of the *same*
     * severity carrying *different* glyphs. A component picking the glyph from the severity cannot
     * draw this, which is the whole reason the wire has four fields where a browser might expect
     * two.
     */
    expect(toneOfRow(rowFor(container, "evt-2"))).toBe("warning");
    expect(toneOfRow(rowFor(container, "evt-3"))).toBe("warning");
    expect(iconOfRow(rowFor(container, "evt-2"))).toBe("disk");
    expect(iconOfRow(rowFor(container, "evt-3"))).toBe("refresh");

    // The other rows of both tables, so that a table swapped wholesale is red too.
    expect(toneOfRow(rowFor(container, "evt-1"))).toBe("danger");
    expect(iconOfRow(rowFor(container, "evt-1"))).toBe("partitions");
    expect(iconOfRow(rowFor(container, "evt-4"))).toBe("topology");

    dispose();
  });

  it("draws the full-width layout as a modifier on the one card, not as a second one", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => <AlertsFeed wide state={state} now={() => NOW} />);
    await flush();

    expect(container.querySelector(".kui-alerts--wide")).not.toBeNull();
    // Same rows, two layouts (§3.8). Two components drawing one feed is two places for the sentence
    // under an empty one to drift apart.
    expect(rowsIn(container)).toHaveLength(5);
    dispose();
  });

  it("draws a resolved event in the tone the service derived, not the one its severity implies", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    /*
     * `evt-5` opened `critical` and is resolved, and the service sends `tone: "success"` for it:
     * a resolved row is drawn in the success tone whatever it opened at, which is §3.8's fourth dot
     * and is decided in `AlertEvent.tone` on the server. A browser that re-derived the tone from the
     * severity would paint this row red and tell an operator an incident is running.
     */
    const resolved = (await pageOf(openDocument)).items.find((event) => event.id === "evt-5");
    expect(resolved?.severity).toBe("critical");
    expect(toneOfRow(rowFor(container, "evt-5"))).toBe("success");
    dispose();
  });

  it("says in words that a severity and a category are ones it does not recognise", async () => {
    const state = await feedState(unknownDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    const row = rowsIn(container)[0];
    // Neither is folded onto the nearest thing this build knows: the nearest thing is a confident
    // statement about an event the service may have meant as the loudest one on the cluster.
    expect(toneOfRow(row)).toBe("neutral");
    expect(iconOfRow(row)).toBe("dot");
    expect(row?.textContent).toContain('"emergency"');
    expect(row?.textContent).toContain('"connector"');
    dispose();
  });

  it("does not reinterpret an unknown tone from the event's severity", () => {
    const bare = {
      id: "evt-z",
      severity: "critical",
      tone: "future-tone",
      category: "storage",
      glyph: "",
      title: "A log directory is full",
      detail: undefined,
      openedAt: NOW.toISOString(),
      lastSeenAt: undefined,
      resolution: undefined,
    };
    expect(toneOf(bare)).toBe("neutral");
    expect(toneOf({ ...bare, severity: "warning" })).toBe("neutral");
    expect(toneOf({ ...bare, severity: "moderate" })).toBe("neutral");
    expect(glyphOf(bare)).toBe("dot");
    // Vocabulary labels describe their own server fields. A future rendering word must not make a
    // known severity or category read as unknown, or make an unknown one appear familiar.
    expect(severityWords(bare)).toBe("Severity critical");
    expect(categoryWords(bare)).toBe("Category storage");
    expect(severityWords({ ...bare, severity: "emergency", tone: "danger" })).toContain(
      '"emergency"',
    );
    expect(categoryWords({ ...bare, category: "connector", glyph: "storage" })).toContain(
      '"connector"',
    );
  });
});

describe("the open count", () => {
  /**
   * This packet's owned rule from wave 6, kept.
   *
   * The document is a page: seven events are open in the store, three rows are on this page, and
   * two of those three are open. Any arithmetic over the rows the card holds answers 2 or 3, and
   * the only way to draw 7 is to read the service's own figure.
   */
  it("the open count on the card is the API's own figure", async () => {
    const page = await pageOf(pagedDocument);
    expect(page.openCount).toBe(7);
    expect(page.items).toHaveLength(3);
    expect(page.items.filter(isOpen)).toHaveLength(2);

    const state = await feedState(pagedDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    expect(pillIn(container)?.textContent).toContain("7 open");
    expect(rowsIn(container)).toHaveLength(3);
    dispose();
  });

  it("keeps the service's figure when a filter has removed rows from the page", async () => {
    const state = await feedState(pagedDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={state}
        filter={{ severity: "critical", state: "all" }}
        now={() => NOW}
      />
    ));
    await flush();

    expect(rowsIn(container)).toHaveLength(1);
    // The reader's filter is a fact about the page; the count is the service's about the store.
    expect(pillIn(container)?.textContent).toContain("7 open");
    expect(container.textContent).toContain("1 of the 3 events on this page match.");
    dispose();
  });

  it("says in words that the service answered no open count, and never draws a zero", async () => {
    const document = {
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: {
          evaluatedAt: "2026-03-04T09:40:00Z",
          items: [
            {
              id: "evt-1",
              title: "2 partitions are offline",
              severity: "critical",
              tone: "danger",
              category: "partition",
              glyph: "partition",
              openedAt: "2026-03-04T09:28:00Z",
            },
          ],
        },
      },
    };
    const page = await pageOf(document);
    expect(page.openCount).toBeNull();
    expect(openPill(page)).toBeUndefined();

    const state = await feedState(document);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    expect(pillIn(container)).toBeNull();
    expect(container.textContent).toContain(NO_OPEN_COUNT);
    expect(container.textContent).not.toContain("0 open");
    dispose();
  });

  it("draws a measured zero as words, and an unevaluated cluster as neither", async () => {
    expect(openPill(await pageOf(emptyDocument))).toEqual({ text: "None open", tone: "success" });
    expect(openPill(await pageOf(pagedDocument))).toEqual({ text: "7 open", tone: "danger" });
    // The one that matters: rules that have never run here have not established that nothing is
    // wrong, so the pill says what it is instead of drawing a reassuring zero.
    expect(openPill(await pageOf(neverEvaluatedDocument))).toEqual({
      text: "Not evaluated yet",
      tone: "neutral",
    });
  });

  /**
   * The two guards in `openPill`, in the order that matters.
   *
   * A document with **neither** figure: no `evaluatedAt` and no `openCount`. Read the count first
   * and this card draws no pill at all, which says "there is nothing here to show" about a cluster
   * KUI has never looked at — the more reassuring of the two possible errors, which is the one this
   * product is built to refuse. Read the evaluation first and it says so.
   *
   * `AlertFeedDto.openCount` is a required `Int`, so `services/alerts` cannot itself emit this; a
   * proxy, an older build, or a truncated body can, and the browser's answer to a document it has
   * not been promised is exactly where it is worth pinning.
   */
  it("a feed carrying neither figure says the cluster has not been looked at, and not nothing", async () => {
    const blind = {
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: { items: [], total: 0, unreadCount: 0, lastReadAt: null, rules: [] },
      },
    };
    const page = await pageOf(blind);
    expect(page.evaluatedAt).toBeUndefined();
    expect(page.openCount).toBeNull();
    expect(openPill(page)).toEqual({ text: "Not evaluated yet", tone: "neutral" });

    const state = await feedState(blind);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    expect(pillIn(container)?.textContent).toContain("Not evaluated yet");
    expect(container.textContent).toContain(NOT_EVALUATED);
    dispose();
  });
});

describe("the states this card has", () => {
  it("a feed that answered with no events says so and draws no count", async () => {
    const page = await pageOf(emptyDocument);
    expect(page.items).toHaveLength(0);
    const state = await feedState(emptyDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    expect(container.textContent).toContain(NO_EVENTS);
    // Not "None open" either: the sentence is the answer, and a pill beside it is a second one.
    expect(pillIn(container)).toBeNull();
    expect(rowsIn(container)).toHaveLength(0);
    dispose();
  });

  it("an empty feed the rules never ran on says that, and never that the cluster is well", async () => {
    const state = await feedState(neverEvaluatedDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    /*
     * Two empty feeds, two opposite facts, and the same picture until somebody writes the sentence.
     * `AlertFeedDto` puts it in the wire's own words: a feed with `openCount: 0` and no
     * `evaluatedAt` has not established that the cluster is well, it has established that KUI has
     * not looked yet.
     */
    expect(container.textContent).toContain(NOT_EVALUATED);
    expect(container.textContent).not.toContain(NO_EVENTS);
    expect(pillIn(container)?.textContent).toContain("Not evaluated yet");
    dispose();
  });

  it("a deployment with no alerts service draws no Alerts row at all rather than an empty one", async () => {
    expect((await feedState(notConfiguredDocument)).kind).toBe("not-configured");
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "not-configured" }} now={() => NOW} />
    ));
    await flush();

    // ADR-032: not configured is hidden, not empty. Nothing is drawn — no card, no heading, no
    // sentence — because an empty card headed "Alerts & events" sends an operator hunting for an
    // outage that does not exist.
    expect(container.querySelector(".kui-panel")).toBeNull();
    expect(container.textContent?.trim()).toBe("");
    dispose();
  });

  it("a failed read draws its message and its code beside a retry", async () => {
    let retried = 0;
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={{
          kind: "failed",
          message: "The alerts service did not answer.",
          code: "KUI-UPSTREAM-UNAVAILABLE",
        }}
        onRetry={() => (retried += 1)}
        now={() => NOW}
      />
    ));
    await flush();

    expect(container.textContent).toContain("The alerts service did not answer.");
    expect(container.textContent).toContain("KUI-UPSTREAM-UNAVAILABLE");
    const retry = [...container.querySelectorAll("button")].find((b) =>
      b.textContent?.includes("Retry"),
    );
    retry?.click();
    await flush();
    expect(retried).toBe(1);
    dispose();
  });

  /**
   * The other half of the rule above, and the half nothing checked.
   *
   * `onRetry` is handed in, so the only thing that can withhold the button is the card's own
   * judgement about the state it is in. A `forbidden` read is not a read that might succeed on the
   * second press: the principal is not allowed to make it, and a Retry beside it either teaches the
   * operator that KUI's retries do nothing or sends them to raise a ticket about an outage that is
   * not one. `not-configured` draws no card at all and is covered above; `loading` and `stale` are
   * not failures either, and neither offers one.
   */
  it("offers no retry on a read this principal may not make", async () => {
    let retried = 0;
    const onRetry = (): void => {
      retried += 1;
    };
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "forbidden" }} onRetry={onRetry} now={() => NOW} />
    ));
    await flush();

    expect(container.textContent).toContain(
      "You do not have permission to read this cluster's alerts.",
    );
    const buttons = [...container.querySelectorAll("button")].filter((button) =>
      (button.textContent ?? "").includes("Retry"),
    );
    expect(buttons).toHaveLength(0);
    expect(retried).toBe(0);
    dispose();
  });

  it("draws a stale answer with its reason above the rows and no invented code", async () => {
    const ready = await pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={{
          kind: "stale",
          value: ready,
          reason: "The alerts service has not answered since 09:38.",
        }}
        now={() => NOW}
      />
    ));
    await flush();

    expect(container.textContent).toContain("has not answered since 09:38");
    expect(rowsIn(container)).toHaveLength(5);
    // `KUI-STALE` is a code no service in this repository has ever sent, and escalating it wastes
    // the afternoon of whoever receives it.
    expect(container.textContent).not.toContain("KUI-STALE");
    dispose();
  });

  /**
   * The `filtered` state, which had no case at all.
   *
   * The page holds three events and the reader has asked for resolved criticals, of which it holds
   * none. The sentence must be about the **filter** — nothing on this page matches — and not about
   * the service, because the service answered and is holding seven open events. Wave 6 shipped
   * `NO_MATCHES` and the whole `filtered` branch with nothing asserting either: the case named for
   * it left one row on screen, so it was testing the ordinary drawn state under a title about an
   * empty one.
   */
  it("says nothing on this page matches when the reader's filter empties it", async () => {
    const state = await feedState(pagedDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={state}
        filter={{ severity: "critical", state: "resolved" }}
        now={() => NOW}
      />
    ));
    await flush();

    expect(rowsIn(container)).toHaveLength(0);
    expect(container.textContent).toContain(NO_MATCHES);
    // Not the service's sentence. It answered, and it is holding seven open events.
    expect(container.textContent).not.toContain(NO_EVENTS);
    expect(container.textContent).not.toContain(NOT_EVALUATED);
    // And the count stays the service's: a filter is not a fact about the store.
    expect(pillIn(container)?.textContent).toContain("7 open");
    dispose();
  });

  it("keeps the service's count when a filter leaves some rows standing", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={state}
        filter={{ severity: "critical", state: "open" }}
        now={() => NOW}
      />
    ));
    await flush();

    expect(rowsIn(container)).toHaveLength(1);
    expect(pillIn(container)?.textContent).toContain("3 open");
    dispose();
  });
});

describe("the rows", () => {
  it("draws how an event closed, and offers no acknowledgement for one that has", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={state} onAcknowledge={() => {}} now={() => NOW} />
    ));
    await flush();

    const cleared = rowFor(container, "evt-4");
    expect(cleared?.textContent).toContain("Cleared");
    expect(cleared?.querySelector("button")).toBeNull();

    const acknowledged = rowFor(container, "evt-5");
    expect(acknowledged?.textContent).toContain("Acknowledged");
    expect(acknowledged?.textContent).toContain("By ops@example.test");
    expect(acknowledged?.querySelector("button")).toBeNull();

    // And an open one does offer it, or the two assertions above would pass on a card with no
    // controls at all.
    expect(rowFor(container, "evt-1")?.querySelector("button")).not.toBeNull();
    dispose();
  });

  /**
   * The disabled control's reason, when the caller supplies none.
   *
   * This is the arrangement the dashboard's card is mounted in — a feed and no acknowledgement
   * handler, with nobody threading a refusal through — and until wave 7 the fallback sentence could
   * be emptied with every case green. A disabled button with no reason cannot be told from a broken
   * build: the operator does not know whether to ask for a permission or to file a bug. Both the
   * blank string and the absent one are checked, because a caller passing `""` produces exactly the
   * same dead end as a caller passing nothing.
   */
  it("says why acknowledgement is unavailable even when nobody said why", async () => {
    const state = await feedState(openDocument);
    for (const refusal of [undefined, "   "]) {
      const { container, dispose } = mount(() => (
        <AlertsFeed state={state} acknowledgeRefusal={refusal} now={() => NOW} />
      ));
      await flush();

      const button = [...(rowFor(container, "evt-1")?.querySelectorAll("button") ?? [])].find(
        (candidate) => (candidate.textContent ?? "").includes("Acknowledge"),
      );
      expect(button?.getAttribute("aria-disabled")).toBe("true");
      // Reachable by keyboard, the way the control is reached, rather than through a title nobody
      // hears: `Tooltip` renders its bubble on focus.
      expect(button?.getAttribute("aria-describedby")).not.toBeNull();
      button?.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
      await flush();
      expect(document.body.textContent).toContain(
        "You do not have permission to acknowledge alerts on this cluster.",
      );
      dispose();
    }
  });

  it("dates each row against the clock it was given, and says whose observation it is", async () => {
    const state = await feedState(pagedDocument);
    const { container, dispose } = mount(() => <AlertsFeed state={state} now={() => NOW} />);
    await flush();

    // "KUI noticed", not "opened": a broker publishes no "this partition went offline at", so after
    // a restart this age is an age since the restart. The row says which it is.
    expect(rowFor(container, "evt-1")?.textContent).toContain("KUI noticed 13m ago");
    dispose();
  });

  /**
   * The story helper, drawn.
   *
   * `FromDocument` is what every story in `alerts.stories.tsx` renders, and it is reactive rather
   * than synchronous: the store answers on a microtask, so the story paints its loading state and
   * then the card. A helper that never settled would leave every story an empty box — and the a11y
   * sweep would report no violations over it, because there is nothing on the page to violate
   * anything. So the settling is asserted here, where a failure is legible.
   */
  it("draws the card the stories draw, from a document, once the store has answered", async () => {
    const { container, dispose } = mount(() => (
      <FromDocument document={openDocument}>
        {(state) => <AlertsFeed state={state} onAcknowledge={() => {}} now={() => NOW} />}
      </FromDocument>
    ));
    for (let turn = 0; turn < 8; turn += 1) await flush();

    expect(rowsIn(container)).toHaveLength(5);
    expect(pillIn(container)?.textContent).toContain("3 open");
    dispose();
  });

  it("has no accessibility violations in the state an operator meets on a bad afternoon", async () => {
    const state = await feedState(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={state} onAcknowledge={() => {}} now={() => NOW} />
    ));
    await flush();

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

describe("the filter, which is over the page", () => {
  it("keeps an unrecognised severity out of every severity filter but 'all'", async () => {
    const page = await pageOf(unknownDocument);
    expect(filterEvents(page.items, { severity: "all", state: "all" })).toHaveLength(1);
    expect(filterEvents(page.items, { severity: "critical", state: "all" })).toHaveLength(0);
    // It is still open, so the lifecycle filter finds it: the one event nobody understands must not
    // be the one event nobody sees.
    expect(filterEvents(page.items, { severity: "all", state: "open" })).toHaveLength(1);
  });

  it("separates open from resolved on the resolution field alone", async () => {
    const page = await pageOf(openDocument);
    expect(filterEvents(page.items, { severity: "all", state: "open" })).toHaveLength(3);
    expect(filterEvents(page.items, { severity: "all", state: "resolved" })).toHaveLength(2);
  });

  it("says how many of the page it is showing, in the page's own words", () => {
    expect(pageCaption(5, 5)).toBe("5 events on this page.");
    expect(pageCaption(1, 1)).toBe("1 event on this page.");
    expect(pageCaption(2, 5)).toBe("2 of the 5 events on this page match.");
  });
});

describe("what KUI checked", () => {
  it("a rule that could not be evaluated says so rather than reporting nothing found", async () => {
    const page = await pageOf(darkRuleDocument);
    const dark = page.rules.find((report) => report.rule === "disk-usage");
    expect(dark?.status).toBe("unavailable");
    // Not zero. `ok` with `openEvents: 0` is a measurement; `unavailable` is the absence of one.
    expect(dark?.openEvents).toBeNull();
    expect(ruleRefusal(dark!)).toContain("could not evaluate");

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = ruleRow(container, "disk-usage");
    expect(row?.textContent).toContain("Not evaluated");
    expect(row?.textContent).toContain("describeLogDirs was refused");
    expect(row?.textContent).not.toContain("Nothing open");
    dispose();
  });

  /**
   * The third thing a rule row can be, and the one `ruleRefusal` had no case for.
   *
   * A `stale` evaluation carries a figure that **was** measured, some time ago. Answering it with
   * *"KUI could not evaluate the disk-usage rule, so this row is not a zero"* discards a
   * real reading and replaces it with the sentence for a rule that never looked — the opposite
   * direction from this product's usual failure and just as wrong. The figure is kept, captioned.
   *
   * The caption does not depend on a reason arriving with it, and that is deliberate:
   * `Section.Stale` puts a bare `reason` **code** on the wire and no `message`, and the kernel's
   * `readRule` reads `message`, so on a document `services/alerts` actually emits there is nothing
   * to quote. A row that said nothing in that case would draw a figure from 04:58 as though it had
   * been taken at 09:40.
   */
  it("a stale rule keeps the figure it measured and says it is not current", async () => {
    const page = await pageOf(staleRuleDocument);
    const stale = page.rules.find((report) => report.rule === "disk-usage");
    expect(stale?.status).toBe("stale");
    expect(stale?.openEvents).toBe(0);
    expect(ruleRefusal(stale!)).toBeUndefined();

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = ruleRow(container, "disk-usage");
    expect(row?.textContent).toContain("Nothing open.");
    expect(row?.textContent).toContain("last reading KUI took");
    expect(row?.textContent).not.toContain("could not evaluate");
    expect(row?.textContent).not.toContain("Not evaluated");
    dispose();
  });

  it("names the subjects a rule skipped rather than folding them into its figure", async () => {
    const page = await pageOf(openDocument);
    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const storage = ruleRow(container, "disk-usage");
    // "1 open event" and "2 subjects skipped" are two facts, and the second is what makes the first
    // readable: nothing above 80% *of the directories whose capacity this cluster reports*.
    expect(storage?.textContent).toContain("1 open event.");
    expect(storage?.textContent).toContain("2 subjects were skipped");

    expect(ruleRow(container, "under-replicated-partitions")?.textContent).toContain(
      "Nothing open.",
    );
    dispose();
  });

  /**
   * And the rendered zero the same sentence becomes when nothing was skipped.
   *
   * Three of this fixture's four rules skipped no subject at all, which is what a healthy cluster
   * looks like. Drop the `skipped === 0` guard and every one of them gains *"0 subjects were
   * skipped because the figures they compare were not measured"* — a zero rendered as a caveat, in
   * panel on this screen whose entire argument is that a zero and an absence must not read alike.
   */
  it("says nothing at all about skipped subjects when a rule skipped none", async () => {
    const page = await pageOf(emptyDocument);
    const skipped = page.rules.filter((report) => report.unmeasuredSubjects === 0);
    expect(skipped).toHaveLength(3);

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    for (const report of skipped) {
      expect(ruleRow(container, report.rule)?.textContent).not.toContain("were skipped");
      expect(ruleRow(container, report.rule)?.textContent).not.toContain("was skipped");
    }
    // The one that did skip some still says so, or the four assertions above would pass on a panel
    // that had simply stopped drawing the sentence.
    expect(ruleRow(container, "disk-usage")?.textContent).toContain(
      "2 subjects were skipped",
    );
    dispose();
  });

  it("says a rule this deployment does not run is not running, and not that it found nothing", async () => {
    const page = await pageOf(darkRuleDocument);
    const absent = page.rules.find((report) => report.rule === "stuck-rebalance");
    expect(absent?.status).toBe("not_configured");
    expect(absent?.openEvents).toBeNull();

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = ruleRow(container, "stuck-rebalance");
    // A rule that is not configured and a rule that could not look are different sentences, for the
    // reason `Fetched` keeps `not-configured` apart from `failed`: one of them is nothing being
    // wrong, and telling an operator to investigate it wastes an afternoon.
    expect(row?.textContent).toContain("does not run the stuck-rebalance rule");
    expect(row?.textContent).not.toContain("could not evaluate");
    expect(row?.textContent).not.toContain("Nothing open");
    dispose();
  });

  it("says a rule answered no figure rather than reading it as nothing found", async () => {
    const page = await pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00.000Z",
        data: {
          items: [],
          openCount: 0,
          evaluatedAt: "2026-03-04T09:40:00Z",
          rules: [
            {
              rule: "offline-partitions",
              category: "partition",
              // An `ok` section whose payload carries no count: the rule ran and the document says
              // nothing about what it found.
              evaluation: { status: "ok", fetchedAt: "2026-03-04T09:40:00.000Z", data: {} },
            },
          ],
        },
      },
    });
    expect(page.rules[0]?.openEvents).toBeNull();

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    // "Nothing open" is a measurement and this is not one. The two must not read alike, for the
    // same reason `unavailable` and `ok with zero` must not.
    expect(container.textContent).toContain("reported no figure for this rule");
    expect(container.textContent).not.toContain("Nothing open.");
    dispose();
  });

  it("draws nothing at all when the document carried no rules", async () => {
    const { container, dispose } = mount(() => <RuleReports reports={[]} />);
    await flush();
    expect(container.textContent?.trim()).toBe("");
    dispose();
  });
});
