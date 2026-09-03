package kui.ui.kernel.query

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.scalajs.js

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.status.{Pending, Resolved, Status}
import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.ui.kernel.api.ApiError

/** A clock the test moves by hand.
  *
  * Waiting thirty real seconds to check a thirty-second staleness rule produces a suite nobody runs, and a
  * suite nobody runs protects nothing. The same arrangement `Notifications` already uses in this module.
  */
final class TestClock(private var millis: Double = 1_700_000_000_000.0) {
  def now(): js.Date = new js.Date(millis)
  def advance(by: FiniteDuration): Unit = millis += by.toMillis.toDouble
}

/** A `fetch` that answers on demand and counts how often it was asked. */
final class FakeFetch[K, A] {

  private val buses = mutable.Map.empty[K, EventBus[Either[ApiError, A]]]

  val calls: mutable.ListBuffer[K] = mutable.ListBuffer.empty

  def fetch(key: K): EventStream[Either[ApiError, A]] = {
    calls.append(key): Unit
    buses.getOrElseUpdate(key, new EventBus[Either[ApiError, A]]).events
  }

  /** Answers whatever request is outstanding for this key. */
  def respond(key: K, value: Either[ApiError, A]): Unit =
    buses.get(key).foreach(bus => bus.writer.onNext(value))

  def callsFor(key: K): Int = calls.count(_ == key)
}

class QueryCacheSuite extends FunSuite {

  private def watch[A](signal: Signal[A], owner: ManualOwner): mutable.ListBuffer[A] = {
    val seen = mutable.ListBuffer.empty[A]
    signal.foreach(value => seen.append(value): Unit)(using owner): Unit
    seen
  }

  /** Starts watching a signal, which is what makes the cache fetch. The subscription handle is
    * deliberately dropped: the owner is what releases it, and every test kills its owner.
    */
  private def subscribe[A](signal: Signal[A])(using owner: ManualOwner): Unit =
    signal.foreach(_ => ())(using owner): Unit

  test("twoSubscribersForOneKeyCauseOneFetch") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val first = watch(cache.get("a"), owner)
    val second = watch(cache.get("a"), owner)

    assertEquals(source.callsFor("a"), 1)
    assertEquals(first.last, Pending("a"))

    source.respond("a", Right(7))

