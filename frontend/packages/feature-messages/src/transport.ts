/**
 * The browse session's transport: the one adapter between `createBrowseSession` and the network.
 *
 * ## Why this file has to exist
 *
 * `BrowseTransport` is an interface, and until now every implementation of it was a fake — one in
 * `messages.test.tsx`, one in `storyHarness.ts`. The session was complete and correct, the kernel's
 * streaming client was complete and tested, and nothing joined them, so `MessagesTab`'s promise
 * that "Stop aborts the request, and that abort travels" was a promise the code could not keep.
 *
 * ## `openFetchStream`, never `openEventSource`
 *
 * This is the whole reason the kernel has two transports, and choosing the wrong one here is a
 * production incident rather than a bug.
 *
 * The browser's native `EventSource` cannot be aborted. Its `close()` stops the *browser* listening;
 * it does not cancel the HTTP request. A browse that the user stopped, or a tab they closed, would
 * therefore leave the request open at the gateway, which keeps the message service's fiber alive,
 * which keeps a Kafka consumer assigned — until the server's own budget expires. One operator
 * flicking through ten topics leaks ten consumers.
 *
 * `openFetchStream` builds an `AbortController` and hands its signal to `fetch`, so `close()` tears
 * down the body read. That abort is what cancels the gateway's fs2 stream, which cancels the service
 * fiber, which runs the consumer's `Resource` finaliser. The chain is real and already wired on the
 * server; this file is what starts using it.
 *
 * `EventSource` also keeps the last event id to itself, so `endMarker()` is always `undefined` there
 * — and the marker *is* the continuation cursor. With `EventSource`, "load more" could never work.
 *
 * ## `phase` comes through its own callback
 *
 * `phase` is one of ADR-035's shared events, so it must not be listed among `events` — but unlike
 * `done`, `error` and `heartbeat` it carries a payload the status line is built from. The kernel
 * delivers it through `onPhase`, and this adapter is what turns that raw payload into the session's
 * `phase` event.
 */
import { openFetchStream, type SseConnection, type SseError } from "@kui/kernel";
import {
  decodeBrowseEvent,
  type BrowseConnection,
  type BrowseEvent,
  type BrowseFailure,
  type BrowseHandle,
  type BrowseTransport,
} from "./session.js";

/** The data events a browse subscribes to. The shared four are handled by the transport itself. */
const BROWSE_EVENTS = ["message", "consumed"] as const;

/**
 * The real transport.
 *
 * A function rather than a constant so that a caller can supply request options — credentials, a
 * correlation header — without this module knowing what they are.
 */
export function createBrowseTransport(options?: {
  readonly headers?: Readonly<Record<string, string>> | undefined;
}): BrowseTransport {
  return {
    open(url, handlers): BrowseHandle {
      const handle = openFetchStream(
        {
          url,
          // `GET`, and no body: the whole query is in the URL, which is what makes a browse a link
          // somebody can paste to a colleague.
          method: "GET",
          headers: options?.headers ?? {},
        },
        {
          events: [...BROWSE_EVENTS],
          decode: (event, data) => decodeBrowseEvent(event, data),
          onEvent: (event: BrowseEvent) => handlers.onEvent(event),
          onPhase: (data) => {
            // Reuses the session's own decoder rather than parsing here, so there is one definition
            // of what a phase frame looks like. A phase that does not decode is dropped rather than
            // reported: it is a progress hint, and failing a browse because the status line could
            // not be updated would be the tail wagging the dog.
            const decoded = decodeBrowseEvent("phase", data);
            if (decoded.ok) handlers.onEvent(decoded.value);
          },
          onError: (error) => handlers.onFailure(toFailure(error)),
        },
      );

      // The kernel reports connection state as a signal; the session wants callbacks. Reading it
      // here rather than exposing the signal keeps the session free of any reactive dependency on
      // the kernel's streaming module, which is what lets a test replace this whole object.
      handlers.onConnection(toConnection(handle.connection()));

      return {
        close: () => handle.close(),
        endMarker: () => handle.endMarker(),
      };
    },
  };
}

/**
 * A stream failure, in the session's vocabulary.
 *
 * A `decode` failure is deliberately *not* terminal: one record whose payload this build cannot
 * read must not end a browse that is otherwise delivering good records. That rule is the kernel's
 * and the session's both; this mapping is where the two agree on it.
 */
function toFailure(error: SseError): BrowseFailure {
  switch (error.kind) {
    case "decode":
      // The event name is kept as its own field rather than folded into the sentence: the status
      // line says which kind of frame it could not read, and a reader chasing it needs the name
      // separable from the prose.
      return { kind: "decode", event: error.event, cause: error.cause };
    case "server":
      return { kind: "server", error: error.error };
    case "transport":
      return { kind: "transport", cause: error.cause };
  }
}

function toConnection(connection: SseConnection): BrowseConnection {
  switch (connection.phase) {
    case "open":
      return { phase: "open" };
    case "connecting":
      return { phase: "connecting" };
    case "reconnecting":
      return { phase: "connecting" };
    case "closed":
      return { phase: "closed", reason: connection.reason };
  }
}
