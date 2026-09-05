/**
 * A browse session a story can drive without a network.
 *
 * Stories exist so that somebody can *look* at a state, and every interesting state of this screen
 * is a state of the stream: reading, following, paused with records queued behind it, finished with
 * a continuation, failed halfway through with records already on screen. None of those is reachable
 * from a story that fetches — so the transport is replaced, and the story says what it wants the
 * stream to have done.
 */

import { createBrowseSession, type BrowseSession, type BrowseTransport } from "./session.js";
import type { BrowseQuery } from "./browse.js";
import type { KafkaRecord } from "@kui/kernel";

export interface ScriptedStream {
  /** Records to deliver as soon as the browse starts. */
  readonly records?: readonly KafkaRecord[];
  /** Close the stream after delivering them, with this continuation cursor (or none). */
  readonly finish?: { readonly cursor?: string | undefined } | undefined;
  /** Report this failure after the records. */
  readonly failure?: string | undefined;
}

/**
 * A session that plays a script.
 *
 * Deliberately synchronous: a story that delivered on a timer would be a story whose screenshot
 * depends on when it was taken.
 */
export function scriptedSession(script: ScriptedStream): BrowseSession {
  const transport: BrowseTransport = {
    open: (_url, handlers) => {
      let cursor: string | undefined;
      handlers.onConnection({ phase: "open" });
      /* Replayed oldest-first, because that is the order a stream delivers in and the session
       * prepends each arrival — so a script written newest-first (which is how the fixtures read,
       * and how the finished list reads) would come out upside down. Getting this wrong was
       * visible the moment the story was opened: offset 18,442,895 sat above 18,442,901. */
      for (const record of [...(script.records ?? [])].reverse()) {
        handlers.onEvent({ kind: "record", record });
      }
      handlers.onEvent({
        kind: "consumed",
        consumed: { messages: (script.records?.length ?? 0) * 140, bytes: 2_400_000, elapsedMs: 820 },
      });
      if (script.failure !== undefined) {
        handlers.onFailure({ kind: "transport", cause: script.failure });
      }
      if (script.finish !== undefined) {
        cursor = script.finish.cursor;
        handlers.onConnection({ phase: "closed", reason: "the server finished the browse" });
      }
      return { close: () => undefined, endMarker: () => cursor };
    },
  };
  return createBrowseSession({ streamUrl: "/api/v1/stream", transport });
}

/** Starts a session so that a story renders a browse that has already happened. */
export function startedSession(script: ScriptedStream, query: BrowseQuery): BrowseSession {
  const session = scriptedSession(script);
  session.start(query);
  return session;
}
