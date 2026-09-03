package kui.ui.clusters

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.duration.*

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import sttp.tapir.PublicEndpoint

import kui.cluster.contract.dto.RefreshAcceptedDto
import kui.contracts.ErrorEnvelope
import kui.ui.clusters.dashboard.ClusterFixtures
import kui.ui.kernel.api.{ApiClient, ApiError}

/** The schedule, and the four ways it ends.
  *
  * Every timer is a fake the test fires by hand: a suite that waited fifteen real seconds to check a
  * fifteen-second contract is a suite nobody runs, and a suite nobody runs protects nothing.
  */
class RefreshFlowSuite extends FunSuite {

  private val cluster = ClusterFixtures.clusterId("local")
  private val baseline = Instant.parse("2026-09-03T12:00:00Z")

  final private class FakeTimer {
    private val buses = mutable.Map.empty[FiniteDuration, EventBus[Unit]]
    val requested: mutable.ListBuffer[FiniteDuration] = mutable.ListBuffer.empty

    def apply(after: FiniteDuration): EventStream[Unit] = {
      requested.append(after): Unit
      buses.getOrElseUpdate(after, new EventBus[Unit]).events
    }

    def fire(after: FiniteDuration): Unit = buses.get(after).foreach(_.writer.onNext(()))
  }

  final private class FakeApi extends ApiClient {
    private val refreshes = new EventBus[Either[ApiError, RefreshAcceptedDto]]
    val posts: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    def call[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I): EventStream[Either[ApiError, O]] = {
      posts.append(endpoint.info.name.getOrElse("?")): Unit
      refreshes.events.map(_.map(_.asInstanceOf[O]))
    }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def accept(): Unit = refreshes.writer.onNext(Right(RefreshAcceptedDto(cluster, baseline)))
    def refuse(error: ApiError): Unit = refreshes.writer.onNext(Left(error))
    def refreshCount: Int = posts.count(_ == "clusters.refresh")
  }

  final private class Fixture(using owner: Owner) {
    val api = new FakeApi
    val timer = new FakeTimer
    val scrapedAt: Var[Option[Instant]] = Var(Some(baseline))

    val flow: RefreshFlow =
      new RefreshFlow(cluster, new ClustersQueries(api), scrapedAt.signal, timer = timer.apply)

    def status: RefreshStatus = flow.status.observe(using owner).now()
  }

  private def withFixture(check: (Fixture, ManualOwner) => Unit): Unit = {
    given owner: ManualOwner = new ManualOwner
    try check(new Fixture, owner)
    finally owner.killSubscriptions()
  }

  test("noPostIsEverSentWithoutAClick") {
    // The standing guarantee: the browser does not poll and does not ask a cluster to be re-read on its
    // own. Building the flow, and watching it, must send nothing.
    withFixture { (fixture, _) =>
      assertEquals(fixture.api.refreshCount, 0)
      assertEquals(fixture.status, RefreshStatus.Idle)
    }
  }

  test("theScheduleIsExactlyOneThreeSixTenFifteen") {
    withFixture { (fixture, _) =>
      fixture.flow.request()
      fixture.api.accept()
      // Asserted against the timers actually asked for, so changing the schedule is a deliberate act
      // rather than a side effect of editing something nearby.
      assertEquals(fixture.timer.requested.toList, List(1.second, 3.seconds, 6.seconds, 10.seconds, 15.seconds))
    }
  }

  test("aSuccessfulRefreshStopsAtTheFirstAdvancedTimestamp") {
    withFixture { (fixture, _) =>
      fixture.flow.request()
      fixture.api.accept()

      fixture.timer.fire(1.second)
      assert(fixture.status.isInstanceOf[RefreshStatus.Running], fixture.status.toString)

      fixture.scrapedAt.set(Some(baseline.plusSeconds(5)))
      fixture.timer.fire(3.seconds)
      assertEquals(fixture.status, RefreshStatus.Completed(baseline.plusSeconds(5)))

      // And it stops: a later timer that still fires must not reopen a finished flow.
      fixture.timer.fire(6.seconds)
      assertEquals(fixture.status, RefreshStatus.Completed(baseline.plusSeconds(5)))
    }
  }

  test("anIdenticalPayloadWithAnAdvancedTimestampCountsAsSuccess") {
    // The case that fails if anybody ever compares payloads instead of timestamps: two consecutive scrapes
    // of an unchanged cluster are identical bytes, and reporting "nothing happened" for a refresh that
    // worked perfectly is the wrong answer to the question the user asked.
    withFixture { (fixture, _) =>
      fixture.flow.request()
      fixture.api.accept()
      fixture.scrapedAt.set(Some(baseline.plusSeconds(1)))
      fixture.timer.fire(1.second)
      assertEquals(fixture.status, RefreshStatus.Completed(baseline.plusSeconds(1)))
    }
  }

  test("noAdvanceInFifteenSecondsEndsInTimedOut") {
    withFixture { (fixture, _) =>
      fixture.flow.request()
      fixture.api.accept()
      List(1.second, 3.seconds, 6.seconds, 10.seconds, 15.seconds).foreach(fixture.timer.fire)
      // Not "failed": the server accepted it and may still be working. The sentence says so.
      assertEquals(fixture.status, RefreshStatus.TimedOut)
    }
  }

  test("aSecondClickWhileRunningIsIgnored") {
    withFixture { (fixture, _) =>
      fixture.flow.request()
      fixture.api.accept()
      fixture.flow.request()
      assertEquals(fixture.api.refreshCount, 1)
    }
  }

  test("aRejectedPostEndsInRejectedAndIssuesNoReads") {
    withFixture { (fixture, _) =>
      fixture.flow.request()
      val envelope = ApiError.Envelope("KUI-FORBIDDEN", "not permitted", Nil, "req-1", retryable = false)
      fixture.api.refuse(envelope)
      assertEquals(fixture.status, RefreshStatus.Rejected(envelope))
      assertEquals(fixture.timer.requested.toList, Nil)
      // A button that fails identically on every press is worse than one that says it has stopped trying.
      assertEquals(fixture.flow.enabled.observe(using new ManualOwner).now(), false)
    }
  }

  test("aFlowWhoseOwnerIsKilledMidScheduleIssuesNoFurtherReads") {
    // The navigate-away case. A timer that outlives its page is a request nobody is waiting for.
    given owner: ManualOwner = new ManualOwner
    val fixture = new Fixture
    fixture.flow.request()
    fixture.api.accept()
    fixture.timer.fire(1.second)

    owner.killSubscriptions()
    fixture.scrapedAt.set(Some(baseline.plusSeconds(5)))
    fixture.timer.fire(3.seconds)
    // Nothing crashed and nothing advanced: the subscriptions went with the owner.
    assertEquals(fixture.api.refreshCount, 1)
  }

  test("everyStatusHasASentenceExceptIdle") {
    assertEquals(RefreshFlow.describe(RefreshStatus.Idle), None)
    assert(RefreshFlow.describe(RefreshStatus.TimedOut).exists(_.contains("still be running")))
    assert(RefreshFlow.describe(RefreshStatus.Completed(baseline)).isDefined)
    assert(
      RefreshFlow
        .describe(RefreshStatus.Rejected(ApiError.Envelope("KUI-FORBIDDEN", "not permitted", Nil, "req-1", retryable = false)))
        // The envelope's own words, unedited: it is the string the operator can act on.
        .exists(_.contains("not permitted"))
    )
  }
}
