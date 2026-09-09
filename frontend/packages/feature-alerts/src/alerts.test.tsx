/**
 * The alerts feed: the document it decodes, the two words §3.9 separated, and the count it must not
 * compute.
 *
 * ## Why every case here starts from a document
 *
 * The files in `src/documents/` are whole responses in the shape `services/alerts` sends — one
 * `Section` under `events`, wrapping `{items, total, openCount, unreadCount, lastReadAt,
 * evaluatedAt, rules}` with `{id, severity, tone, category, glyph, openedAt, lastSeenAt, title,
 * detail, resolution?}` rows — and every case decodes one. Wave 5 put a hand-written literal on each
 * side of the metrics wire, both sides passed, and two cards drew nothing against a real broker for
 * a whole milestone. A literal typed beside an assertion asserts that the author typed the same
 * thing twice.
 *
 * The half a browser packet cannot keep alone is that these documents were written from
 * `AlertDtos.scala` and `AlertsEndpoints.scala` rather than captured from a running service. That is
 * disclosed in the packet's report, and it is why {@link feedSection} **refuses** a shape it does
 * not recognise rather than defaulting: a service that answers something else makes these cases
 * fail instead of making the screen quietly empty.
 */
import { describe, expect, it } from "vitest";
import { flush } from "solid-js";

import { mount, findViolations, describeViolations } from "./testing.js";
import {
  AlertsFeed,
  RuleReports,
  acknowledgedBy,
  categoryWords,
  feedSection,
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
  NO_OPEN_COUNT,
  type AlertsFeedPage,
} from "./index.jsx";
import openDocument from "./documents/events-open.json" with { type: "json" };
import pagedDocument from "./documents/events-paged.json" with { type: "json" };
import emptyDocument from "./documents/events-empty.json" with { type: "json" };
import neverEvaluatedDocument from "./documents/events-never-evaluated.json" with { type: "json" };
import notConfiguredDocument from "./documents/events-not-configured.json" with { type: "json" };
import unavailableDocument from "./documents/events-unavailable.json" with { type: "json" };
import unknownDocument from "./documents/events-unknown-vocabulary.json" with { type: "json" };
import darkRuleDocument from "./documents/events-dark-rule.json" with { type: "json" };

/** A clock the cases hold still, so a relative age is a fact rather than a race. */
const NOW = new Date("2026-03-04T09:41:00Z");

/** The page inside an `ok` document, or a failure naming what came back instead. */
function pageOf(document: unknown): AlertsFeedPage {
  const section = feedSection(document);
  if (section.status !== "ok") throw new Error(`expected an ok section, got ${section.status}`);
  return section.data;
}

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

