package kui.ui.kernel.sse

import scala.collection.mutable
import scala.scalajs.js

import com.raquo.airstream.ownership.ManualOwner
import io.circe.parser.decode
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.sse.SseEventName

/** An `EventSource` a test drives by hand.
  *
  * jsdom, the fake document these suites run against, has no `EventSource` at all, so the wiring around the
  * browser's object could otherwise only be tested in a real browser. This stands in for it: the suite says
  * "the server sent this", "the connection dropped", "the browser gave up", and asserts what the kernel does
  * about it.
  */
final class FakeEventSource extends EventSourceLike {

  private val listeners = mutable.Map.empty[String, List[js.Function1[dom.Event, Unit]]]

  var readyState: Int = 0
  var closed: Boolean = false

  def addEventListener(name: String, handler: js.Function1[dom.Event, Unit]): Unit =
    listeners.update(name, listeners.getOrElse(name, Nil) :+ handler)

  def close(): Unit = {
    closed = true
    readyState = 2
  }

  def open(): Unit = {
    readyState = 1
    dispatch("open", new dom.Event("open"))
  }

  /** The server sent a named event with a payload. */
  def send(name: String, payload: String): Unit =
    dispatch(name, new dom.MessageEvent(name, new dom.MessageEventInit { data = payload }))

  /** The connection dropped. `EventSource` reports this as a bare `error` event with no payload, which is
    * exactly what makes it indistinguishable from a server-sent event *named* `error` except by that absence.
    */
  def drop(stillRetrying: Boolean): Unit = {
    readyState = if stillRetrying then 0 else 2
    dispatch(SseEventName.Error, new dom.Event(SseEventName.Error))
  }

  private def dispatch(name: String, event: dom.Event): Unit =
    listeners.getOrElse(name, Nil).foreach(handler => handler(event))
}

class SseSuite extends FunSuite {

  private val owner = new ManualOwner

  /** Decodes a payload that is expected to be a bare JSON string, so the suite can talk about values. */
  private def decodeText(event: String, data: String): Either[SseError, String] =
    decode[String](data).left.map(failure => SseError.Decode(event, failure.getMessage))

  private def subscribe[A](handle: SseHandle[A]): mutable.ListBuffer[Either[SseError, A]] = {
    val seen = mutable.ListBuffer.empty[Either[SseError, A]]
    handle.events.foreach(value => seen.append(value): Unit)(using owner): Unit
    seen
  }

  override def afterAll(): Unit = owner.killSubscriptions()

  /** A `Signal`'s current value. `now()` is Airstream-internal, and rightly so: a signal only has a
    * current value while something is observing it, and `observe` is how a test says it is.
    */
  private def current[A](signal: com.raquo.laminar.api.L.Signal[A]): A = signal.observe(using owner).now()

