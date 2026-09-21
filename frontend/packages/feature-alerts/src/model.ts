/**
 * What the browser does with an alert event, which is less than it looks.
 *
 * ## Severity chooses the tone; category chooses the glyph — and the server has already said which
 *
 * `SCREENS-V4.md` §3.9 is a correction: two of the notifications panel's four items are the same
 * severity — both warnings — and they carry **different** glyphs, a rebalance arrow and a disk. A
 * component that picks the glyph from the severity cannot draw that panel at all.
 *
 * `services/alerts` settles it on the wire rather than leaving each reader to map it:
 * `AlertEventDto` carries `severity` **and** `tone`, `category` **and** `glyph`, and its scaladoc
 * says why — *"the derived halves travel rather than being re-derived in the browser so that the
 * bell, the card and the panel cannot each map them differently"*, and because the resolved
 * rendering is not a function of severity at all: a resolved row is drawn in the success tone
 * whatever it opened at. `AlertCategory.glyph` adds the other half of the contract: *"`glyph` is a
 * name and not a character on purpose. Which shape is drawn is the browser's decision and the icon
 * set is the browser's; what the server owes is a stable word to key on."*
 *
 * So this file holds two lookups from the server's words into this build's rendering vocabulary —
 * a tone word to a `PillTone`, a glyph word to an `IconName` — and one fallback, which is what
 * happens when the server's word is not one of them. Nothing here derives a severity, and nothing
 * derives a glyph from a severity: house rule 7, and the whole of §3.9.
 *
 * ## An unrecognised word is drawn as unrecognised
 *
 * A tone this build does not know draws the neutral mark and the row says so in words; a glyph it
 * does not know draws the severity dot §3.8 already has. Neither is folded onto the nearest thing
 * this build does know, because the nearest thing is a confident statement about an event the
 * server may have meant as the loudest one on the cluster. M9's connector-task rule is the first
 * category that will arrive here unrecognised, and it will read as unrecognised rather than as a
 * disk.
 */
import type {
  AlertEvent as KernelAlertEvent,
  AlertFeed,
  AlertResolution as KernelAlertResolution,
  AlertRuleReport,
  IconName,
  PillTone,
} from "@kui/kernel";

/**
 * The severities `services/alerts` opens events at, in the order the filter offers them.
 *
 * Two, and the count is the argument: `AlertSeverity`'s own scaladoc explains that of §3.8's four
 * dots, `success` is a *resolved* row and `primary` is an informational one that **no rule this
 * service ships opens**. A filter chip for a severity nothing can open is a chip that always
 * answers nothing, so there are two chips and not four.
 *
 * The argument is checked rather than left to this paragraph: `alertsRoute.test.tsx`'s "the
 * severity filter offers one chip per severity this service opens events at, and no others" reads
 * the rendered chip bar, so a third entry here draws a third chip and that case fails.
 */
export const SEVERITIES = ["critical", "warning"] as const;

export type Severity = (typeof SEVERITIES)[number];

/** The words on a severity chip. Capitalised for a control rather than for a sentence. */
export function severityChip(severity: Severity): string {
  return severity === "critical" ? "Critical" : "Warning";
}

/**
 * The tone words the server derives, mapped onto this build's pill tones.
 *
 * `primary` is §0's info dot, which the design samples to `--kui-color-primary` — the accent tone.
 * It is here although no rule opens one today, because the mapping is a rendering decision and
 * costs nothing, and because the alternative is that M9's first informational event draws neutral
 * and reads as a word this build does not understand.
 */
const TONES: Record<string, PillTone> = {
  danger: "danger",
  warning: "warning",
  success: "success",
  primary: "accent",
};

/**
 * How this row is painted.
 *
 * The server's `tone`, or `neutral` when this build does not recognise it. The shared decoder
 * refuses an event whose tone is absent, so consulting severity here would be a second mapping of
 * a value the service already derived. It would also turn a future tone into a familiar colour and
 * conceal the version skew this fallback is meant to expose.
 */
export function toneOf(event: AlertEvent): PillTone {
  return TONES[event.tone] ?? "neutral";
}

/** Whether this build has a tone for what the server sent. */
export function toneIsKnown(event: AlertEvent): boolean {
  return TONES[event.tone] !== undefined;
}

/**
 * The server's glyph words, mapped onto the icon set.
 *
 * Four, one per rule `services/alerts` ships (`AlertCategory`). The keys are the server's words and
 * the values are this build's icon names, which is exactly the seam the server's scaladoc asks for:
 * a category it grows later arrives as a word with no entry here and is drawn as unrecognised
 * rather than as whatever the icon lookup happens to answer for an unknown key.
 */
