package kui.ui.kernel.sse

import scala.concurrent.duration.*
import scala.scalajs.js
import scala.util.control.NonFatal

import com.raquo.laminar.api.L.*
import io.circe.parser.decode
import org.scalajs.dom

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given
import kui.contracts.sse.SseEventName

/** Where a stream is in its life.
  *
  * Rendered as the capability banner's connection indicator (UI-010), which is the only honest way to tell a
  * user that what they are looking at may be a few seconds out of date. A screen that silently stops updating
  * is worse than one that says it has stopped.
  */
enum SseConnection {
  case Connecting, Open

  /** Between attempts. `attempt` counts from 1 and is shown, because "reconnecting" that has been trying for
    * twenty minutes means something different from one that started a second ago.
    */
  case Reconnecting(attempt: Int)

  /** Finished, and not coming back without somebody asking. */
  case Closed(reason: String)
}

object SseConnection {
  given CanEqual[SseConnection, SseConnection] = CanEqual.derived
}

/** Why one event, or one stream, did not work out. */
enum SseError {

  /** One event's payload was not what its decoder expected.
    *
    * The stream keeps running. This is the same rule ADR-035 gives the server for a message it cannot
    * deserialize: one bad element must not end a stream that is otherwise delivering good ones.
    */
  case Decode(event: String, cause: String)

  /** The connection itself failed. */
  case Transport(cause: String)

  /** The server's own terminal `error` event (ADR-035) — an ordinary error envelope, deliberately, so that a
    * failure after the headers were sent is handled by the same code as one before.
    */
  case Server(envelope: ErrorEnvelope)
}

object SseError {
  given CanEqual[SseError, SseError] = CanEqual.derived
}

/** A live stream, and the two things a caller does with one: read it, and stop it.
  *
  * @param events
  *   every decoded event, and every event that failed to decode. It does not end on a decode failure.
  * @param connection
  *   what the transport is doing, for the indicator.
  * @param close
  *   stops the stream and releases the server's resources. Idempotent.
  */
final case class SseHandle[A](
    events: EventStream[Either[SseError, A]],
    connection: Signal[SseConnection],
    close: () => Unit
)

/** The slice of the browser's `EventSource` the kernel actually uses.
  *
  * An interface rather than the DOM class directly, for one reason: jsdom — the fake document the frontend
  * suites run against — has no `EventSource` at all, so a test could otherwise only be written against a real
  * browser. With this, everything the kernel does *around* the browser's object (routing named events,
  * turning readiness into `SseConnection`, keeping the stream alive through a decode failure) is testable
  * without one, and only the browser's own behaviour is not.
  */
trait EventSourceLike {
  def addEventListener(name: String, handler: js.Function1[dom.Event, Unit]): Unit
  def close(): Unit

  /** `0` connecting, `1` open, `2` closed — the constants `EventSource` defines. */
  def readyState: Int
}

/** The real thing, wrapping `window.EventSource`. */
final class BrowserEventSource(url: String) extends EventSourceLike {

  private val source: dom.EventSource =
    new dom.EventSource(url, new dom.EventSourceInit { withCredentials = true })

  def addEventListener(name: String, handler: js.Function1[dom.Event, Unit]): Unit =
    source.addEventListener(name, handler)

  def close(): Unit = source.close()

  def readyState: Int = source.readyState
}

/** The browser half of KUI's streaming (ADR-035).
  *
  * Two wrappers, because the browser offers two mechanisms and neither can do the other's job:
  *
  *   - [[eventSource]] uses the native `EventSource`. It reconnects on its own, sends cookies, and survives a
  *     tab being backgrounded. It cannot `POST` and it cannot be given headers. Use it for `GET` streams —
  *     `/api/v1/capabilities/stream` is the one M0 has.
  *   - [[fetchStream]] reads a `fetch` response by hand. It can `POST`, it can carry headers, and it can be
  *     aborted, which the native object cannot. Use it when the stream needs a request body or when the user
  *     must be able to stop it — M3's message browsing needs both.
  */
object Sse {

  /** How long to wait before the *n*th reconnection attempt, before jitter.
    *
    * One second, two, five, then every ten. The shape matters more than the numbers: the first retry is
    * almost immediate, because most disconnections are a proxy timing out an idle connection and reconnecting
    * works instantly; the ceiling is low, because a stream is how the user finds out the world changed, and
    * making them wait a minute for it is worse than a little extra traffic.
    */
  def backoffFor(attempt: Int): FiniteDuration =
    attempt match {
      case n if n <= 1 => 1.second
      case 2 => 2.seconds
      case 3 => 5.seconds
      case _ => 10.seconds
    }

