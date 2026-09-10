/**
 * The shell's end of the alert feed: the two addresses it is read from, and the one badge the
 * drawer draws out of it.
 *
 * ## Why there is a file here at all, when the store is the kernel's
 *
 * `createAlerts` holds the feed and every rule about it — what an unreadable frame does, what a
 * `null` open count means, how the read marker is compared. What it deliberately does not hold is
 * *where the feed comes from*: it takes `load` and `openStream` as functions, because the kernel
 * has no API client, no bootstrap and no idea which cluster the frame is describing. Those three
 * facts live in `App.tsx`, and this file is the seam between them — one place that spells the two
 * addresses, so that the bell, the drawer's badge and the alerts screen cannot end up reading three
 * different URLs.
 *
 * ## The addresses
 *
 * `GET /api/v1/clusters/{clusterId}/alerts/events` is the `Section`-wrapped read and
 * `/api/v1/clusters/{clusterId}/alerts/stream` is its ADR-035 stream. ADR-053 owns the feed shape;
 * the generated browser schema owns the public path and parameter spellings.
 *
 * The stream address needs nothing from the generated schema — `openEventSource` takes a string,
 * exactly as the capability stream does. The feed and acknowledgement paths are in the generated
 * client schema, so their path and parameter spellings are checked at their call sites.
 *
 * What stands in for the compiler in the meantime is
 * `app.render.test.tsx`'s "asks the alerts service for this cluster's feed", which mounts the real
 * application over a stubbed gateway and asserts the **URL the shell actually requested**. A
 * spelling that drifts from ADR-053's is then a red case rather than a card that quietly draws
 * nothing — which is precisely how wave 5's producers wire reached a released build.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";
import type { AlertEvent, AlertFeed, Fetched } from "@kui/kernel";
import type {
  Notice,
  NoticeCategory,
  NoticeFeed,
  NoticeSeverity,
} from "../chrome/Notifications.jsx";
import type { NavCount } from "../chrome/types.js";

/**
 * The feed read, as a template with the cluster still in it. One spelling, one file.
 *
 * Module-private. It was exported and the only thing that ever named it was a case asserting the
 * constant against a copy of itself, which is a rule that cannot fail for any reason a reader would
 * care about. What pins the address is what {@link loadAlertFeed} actually hands the client —
 * asserted in `alerts.test.ts` through the function, and end to end off the real `Request` in
 * `app.render.test.tsx`.
 */
const ALERTS_FEED_PATH = "/api/v1/clusters/{clusterId}/alerts/events";

/**
 * The query that asks the server to mark the feed read for this principal while it answers.
 *
 * `AlertsEndpoints.MarkReadParam` — one endpoint and one query rather than a second address,
 * because a read and a read-and-mark differ in what the *server* records and in nothing the
 * browser draws. Two addresses would be two spellings of one page.
 *
 * Module-private, and it was exported. Nothing outside this file ever named it, in production or in
 * a test, and an export with no caller is a promise this module is not being asked for: the two
 * spellings that matter are the ones {@link loadAlertFeed} sends, which
 * `app.render.test.tsx`'s "asks the alerts service for this cluster's feed" reads back off the URL
 * the client actually built.
 */
const ALERTS_MARK_READ_PARAM = "markRead";

/**
 * The feed's ADR-035 stream, relative to the deployment's API base.
 *
 * Module-private for the same reason as the query above: `alertsStreamUrl` is the caller, the
 * address it produces is asserted end to end, and the segment on its own was named by nothing.
 */
const ALERTS_STREAM_SEGMENT = "alerts/stream";

/**
 * The read, through the generated API client and emphatically not through a bare `fetch`.
 *
 * Going around the client would go around the session gate, the CSRF settlement and the
 * `onUnauthorized` hook that empties the session when the gateway says it has lapsed — three rules
 * that are stated once, in `createApiClient`, precisely so that no call site has to remember them.
 * The answer is intentionally exposed to the store as `unknown`; the kernel decoder remains the
 * one place that accepts or refuses the payload.
 */
export function loadAlertFeed(
  api: KuiApiClient,
  cluster: string,
  markRead: boolean,
): Promise<ApiResult<unknown>> {
  return api.get(ALERTS_FEED_PATH, {
    params: {
      path: { clusterId: cluster },
      /* Always sent, both ways round. The endpoint declares the query, and a request that omitted
         it on the read path and supplied it on the mark path would be two shapes of one call — the
         second of which nothing had ever exercised until an operator pressed "Mark all read". */
      query: { [ALERTS_MARK_READ_PARAM]: markRead },
    },
  });
}

