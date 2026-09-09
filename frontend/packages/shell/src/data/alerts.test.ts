/**
 * The shell's end of the alert feed, as a fold over plain data.
 *
 * Everything here is decided without a DOM and without a server, which is the point: the addresses
 * the shell asks for, the badge the drawer draws, and the six ways one `Fetched<AlertFeed>` becomes
 * something the notifications panel can render. `app.render.test.tsx` asserts the two halves meet
 * over a real client — the URL that actually reached `fetch`, and the count that actually reached
 * the bell — and this file asserts what each half decides.
 *
 * The division matters because of what wave 5 shipped: two sides of one wire, each unit-tested
 * against its own hand-written shape, both green, and a card drawing "the metrics source named no
 * producers" over a source that had named five. Cases like these are necessary and they are not
 * sufficient, which is why the end-to-end case exists beside them rather than instead of them.
 */
import { describe, expect, it } from "vitest";
import type { AlertEvent, AlertFeed, Fetched } from "@kui/kernel";

import {
  ALERTS_FEED_PATH,
  alertsBadge,
  alertsStreamUrl,
  noticeGlyphOf,
  noticeToneOf,
  noticesOf,
  openCountOf,
} from "./alerts.js";

/**
 * One row, in exactly the shape `AlertEventDto` encodes it.
 *
 * Four fields where two would do — `severity` beside `tone`, `category` beside `glyph` — because
 * that is what the server sends and why: the derived halves travel so the bell, the card and the
 * panel cannot map them differently, and because a resolved row's tone is not a function of its
 * severity at all.
 */
const event = (over: Partial<AlertEvent> = {}): AlertEvent => ({
  id: "evt-1",
  severity: "warning",
  tone: "warning",
  category: "rebalance",
  glyph: "rebalance",
  openedAt: "2026-09-06T08:00:00.000Z",
  lastSeenAt: "2026-09-06T08:05:00.000Z",
  title: "orders-consumers has been rebalancing for 4m",
  detail: "12 members",
  resolution: undefined,
  ...over,
});

const feed = (over: Partial<AlertFeed> = {}): AlertFeed => ({
  items: [event()],
  total: 1,
  openCount: 1,
  unreadCount: 1,
  lastReadAt: undefined,
  evaluatedAt: "2026-09-06T08:05:00.000Z",
  rules: [],
  ...over,
});

const ready = (over: Partial<AlertFeed> = {}): Fetched<AlertFeed> => ({
  kind: "ready",
  value: feed(over),
});

describe("the addresses the shell asks the alerts service for", () => {
  /**
   * Both spellings, in one place, because neither has a compiler behind it.
   *
   * `frontend/packages/api/src/schema.d.ts` carries no alerts path until `docs/api/**` has been
   * regenerated, so the read is made through a widened `get` and the stream — like every stream —
   * is a string the browser is handed. M8's exit criterion in `docs/plan/ROADMAP.md:471-476` is
   * written in `jq` against `.events.data.items`, which is what fixes the read's shape; the path
   * itself is wave 6's `/api/v1/clusters/{clusterId}/alerts/…` contract row.
   */
  it("names the feed and its stream under the cluster the frame is describing", () => {
    expect(ALERTS_FEED_PATH).toBe("/api/v1/clusters/{clusterId}/alerts/events");
    expect(alertsStreamUrl("/api/v1", "prod-kyiv-01")).toBe(
      "/api/v1/clusters/prod-kyiv-01/alerts/stream",
    );
  });

  it("tolerates a deployment whose API base carries a trailing slash or a prefix", () => {
    /* `bootstrap.apiBase` is whatever the deployment injected, and a doubled slash is a path the
       gateway does not route — the same defect a hard-coded root link caused behind a reverse
       proxy once already. */
    expect(alertsStreamUrl("/kui/api/v1/", "prod")).toBe("/kui/api/v1/clusters/prod/alerts/stream");
  });

  it("encodes a cluster id that is not URL-safe", () => {
    // Cluster ids come from an operator's configuration file, not from a validator.
    expect(alertsStreamUrl("/api/v1", "eu west/1")).toBe(
      "/api/v1/clusters/eu%20west%2F1/alerts/stream",
    );
  });
});

