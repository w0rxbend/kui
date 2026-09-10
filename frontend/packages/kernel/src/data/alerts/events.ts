/**
 * The alert feed's vocabulary, and the one place its wire is turned into it.
 *
 * ## Why the kernel holds this and not a feature package
 *
 * Two things draw the feed and they are in packages that may not see each other: the bell lives in
 * the shell's chrome and the card lives in `@kui/feature-alerts`. A feature may not import the
 * shell, nothing may import a feature statically, and the two must never be able to disagree about
 * how many alerts are open. The kernel is the only package both can read from.
 *
 * ## What this decodes, and where every name in it came from
 *
 * Not from a sibling packet's diff and not from a guess. `services/alerts`' own encoder is
 * `services/alerts/contract/src/kui/alerts/contract/dto/AlertDtos.scala`, and every field below is
 * read from the `Json.obj(…)` in it:
 *
 * - `AlertFeedResponse` is `events: Section[AlertFeedDto]` — one section under the key `events`,
 *   which is also what M8's exit criterion greps for (`jq '.events.status'`).
 * - `AlertFeedDto` is `items`, `total`, `openCount`, `unreadCount`, `lastReadAt`, `evaluatedAt`,
 *   `rules`.
 * - `AlertEventDto` is `id`, `severity`, `tone`, `category`, `glyph`, `openedAt`, `lastSeenAt`,
 *   `title`, `detail`, `resolution` — and `AlertResolutionDto` is `at`, `kind`, `by`.
 * - The stream frame is `AlertChangeDto`: `cluster`, `openCount`, `at`, under the event name
 *   `AlertChangeDto.EventName` = `"alerts"`. **It deliberately carries no events**; its own
 *   scaladoc says a subscriber that sees a count it does not hold re-reads the feed it is already
 *   authorized for, which is what keeps one principal's unread count off every other subscriber's
 *   socket.
 *
 * Those are **symbol** citations and were line citations until wave 7, when three of the four in
 * this file were measured pointing eleven lines above the type they named — `AlertFeedResponse` at
 * `:265` when it is at `:276`, `AlertFeedDto` at `:219` for `:230`, `AlertChangeDto` at `:322` for
 * `:333`. Nothing in this repository compares a comment to a line number, so the drift was silent
 * and cost a reader eleven lines of scrolling to discover the comment was stale rather than the
 * code. A symbol survives an edit above it. What checks the field names themselves is
 * `./wire.golden.test.ts`, which decodes the service's own committed documents through this file.
 *
 * ## Why the decoder refuses instead of defaulting
 *
 * Wave 5 shipped an encoder writing `topics[…]` and a decoder reading `entries[…]`, both green,
 * both unit-tested against their own hand-written literal. The section key matched, so the decode
 * *succeeded* and answered an empty array, and the card then said the metrics source had named no
 * producers over a source that had named five. A feed with no `items` array is therefore refused
 * here, with the keys it did carry in the message. An empty feed is a statement about the cluster;
 * an unreadable body is a statement about the software, and they must not read alike.
 *
 * ## Why there is no vocabulary table in this file at all
 *
 * The server sends **both** halves of each pair — `severity` *and* `tone`, `category` *and*
 * `glyph` — precisely so that the bell, the card and the notification panel cannot map them
 * differently (`AlertEventDto`'s own Tapir description says so). So the four words travel and
 * nothing here interprets any of them. KUI inferring a severity from a number it did not measure is
 * the fabrication house rule 7 forbids, and the surest way not to do it is to own no mapping.
 *
 * A list of the words the service uses today would be a fifth copy of
 * `services/alerts/domain/.../AlertVocabulary.scala` with nothing checking it, and the renderer that
 * needs one already has it: `@kui/feature-alerts`' `model.ts` holds the tone and glyph tables beside
 * the components that draw from them, which is where an unrecognised word has to be answered anyway.
 * This module's only job is to hand those components the words the server actually sent.
 */

import { decodeSection, type Section } from "@kui/api";

/** How an event ended. `kind` is the server's word for it; `by` is a principal when there was one. */
export interface AlertResolution {
  readonly at: string;
  /** The server's own word for how it ended. */
  readonly kind: string | undefined;
  readonly by: string | undefined;
}

/** One row of the feed. */
export interface AlertEvent {
  readonly id: string;
  /** Chooses nothing here. Carried so a screen can say it in words. */
  readonly severity: string;
  /** The tone the server derived from the severity. Carried, never computed. */
  readonly tone: string;
  readonly category: string | undefined;
  /** The glyph the server derived from the category. Carried, never computed. */
  readonly glyph: string | undefined;
  /**
   * When KUI first saw the condition — its own observation and not the cluster's, as the DTO's
   * scaladoc is careful to say. After a restart it is an age since the restart.
   */
  readonly openedAt: string;
  /** The last pass on which the rule was still firing, when the server said. */
  readonly lastSeenAt: string | undefined;
  readonly title: string;
  readonly detail: string | undefined;
  /** Absent while the event is open. */
  readonly resolution: AlertResolution | undefined;
}