/** Where the feed's stream is, for the deployment's own API base. */
export function alertsStreamUrl(apiBase: string, cluster: string): string {
  const base = apiBase.replace(/\/$/, "");
  return `${base}/clusters/${encodeURIComponent(cluster)}/${ALERTS_STREAM_SEGMENT}`;
}

/**
 * The figure the drawer's Alerts row carries, or `undefined` when it carries none.
 *
 * **The server's own count, and never a fold over the rows the browser holds.** The feed is paged
 * and this badge is not: a count recomputed from a page would be a smaller number wearing the same
 * badge, and it would disagree with the bell and the alerts card, which both read the store's
 * `openCount`. ADR-053 §7 makes the service's aggregate the one count every reader uses.
 *
 * `null` — nobody has said — and `0` both draw **no badge**, for the two different reasons the
 * whole `NavCount` vocabulary exists to keep apart: an unknown count must never be printed as a
 * zero, and a zero defect is a permanently present marker that nobody looks at. The drawer cannot
 * say which of the two it is in a badge, so it says nothing; the bell's accessible name is where
 * that distinction is drawn, because a sentence can hold it and a badge cannot.
 *
 * A `total` and not a `defect`, which is the one judgement here. `defect` carries a severity, and
 * the only severity available to this row would be folded from the events the browser is holding —
 * the same page-shaped fold the paragraph above refuses for the count. So the row states a
 * quantity, neutrally, and the severity stays where the server put it: on the individual rows, in
 * the tone of each one. A badge that inferred `danger` from a page might be inferring it from a
 * page that happens to exclude the danger.
 */
export function alertsBadge(openCount: number | null): NavCount | undefined {
  if (openCount === null || !Number.isFinite(openCount) || openCount <= 0) return undefined;
  return { kind: "total", value: openCount, noun: "open" };
}

/*
 * There was a second open count here, and it is gone.
 *
 * `openCountOf(feed)` re-derived the bell's figure from the `Fetched<AlertFeed>` this file could
 * see, under its own reading of the `evaluatedAt` rule, while the kernel store's `openCount()` did
 * the same thing over the same feed **and** over the count the newest stream frame carried. Two
 * derivations of one number is the shape this whole store exists to prevent: they agreed on the
 * feed and disagreed the moment a frame arrived, because only the kernel's had ever heard of
 * `streamed`. Nothing in the product called this one — it had a test and no caller — so the
 * kernel's accessor is the number, and the drawer's badge, the bell and the dashboard's alerts card
 * all read `Alerts.openCount()`.
 *
 * The rule itself is unchanged and still stated where it is now applied
 * (`@kui/kernel`'s `knownOpenCount`): a zero the service's rules have never produced is not a zero,
 * so an absent `evaluatedAt` answers `null` however well-formed the feed is.
 */

/**
 * The alert feed as the notifications panel draws it.
 *
 * ## Why the panel is the feed, and not a second source
 *
 * The bell and the panel under it were wired to a hard-coded `{ kind: "ready", notices: [] }` for
 * three waves, with a comment saying there was no notification service yet. There is one now, and
 * it is this feed: `SCREENS-V4.md` §4.16 puts the panel in the *frame* rather than on a page for
 * exactly this reason, and §3.9's four items are an opened-at, a severity, a category, a title and
 * a body — an `AlertEvent`, field for field. A bell counting one feed over a panel listing another
 * would be two answers to one question, which is the failure this whole store exists to prevent.
 *
 * ## The six states, and the two that are easy to collapse
 *
 * `not-configured` becomes its own `NoticeFeed` case rather than an empty list: "the cluster has
 * been quiet" is a claim about the cluster that a deployment running no alerts service has not
 * established. `forbidden` becomes a sentence with **no retry**, because retrying a permission
 * cannot help and a Try-again button under a refusal teaches operators to press it.
 *
 * ## Nothing here derives a tone or a glyph
 *
 * `services/alerts` sends four fields where a browser might expect two — `severity` *and* `tone`,
 * `category` *and* `glyph* — and `AlertEventDto`'s own scaladoc says why: the derived halves travel
 * so that the bell, the card and this panel cannot each map them differently, and because the
 * resolved rendering is not a function of severity at all (`AlertEvent.tone` in the domain answers
 * `success` for a resolved row whatever it opened at). So this file **matches words by name and
 * derives nothing**. A word it has no picture or colour for is drawn as unrecognised, which is what
 * both the kernel's vocabulary and house rule 7 ask for: a rule the service grows later must reach
 * the screen labelled unknown rather than dressed as something else.
 */
