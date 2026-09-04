package kui.ui.messages

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

import scala.collection.mutable

import java.time.Instant

import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.contracts.message.DecodedPayloadDto
import kui.message.contract.MessageDto
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

  /** One delivered record, distinguishable from another only by its offset — which is all these tests care
    * about, because what is being checked is which records are on screen and not what is in them.
    */
  private def record(offset: Long): MessageDto =
    MessageDto(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(offset),
      timestamp = Instant.parse("2026-09-04T09:00:00Z"),
      timestampType = MessageDto.TimestampType.CreateTime,
      key = DecodedPayloadDto(text = "", kind = DecodedPayloadDto.Kind.Text, serde = "String", properties = Map.empty),
      value = DecodedPayloadDto(text = "", kind = DecodedPayloadDto.Kind.Text, serde = "String", properties = Map.empty),
      headers = Map.empty,
      keySize = 0,
      valueSize = 0,
      headersSize = 0,
      deserializeErrors = Nil
    )

  /** A session that records the browses it opens and lets a test say where each one ended. */
  final private class Rig(markers: List[Option[String]]) {
    val opened: mutable.ListBuffer[BrowseQuery] = mutable.ListBuffer.empty
    val connection: Var[SseConnection] = Var[SseConnection](SseConnection.Connecting)
    // One bus per browse, as the real transport gives one connection per request. Sharing a single bus
    // would deliver each record to every browse this test has ever started, which is a property of the rig
    // and not of the session under test.
    private val buses: mutable.ListBuffer[EventBus[Either[SseError, BrowseEvent]]] = mutable.ListBuffer.empty
    private var remaining = markers

    /** Delivers a record on the browse that is currently open. */
    def deliver(offset: Long): Unit =
      buses.lastOption.foreach(_.writer.onNext(Right(BrowseEvent.Record(record(offset)))))

    val browse: BrowseSession =
      new BrowseSession(
        apiRoot = "/api/v1",
        cluster = ClusterId.unsafe("quickstart"),
        topic = TopicName.unsafe("orders.v1"),
        open = (_, _, _, query: BrowseQuery) => {
          opened.append(query)
          val marker = remaining.headOption.flatten
          remaining = remaining.drop(1)
          val bus = new EventBus[Either[SseError, BrowseEvent]]
          buses.append(bus)
          SseHandle(bus.events, connection.signal, () => (), () => marker)
        }
      )

    /** Ends the browse the way a finished one ends: the terminal `done` event closes the connection. */
    def finish(): Unit = {
      connection.set(SseConnection.Open)
      connection.set(SseConnection.Closed("the browse finished"))
      connection.set(SseConnection.Connecting)
    }
  }

  private def canLoadMore(browse: BrowseSession): Boolean = {
    var latest = false
    browse.canLoadMore.foreach(value => latest = value): Unit
    latest
  }

  private def rows(browse: BrowseSession): List[MessageDto] = {
    var latest = List.empty[MessageDto]
    browse.rows.foreach(value => latest = value): Unit
    latest
  }

  test("thereIsNoNextPageUntilTheServerSaysThereIs") {
    // The server omits the cursor whenever asking again would be pointless. Guessing from a full page
    // instead would put a Load more button under the last page of every topic.
    val rig = new Rig(List(None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.finish()
    assertEquals(canLoadMore(rig.browse), false)
  }

  test("aFinishedBrowseThatSentACursorOffersOne") {
    val rig = new Rig(List(Some("cursor.v1.signed")))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.finish()
    assertEquals(canLoadMore(rig.browse), true)
  }

  test("loadMoreSendsTheServersCursorBackAndNoStartPositionOfItsOwn") {
    // The browser cannot compute the next offsets and does not try: forward and backward boundaries are
    // different numbers for the same place. Handing the cursor back is the whole of paging.
    val rig = new Rig(List(Some("cursor.v1.signed"), None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.finish()
    rig.browse.loadMore().foreach(_ => ()): Unit

    assertEquals(rig.opened.size, 2)
    assertEquals(rig.opened.last.cursor, Some("cursor.v1.signed"))
  }

  test("aSecondPageIsAppendedRatherThanReplacingTheFirst") {
    val rig = new Rig(List(Some("cursor.v1.signed"), None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.deliver(0L)
    rig.finish()
    rig.browse.loadMore().foreach(_ => ()): Unit
    rig.deliver(1L)

    assertEquals(rows(rig.browse).map(_.offset.value).sorted, List(0L, 1L))
  }

  test("startingAFreshBrowseClearsWhatTheLastOneDelivered") {
    val rig = new Rig(List(Some("cursor.v1.signed"), None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.deliver(0L)
    rig.finish()
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit

    assertEquals(rows(rig.browse), Nil, clue = "a new range was mixed into the old one")
  }

  test("theCursorIsSpentTheMomentTheNextPageStarts") {
    // Otherwise Load more goes on offering the page that is already being read.
    val rig = new Rig(List(Some("cursor.v1.signed"), None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.finish()
    rig.browse.loadMore().foreach(_ => ()): Unit
    assertEquals(canLoadMore(rig.browse), false)
  }

  test("loadMoreWithNoCursorDoesNothingRatherThanReReadingTheFirstPage") {
    // A button that quietly scrolled the user back to where they began is worse than one that does nothing.
    val rig = new Rig(List(None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.finish()
    rig.browse.loadMore().foreach(_ => ()): Unit
    assertEquals(rig.opened.size, 1)
  }

  private def held(browse: BrowseSession): Int = {
    var latest = 0
    browse.held.foreach(value => latest = value): Unit
    latest
  }

  test("aPausedTailHoldsNewRecordsBackWithoutClosingTheStream") {
    // Pausing is not stopping, and on a tail that is the whole distinction. Stopping would close the
    // consumer, and the records produced while somebody reads the row that caught their eye would be gone
    // with no way back to them.
    val rig = new Rig(List(None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.deliver(0L)
    rig.browse.setPaused(true)
    rig.deliver(1L)
    rig.deliver(2L)

    assertEquals(rows(rig.browse).map(_.offset.value), List(0L))
    assertEquals(held(rig.browse), 2)
  }

  test("resumingShowsWhatArrivedWhilePausedInTheOrderItArrived") {
    val rig = new Rig(List(None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.deliver(0L)
    rig.browse.setPaused(true)
    rig.deliver(1L)
    rig.deliver(2L)
    rig.browse.setPaused(false)

    // Newest first, which is the order the table is in: the pause changes when rows appear, never which
    // ones or in what order.
    assertEquals(rows(rig.browse).map(_.offset.value), List(2L, 1L, 0L))
    assertEquals(held(rig.browse), 0)
  }

  test("stoppingAPausedTailShowsWhatWasHeldRatherThanDiscardingIt") {
    // Those records were delivered before the press. Throwing them away because the user pressed Stop
    // would lose evidence that had already arrived.
    val rig = new Rig(List(None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.browse.setPaused(true)
    rig.deliver(1L)
    rig.browse.stop()

    assertEquals(rows(rig.browse).map(_.offset.value), List(1L))
  }

  test("aNewBrowseStartsUnpaused") {
    // Carrying a pause across a Read would leave the user pressing a button that appears to do nothing.
    val rig = new Rig(List(None, None))
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.browse.setPaused(true)
    rig.browse.start(BrowseQuery.Default).foreach(_ => ()): Unit
    rig.deliver(5L)

    assertEquals(rows(rig.browse).map(_.offset.value), List(5L))
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
