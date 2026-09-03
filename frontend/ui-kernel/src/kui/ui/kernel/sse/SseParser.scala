package kui.ui.kernel.sse

/** One complete event read off a stream, before anybody has tried to make sense of its payload.
  *
  * @param name
  *   the `event:` field, or `"message"` when the server did not send one — the default the
  *   [[https://html.spec.whatwg.org/multipage/server-sent-events.html server-sent events specification]]
  *   mandates, and what a browser's `EventSource` would report.
  * @param data
  *   every `data:` line joined with a single newline, with no trailing newline.
  * @param id
  *   the last `id:` seen on the stream. KUI puts the signed cursor there (ADR-026, ADR-035), and it persists
  *   across events until the server sends a new one, which is why it can be present on an event that carried
  *   no `id:` line of its own.
  */
final case class RawSseEvent(name: String, data: String, id: Option[String])

object RawSseEvent {
  given CanEqual[RawSseEvent, RawSseEvent] = CanEqual.derived
}

/** Everything the parser has seen and not yet been able to turn into an event.
  *
  * It is a value rather than mutable state inside the parser so that [[SseParser.feed]] can be a pure
  * function — which is what lets a property test feed the same bytes in every possible chunking and assert
  * that the answer never changes. A network splits a stream wherever it likes, including in the middle of a
  * word, in the middle of a `\r\n` pair, and between a `data:` line and the blank line that ends its event.
  *
  * @param pending
  *   characters after the last complete line terminator.
  * @param retry
  *   the server's reconnection hint in milliseconds, from a `retry:` line. Carried because the field is part
  *   of the format; `Sse` does not act on it, because KUI's backoff is its own (see `Sse.backoff`).
  */
final case class ParserState(
    pending: String,
    eventName: Option[String],
    dataLines: Vector[String],
    lastId: Option[String],
    retry: Option[Int]
)

object ParserState {

  val empty: ParserState = ParserState(
    pending = "",
    eventName = None,
    dataLines = Vector.empty,
    lastId = None,
    retry = None
  )

  given CanEqual[ParserState, ParserState] = CanEqual.derived
}

/** The `text/event-stream` wire format, parsed incrementally and without a DOM.
  *
  * The browser's own `EventSource` does this internally, and `Sse.eventSource` uses it. This parser exists
  * for `Sse.fetchStream`, which reads a `fetch` response body by hand in order to gain two things
  * `EventSource` cannot offer: a `POST` (M3's message browsing sends a filter) and cancellation (a user who
  * navigates away must stop the Kafka consumer behind the stream, not leave it running).
  *
  * Being pure and separate also makes it the one place where client and server can be proved to agree: the
  * suite parses the exact bytes `libs/http`'s golden test writes.
  */
object SseParser {

  /** The character an `id:` may not contain; such a line is ignored rather than truncated. */
  private val NullCharacter: Char = 0.toChar

  /** The event name a server-sent event has when the server did not name one. */
  val DefaultEventName = "message"

  /** Feeds one chunk and returns whatever became complete.
    *
    * A chunk that completes no event returns the list empty and a state carrying the leftovers. Nothing is
    * ever lost and nothing is ever emitted twice.
    */
  def feed(state: ParserState, chunk: String): (ParserState, List[RawSseEvent]) = {
    val builder = List.newBuilder[RawSseEvent]

    @annotation.tailrec
    def loop(current: ParserState): ParserState =
      splitLine(current.pending) match {
        case None => current
        case Some((line, rest)) =>
          val advanced = consume(current.copy(pending = rest), line, builder += _)
          loop(advanced)
      }

    val finished = loop(state.copy(pending = state.pending + chunk))
    (finished, builder.result())
  }

  /** Splits off the first complete line, or `None` when the buffer holds no terminated line yet.
    *
    * The awkward case is a buffer ending in a bare `\r`: it is a complete line if the next character is
    * anything but `\n`, and half of a `\r\n` pair if it is. Since the next character has not arrived, the
    * only correct answer is to wait — treating it as complete would emit an event one chunk early and then
    * see a stray empty line, which in this format means "dispatch", and would produce a phantom event.
    */
  private def splitLine(buffer: String): Option[(String, String)] = {
    val breakAt = buffer.indexWhere(character => character == '\n' || character == '\r')
    if breakAt < 0 then None
    else if buffer.charAt(breakAt) == '\r' && breakAt == buffer.length - 1 then None
    else {
      val skip = if buffer.startsWith("\r\n", breakAt) then 2 else 1
      Some((buffer.substring(0, breakAt), buffer.substring(breakAt + skip)))
    }
  }

  /** Applies one line to the state, dispatching an event when the line is the blank one that ends a block. */
  private def consume(state: ParserState, line: String, emit: RawSseEvent => Unit): ParserState =
    if line.isEmpty then dispatch(state, emit)
    else if line.startsWith(":") then
      state // A comment. Servers use it as a keep-alive; it means nothing here.
    else {
      val (field, value) = splitField(line)
      field match {
        case "event" => state.copy(eventName = Some(value))
        case "data" => state.copy(dataLines = state.dataLines :+ value)
        // An id containing NUL must be ignored rather than truncated (the format's own rule).
        case "id" => if value.contains(NullCharacter) then state else state.copy(lastId = Some(value))
        case "retry" => value.toIntOption.fold(state)(millis => state.copy(retry = Some(millis)))
        // An unknown field is ignored, never an error: that is what lets the server add one.
        case _ => state
      }
    }

  /** Splits `field: value`, removing exactly one space after the colon and no more.
    *
    * "Exactly one" is the format's rule and it matters: JSON payloads are indented by some servers, and
    * eating all the leading whitespace would silently change the bytes the decoder sees.
    */
  private def splitField(line: String): (String, String) = {
    val colon = line.indexOf(':')
    if colon < 0 then (line, "")
    else {
      val raw = line.substring(colon + 1)
      (line.substring(0, colon), if raw.startsWith(" ") then raw.substring(1) else raw)
    }
  }

  /** Ends the current block.
    *
    * A block with no `data:` line emits nothing — a lone `event: ping` is not an event — but it still clears
    * the name, so the next block does not inherit it. `lastId` deliberately survives: the format defines it
    * as a property of the stream, not of one event.
    */
  private def dispatch(state: ParserState, emit: RawSseEvent => Unit): ParserState = {
    if state.dataLines.nonEmpty then {
      emit(
        RawSseEvent(
          name = state.eventName.getOrElse(DefaultEventName),
          data = state.dataLines.mkString("\n"),
          id = state.lastId
        )
      )
    }
    state.copy(eventName = None, dataLines = Vector.empty)
  }
}