export function noticesOf(feed: Fetched<AlertFeed>, href: string | undefined): NoticeFeed {
  switch (feed.kind) {
    case "loading":
      return { kind: "loading" };
    case "ready":
      return { kind: "ready", notices: noticeRows(feed.value, href) };
    case "stale":
      return { kind: "stale", notices: noticeRows(feed.value, href), reason: feed.reason };
    case "failed":
      /* The code beside the sentence, because a `KUI-` code is the one part of a failure that
         survives being copied into a ticket. The message is already something a person can read;
         what it cannot carry is the identifier support will ask for. */
      return { kind: "failed", reason: `${feed.message} (${feed.code})` };
    case "forbidden":
      return { kind: "failed", reason: "You do not have permission to see this cluster's alerts." };
    case "not-configured":
      return {
        kind: "not_configured",
        reason: "This deployment runs no alerts service, so KUI has nothing to notify you about.",
      };
  }
}

function noticeRows(feed: AlertFeed, href: string | undefined): readonly Notice[] {
  return feed.items.map((event) => noticeOf(event, feed.lastReadAt, href));
}

/**
 * The tones this build has a colour for, matched against the word the server sent.
 *
 * `services/alerts`' `AlertVocabulary.scala` sends `warning` and `danger` for its two severities
 * and `success` for any resolved row; `info` is here because the design's first dot is one and
 * because a later severity may take it. Nothing is folded: a word that is not on this list draws
 * the neutral tile, which is the one honest answer for "this build has no colour for that".
 */
const NOTICE_TONES: ReadonlySet<string> = new Set<NoticeSeverity>([
  "info",
  "success",
  "warning",
  "danger",
]);

/**
 * The glyph words this build has a picture for, matched against the word the server sent.
 *
 * `AlertCategory` sends `partition`, `replication`, `rebalance` and `storage` today; the other
 * three here are the panel's own older vocabulary, kept because the panel is not only the alert
 * feed's. A word that is not on this list draws the tone's fallback mark, which claims only that a
 * notification happened — and `Notifications.tsx` argues at length that this is better than a disk
 * icon over a rebalance, which is a confident lie about what broke.
 */
const NOTICE_GLYPHS: ReadonlySet<string> = new Set<NoticeCategory>([
  "partition",
  "replication",
  "rebalance",
  "storage",
  "connector",
  "schema",
  "cluster",
  "topic",
  "security",
]);

/** The tone the server derived, when this build has a colour for it. Never a fold over severity. */
export function noticeToneOf(event: AlertEvent): NoticeSeverity {
  return NOTICE_TONES.has(event.tone) ? (event.tone as NoticeSeverity) : "unknown";
}

/**
 * The glyph the server derived, when this build has a picture for it.
 *
 * `undefined` covers both ways of having none, and they are the same rendering for two different
 * reasons: the server sent no glyph at all — a real case, which the kernel keeps as `undefined`
 * rather than inventing a stand-in — or it sent a word this build has no picture for. Either way
 * the row falls back to the tone's own mark, which claims only that a notification happened.
 */
export function noticeGlyphOf(event: AlertEvent): NoticeCategory | undefined {
  const glyph = event.glyph;
  return glyph !== undefined && NOTICE_GLYPHS.has(glyph) ? (glyph as NoticeCategory) : undefined;
}

function noticeOf(
  event: AlertEvent,
  lastReadAt: string | undefined,
  href: string | undefined,
): Notice {
  const category = noticeGlyphOf(event);
  return {
    id: event.id,
    severity: noticeToneOf(event),
    ...(category === undefined ? {} : { category }),
    title: event.title,
    ...(event.detail === undefined ? {} : { body: event.detail }),
    at: new Date(event.openedAt),
    /* The server's own marker, compared the way the server writes it: `lastReadAt` and `openedAt`
       are `Z`-normalised fixed-width RFC 3339, so lexical order is chronological order, and parsing
       both to a `Date` here would be a second interpretation of one string. A feed with no marker
       is a principal who has read **nothing**, not one who has read everything — the reverse would
       start every new operator with a bell that never tells them anything. */
    read: lastReadAt !== undefined && event.openedAt <= lastReadAt,
    ...(href === undefined ? {} : { href }),
  };
}