/**
 * The drawer's Alerts badge.
 *
 * Three inputs and two outputs, and the whole rule is which of the three produce nothing. A `0` and
 * a `null` draw the same absence for opposite reasons — nothing is open, and nobody has said — and
 * the drawer cannot express the difference in a badge, which is why the bell's accessible name is
 * where it is expressed instead.
 */
describe("the figure the drawer's Alerts row carries", () => {
  it("is the count the server sent, drawn neutrally", () => {
    /* Neutral, and a `total` rather than a `defect`. A `defect` carries a severity, and the only
       severity available here would be folded from whichever events this browser happens to hold —
       the same page-shaped guess the count rule refuses. `2 open` in amber over a page that
       excludes the one critical event is a worse answer than `2` in grey. */
    expect(alertsBadge(7)).toEqual({ kind: "total", value: 7, noun: "open" });
    expect(alertsBadge(1)).toEqual({ kind: "total", value: 1, noun: "open" });
  });

  it("is no badge at all for nothing open, and no badge for a count nobody sent", () => {
    // Not a `0`: a permanently present marker is a marker nobody looks at, and an unknown printed
    // as a zero is a statement about the cluster that nothing measured.
    expect(alertsBadge(0)).toBeUndefined();
    expect(alertsBadge(null)).toBeUndefined();
    expect(alertsBadge(Number.NaN)).toBeUndefined();
    expect(alertsBadge(-3)).toBeUndefined();
  });
});

/**
 * The count the bell is allowed to draw, which is not always the count the feed carries.
 *
 * `evaluatedAt` is the field that turns a number into a measurement. Absent, the service's rules
 * have never run over this cluster here, and `openCount: 0` says only that nothing has been looked
 * at — so drawing it is a green claim about a cluster nobody has swept. Present, a `0` is a real
 * zero and is drawn as one.
 */
describe("the open count the bell is allowed to draw", () => {
  it("is the server's figure once the rules have run, zero included", () => {
    expect(openCountOf(ready({ openCount: 7 }))).toBe(7);
    /* A measured zero **is** drawn, and that is the other half of the rule: the bell then says "no
       open alerts", which is a statement this deployment can support. */
    expect(openCountOf(ready({ items: [], openCount: 0 }))).toBe(0);
  });

  it("is not known for a feed whose rules have never run, however well-formed it is", () => {
    expect(openCountOf(ready({ items: [], openCount: 0, evaluatedAt: undefined }))).toBeNull();
    /* Even a positive count is untrustworthy without it — though in practice the service cannot
       produce one, which is why the zero above is the case that matters. */
    expect(openCountOf(ready({ openCount: 7, evaluatedAt: undefined }))).toBeNull();
  });

  it("is not known for any state that holds no feed at all", () => {
    expect(openCountOf({ kind: "loading" })).toBeNull();
    expect(openCountOf({ kind: "forbidden" })).toBeNull();
    expect(openCountOf({ kind: "not-configured" })).toBeNull();
    expect(openCountOf({ kind: "failed", message: "no", code: "KUI-X" })).toBeNull();
  });

  it("still answers for a stale feed, which is old and not absent", () => {
    /* Stale is data KUI really received and is showing under a warning; refusing to count it would
       blank the bell over an outage, which is when the count matters most. */
    const stale: Fetched<AlertFeed> = {
      kind: "stale",
      value: feed({ openCount: 4 }),
      reason: "The stream closed.",
    };
    expect(openCountOf(stale)).toBe(4);
  });
});