/** What one rule established on its last evaluation, including an honest refusal. */
export interface AlertRuleReport {
  readonly rule: string;
  readonly category: string;
  readonly status: Section<unknown>["status"];
  readonly openEvents: number | null;
  readonly unmeasuredSubjects: number | null;
  readonly reason: string | undefined;
}

/**
 * One page of the feed, and the counts that are **not** of that page.
 *
 * `openCount` and `unreadCount` are counted over the whole store by the service — its own scaladoc
 * says so — while `items` is one page. That is why nothing in this file folds either number out of
 * the rows: a browser that did would show `2 open` on a feed whose next page holds a third.
 */
export interface AlertFeed {
  readonly items: readonly AlertEvent[];
  /** How many events the store holds in total, or `null` when the server did not say. */
  readonly total: number | null;
  /** Open across the whole cluster, as the server counted it. `null` is "KUI does not know". */
  readonly openCount: number | null;
  /** Unread for **this principal**, as the server counted it. Never derived here. */
  readonly unreadCount: number | null;
  /** How far this principal has read, RFC 3339. The comparison is the server's, not ours. */
  readonly lastReadAt: string | undefined;
  /**
   * When the rules last ran. Absent means they never have here — which is what makes a zero beside
   * it readable: `openCount: 0` with no `evaluatedAt` has not established that the cluster is well,
   * only that KUI has not looked yet.
   */
  readonly evaluatedAt: string | undefined;
  /** One row per configured rule, whether it measured a figure or explains why it could not. */
  readonly rules: readonly AlertRuleReport[];
}

/** One frame of the change stream. It carries a count and no events; see the header. */
export interface AlertChange {
  readonly cluster: string;
  readonly openCount: number | null;
  readonly at: string;
}

export type Decoded<A> =
  | { readonly ok: true; readonly value: A }
  | { readonly ok: false; readonly cause: string };

