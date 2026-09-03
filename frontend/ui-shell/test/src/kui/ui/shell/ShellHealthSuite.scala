package kui.ui.shell

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.scalajs.js

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite

import kui.ui.kernel.api.ApiError

/** When the full-screen state appears, when it must not, and what it does while it is there.
  *
  * Every test drives the clock and the timer by hand. A suite that waited real seconds for a thirty-second
  * cap would take half a minute per assertion and would be skipped by everybody within a week.
  */
class ShellHealthSuite extends FunSuite {

  private final class Fixture {
    private var millis: Double = 1_700_000_000_000.0

    val timers: mutable.ListBuffer[(FiniteDuration, () => Unit)] = mutable.ListBuffer.empty
    var retries: Int = 0

    val health: Health = new Health(
      now = () => new js.Date(millis),
      schedule = (delay, action) => timers.append((delay, action)): Unit,
      onRetry = () => retries += 1
    )

    def advance(by: FiniteDuration): Unit = millis += by.toMillis.toDouble

    /** Runs every timer that is due, once. */
    def fireTimers(): Unit = {
      val due = timers.toList
      timers.clear()
      due.foreach((_, action) => action())
    }

    /** Runs the countdown forward by `seconds` ticks. */
    def tick(seconds: Int): Unit = (1 to seconds).foreach(_ => fireTimers())

    def failShell(times: Int): Unit =
      (1 to times).foreach(_ => health.report(CallScope.Shell, Left(ApiError.Unreachable("offline"))))
  }

  private val owner = new ManualOwner

  override def afterAll(): Unit = owner.killSubscriptions()

  private def current(fixture: Fixture): ShellConnectivity =
    fixture.health.connectivity.observe(using owner).now()

  private def isLost(state: ShellConnectivity): Boolean = state match {
    case ShellConnectivity.Lost(_, _, _) => true
    case ShellConnectivity.Connected(_) => false
  }

  private def remaining(state: ShellConnectivity): Int = state match {
    case ShellConnectivity.Lost(_, _, seconds) => seconds
    case ShellConnectivity.Connected(_) => fail(s"expected the lost state, got $state")
  }

  test("aFeatureCallFailureDoesNotTriggerTheFullScreenState") {
    // The distinction the whole task exists for. Taking the entire application away from a user
    // because one feature's endpoint is down throws them out of everything that still worked.
    val fixture = new Fixture
    (1 to 10).foreach(_ =>
      fixture.health.report(CallScope.Feature, Left(ApiError.Unreachable("offline")))
    )
    assert(!isLost(current(fixture)))
  }

  test("threeShellCallFailuresTriggerItAndOneOrTwoDoNot") {
    // One transient failure must not flash the screen: a laptop's wifi hiccups several times a day,
    // and a full-screen takeover for each of them is worse than the hiccup.
    val fixture = new Fixture
    fixture.failShell(1)
    assert(!isLost(current(fixture)), "one failure must not take the application away")
    fixture.failShell(1)
    assert(!isLost(current(fixture)), "two failures must not either")
    fixture.failShell(1)
    assert(isLost(current(fixture)), "three consecutive failures is an outage")
  }

  test("aSuccessResetsTheCountSoTwoFailuresEitherSideOfOneDoNotAddUp") {
    val fixture = new Fixture
    fixture.failShell(2)
    fixture.health.report(CallScope.Shell, Right(()))
    fixture.failShell(2)
    assert(!isLost(current(fixture)))
  }

  test("aServerThatAnsweredWithAnErrorIsNotUnreachable") {
    // A 403 or a 404 is the gateway answering, and answering is the opposite of being unreachable.
    // Escalating one to the full-screen state would hide a permission problem behind a network one.
    val fixture = new Fixture
    val forbidden = ApiError.Envelope("KUI-FORBIDDEN", "No.", Nil, "corr", retryable = false)
    (1 to 5).foreach(_ => fixture.health.report(CallScope.Shell, Left(forbidden)))
    assert(!isLost(current(fixture)))
  }

  test("aDecodingFailureIsNotUnreachableEither") {
    // Something answered, so the gateway is reachable; the two sides simply disagree about the
    // contract. Showing "cannot reach the server" would send an operator to look at the network for
    // a bug that is in the code.
    val fixture = new Fixture
    (1 to 5).foreach(_ =>
      fixture.health.report(CallScope.Shell, Left(ApiError.Decoding("<html>502</html>")))
    )
    assert(!isLost(current(fixture)))
  }