/**
 * The tone and the glyph one row is drawn with, neither of which anything in the browser derives.
 *
 * `services/alerts` sends four fields where two would do — `severity` beside `tone`, `category`
 * beside `glyph` — and `AlertEventDto`'s scaladoc says why: the derived halves travel so that the
 * bell, the card and this panel cannot map them differently, and because a resolved row's tone is
 * not a function of its severity at all. `@kui/kernel` carries all four verbatim and folds nothing.
 *
 * So what is asserted below is a **name match and its failure**: a word this build has a colour or
 * a picture for is used, and a word it does not is drawn as unrecognised rather than as the nearest
 * thing it knows. That failure is the interesting half — it is the one that keeps a rule
 * `services/alerts` ships after this bundle was built from arriving dressed as something else.
 */
describe("the tone and the glyph an alert row is drawn with", () => {
  it("takes the tone the server derived, by name, and folds nothing", () => {
    /* `AlertSeverity` in `services/alerts/domain` sends `warning` for `warning` and `danger` for
       `critical`; the mapping from one to the other is the server's, and re-deriving it here is
       what `AlertEventDto`'s scaladoc says the second field exists to prevent. */
    expect(noticeToneOf(event({ severity: "warning", tone: "warning" }))).toBe("warning");
    expect(noticeToneOf(event({ severity: "critical", tone: "danger" }))).toBe("danger");
  });

  it("draws a resolved row in the tone the server sent for it, not in the one it opened at", () => {
    /* `AlertEvent.tone` in the domain answers `success` for any resolved row whatever its severity
       — §3.8's fourth dot, and the reason `AlertSeverity` has no `Success` case. A closed critical
       event still painted red is how a feed of five reads as five ongoing emergencies when two of
       them are already fixed, which is exactly what §3.8 says the pill count is smaller than the
       row count for. */
    const resolved = event({
      severity: "critical",
      tone: "success",
      resolution: { at: "2026-09-06T09:00:00.000Z", kind: "cleared", by: undefined },
    });
    expect(noticeToneOf(resolved)).toBe("success");
  });

  it("draws a tone this build has no colour for as unrecognised, and never as info", () => {
    /* The whole point of the words travelling rather than a table. `info` is a claim that something
       is not serious, and a later `services/alerts` shipping a `blocker` tone must not have that
       claim made on its behalf; `danger` would be the same invention in the other direction. */
    expect(noticeToneOf(event({ tone: "blocker" }))).toBe("unknown");
    expect(noticeToneOf(event({ tone: "" }))).toBe("unknown");
  });

  it("takes the glyph the server derived, and drops one it has no picture for", () => {
    /* All four of `AlertCategory`'s words have a mark, and the two that arrive as the same
       `warning` tone — a partition with no leader and a partition short of replicas — have two
       different ones. That is §3.9's whole correction, at the seam where it can go wrong. */
    expect(noticeGlyphOf(event({ category: "partition", glyph: "partition" }))).toBe("partition");
    expect(noticeGlyphOf(event({ category: "replication", glyph: "replication" }))).toBe(
      "replication",
    );
    expect(noticeGlyphOf(event({ category: "storage", glyph: "storage" }))).toBe("storage");
    expect(noticeGlyphOf(event({ category: "rebalance", glyph: "rebalance" }))).toBe("rebalance");
    // A rule shipped after this bundle was built. The fallback is the tone's own mark, which claims
    // only that a notification happened.
    expect(noticeGlyphOf(event({ category: "quorum", glyph: "quorum" }))).toBeUndefined();
    // And an event the server sent no glyph for at all, which is a different way of arriving at
    // the same rendering and is a state the wire really has.
    expect(noticeGlyphOf(event({ category: undefined, glyph: undefined }))).toBeUndefined();
  });
});

/**
 * One feed, and the six things the panel can be told.
 *
 * The two worth arguing about are the two that draw no rows and are not the same: a deployment with
 * no alerts service has established nothing about the cluster, so it must not be handed the "the
 * cluster has been quiet" sentence, and a refusal must not be handed a retry.
 */
