/**
 * Following one business event across several topics.
 *
 * ## The question this screen exists to answer
 *
 * "Where did order 4711 go?" A support engineer has an identifier and six topics, and no console
 * consumer can answer it: the records are in different topics, written at different times, and the
 * answer is the list of them in time order.
 *
 * ## Why nothing happens until Search is pressed
 *
 * A track is a bounded read of every named topic across the whole window, and there is no index
 * behind it. A screen that searched as the user typed would start a multi-topic scan per keystroke,
 * on a cluster somebody is already investigating an incident on. So the form is filled in, the
 * window is chosen, and Search reads.
 *
 * ## Why the scanned count is carried even when there are no hits
 *
 * "Nothing matched" and "nothing was read" are the same screen without it, and they mean opposite
 * things. The first says the value is not in those topics in that window; the second says the window
 * was empty and the user should widen it before concluding anything. That distinction is the
 * difference between a support engineer closing a ticket correctly and closing it wrongly.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";

/** Where to look. A named header is a third case, not a flag on the other two. */
export type MatchSource = "value" | "key" | "header";

/**
 * How to compare.
 *
 * The server's own three words. `matches` and not `regex`: the spelling is the contract's, and a
 * near-miss here is a validation error about a field the operator never chose the wording of.
 */
export type MatchOperator = "contains" | "equals" | "matches";

export interface TrackQuery {
  /**
   * The topics to read. **At least one is required.**
   *
   * Not "empty means all": the server refuses a track that names no topics, with "at least one
   * topic". That is the right rule — a track is a full read of everything it is pointed at, and
   * pointing it at a whole cluster during an incident is how somebody turns an investigation into a
   * second outage — but it does mean the field cannot be left blank, and the screen has to say so
   * rather than letting the round trip say it.
   */
  readonly topics: readonly string[];
  readonly source: MatchSource;
  /** The header's name, when the source is a header. Ignored otherwise. */
  readonly header: string;
  readonly operator: MatchOperator;
  readonly value: string;
  /** RFC 3339, both ends. A track is always a closed window — see the header. */
  readonly from: string;
  readonly to: string;
  readonly limit: number;
}

export interface TrackHit {
  readonly topic: string;
  readonly partition: number | null;
  readonly offset: number | null;
  readonly timestamp: string | null;
  readonly key: string | null;
  readonly value: string | null;
}

export interface TrackResult {
  readonly hits: readonly TrackHit[];
  /**
   * How many records were read. The figure that makes an empty result meaningful — see the header.
   */
  readonly scanned: number;
  readonly matched: number;
  /** The read stopped at its budget. There may be more, and the screen must say so. */
  readonly truncated: boolean;
}

/** Why this query cannot be run, or `undefined`. */
/**
 * How wide a window the server will accept.
 *
 * `PT168H` — seven days — refused with "the window is wider than this deployment allows". Checked
 * here as well so the operator learns it while choosing the window rather than after a scan they
 * waited for. It is a *deployment* setting, so the server stays the authority: this is the default,
 * and a deployment that lowers it will still refuse, legibly.
 */
export const MAX_WINDOW_MS = 168 * 60 * 60 * 1000;

export function queryProblem(query: TrackQuery): string | undefined {
  if (query.topics.length === 0) return "Name at least one topic to read.";
  if (query.value.trim() === "") return "Type the value to look for.";
  if (query.source === "header" && query.header.trim() === "") {
    return "Name the header to look in.";
  }
  if (query.from === "" || query.to === "") return "Choose a time window.";
  if (Date.parse(query.from) >= Date.parse(query.to)) {
    // Caught here rather than by the server: an inverted window reads back as "nothing matched",
    // which is the one answer this screen must never give wrongly.
    return "The window ends before it starts.";
  }
  if (Date.parse(query.to) - Date.parse(query.from) > MAX_WINDOW_MS) {
    return "The window is longer than seven days, which is more than this deployment will read.";
  }
  return undefined;
}

interface HitPayload {
  readonly topic: string;
  readonly record?: {
    readonly partition?: number;
    readonly offset?: number;
    readonly timestamp?: string;
    readonly key?: { readonly text?: string } | string | null;
    readonly value?: { readonly text?: string } | string | null;
  };
}

/**
 * A payload that is either a decoded object or a bare string.
 *
 * The message DTO carries a decoded key and value as objects with a `text` — because a record also
 * has a serde, a kind and possibly a decode error — but a track's hits are read from the same shape
 * and it costs nothing to accept both. `null` is a real value here: a record with no key, or a
 * tombstone, and neither is an empty string.
 */
function textOf(payload: { readonly text?: string } | string | null | undefined): string | null {
  if (payload === null || payload === undefined) return null;
  if (typeof payload === "string") return payload;
  return payload.text ?? null;
}

export async function track(
  api: KuiApiClient,
  clusterId: string,
  query: TrackQuery,
): Promise<ApiResult<TrackResult>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/messages/track", {
    params: { path: { clusterId } },
    body: {
      // Always sent, and never empty: the server requires at least one topic, and the field is
      // required by its decoder even though the published schema marks it optional.
      topics: query.topics,
      match: {
        source: query.source,
        ...(query.source === "header" ? { header: query.header.trim() } : {}),
        operator: query.operator,
        value: query.value,
      },
      from: query.from,
      to: query.to,
      limit: query.limit,
    } as never,
  });
  if (!answer.ok) return answer;

  const payload = answer.value as unknown as {
    readonly hits?: readonly HitPayload[];
    readonly scanned: number;
    readonly matched: number;
    readonly truncated: boolean;
  };

  return {
    ok: true,
    value: {
      hits: (payload.hits ?? []).map((hit) => ({
        topic: hit.topic,
        partition: hit.record?.partition ?? null,
        offset: hit.record?.offset ?? null,
        timestamp: hit.record?.timestamp ?? null,
        key: textOf(hit.record?.key),
        value: textOf(hit.record?.value),
      })),
      scanned: payload.scanned,
      matched: payload.matched,
      truncated: payload.truncated,
    },
  };
}

/** The default window: the last hour, which is where an incident being investigated usually is. */
export function defaultWindow(now: Date = new Date()): {
  readonly from: string;
  readonly to: string;
} {
  const hour = 60 * 60 * 1000;
  return { from: new Date(now.getTime() - hour).toISOString(), to: now.toISOString() };
}

export function emptyQuery(now: Date = new Date()): TrackQuery {
  const window = defaultWindow(now);
  return {
    topics: [],
    source: "value",
    header: "",
    operator: "contains",
    value: "",
    from: window.from,
    to: window.to,
    // A bound, not a page size: a track has no cursor, and a screen that offered "load more" would
    // be offering to run the whole scan again.
    limit: 100,
  };
}