const GLYPHS: Record<string, IconName> = {
  partition: "partitions",
  replication: "topology",
  rebalance: "refresh",
  storage: "disk",
};

/**
 * The icon for this row.
 *
 * `dot` — the plain severity dot §3.8 draws — for a glyph this build does not know. The row still
 * has its tone, so the reader loses the extra distinction and keeps the important one.
 */
export function glyphOf(event: AlertEvent): IconName {
  return GLYPHS[event.glyph ?? ""] ?? "dot";
}

export function glyphIsKnown(event: AlertEvent): boolean {
  return GLYPHS[event.glyph ?? ""] !== undefined;
}

/**
 * What the row says about its severity and its category, in words.
 *
 * Colour is not an accessible distinction on its own, and "this build does not recognise what the
 * server called this" is something a screen-reader user needs told as much as anybody. The
 * unrecognised case quotes the server's word rather than dropping it: whoever is reading the screen
 * during an incident can take it to whoever wrote the rule.
 */
export function severityWords(event: AlertEvent): string {
  return (SEVERITIES as readonly string[]).includes(event.severity)
    ? `Severity ${event.severity}`
    : `Severity "${event.severity}" is not one this build recognises`;
}

export function categoryWords(event: AlertEvent): string {
  return event.category !== undefined && Object.hasOwn(GLYPHS, event.category)
    ? `Category ${event.category}`
    : `Category "${event.category}" is not one this build recognises`;
}

/**
 * How an event closed, and one row of the feed — both the kernel's.
 *
 * The feature renders the kernel's one decoded event and owns no second wire model. `cleared` and
 * `acknowledged` are not the same fact and the wire keeps them apart deliberately: the first is the
 * cluster saying the condition stopped, the second is a person saying they know about it.
 * Collapsing them into a boolean answers *"is it fixed?"* with *"somebody looked at it"*.
 */
export type AlertResolution = KernelAlertResolution;
export type AlertEvent = KernelAlertEvent;

/** Whether this event is still open. */
export function isOpen(event: AlertEvent): boolean {
  return event.resolution === undefined;
}

/** Who acknowledged this event, or `undefined` — including for one the cluster cleared itself. */
export function acknowledgedBy(event: AlertEvent): string | undefined {
  return event.resolution?.kind === "acknowledged" ? event.resolution.by : undefined;
}

/** What one rule found on its last pass, or the reason it could not look. */
export type RuleReport = AlertRuleReport;

/**
 * One page of the feed and the counts that are **not** computed from it.
 *
 * `openCount`, `unreadCount` and `total` are the service's own figures over the whole store;
 * `events` is one page. `AlertFeedDto`'s scaladoc states the rule in the same words this packet was
 * given it in: *"an alert count recomputed from a page is not the open count"*. Each is `undefined`
 * — never `0` — when the document did not carry it.
 */
export type AlertsFeedPage = AlertFeed;

/* --- The feed's own sentences ------------------------------------------------------------------ */

/** What the card says when the rules have run here and opened nothing. */
export const NO_EVENTS = "The alerts service answered and is holding no events for this cluster.";

/**
 * What it says when they have never run here.
 *
 * A different sentence from the one above, and the difference is the product's central promise: an
 * empty feed KUI has checked and an empty feed KUI has not looked at are the same picture and
 * opposite facts.
 */
export const NOT_EVALUATED =
  "KUI has not evaluated this cluster's alert rules yet, so an empty feed says nothing about it.";

/** What the screen says when nothing on this page matches the filter the reader chose. */
export const NO_MATCHES = "No event on this page matches the filters above.";

/** What the card says when the document carried events and no open count. */
export const NO_OPEN_COUNT =
  "The alerts service did not say how many events are open, so this card does not count them.";

/**
 * The pill in the card's header (§3.8), or nothing.
 *
 * Four cases and each is a different fact. A count of zero over rules that have run is good news
 * and is said in words; a count of zero over rules that have never run is not a count at all and
 * says so; a count that never arrived draws no pill and is explained in the caption. `0 open` over
 * a cluster nobody has looked at is the most reassuring possible way to be wrong.
 *
 * **The order of the first two guards is the rule, not an accident.** A feed with neither figure —
 * no evaluation and no count — is a cluster nobody has looked at, and that is what it must say;
 * checking the count first would answer such a feed with no pill at all, which reads as "there is
 * simply nothing to show". The two guards are swapped in `alerts.test.tsx`'s "a feed carrying
 * neither figure says the cluster has not been looked at, and not nothing".
 */