  /** The same, with up to 20% subtracted at random.
    *
    * Without it, every browser that lost the same gateway reconnects in the same millisecond, and the gateway
    * that just came back up is knocked over by its own clients — a thundering herd. The jitter only ever
    * shortens the wait, so the ceiling above stays a ceiling.
    */
  def backoff(attempt: Int, random: () => Double = () => js.Math.random()): FiniteDuration = {
    val base = backoffFor(attempt)
    val factor = 0.8 + (0.2 * random())
    (base.toMillis * factor).toLong.millis
  }

  /** Subscribes to a `GET` stream through the browser's own `EventSource`.
    *
    * @param eventNames
    *   the data events to listen for. The shared events of ADR-035 (`error`, `done`, `heartbeat`) are always
    *   handled and must not be listed: `error` ends the stream with the server's envelope, `done` ends it
    *   cleanly, and `heartbeat` is swallowed — its only job is to stop a proxy from closing an idle
    *   connection, and surfacing it would make every caller filter it out.
    * @param decode
    *   turns `(eventName, data)` into a value. Returning a `Left` reports the event and keeps the stream.
    */
  def eventSource[A](url: String, eventNames: List[String])(
      decode: (String, String) => Either[SseError, A]
  ): SseHandle[A] = eventSourceWith(() => new BrowserEventSource(url), eventNames)(decode)

  /** [[eventSource]] against a source a test supplies. */
  def eventSourceWith[A](open: () => EventSourceLike, eventNames: List[String])(
      decode: (String, String) => Either[SseError, A]
  ): SseHandle[A] = {
    val events = new EventBus[Either[SseError, A]]
    val connection = Var[SseConnection](SseConnection.Connecting)
    val source = open()

    // Per handle, not per application: two streams that both lost their connection are each on their
    // own attempt, and sharing a counter would make one of them report the other's history.
    var attempts = 0

    def emit(value: Either[SseError, A]): Unit = events.writer.onNext(value)

    /** A dropped connection: `EventSource` is either already retrying or has given up, and `readyState` is
      * the only way to tell which.
      */
    def noteTransportProblem(): Unit =
      if source.readyState == ClosedState then
        connection.set(SseConnection.Closed("the connection was lost and will not be retried"))
      else {
        attempts += 1
        connection.set(SseConnection.Reconnecting(attempts))
      }

    source.addEventListener(
      "open",
      (_: dom.Event) => connection.set(SseConnection.Open)
    )

    // `EventSource` reports both a transport failure and a server-sent event *named* `error` as a DOM
    // event of type "error". They are told apart by whether the event carries data: only a message
    // does. Getting this wrong either swallows the server's explanation or invents a disconnection.
    source.addEventListener(
      SseEventName.Error,
      (event: dom.Event) =>
        payloadOf(event) match {
          case Some(data) =>
            emit(Left(decodeEnvelope(data)))
            closeWith(source, connection, "the server sent an error event")
          case None => noteTransportProblem()
        }
    )

    source.addEventListener(
      SseEventName.Done,
      (_: dom.Event) => closeWith(source, connection, "the stream finished")
    )

    // Heartbeats are deliberately not forwarded: they carry `{}` and exist only to keep the connection
    // from being reaped. They do prove the connection is alive, which is why one re-asserts `Open`.
    source.addEventListener(
      SseEventName.Heartbeat,
      (_: dom.Event) => connection.set(SseConnection.Open)
    )

    eventNames.filterNot(SseEventName.shared.contains).foreach { name =>
      source.addEventListener(
        name,
        (event: dom.Event) =>
          payloadOf(event).foreach(data =>
            emit(decode(name, data).left.map {
              case SseError.Decode(_, cause) => SseError.Decode(name, cause)
              case other => other
            })
          )
      )
    }

    SseHandle(
      events = events.events,
      connection = connection.signal,
      close = () => closeWith(source, connection, "closed by the client")
    )
  }

  private def closeWith(source: EventSourceLike, connection: Var[SseConnection], reason: String): Unit = {
    source.close()
    connection.set(SseConnection.Closed(reason))
  }

  /** The `data` of an event, when it has one. A transport error event does not. */
  private def payloadOf(event: dom.Event): Option[String] =
    event match {
      case message: dom.MessageEvent => Option(message.data).map(_.toString)
      case _ => None
    }

  private[sse] def decodeEnvelope(data: String): SseError =
    decode[ErrorEnvelope](data) match {
      case Right(envelope) => SseError.Server(envelope)
      case Left(failure) => SseError.Decode(SseEventName.Error, failure.getMessage)
    }

  private val ClosedState = 2

  /** The browser's UTF-8 decoder.
    *
    * `scalajs-dom` 2.8 does not declare `TextDecoder`, so it is declared here. It is used with
    * `stream = true` so that a multi-byte character split across two network chunks is held back rather than
    * turned into a replacement character — which, in a JSON payload, would be a decode failure for an event
    * that was perfectly well formed on the wire.
    */
  @js.native
  @js.annotation.JSGlobal
  private class TextDecoder extends js.Object {
    def decode(input: js.typedarray.Uint8Array, options: js.Dynamic): String = js.native
  }

