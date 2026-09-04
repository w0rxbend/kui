package kui.ui.consumers.list

import scala.collection.mutable
import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.consumer.contract.ConsumerEndpoints
import kui.consumer.contract.dto.{LagDeltaDto, LagUpdateDto}
import kui.contracts.consumer.GroupSummaryDto
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{ClusterId, GroupId}
import kui.ui.kernel.api.ApiError

/** The lag poller and the fold that applies its answers.
  *
  * The clock and the request are both parameters, so nothing here waits for real seconds — a poller whose
  * test takes half a minute is a poller whose test stops being run.
  */
class LagPollingSuite extends FunSuite {

  /** Mounts an element for the duration of one check. Mounting is not optional: a Laminar binding only
    * becomes active once its element is in the document, so an element built but never mounted has none of
    * its subscriptions running and a test that inspects one is testing nothing.
    */
  private def mounted[A](element: HtmlElement)(check: org.scalajs.dom.Element => A): A = {
    val container = org.scalajs.dom.document.createElement("div")
    org.scalajs.dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      org.scalajs.dom.document.body.removeChild(container): Unit
    }
  }


  /** The poller's view, read once. A `Signal` is only observable while something is subscribed to it, and
    * the suite is not the element, so it takes its own momentary subscription rather than reaching for the
    * `Var` behind it.
    */
  private def viewOf(poller: LagPoller): LagView = {
    var seen = LagView.Empty
    val owner = new com.raquo.airstream.ownership.ManualOwner
    poller.view.foreach(current => seen = current)(using owner): Unit
    owner.killSubscriptions()
    seen
  }

  private val cluster: ClusterId = ClusterId.from("prod").getOrElse(fail("'prod' should be a legal id"))

  private def group(raw: String): GroupId = GroupId.unsafe(raw)

  private def row(id: String, lag: Option[Long], members: Int = 2): GroupSummaryDto =
    GroupSummaryDto(
      groupId = group(id),
      state = GroupState.Stable,
      protocol = GroupProtocol.Consumer,
      isSimple = false,
      members = members,
      topics = 1,
      partitions = 12,
      coordinatorId = Some(1),
      totalLag = lag,
      pace = None,
      excludedPartitions = 0,
      incomplete = None
    )

  private def update(id: String, lag: Option[Long], state: GroupState = GroupState.Stable): LagUpdateDto =
    LagUpdateDto(group(id), totalLag = lag, pace = None, state = state, members = 3)

  private def delta(
      changed: List[LagUpdateDto] = Nil,
      gone: List[GroupId] = Nil,
      token: String = "t1",
      nextPollMs: Long = 5000L,
      full: Boolean = false
  ): LagDeltaDto = LagDeltaDto(changed, gone, token, nextPollMs, full)

  // --- The fold ----------------------------------------------------------------------------------

  test("aDeltaMergesSoAnUnmentionedGroupKeepsTheFigureItHad") {
    val first = LagFeed.merge(LagView.Empty, delta(changed = List(update("a", Some(10L)))))
    val second = LagFeed.merge(first, delta(changed = List(update("b", Some(20L)))))

    assertEquals(second.changed.get(group("a")).flatMap(_.totalLag), Some(10L))
    assertEquals(second.changed.get(group("b")).flatMap(_.totalLag), Some(20L))
  }

  test("aFullPayloadReplacesRatherThanMerging") {
    // A full payload is the server saying "start again" — it is what an unrecognised, expired or
    // restarted-service token is answered with. Merging one leaves every group deleted since on screen for
    // ever, showing the lag it had when it died.
    val first = LagFeed.merge(LagView.Empty, delta(changed = List(update("a", Some(10L)))))
    val replaced = LagFeed.merge(first, delta(changed = List(update("b", Some(20L))), full = true))

    assertEquals(replaced.changed.keySet, Set(group("b")))
  }

  test("aGoneGroupIsRemovedFromTheRowsRatherThanShowingItsLastLag") {
    // A deleted group is not a group that has caught up.
    val view = LagFeed.merge(LagView.Empty, delta(gone = List(group("b"))))
    val drawn = LagFeed.applyTo(List(row("a", Some(1L)), row("b", Some(9L))), view)

    assertEquals(drawn.map(_.groupId.value), List("a"))
  }

  test("aGroupThatCameBackIsNoLongerGone") {
    val gone = LagFeed.merge(LagView.Empty, delta(gone = List(group("a"))))
    val back = LagFeed.merge(gone, delta(changed = List(update("a", Some(5L)))))

    assertEquals(back.gone, Set.empty[GroupId])
    assertEquals(back.changed.get(group("a")).flatMap(_.totalLag), Some(5L))
  }

  test("onlyTheFourFieldsThePollCarriesAreRepainted") {
    // The topic count, the partition count and the coordinator come from the list endpoint. A poll that
    // carried a whole group summary every few seconds would be the most expensive request in the product.
    val before = row("a", Some(10L))
    val view = LagFeed.merge(LagView.Empty, delta(changed = List(update("a", Some(4L), GroupState.Empty))))
    val after = LagFeed.applyTo(List(before), view).head

    assertEquals(after.totalLag, Some(4L))
    assertEquals(after.state, GroupState.Empty)
    assertEquals(after.members, 3)
    assertEquals(after.partitions, before.partitions)
    assertEquals(after.coordinatorId, before.coordinatorId)
  }

  test("aLagThatIsNotKnownStaysNotKnownRatherThanBecomingZero") {
    val view = LagFeed.merge(LagView.Empty, delta(changed = List(update("a", None))))
    assertEquals(LagFeed.applyTo(List(row("a", Some(10L))), view).head.totalLag, None)
  }

  // --- The interval ------------------------------------------------------------------------------

  test("theServersIntervalIsObeyedAndOnlyItsExtremesAreClamped") {
    assertEquals(LagPoller.intervalOf(5000L), 5.seconds)
    // A `nextPollMs` of zero would turn every open browser into a tight loop against the thing that sent it.
    assertEquals(LagPoller.intervalOf(0L), LagPoller.MinimumInterval)
    // And an hour is a poller that has been turned off with nothing on screen saying so.
    assertEquals(LagPoller.intervalOf(3600000L), LagPoller.MaximumInterval)
  }

  // --- The poller --------------------------------------------------------------------------------

  final private class Rig(answers: List[Either[ApiError, LagDeltaDto]]) {
    val asked: mutable.ListBuffer[(Set[GroupId], Option[String])] = mutable.ListBuffer.empty
    val waited: mutable.ListBuffer[FiniteDuration] = mutable.ListBuffer.empty

    private val fire: EventBus[Unit] = new EventBus[Unit]
    private var remaining: List[Either[ApiError, LagDeltaDto]] = answers

    /** Releases the wait the poller is sitting in, which is how the test advances time. */
    def tick(): Unit = fire.writer.onNext(())

    val groups: Var[Set[GroupId]] = Var(Set(group("a"), group("b")))

    val poller: LagPoller = new LagPoller(
      cluster = cluster,
      groups = groups.signal,
      poll = (_, ids, since) => {
        asked.append((ids, since))
        remaining match {
          case head :: tail => remaining = tail; EventStream.fromValue(head)
          case Nil => EventStream.empty
        }
      },
      timer = wait => { waited.append(wait); fire.events }
    )
  }

  test("theFirstPollAsksAboutTheGroupsOnScreenWithNoToken") {
    val rig = new Rig(List(Right(delta(changed = List(update("a", Some(7L)))))))
    mounted(div(rig.poller.binder)) { _ =>
      assertEquals(rig.asked.toList.map(_._2), List(None))
      assertEquals(rig.asked.head._1, Set(group("a"), group("b")))
      assertEquals(viewOf(rig.poller).changed.get(group("a")).flatMap(_.totalLag), Some(7L))
    }
  }

  test("theTokenFromEachAnswerGoesBackWithTheNext") {
    val rig = new Rig(List(Right(delta(token = "t1")), Right(delta(token = "t2"))))
    mounted(div(rig.poller.binder)) { _ =>
      rig.tick()
      assertEquals(rig.asked.toList.map(_._2), List(None, Some("t1")))
    }
  }

  test("theServerDecidesHowLongToWait") {
    // The one mechanism by which a struggling consumer service can slow every open browser at once. A
    // client-side interval means the moment a service starts struggling is the moment its traffic does not
    // change at all.
    val rig = new Rig(List(Right(delta(nextPollMs = 12000L))))
    mounted(div(rig.poller.binder)) { _ =>
      assertEquals(rig.waited.toList, List(12.seconds))
    }
  }

  test("aFailedPollBacksOffAndLeavesTheRowsAlone") {
    val rig = new Rig(List(Left(ApiError.Unreachable("connection refused"))))
    mounted(div(rig.poller.binder)) { _ =>
      assertEquals(rig.waited.toList, List(LagPoller.RetryAfter))
      // The rows on screen are still the rows the list endpoint sent, and they are still true as of when it
      // sent them. Blanking them because a poll failed would lose information the user already has.
      assertEquals(viewOf(rig.poller), LagView.Empty)
    }
  }

  test("changingWhatIsOnScreenPollsImmediatelyRatherThanWaitingOutTheInterval") {
    val rig = new Rig(List(Right(delta(token = "t1")), Right(delta(token = "t2"))))
    mounted(div(rig.poller.binder)) { _ =>
      rig.groups.set(Set(group("c")))
      assertEquals(rig.asked.toList.map(_._1), List(Set(group("a"), group("b")), Set(group("c"))))
    }
  }

  test("aPageBiggerThanTheUrlLimitIsAskedAboutInPartRatherThanNotAtAll") {
    // Over the limit the query string is truncated by some proxies and refused with a 414 by others, and
    // from the browser both look identical to a lag column that has simply stopped moving.
    val many = (1 to ConsumerEndpoints.MaxLagGroups + 20).map(n => group(s"group-$n")).toSet
    val rig = new Rig(List(Right(delta())))
    rig.groups.set(many)
    mounted(div(rig.poller.binder)) { _ =>
      assertEquals(rig.asked.head._1.size, ConsumerEndpoints.MaxLagGroups)
    }
  }

  test("unmountingTheScreenStopsThePolling") {
    val rig = new Rig(List(Right(delta()), Right(delta())))
    mounted(div(rig.poller.binder))(_ => ())
    val afterUnmount = rig.asked.size
    rig.tick()
    assertEquals(rig.asked.size, afterUnmount, clue = "a poll ran for a screen nobody is looking at")
  }
}