describe("the alerts wire", () => {
  it("decodes a whole events document into the page the screen draws", () => {
    const page = pageOf(openDocument);
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

  it("reads a resolution as the two facts it is, and never as a boolean", () => {
    const page = pageOf(openDocument);
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

  it("reports a document it cannot read as unreadable, and never as an empty feed", () => {
    /*
     * The exact shape wave 5's metrics wire failed in: the section decodes, the payload does not,
     * and an empty array is the most convincing possible way to say nothing is wrong. It is also
     * the shape this feed would arrive in if the service renamed `items` — which is why the
     * refusal, and not a default, is what this build does with it.
     */
    const renamed = { events: { status: "ok", fetchedAt: "2026-03-04T09:41:00Z", data: { rows: [] } } };
    expect(feedSection(renamed).status).toBe("unreadable");
    expect(feedSection({ alerts: {} }).status).toBe("unreadable");
    expect(feedSection(null).status).toBe("unreadable");
  });

  it("carries the section's own status through untouched", () => {
    expect(feedSection(notConfiguredDocument).status).toBe("not_configured");
    const unavailable = feedSection(unavailableDocument);
    expect(unavailable.status).toBe("unavailable");
    if (unavailable.status === "unavailable") {
      expect(unavailable.reason.code).toBe("KUI-UPSTREAM-UNAVAILABLE");
    }
  });

  it("refuses a feed containing a row it cannot date rather than dating it now", () => {
    const section = feedSection({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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
    expect(section.status).toBe("unreadable");
  });

  it("requires a resolution time and never invents how it closed", () => {
    const page = pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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
    const noKind = pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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

  it("refuses a count that is not a finite number rather than printing what it is", () => {
    /*
     * `1e999` is a legal JSON number and `JSON.parse` answers `Infinity` for it — as would a
     * misconfigured encoder writing an overflowed counter. Drawn, it reads "Infinity open"; folded
     * into arithmetic it reads "NaN open". Both are worse than the card saying it does not know.
     */
    const page = pageOf(
      JSON.parse(
        '{"events":{"status":"ok","fetchedAt":"2026-03-04T09:41:00Z","data":' +
          '{"items":[],"openCount":1e999,"evaluatedAt":"2026-03-04T09:40:00Z"}}}',
      ),
    );
    expect(page.openCount).toBeNull();
  });

  it("keeps every count absent rather than zero when the document did not carry it", () => {
    const page = pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
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
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed wide state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    expect(container.querySelector(".kui-alerts--wide")).not.toBeNull();
    // Same rows, two layouts (§3.8). Two components drawing one feed is two places for the sentence
    // under an empty one to drift apart.
    expect(rowsIn(container)).toHaveLength(5);
    dispose();
  });

  it("draws a resolved event in the tone the service derived, not the one its severity implies", async () => {
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    /*
     * `evt-5` opened `critical` and is resolved, and the service sends `tone: "success"` for it:
     * a resolved row is drawn in the success tone whatever it opened at, which is §3.8's fourth dot
     * and is decided in `AlertEvent.tone` on the server. A browser that re-derived the tone from the
     * severity would paint this row red and tell an operator an incident is running.
     */
    const resolved = pageOf(openDocument).items.find((event) => event.id === "evt-5");
    expect(resolved?.severity).toBe("critical");
    expect(toneOfRow(rowFor(container, "evt-5"))).toBe("success");
    dispose();
  });

  it("says in words that a severity and a category are ones it does not recognise", async () => {
    const page = pageOf(unknownDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
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
   * This packet's owned rule.
   *
   * The document is a page: seven events are open in the store, three rows are on this page, and
   * two of those three are open. Any arithmetic over the rows the card holds answers 2 or 3, and
   * the only way to draw 7 is to read the service's own figure. The mutation the packet was set —
   * make the card recompute the open count from the rows it holds — reddens exactly this case.
   */
  it("the open count on the card is the API's own figure", async () => {
    const page = pageOf(pagedDocument);
    expect(page.openCount).toBe(7);
    expect(page.items).toHaveLength(3);
    expect(page.items.filter(isOpen)).toHaveLength(2);

    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    expect(pillIn(container)?.textContent).toContain("7 open");
    expect(rowsIn(container)).toHaveLength(3);
    dispose();
  });

  it("keeps the service's figure when a filter has removed rows from the page", async () => {
    const page = pageOf(pagedDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={{ kind: "ready", value: page }}
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
    const page = pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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
    });
    expect(page.openCount).toBeNull();
    expect(openPill(page)).toBeUndefined();

    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    expect(pillIn(container)).toBeNull();
    expect(container.textContent).toContain(NO_OPEN_COUNT);
    expect(container.textContent).not.toContain("0 open");
    dispose();
  });

  it("draws a measured zero as words, and an unevaluated cluster as neither", () => {
    expect(openPill(pageOf(emptyDocument))).toEqual({ text: "None open", tone: "success" });
    expect(openPill(pageOf(pagedDocument))).toEqual({ text: "7 open", tone: "danger" });
    // The one that matters: rules that have never run here have not established that nothing is
    // wrong, so the pill says what it is instead of drawing a reassuring zero.
    expect(openPill(pageOf(neverEvaluatedDocument))).toEqual({
      text: "Not evaluated yet",
      tone: "neutral",
    });
  });
});

describe("the states this card has", () => {
  it("a feed that answered with no events says so and draws no count", async () => {
    const page = pageOf(emptyDocument);
    expect(page.items).toHaveLength(0);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    expect(container.textContent).toContain(NO_EVENTS);
    // Not "None open" either: the sentence is the answer, and a pill beside it is a second one.
    expect(pillIn(container)).toBeNull();
    expect(rowsIn(container)).toHaveLength(0);
    dispose();
  });

  it("an empty feed the rules never ran on says that, and never that the cluster is well", async () => {
    const page = pageOf(neverEvaluatedDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
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
    expect(feedSection(notConfiguredDocument).status).toBe("not_configured");
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

  it("draws a stale answer with its reason above the rows and no invented code", async () => {
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={{
          kind: "stale",
          value: page,
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

  it("says nothing on this page matches when the filter empties it", async () => {
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed
        state={{ kind: "ready", value: page }}
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
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} onAcknowledge={() => {}} now={() => NOW} />
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

  it("dates each row against the clock it was given, and says whose observation it is", async () => {
    const page = pageOf(pagedDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} now={() => NOW} />
    ));
    await flush();

    // "KUI noticed", not "opened": a broker publishes no "this partition went offline at", so after
    // a restart this age is an age since the restart. The row says which it is.
    expect(rowFor(container, "evt-1")?.textContent).toContain("KUI noticed 13m ago");
    dispose();
  });

  it("has no accessibility violations in the state an operator meets on a bad afternoon", async () => {
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => (
      <AlertsFeed state={{ kind: "ready", value: page }} onAcknowledge={() => {}} now={() => NOW} />
    ));
    await flush();

    const violations = await findViolations(container);
    expect(describeViolations(violations)).toBe("");
    dispose();
  });
});

describe("the filter, which is over the page", () => {
  it("keeps an unrecognised severity out of every severity filter but 'all'", () => {
    const page = pageOf(unknownDocument);
    expect(filterEvents(page.items, { severity: "all", state: "all" })).toHaveLength(1);
    expect(filterEvents(page.items, { severity: "critical", state: "all" })).toHaveLength(0);
    // It is still open, so the lifecycle filter finds it: the one event nobody understands must not
    // be the one event nobody sees.
    expect(filterEvents(page.items, { severity: "all", state: "open" })).toHaveLength(1);
  });

  it("separates open from resolved on the resolution field alone", () => {
    const page = pageOf(openDocument);
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
    const page = pageOf(darkRuleDocument);
    const dark = page.rules.find((report) => report.rule === "log-directory-usage");
    expect(dark?.status).toBe("unavailable");
    // Not zero. `ok` with `openEvents: 0` is a measurement; `unavailable` is the absence of one.
    expect(dark?.openEvents).toBeNull();
    expect(ruleRefusal(dark!)).toContain("could not evaluate");

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = [...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]')].find(
      (candidate) => candidate.dataset["rule"] === "log-directory-usage",
    );
    expect(row?.textContent).toContain("Not evaluated");
    expect(row?.textContent).toContain("describeLogDirs was refused");
    expect(row?.textContent).not.toContain("Nothing open");
    dispose();
  });

  it("names the subjects a rule skipped rather than folding them into its figure", async () => {
    const page = pageOf(openDocument);
    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const storage = [...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]')].find(
      (candidate) => candidate.dataset["rule"] === "log-directory-usage",
    );
    // "1 open event" and "2 subjects skipped" are two facts, and the second is what makes the first
    // readable: nothing above 80% *of the directories whose capacity this cluster reports*.
    expect(storage?.textContent).toContain("1 open event.");
    expect(storage?.textContent).toContain("2 subjects were skipped");

    const replication = [
      ...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]'),
    ].find((candidate) => candidate.dataset["rule"] === "under-replicated-partitions");
    expect(replication?.textContent).toContain("Nothing open.");
    dispose();
  });

  it("says a rule this deployment does not run is not running, and not that it found nothing", async () => {
    const page = pageOf(darkRuleDocument);
    const absent = page.rules.find((report) => report.rule === "connector-task-failure");
    expect(absent?.status).toBe("not_configured");
    expect(absent?.openEvents).toBeNull();

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = [...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]')].find(
      (candidate) => candidate.dataset["rule"] === "connector-task-failure",
    );
    // A rule that is not configured and a rule that could not look are different sentences, for the
    // reason `Fetched` keeps `not-configured` apart from `failed`: one of them is nothing being
    // wrong, and telling an operator to investigate it wastes an afternoon.
    expect(row?.textContent).toContain("does not run the connector-task-failure rule");
    expect(row?.textContent).not.toContain("could not evaluate");
    expect(row?.textContent).not.toContain("Nothing open");
    dispose();
  });

  it("says a rule answered no figure rather than reading it as nothing found", async () => {
    const page = pageOf({
      events: {
        status: "ok",
        fetchedAt: "2026-03-04T09:41:00Z",
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
              evaluation: { status: "ok", fetchedAt: "2026-03-04T09:40:00Z", data: {} },
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