function refuse<A>(cause: string): Decoded<A> {
  return { ok: false, cause };
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

/** An RFC 3339 instant kept in the server's spelling, or no instant at all. */
function optionalInstant(value: unknown): string | undefined {
  const text = optionalString(value);
  if (text === undefined) return undefined;
  return Number.isNaN(Date.parse(text)) ? undefined : text;
}

/**
 * A count as the server sent it, or `null`.
 *
 * Anything that is not a finite number is `null` and never `0`. An absent count means "KUI does not
 * know how many are open", and drawing that as a zero tells the operator that nothing is wrong —
 * the most reassuring possible rendering of the one thing nobody measured.
 */
function readCount(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function readResolution(raw: unknown): AlertResolution | undefined {
  if (typeof raw !== "object" || raw === null) return undefined;
  const candidate = raw as Record<string, unknown>;
  const at = optionalInstant(candidate["at"]);
  const kind = optionalString(candidate["kind"]);
  // A resolution without a time cannot establish that the condition ended. Its kind remains the
  // server's word (or absence) rather than acquiring a browser-invented value.
  if (at === undefined) return undefined;
  return {
    at,
    kind,
    by: optionalString(candidate["by"]),
  };
}

/**
 * Reads one event, refusing anything it would otherwise have to invent.
 *
 * `id`, `openedAt`, `title`, `severity` and `tone` are all required. A row with no `openedAt` has no
 * age, and "just now" for something that opened yesterday is worse than no row at all; a row with no
 * `tone` cannot be painted without the browser choosing a colour, which is the claim rule 7 forbids.
 * `category`, `glyph`, `detail` and `lastSeenAt` cost a picture or a line and claim nothing, so
 * their absence is carried rather than refused.
 */
export function decodeAlertEvent(raw: unknown): Decoded<AlertEvent> {
  if (typeof raw !== "object" || raw === null) {
    return refuse(`expected an event object, got ${raw === null ? "null" : typeof raw}`);
  }
  const candidate = raw as Record<string, unknown>;
  const id = optionalString(candidate["id"]);
  const openedAt = optionalInstant(candidate["openedAt"]);
  const title = optionalString(candidate["title"]);
  const severity = optionalString(candidate["severity"]);
  const tone = optionalString(candidate["tone"]);

  if (id === undefined) return refuse("an event carried no id");
  if (openedAt === undefined) return refuse(`event ${id} carried no openedAt`);
  if (title === undefined) return refuse(`event ${id} carried no title`);
  if (severity === undefined) return refuse(`event ${id} carried no severity`);
  if (tone === undefined) return refuse(`event ${id} carried no tone`);

  return {
    ok: true,
    value: {
      id,
      severity,
      tone,
      category: optionalString(candidate["category"]),
      glyph: optionalString(candidate["glyph"]),
      openedAt,
      lastSeenAt: optionalInstant(candidate["lastSeenAt"]),
      title,
      detail: optionalString(candidate["detail"]),
      resolution: readResolution(candidate["resolution"]),
    },
  };
}

function readRule(raw: unknown): Decoded<AlertRuleReport> {
  if (typeof raw !== "object" || raw === null) return refuse("expected a rule report object");
  const candidate = raw as Record<string, unknown>;
  const rule = optionalString(candidate["rule"]);
  const category = optionalString(candidate["category"]);
  if (rule === undefined) return refuse("a rule report carried no rule name");
  if (category === undefined) return refuse(`rule ${rule} carried no category`);

  const evaluation = decodeSection<unknown>(candidate["evaluation"]);
  if (evaluation.status === "ok" || evaluation.status === "stale") {
    if (typeof evaluation.data !== "object" || evaluation.data === null) {
      return refuse(`rule ${rule} carried no evaluation data`);
    }
    const data = evaluation.data as Record<string, unknown>;
    return {
      ok: true,
      value: {
        rule,
        category,
        status: evaluation.status,
        openEvents: readCount(data["openEvents"]),
        unmeasuredSubjects: readCount(data["unmeasuredSubjects"]),
        reason: evaluation.status === "stale" ? evaluation.reason.message : undefined,
      },
    };
  }

  return {
    ok: true,
    value: {
      rule,
      category,
      status: evaluation.status,
      openEvents: null,
      unmeasuredSubjects: null,
      reason:
        evaluation.status === "unavailable" || evaluation.status === "unreadable"
          ? evaluation.reason.message
          : undefined,
    },
  };
}

/**
 * Reads the `data` of an `events` section.
 *
 * A document with no `items` array is **refused**, with the keys it did carry named in the message
 * so that a rename on the server side is legible the first time somebody looks. See the header.
 */
export function decodeAlertFeed(raw: unknown): Decoded<AlertFeed> {
  if (typeof raw !== "object" || raw === null) {
    return refuse(`expected a feed object, got ${raw === null ? "null" : typeof raw}`);
  }
  const candidate = raw as Record<string, unknown>;
  const rows = candidate["items"];
  if (!Array.isArray(rows)) {
    const keys = Object.keys(candidate).join(", ");
    return refuse(`the feed carried no 'items' array; its keys are ${keys === "" ? "(none)" : keys}`);
  }

  const items: AlertEvent[] = [];
  for (const row of rows) {
    const decoded = decodeAlertEvent(row);
    // One unreadable row refuses the whole feed, and that is deliberate. A row this build cannot
    // read is version skew rather than corruption — the same encoder wrote every row, so the same
    // field is wrong in all of them — and keeping the four that happened to parse would draw a feed
    // quietly short by one and say nothing. ADR-035's "one bad frame does not end the stream" is
    // about frames arriving over time; inside a single document the honest unit is the document.
    if (!decoded.ok) return refuse(decoded.cause);
    items.push(decoded.value);
  }

  const rawRules = candidate["rules"];
  if (rawRules !== undefined && !Array.isArray(rawRules)) {
    return refuse("the feed carried a 'rules' value that was not an array");
  }
  const rules: AlertRuleReport[] = [];
  for (const rawRule of rawRules ?? []) {
    const decoded = readRule(rawRule);
    if (!decoded.ok) return refuse(decoded.cause);
    rules.push(decoded.value);
  }

  return {
    ok: true,
    value: {
      items,
      total: readCount(candidate["total"]),
      openCount: readCount(candidate["openCount"]),
      unreadCount: readCount(candidate["unreadCount"]),
      lastReadAt: optionalInstant(candidate["lastReadAt"]),
      evaluatedAt: optionalInstant(candidate["evaluatedAt"]),
      rules,
    },
  };
}

/** Reads one change frame: a cluster, the new open count, and when. */
export function decodeAlertChange(data: string): Decoded<AlertChange> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch (error) {
    return refuse(`the frame was not JSON: ${String(error)}`);
  }
  if (typeof parsed !== "object" || parsed === null) return refuse("expected a frame object");
  const candidate = parsed as Record<string, unknown>;
  const cluster = optionalString(candidate["cluster"]);
  const at = optionalInstant(candidate["at"]);
  if (cluster === undefined) return refuse("a change frame named no cluster");
  if (at === undefined) return refuse(`the change frame for ${cluster} carried no instant`);
  return { ok: true, value: { cluster, openCount: readCount(candidate["openCount"]), at } };
}