export function openPill(feed: AlertsFeedPage): { text: string; tone: PillTone } | undefined {
  if (feed.evaluatedAt === undefined) return { text: "Not evaluated yet", tone: "neutral" };
  if (feed.openCount === null) return undefined;
  if (feed.openCount === 0) return { text: "None open", tone: "success" };
  return { text: `${feed.openCount} open`, tone: "danger" };
}

/**
 * The screen's voice line (§4.4, §6), from the open count and from nothing else.
 *
 * The design's line is *"Two open alerts. One is the usual suspect."* The second sentence is a joke
 * about *which* alert it is, and the browser does not know that; writing it anyway would be the
 * product asserting something nobody measured. So the count is stated and the aside is dropped,
 * which is SPEC §6.3 rule 3's own instruction for a state that is not the healthy one.
 *
 * **The first arm is this packet's owned rule.** Delete it and a cluster KUI has never evaluated
 * reads *"Nothing is open. The bell is quiet."* at the top of its own Alerts screen, over an
 * `openCount: 0` that measures nothing — the most reassuring possible rendering of the one thing
 * nobody looked at, in the largest sentence on the page. `AlertsFeed`'s card is gated for that
 * document; this line is the one above it, and the case that fails when it goes is
 * `alertsRoute.test.tsx`'s "a cluster the rules have never run on is told so in the voice line".
 */
export function feedVoice(feed: AlertsFeedPage): string {
  if (feed.evaluatedAt === undefined) return "KUI has not run its alert rules on this cluster yet.";
  if (feed.openCount === null) return "The alerts service did not say how many events are open.";
  if (feed.openCount === 0) return "Nothing is open. The bell is quiet.";
  return feed.openCount === 1 ? "One open alert." : `${feed.openCount} open alerts.`;
}

/* --- Filtering, which is over the page and says so ---------------------------------------------- */

/** The severity filter's value: one severity, or every one of them. */
export type SeverityFilter = Severity | "all";

/** The lifecycle filter's value. */
export type StateFilter = "all" | "open" | "resolved";

export interface FeedFilter {
  readonly severity: SeverityFilter;
  readonly state: StateFilter;
}

export const EVERY_EVENT: FeedFilter = { severity: "all", state: "all" };

/**
 * Whether one event survives the filter.
 *
 * An event whose severity is not one of the two chips is kept by `all` and by nothing else. It
 * cannot be matched against a severity it does not carry, and hiding it from every severity filter
 * would make the one event nobody understands the one event nobody sees.
 */
export function matches(event: AlertEvent, filter: FeedFilter): boolean {
  const bySeverity = filter.severity === "all" || event.severity === filter.severity;
  const byState =
    filter.state === "all" || (filter.state === "open" ? isOpen(event) : !isOpen(event));
  return bySeverity && byState;
}

export function filterEvents(
  events: readonly AlertEvent[],
  filter: FeedFilter,
): readonly AlertEvent[] {
  return events.filter((event) => matches(event, filter));
}

/**
 * The caption under a filtered feed.
 *
 * It says *on this page* on purpose. How many rows a filter leaves is a fact about the page the
 * browser is holding; the count in the header is the service's figure over the whole store. A
 * caption that blurred the two would be the browser quietly answering the question the API was
 * asked, and that is the one sentence wave 6 asks every card packet to keep.
 */
export function pageCaption(shown: number, held: number): string {
  if (shown === held) return held === 1 ? "1 event on this page." : `${held} events on this page.`;
  return `${shown} of the ${held} events on this page match.`;
}

/**
 * What a rule's row says when it could not look.
 *
 * `AlertRuleReportDto`'s own words: *"`ok` with `openEvents: 0` is a measurement; `unavailable` is
 * the absence of one"*. This is the sentence for the second one, and it names the rule so that a
 * reader can tell which of the four is dark.
 *
 * `stale` is **not** one of them and is excluded beside `ok` for that reason: a stale evaluation
 * carries a real figure that was measured, just not recently, and answering it with *"KUI could not
 * evaluate…"* throws away a measurement and replaces it with a refusal. `RuleReports` draws the
 * figure and captions it; `alerts.test.tsx`'s "a stale rule keeps the figure it measured and says
 * it is not current" is what fails when this arm is dropped.
 */
export function ruleRefusal(report: RuleReport): string | undefined {
  if (report.status === "ok" || report.status === "stale") return undefined;
  const because = report.reason === undefined ? "" : ` ${report.reason}`;
  return report.status === "not_configured"
    ? `This deployment does not run the ${report.rule} rule.${because}`
    : `KUI could not evaluate the ${report.rule} rule, so this row is not a zero.${because}`;
}