    assertEquals(first.last, Resolved("a", Right(7), 1))
    assertEquals(second.last, Resolved("a", Right(7), 1))
    owner.killSubscriptions()
  }

  test("staleEntriesRefetchOnNextSubscriptionAndFreshOnesDoNot") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, staleAfter = 30.seconds, now = clock.now)

    val first = new ManualOwner
    subscribe(cache.get("a"))(using first)
    source.respond("a", Right(1))
    first.killSubscriptions()

    clock.advance(29.seconds)
    val second = new ManualOwner
    subscribe(cache.get("a"))(using second)
    assertEquals(source.callsFor("a"), 1, "a value that is still fresh must not be fetched again")
    second.killSubscriptions()

    clock.advance(2.seconds)
    val third = new ManualOwner
    subscribe(cache.get("a"))(using third)
    assertEquals(source.callsFor("a"), 2, "a value past its staleness window is refetched when watched")
    third.killSubscriptions()
  }

  test("invalidateWhereRefetchesOnlyMatchingKeys") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    subscribe(cache.get("cluster-a/topics"))
    subscribe(cache.get("cluster-b/topics"))
    source.respond("cluster-a/topics", Right(1))
    source.respond("cluster-b/topics", Right(2))

    cache.invalidateWhere(_.startsWith("cluster-a/"))

    assertEquals(source.callsFor("cluster-a/topics"), 2)
    assertEquals(source.callsFor("cluster-b/topics"), 1)
    owner.killSubscriptions()
  }

  test("anEntryWithNoSubscribersStopsRefreshingAndIsEvictedAtTheBound") {
    val clock = new TestClock
    val source = new FakeFetch[Int, Int]
    val cache = QueryCache.make(source.fetch, maxEntries = 2, now = clock.now)

    val held = new ManualOwner
    subscribe(cache.get(0))(using held)
    source.respond(0, Right(0))

    // Two more keys, each watched briefly and then released, take the cache past its bound.
    List(1, 2).foreach { key =>
      val brief = new ManualOwner
      subscribe(cache.get(key))(using brief)
      source.respond(key, Right(key))
      clock.advance(1.second)
      brief.killSubscriptions()
    }

    // Invalidating everything must not refetch the released keys: nothing is looking at them, and a
    // cache that keeps talking to the server for a page the user has left is a cache that never
    // stops.
    val before = source.calls.size
    cache.invalidateWhere(_ => true)
    assertEquals(source.calls.size - before, 1, "only the watched key is refetched")
    assertEquals(source.calls.last, 0)

    // Key 0 is still watched, so it survives the bound; the oldest unwatched key is what goes.
    source.respond(0, Right(99))
    assertEquals(cache.get(0).observe(using held).now(), Resolved(0, Right(99), 2))
    held.killSubscriptions()
  }

  test("errorsAreCachedBrieflySoAFailingEndpointIsNotHammered") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, staleAfter = 30.seconds, now = clock.now)

    val first = new ManualOwner
    subscribe(cache.get("a"))(using first)
    source.respond("a", Left(ApiError.Timeout))
    first.killSubscriptions()

    clock.advance(4.seconds)
    val second = new ManualOwner
    subscribe(cache.get("a"))(using second)
    assertEquals(source.callsFor("a"), 1, "inside the negative window the failure is reused")
    second.killSubscriptions()

    // Five seconds, not thirty: a failure is usually transient and somebody is usually waiting.
    clock.advance(2.seconds)
    val third = new ManualOwner
    subscribe(cache.get("a"))(using third)
    assertEquals(source.callsFor("a"), 2)
    third.killSubscriptions()
  }

  test("setStoresAnAnswerAMutationAlreadyGaveUsWithoutAskingAgain") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    cache.set("a", 42)
    val seen = watch(cache.get("a"), owner)

    assertEquals(source.callsFor("a"), 0)
    assertEquals(seen.last, Resolved("a", Right(42), 1))
    owner.killSubscriptions()
  }

  test("fetchedAtCarriesTheTimestampAdr032PutsOnStaleData") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val stamps = watch(cache.fetchedAt("a"), owner)
    assertEquals(stamps.last, None)

    subscribe(cache.get("a"))
    source.respond("a", Right(1))

    assertEquals(stamps.last.map(_.getTime()), Some(clock.now().getTime()))
    owner.killSubscriptions()
  }

  test("aKeyThatWasNeverAskedForIsPending") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val seen = watch(cache.get("a"), owner)
    // "We are asking" is not the same as "we asked and got nothing", and collapsing the two is how a
    // spinner ends up on screen for ever.
    assertEquals(seen.head: Status[String, Either[ApiError, Int]], Pending("a"))
    owner.killSubscriptions()
  }

  // --- ADR-032's stale rule: a failing refetch never throws away the last good answer -----------

  test("aFailingRefetchKeepsTheLastGoodValue") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val states = watch(cache.state("a"), owner)
    source.respond("a", Right(7))
    val goodAt = states.last.lastGoodAt.map(_.getTime())

    clock.advance(1.minute)
    cache.invalidate("a")
    source.respond("a", Left(ApiError.Unreachable("gone")))

    val state = states.last
    assertEquals(state.outcome, Some(Left(ApiError.Unreachable("gone"))))
    assertEquals(state.lastGood, Some(7))
    assertEquals(state.lastGoodAt.map(_.getTime()), goodAt)
    assert(state.isStale)
    owner.killSubscriptions()
  }

  test("aSucceedingRefetchReplacesTheLastGoodValueAndItsTimestamp") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val states = watch(cache.state("a"), owner)
    source.respond("a", Right(7))
    val first = states.last.lastGoodAt.map(_.getTime())

    clock.advance(1.minute)
    cache.invalidate("a")
    source.respond("a", Right(9))

    assertEquals(states.last.lastGood, Some(9))
    assertNotEquals(states.last.lastGoodAt.map(_.getTime()), first)
    assert(!states.last.isStale)
    owner.killSubscriptions()
  }

  test("aKeyThatHasOnlyEverFailedHasNoLastGoodAndIsNotStale") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val states = watch(cache.state("a"), owner)
    source.respond("a", Left(ApiError.Unreachable("gone")))

    // Nothing to keep on screen, so this is a fallback panel's job and not the overlay's. The two
    // cases have to be distinguishable or every screen draws the wrong one half the time.
    assertEquals(states.last.lastGood, None)
    assert(!states.last.isStale)
    owner.killSubscriptions()
  }

  test("stateAndGetShareOneFetch") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    subscribe(cache.get("a"))
    subscribe(cache.state("a"))

    assertEquals(source.callsFor("a"), 1)
    owner.killSubscriptions()
  }

  test("lastGoodSurvivesTheNegativeTtlRefetch") {
    val clock = new TestClock
    val source = new FakeFetch[String, Int]
    val cache = QueryCache.make(source.fetch, now = clock.now)
    given owner: ManualOwner = new ManualOwner

    val values = watch(cache.lastGood("a"), owner)
    source.respond("a", Right(7))

    clock.advance(1.minute)
    cache.invalidate("a")
    source.respond("a", Left(ApiError.Unreachable("first failure")))

    // The 5-second negative TTL makes the next subscription refetch; a second failure must still
    // not clear the value the screen is showing.
    clock.advance(QueryCache.NegativeStaleAfter + 1.second)
    subscribe(cache.get("a"))
    source.respond("a", Left(ApiError.Unreachable("second failure")))

    assertEquals(values.last, Some(7))
    owner.killSubscriptions()
  }
}
