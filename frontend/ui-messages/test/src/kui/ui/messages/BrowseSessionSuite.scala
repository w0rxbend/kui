package kui.ui.messages

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.kernel.{ClusterId, TopicName}
import kui.ui.kernel.sse.{SseConnection, SseError, SseHandle}
import kui.ui.messages.browse.{BrowseEvent, BrowseQuery, BrowseSession}

/** What "a browse is running" means, and when it stops meaning it.
  *
  * The Read button reads `BrowseSession.running` and turns into Stop while it is true. A bounded browse ends
  * by itself the moment it has read its limit — which is what every browse that is not a live tail does — and
  * the session used to hold on to the finished stream's handle for ever. The status line said "Finished"
  * beside a button offering to stop it, and there was no way back to Read short of reloading the page.
  */
final class BrowseSessionSuite extends FunSuite {

  private given owner: ManualOwner = new ManualOwner

  /** A session over a stream this test drives by hand. */
  private def session(
      connection: Var[SseConnection],
      events: EventBus[Either[SseError, BrowseEvent]],
      closed: Var[Int]
  ): BrowseSession =
    new BrowseSession(
      apiRoot = "/api/v1",
      cluster = ClusterId.unsafe("quickstart"),
      topic = TopicName.unsafe("orders.v1"),
      open = (_, _, _, _: BrowseQuery) =>
        SseHandle(events.events, connection.signal, () => closed.update(_ + 1))
    )

  private def started(
      connection: Var[SseConnection],
      events: EventBus[Either[SseError, BrowseEvent]],
      closed: Var[Int]
  ): BrowseSession = {
    val browse = session(connection, events, closed)
    browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    browse
  }

  /** `running` as a plain value. A `Signal` only holds one while something observes it. */
  private def isRunning(browse: BrowseSession): Boolean = {
    var latest = false
    browse.running.foreach(value => latest = value): Unit
    latest
  }

  test("aBrowseIsRunningWhileItsStreamIsOpen") {
    val connection = Var[SseConnection](SseConnection.Connecting)
    val browse = started(connection, new EventBus, Var(0))
    connection.set(SseConnection.Open)
    assertEquals(isRunning(browse), true)
  }

  test("aBrowseThatEndsByItselfStopsBeingRunning") {
    val connection = Var[SseConnection](SseConnection.Connecting)
    val browse = started(connection, new EventBus, Var(0))
    connection.set(SseConnection.Open)
    connection.set(SseConnection.Closed("the browse finished"))
    assertEquals(isRunning(browse), false, "the Read button must come back once the browse has finished")
  }

  test("stopClosesTheStreamAndIsIdempotent") {
    val closed = Var(0)
    val browse = started(Var[SseConnection](SseConnection.Open), new EventBus, closed)
    browse.stop()
    browse.stop()
    assertEquals(closed.now(), 1, "unmount and the Stop button both reach stop(); it must close once")
    assertEquals(isRunning(browse), false)
  }
}