describe("the alert feed as the notifications panel is given it", () => {
  const HREF = "/ui/clusters/prod/alerts";

  it("carries every row, with the detail as the body and the opened-at as the age", () => {
    const drawn = noticesOf(ready(), HREF);
    expect(drawn.kind).toBe("ready");
    const notices = drawn.kind === "ready" ? drawn.notices : [];
    expect(notices).toHaveLength(1);
    expect(notices[0]).toMatchObject({
      id: "evt-1",
      severity: "warning",
      category: "rebalance",
      title: "orders-consumers has been rebalancing for 4m",
      body: "12 members",
      href: HREF,
    });
    /* `openedAt` and not `lastSeenAt`: the age on this row is how long the condition has been
       open, which is the figure §3.8 draws at the right edge. `lastSeenAt` answers a different
       question — still happening, or opened and not checked since — and putting it here would make
       every row read as new on every scrape. */
    expect(notices[0]?.at.toISOString()).toBe("2026-09-06T08:00:00.000Z");
  });

  it("marks a row read only when this principal's marker has reached it", () => {
    const marker = "2026-09-06T08:30:00.000Z";
    const before = event({ id: "old", openedAt: "2026-09-06T08:00:00.000Z" });
    const after = event({ id: "new", openedAt: "2026-09-06T09:00:00.000Z" });
    const drawn = noticesOf(ready({ items: [before, after], lastReadAt: marker }), undefined);
    const notices = drawn.kind === "ready" ? drawn.notices : [];
    expect(notices.map((notice) => notice.read)).toEqual([true, false]);

    /* No marker is a principal who has read *nothing*, and emphatically not one who has read
       everything: a bell that started quiet for somebody who has never opened it is a bell that
       never tells them anything. */
    const unread = noticesOf(ready({ items: [before] }), undefined);
    expect(unread.kind === "ready" ? unread.notices[0]?.read : true).toBe(false);
  });

  it("shows a stale feed with its reason rather than hiding it", () => {
    const stale = { kind: "stale", value: feed(), reason: "The stream closed." } as const;
    const drawn = noticesOf(stale, undefined);
    expect(drawn).toMatchObject({ kind: "stale", reason: "The stream closed." });
    expect(drawn.kind === "stale" ? drawn.notices : []).toHaveLength(1);
  });

  it("keeps the stable code beside a failure's sentence", () => {
    // The sentence is what a person reads; the `KUI-` code is the part that survives being pasted
    // into a ticket, and it is the only part support can search for.
    const drawn = noticesOf(
      {
        kind: "failed",
        message: "The alerts service did not answer.",
        code: "KUI-UPSTREAM-UNAVAILABLE",
      },
      undefined,
    );
    expect(drawn).toEqual({
      kind: "failed",
      reason: "The alerts service did not answer. (KUI-UPSTREAM-UNAVAILABLE)",
    });
  });

  it("says a refusal is a refusal, and does not offer to retry it", () => {
    /* `forbidden` reaches the panel as a sentence and the caller withholds `onRetry` for it —
       asserted where that decision is made, in `app.render.test.tsx`. What is asserted here is that
       it does not arrive as an empty list, which would read as a cluster with nothing wrong. */
    const drawn = noticesOf({ kind: "forbidden" }, undefined);
    expect(drawn.kind).toBe("failed");
    expect(drawn.kind === "failed" ? drawn.reason : "").toContain("permission");
  });

  it("says a deployment with no alerts service has no alerts service, not a quiet cluster", () => {
    const drawn = noticesOf({ kind: "not-configured" }, undefined);
    expect(drawn.kind).toBe("not_configured");
    const reason = drawn.kind === "not_configured" ? drawn.reason : "";
    expect(reason).toContain("runs no alerts service");
    /* The sentence the empty-`ready` panel draws, which would be a claim about a cluster nobody
       has watched. This is the whole reason `not_configured` is a case of its own. */
    expect(reason).not.toContain("quiet");
  });

  it("is loading while nothing has come back, which is not the same as nothing being there", () => {
    expect(noticesOf({ kind: "loading" }, undefined)).toEqual({ kind: "loading" });
  });
});
