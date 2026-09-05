/**
 * The `text/event-stream` wire format, parsed incrementally and without a DOM.
 *
 * ## Why the browser's own parser is not enough
 *
 * `EventSource` parses this format internally, and {@link openEventSource} uses it. This parser
 * exists for {@link openFetchStream}, which reads a `fetch` response body by hand in order to gain
 * two things `EventSource` cannot offer: a `POST` (message browsing sends a filter in the body) and
 * cancellation (a user who navigates away must stop the Kafka consumer behind the stream, not leave
 * it running).
 *
 * ## Why it is a pure function over an explicit state
 *
 * A network splits a stream wherever it likes: in the middle of a word, in the middle of a `\r\n`
 * pair, and between a `data:` line and the blank line that ends its event. Keeping the leftovers in
 * a value the caller threads through — rather than in mutable state inside a parser object — is
 * what lets a test feed the same bytes in every possible chunking and assert that the answer never
 * changes. `parser.test.ts` does exactly that, against the bytes the server's own golden test pins.
 */

/** The event name a server-sent event has when the server did not name one. */
export const DEFAULT_EVENT_NAME = "message";

/** The character an `id:` may not contain; such a line is ignored rather than truncated. */
const NUL = "\u0000";

/** One complete event read off a stream, before anybody has tried to make sense of its payload. */
export interface RawSseEvent {
  /**
   * The `event:` field, or `"message"` when the server did not send one — the default the
   * server-sent events specification mandates, and what a browser's `EventSource` would report.
   */
  readonly name: string;
  /** Every `data:` line joined with a single newline, with no trailing newline. */
  readonly data: string;
  /**
   * The last `id:` seen on the stream. KUI puts the signed continuation cursor there (ADR-026,
   * ADR-035), and it persists across events until the server sends a new one — which is why it can
   * be present on an event that carried no `id:` line of its own.
   */
  readonly id: string | undefined;
}

/** Everything the parser has seen and not yet been able to turn into an event. */
export interface ParserState {
  /** Characters after the last complete line terminator. */
  readonly pending: string;
  readonly eventName: string | undefined;
  readonly dataLines: readonly string[];
  readonly lastId: string | undefined;
  /**
   * The server's reconnection hint in milliseconds, from a `retry:` line. Carried because the field
   * is part of the format; the stream wrappers do not act on it, because KUI's backoff is its own
   * (see {@link backoff}).
   */
  readonly retry: number | undefined;
}

export const EMPTY_PARSER_STATE: ParserState = {
  pending: "",
  eventName: undefined,
  dataLines: [],
  lastId: undefined,
  retry: undefined,
};

/**
 * Feeds one chunk and returns whatever became complete.
 *
 * A chunk that completes no event returns an empty list and a state carrying the leftovers. Nothing
 * is ever lost and nothing is ever emitted twice.
 */
export function feed(
  state: ParserState,
  chunk: string,
): { readonly state: ParserState; readonly events: readonly RawSseEvent[] } {
  const events: RawSseEvent[] = [];
  let current: ParserState = { ...state, pending: state.pending + chunk };

  for (;;) {
    const split = splitLine(current.pending);
    if (split === undefined) break;
    current = consume({ ...current, pending: split.rest }, split.line, (event) => {
      events.push(event);
    });
  }

  return { state: current, events };
}

/**
 * Splits off the first complete line, or `undefined` when the buffer holds no terminated line yet.
 *
 * The awkward case is a buffer ending in a bare `\r`: it is a complete line if the next character is
 * anything but `\n`, and half of a `\r\n` pair if it is. Since the next character has not arrived,
 * the only correct answer is to wait — treating it as complete would emit an event one chunk early
 * and then see a stray empty line, which in this format means "dispatch", producing a phantom event.
 */
function splitLine(buffer: string): { readonly line: string; readonly rest: string } | undefined {
  let breakAt = -1;
  for (let index = 0; index < buffer.length; index += 1) {
    const character = buffer[index];
    if (character === "\n" || character === "\r") {
      breakAt = index;
      break;
    }
  }

  if (breakAt < 0) return undefined;
  if (buffer[breakAt] === "\r" && breakAt === buffer.length - 1) return undefined;

  const skip = buffer.startsWith("\r\n", breakAt) ? 2 : 1;
  return { line: buffer.slice(0, breakAt), rest: buffer.slice(breakAt + skip) };
}

/** Applies one line to the state, dispatching an event when the line is the blank one that ends a block. */
function consume(
  state: ParserState,
  line: string,
  emit: (event: RawSseEvent) => void,
): ParserState {
  if (line === "") return dispatch(state, emit);
  // A comment. Servers use it as a keep-alive; it means nothing here.
  if (line.startsWith(":")) return state;

  const { field, value } = splitField(line);
  switch (field) {
    case "event":
      return { ...state, eventName: value };
    case "data":
      return { ...state, dataLines: [...state.dataLines, value] };
    // An id containing NUL must be ignored rather than truncated (the format's own rule).
    case "id":
      return value.includes(NUL) ? state : { ...state, lastId: value };
    case "retry": {
      const millis = /^\d+$/.test(value) ? Number.parseInt(value, 10) : undefined;
      return millis === undefined ? state : { ...state, retry: millis };
    }
    // An unknown field is ignored, never an error: that is what lets the server add one.
    default:
      return state;
  }
}

/**
 * Splits `field: value`, removing exactly one space after the colon and no more.
 *
 * "Exactly one" is the format's rule and it matters: JSON payloads are indented by some servers, and
 * eating all the leading whitespace would silently change the bytes the decoder sees.
 */
function splitField(line: string): { readonly field: string; readonly value: string } {
  const colon = line.indexOf(":");
  if (colon < 0) return { field: line, value: "" };
  const raw = line.slice(colon + 1);
  return { field: line.slice(0, colon), value: raw.startsWith(" ") ? raw.slice(1) : raw };
}

/**
 * Ends the current block.
 *
 * A block with no `data:` line emits nothing — a lone `event: ping` is not an event — but it still
 * clears the name, so the next block does not inherit it. `lastId` deliberately survives: the format
 * defines it as a property of the stream, not of one event.
 */
function dispatch(state: ParserState, emit: (event: RawSseEvent) => void): ParserState {
  if (state.dataLines.length > 0) {
    emit({
      name: state.eventName ?? DEFAULT_EVENT_NAME,
      data: state.dataLines.join("\n"),
      id: state.lastId,
    });
  }
  return { ...state, eventName: undefined, dataLines: [] };
}