  test("theCountdownIsVisibleAndDecrements") {
    val fixture = new Fixture
    fixture.failShell(3)
    assertEquals(remaining(current(fixture)), 2)

    fixture.tick(1)
    assertEquals(remaining(current(fixture)), 1)
  }

  test("backoffIsTwoFourEightCappedAtThirty") {
    val fixture = new Fixture
    fixture.failShell(3)

    // Each countdown runs to zero, fires a retry, and the next wait doubles until it hits the cap.
    val waits = (1 to 6).map { _ =>
      val wait = remaining(current(fixture))
      fixture.tick(wait)
      wait
    }

    assertEquals(waits.toList, List(2, 4, 8, 16, 30, 30))
    assertEquals(fixture.retries, 6, "each expiry must make exactly one attempt")
  }

  test("theBackoffLadderIsPureAndCanBeReadWithoutAClock") {
    assertEquals(Health.backoffAfter(2.seconds), 4.seconds)
    assertEquals(Health.backoffAfter(4.seconds), 8.seconds)
    assertEquals(Health.backoffAfter(8.seconds), 16.seconds)
    assertEquals(Health.backoffAfter(16.seconds), 30.seconds)
    assertEquals(Health.backoffAfter(30.seconds), 30.seconds)
  }

  test("aSuccessfulRetryRestoresTheAppWithoutAReload") {
    val fixture = new Fixture
    fixture.failShell(3)
    assert(isLost(current(fixture)))

    // What the shell does when its retried call comes back. Nothing here reloads anything: the
    // element simply stops being shown, and every bit of state the application held is still there.
    fixture.health.report(CallScope.Shell, Right(()))
    assert(!isLost(current(fixture)))

    // And the ladder starts from the beginning next time, rather than from wherever it got to.
    fixture.failShell(3)
    assertEquals(remaining(current(fixture)), 2)
  }

  test("tryAgainAttemptsImmediatelyAndRestartsTheLadder") {
    val fixture = new Fixture
    fixture.failShell(3)
    fixture.tick(2)
    fixture.tick(4)
    assertEquals(remaining(current(fixture)), 8)

    val before = fixture.retries
    fixture.health.retryNow()

    assertEquals(fixture.retries, before + 1, "the button must attempt at once, not schedule one")
    // A user pressing the button is evidence they believe something has changed — they have just
    // reconnected. Making them wait out a timer they did not choose gets the page reloaded.
    assertEquals(remaining(current(fixture)), 2)
  }

  test("sinceIsStampedOnceAndNotRestampedByEveryFailedRetry") {
    val fixture = new Fixture
    fixture.failShell(3)
    val firstSince = current(fixture) match {
      case ShellConnectivity.Lost(since, _, _) => since.getTime()
      case other => fail(s"expected the lost state, got $other")
    }

    fixture.advance(1.minute)
    fixture.failShell(3)

    // "How long has this been broken?" is the first question anyone asks. Restamping would answer a
    // different and much less useful one.
    current(fixture) match {
      case ShellConnectivity.Lost(since, _, _) => assertEquals(since.getTime(), firstSince)
      case other => fail(s"expected the lost state, got $other")
    }
  }

  test("lastContactRemembersWhenSomethingLastAnswered") {
    val fixture = new Fixture
    fixture.health.report(CallScope.Shell, Right(()))
    val answeredAt = current(fixture) match {
      case ShellConnectivity.Connected(at) => at.getTime()
      case other => fail(s"expected the connected state, got $other")
    }

    fixture.advance(5.minutes)
    fixture.failShell(3)

    current(fixture) match {
      case ShellConnectivity.Lost(_, lastContact, _) => assertEquals(lastContact.getTime(), answeredAt)
      case other => fail(s"expected the lost state, got $other")
    }
  }

  test("aFeatureCallThatSucceedsCountsAsContact") {
    // If a feature's request came back, the gateway is reachable, whatever the shell's own last
    // attempt did. Ignoring that would keep the full-screen state on top of an application that is
    // demonstrably working.
    val fixture = new Fixture
    fixture.failShell(3)
    assert(isLost(current(fixture)))

    fixture.health.report(CallScope.Feature, Right(()))
    assert(!isLost(current(fixture)))
  }
}