  /** One request for [[fetchStream]].
    *
    * A record rather than sttp's `Request` because this bypasses sttp entirely: reading a response body
    * incrementally needs the browser's `ReadableStream`, which sttp's `FetchBackend` consumes for you.
    */
  final case class FetchRequest(
      url: String,
      method: dom.HttpMethod = dom.HttpMethod.GET,
      headers: Map[String, String] = Map.empty,
      body: Option[String] = None
  )

  /** Subscribes to a stream over `fetch`, so that it can `POST` and can be stopped.
    *
    * `close()` aborts the request, which propagates all the way down: the gateway's stream is cancelled, the
    * service's fiber is cancelled and its consumer is closed (ADR-035). That chain is the reason this exists
    * at all — a user who navigates away from a message browser must not leave a Kafka consumer running.
    *
    * There is no reconnection here. The native `EventSource` retries because it can safely replay a `GET`; a
    * `POST` cannot be replayed without asking whether it should be, and that is a decision for the screen
    * that started it (M3), not for the transport.
    */
  def fetchStream[A](request: FetchRequest, eventNames: List[String])(
      decode: (String, String) => Either[SseError, A]
  ): SseHandle[A] = {
    val events = new EventBus[Either[SseError, A]]
    val connection = Var[SseConnection](SseConnection.Connecting)
    val aborter = new dom.AbortController()

    def emit(value: Either[SseError, A]): Unit = events.writer.onNext(value)

    def handle(raw: RawSseEvent): Unit =
      raw.name match {
        case SseEventName.Heartbeat => ()
        case SseEventName.Done => connection.set(SseConnection.Closed("the stream finished"))
        case SseEventName.Error =>
          emit(Left(decodeEnvelope(raw.data)))
          connection.set(SseConnection.Closed("the server sent an error event"))
        case name if eventNames.contains(name) => emit(decode(name, raw.data))
        // An event this caller did not ask for is not a failure: ADR-035 lets a stream add events.
        case _ => ()
      }

    dom
      .fetch(request.url, requestInit(request, aborter.signal))
      .`then`[Unit] { response =>
        connection.set(SseConnection.Open)
        readBody(response, handle, connection)
        ()
      }
      .`catch`[Unit] { cause =>
        // An abort is not a failure: it is what `close()` does, and the state is already `Closed`.
        if !aborter.signal.aborted then {
          emit(Left(SseError.Transport(String.valueOf(cause))))
          connection.set(SseConnection.Closed("the connection could not be established"))
        }
        ()
      }: Unit

    SseHandle(
      events = events.events,
      connection = connection.signal,
      close = () => {
        connection.set(SseConnection.Closed("closed by the client"))
        aborter.abort()
      }
    )
  }

  private def requestInit(request: FetchRequest, abortSignal: dom.AbortSignal): dom.RequestInit =
    new dom.RequestInit {
      method = request.method
      // The same reason `ApiClient` sets it: without it `fetch` omits the session cookie on anything
      // the browser considers cross-origin, which a reverse proxy is enough to cause.
      credentials = dom.RequestCredentials.include
      headers = js.Dictionary(request.headers.toSeq*)
      signal = abortSignal
      request.body.foreach(payload => body = payload)
    }

  /** Pulls chunks off the response body until it ends, feeding each to the parser.
    *
    * Recursion through promises rather than a loop, because there is no way to block: each `read()` resolves
    * when the network has more, and the continuation is the next iteration.
    */
  private def readBody(
      response: dom.Response,
      handle: RawSseEvent => Unit,
      connection: Var[SseConnection]
  ): Unit = {
    val reader = response.body.getReader()
    val decoder = new TextDecoder()

    def pump(state: ParserState): Unit =
      reader
        .read()
        .`then`[Unit] { chunk =>
          if chunk.done then {
            connection.update {
              case SseConnection.Closed(reason) => SseConnection.Closed(reason)
              case _ => SseConnection.Closed("the server closed the stream")
            }
          } else {
            val (next, ready) =
              SseParser.feed(state, decoder.decode(chunk.value, js.Dynamic.literal(stream = true)))
            ready.foreach(handle)
            pump(next)
          }
          ()
        }
        .`catch`[Unit] { _ =>
          connection.update {
            case SseConnection.Closed(reason) => SseConnection.Closed(reason)
            case _ => SseConnection.Closed("the stream ended unexpectedly")
          }
          ()
        }: Unit

    try pump(ParserState.empty)
    catch { case NonFatal(_) => connection.set(SseConnection.Closed("the stream could not be read")) }
  }
}