  test("routesANamedEventToItsDecoder") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)
    val seen = subscribe(handle)

    source.open()
    source.send("row", "\"hello\"")

    assertEquals(seen.toList, List(Right("hello")))
    assertEquals(current(handle.connection), SseConnection.Open)
  }

  test("aDecodeFailureIsReportedAndTheStreamKeepsRunning") {
    // The rule ADR-035 gives the server for a message it cannot deserialize, applied to the browser:
    // one bad element must not end a stream that is otherwise delivering good ones.
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)
    val seen = subscribe(handle)

    source.open()
    source.send("row", "{not json")
    source.send("row", "\"still here\"")

    assertEquals(seen.size, 2)
    assert(seen.head.left.exists {
      case SseError.Decode(event, _) => event == "row"
      case _ => false
    })
    assertEquals(seen.last, Right("still here"))
    assertEquals(current(handle.connection), SseConnection.Open)
  }

  test("aHeartbeatIsNotForwardedButDoesProveTheConnectionIsAlive") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)
    val seen = subscribe(handle)

    source.send(SseEventName.Heartbeat, "{}")

    assertEquals(seen.toList, Nil)
    assertEquals(current(handle.connection), SseConnection.Open)
  }

  test("theServersErrorEventEndsTheStreamWithItsEnvelope") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)
    val seen = subscribe(handle)

    source.open()
    source.send(
      SseEventName.Error,
      """{"code":"KUI-UPSTREAM-UNAVAILABLE","message":"schema-registry could not be reached",
        |"details":[],"correlationId":"3b1fa9c2e4d54f0b","timestamp":"2026-09-03T10:11:12.000Z",
        |"retryable":true}""".stripMargin.replace("\n", "")
    )

    seen.toList match {
      case Left(SseError.Server(envelope)) :: Nil =>
        assertEquals(envelope.code, "KUI-UPSTREAM-UNAVAILABLE")
        assertEquals(envelope.retryable, true)
      case other => fail(s"expected the server's envelope, got $other")
    }
    assert(source.closed)
    assertEquals(current(handle.connection), SseConnection.Closed("the server sent an error event"))
  }

  test("aDroppedConnectionTheBrowserIsRetryingBecomesReconnectingAndCountsUp") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)

    source.open()
    source.drop(stillRetrying = true)
    assertEquals(current(handle.connection), SseConnection.Reconnecting(1))

    source.drop(stillRetrying = true)
    assertEquals(current(handle.connection), SseConnection.Reconnecting(2))
  }

  test("aDroppedConnectionTheBrowserGaveUpOnBecomesClosed") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)

    source.open()
    source.drop(stillRetrying = false)

    assertEquals(
      current(handle.connection),
      SseConnection.Closed("the connection was lost and will not be retried")
    )
  }

  test("twoStreamsCountTheirOwnReconnectionAttempts") {
    // The attempt counter has to live on the handle. Sharing one would make a second stream report
    // the first one's history, which is exactly the kind of bug nobody notices until an incident.
    val first = new FakeEventSource
    val second = new FakeEventSource
    val firstHandle = Sse.eventSourceWith(() => first, Nil)(decodeText)
    val secondHandle = Sse.eventSourceWith(() => second, Nil)(decodeText)

    first.drop(stillRetrying = true)
    first.drop(stillRetrying = true)
    second.drop(stillRetrying = true)

    assertEquals(current(firstHandle.connection), SseConnection.Reconnecting(2))
    assertEquals(current(secondHandle.connection), SseConnection.Reconnecting(1))
  }

  test("closeStopsTheConnectionAndSaysWho") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)

    source.open()
    handle.close()

    assert(source.closed)
    assertEquals(current(handle.connection), SseConnection.Closed("closed by the client"))
  }

  test("theDoneEventEndsTheStreamCleanly") {
    val source = new FakeEventSource
    val handle = Sse.eventSourceWith(() => source, List("row"))(decodeText)
    val seen = subscribe(handle)

    source.open()
    source.send(SseEventName.Done, """{"reason":"exhausted","cursor":null}""")

    assertEquals(seen.toList, Nil)
    assertEquals(current(handle.connection), SseConnection.Closed("the stream finished"))
  }

  test("backoffIsOneTwoFiveThenTenWithJitterThatOnlyShortens") {
    assertEquals(Sse.backoffFor(1).toSeconds, 1L)
    assertEquals(Sse.backoffFor(2).toSeconds, 2L)
    assertEquals(Sse.backoffFor(3).toSeconds, 5L)
    assertEquals(Sse.backoffFor(4).toSeconds, 10L)
    assertEquals(Sse.backoffFor(99).toSeconds, 10L)

    // The jitter exists so that every browser that lost the same gateway does not reconnect in the
    // same millisecond. It may only shorten the wait, so the ceiling above stays a ceiling.
    (1 to 6).foreach { attempt =>
      val shortest = Sse.backoff(attempt, () => 0.0).toMillis
      val longest = Sse.backoff(attempt, () => 1.0).toMillis
      val base = Sse.backoffFor(attempt).toMillis
      assertEquals(longest, base)
      assertEquals(shortest, (base * 0.8).toLong)
    }
  }

  test("aMalformedErrorEventIsADecodeFailureRatherThanACrash") {
    assert(Sse.decodeEnvelope("not an envelope") match {
      case SseError.Decode(SseEventName.Error, _) => true
      case _ => false
    })
  }
}
