package kui.ui.kernel.query

import scala.concurrent.duration.*
import scala.scalajs.js

import com.raquo.airstream.status.{Pending, Resolved, Status}
import com.raquo.laminar.api.L.*

import kui.ui.kernel.api.ApiError

/** One answer the cache is holding, and when it arrived.
  *
  * A failure is cached as well as a success. That is deliberate: an endpoint that is returning 500 gets asked
  * once and then left alone for a few seconds, instead of being hammered by every component that wanted its
  * data. See [[QueryCache.NegativeStaleAfter]].
  */
final case class CacheEntry[A](value: Either[ApiError, A], fetchedAt: js.Date)

/** Everything a screen needs in order to draw itself: what is happening now, the last value that was ever
  * good, and when that value arrived.
  *
  * All three together, in one type, because ADR-032's stale rule needs all three at once — the old numbers to
  * keep showing, the timestamp to put on the badge, and the current failure to explain why the numbers are
  * not moving. Deriving them separately is what leads every screen to keep a private shadow copy of its own
  * last good answer, which is precisely the duplication this type removes.
  */
final case class QueryState[A](
    pending: Boolean,
    outcome: Option[Either[ApiError, A]],
    lastGood: Option[A],
    lastGoodAt: Option[js.Date]
) {

  /** True when there is something worth showing and the newest thing we know is a failure.
    *
    * False when a key has only ever failed: that is an empty screen with an error on it, which is a fallback
    * panel's job, and telling the two apart is the whole reason this is a method rather than
    * `outcome.exists(_.isLeft)` written out at each call site.
    */
  def isStale: Boolean = lastGood.isDefined && outcome.exists(_.isLeft)
}

/** Server state, fetched once and shared.
  *
  * ## The problem
  *
  * A screen is made of independent components, and several of them usually want the same thing: a header
  * showing the cluster's name, a breadcrumb showing the cluster's name, and a table of the cluster's topics
  * all begin by asking for the cluster. Written naively that is three identical requests on every render, and
  * three different answers on screen while they are in flight.
  *
  * This is the frontend's equivalent of what react-query does in the reference implementation
  * (`research/scala/frontend-research.md` §3.2): components ask the cache, the cache asks the server at most
  * once, and everybody watches the same value.
  *
  * ## What "subscribing" means here
  *
  * Asking for `get(key)` builds a `Signal` and fetches nothing. The request happens when something
  * *subscribes* to that signal — in Laminar, when the element holding it is mounted — and only if the cached
  * answer is missing or stale. When the last subscriber goes away the entry stops being refreshed and becomes
  * a candidate for eviction. That is what stops a page the user left behind from continuing to poll.
  */
trait QueryCache[K, A] {

  /** The value for one key, as an Airstream `Status`: `Pending(key)` while a request is in flight, and
    * `Resolved(key, answer, n)` once there is an answer — including a failed one.
    *
    * A `Status` rather than `Option`, because "we have not asked yet", "we are asking", and "we asked and it
    * failed" are three different things on screen and collapsing them produces the spinner that never stops.
    */
  def get(key: K): Signal[Status[K, Either[ApiError, A]]]

  /** Marks one key stale. A key that something is watching is refetched at once; one that nothing is watching
    * is refetched the next time it is watched.
    */
  def invalidate(key: K): Unit

  /** The same, for every key matching a predicate.
    *
    * This is the prefix invalidation the reference calls for: after creating a topic on cluster `A`, every
    * cached list belonging to cluster `A` is wrong and every list belonging to cluster `B` is still perfectly
    * good. Invalidating everything would be correct and would also refetch the whole application.
    */
  def invalidateWhere(matches: K => Boolean): Unit

  /** Puts a value in without asking the server.
    *
    * For the answer a mutation already returned: creating a topic answers with the topic, so re-fetching it
    * immediately afterwards asks a question that has just been answered.
    */
  def set(key: K, value: A): Unit

  /** When this key's value arrived, for ADR-032's rule that stale data stays on screen with its timestamp. */
  def fetchedAt(key: K): Signal[Option[js.Date]]

  /** The full state of one key.
    *
    * Subscribing to this has exactly the same fetch-on-demand and reference-counting behaviour as [[get]],
    * and it is derived from the same entry, so a screen that watches both still causes one request.
    */
  def state(key: K): Signal[QueryState[A]]

  /** The last successfully fetched value for a key.
    *
    * A failing refetch never clears it. That is the guarantee the stale rule rests on, and it is stated here
    * rather than left as an implementation detail because a screen is allowed to depend on it.
    */
  def lastGood(key: K): Signal[Option[A]]
}

object QueryCache {

  /** How long a successful answer is trusted before the next subscription refetches it. */
  val DefaultStaleAfter: FiniteDuration = 30.seconds

  /** How long a *failed* answer is kept.
    *
    * Much shorter than a success, because a failure is usually transient and the user is usually waiting. Not
    * zero, because zero means every component that wanted the data retries independently and a struggling
    * endpoint is hit by the whole page at once, which is how a slow service becomes a dead one.
    */
  val NegativeStaleAfter: FiniteDuration = 5.seconds

  /** How many keys are kept. Bounded because a user browsing a thousand topics would otherwise accumulate a
    * thousand cached answers in a tab that stays open all day.
    */
  val DefaultMaxEntries: Int = 200

  /** Builds a cache.
    *
    * @param fetch
    *   how to get one key's value. Usually `key => client.call(SomeApi.get, key)`.
    * @param now
    *   the clock. A parameter, because a test for a 30-second staleness rule that waits 30 real seconds is a
    *   test nobody runs — the same reason `Notifications` takes one.
    */
  def make[K, A](
      fetch: K => EventStream[Either[ApiError, A]],
      staleAfter: FiniteDuration = DefaultStaleAfter,
      maxEntries: Int = DefaultMaxEntries,
      now: () => js.Date = () => new js.Date()
  ): QueryCache[K, A] = new AirstreamQueryCache(fetch, staleAfter, maxEntries, now)
}

/** The one implementation. Separate from the trait so that a caller depends on the contract and a test can
  * depend on the clock.
  */
final private class AirstreamQueryCache[K, A](
    fetch: K => EventStream[Either[ApiError, A]],
    staleAfter: FiniteDuration,
    maxEntries: Int,
    now: () => js.Date
) extends QueryCache[K, A] {

  /** Everything known about one key. Mutable, because it is the cache's whole job to change over time. */
  final private class Entry {
    val cached: Var[Option[CacheEntry[A]]] = Var(None)
    val pending: Var[Boolean] = Var(false)

    /** The last answer that was a `Right`, and when it arrived.
      *
      * Held separately from `cached` rather than derived from it, because `cached` is overwritten by a
      * failure and the whole point is that the failure must not take the previous good answer with it.
      */
    val good: Var[Option[CacheEntry[A]]] = Var(None)

    /** How many live subscriptions are watching. Zero means nothing on screen wants it. */
    var watchers: Int = 0

    /** Bumped on every resolution, so that a `Status.Resolved` carries a distinguishable index. */
    var resolutions: Int = 0

    /** Which fetch is the current one.
      *
      * A refetch can start while the previous request is still outstanding — invalidating a key that is on
      * screen does exactly that. Both will eventually answer, and the older answer is by definition the
      * staler one, so it has to be dropped rather than written over the newer. Without this the cache would
      * occasionally settle on the value it asked for first, which is a bug that only appears under a slow
      * network and is therefore never reproduced.
      */
    var generation: Int = 0

    /** Set by `invalidate`, cleared by the next successful fetch. Kept separately from the timestamp so that
      * invalidating does not throw the value away: ADR-032 wants stale data to stay on screen.
      */
    var invalidated: Boolean = false
  }

  private var entries: Map[K, Entry] = Map.empty

  private val statuses: Var[Map[K, Status[K, Either[ApiError, A]]]] = Var(Map.empty)

  def get(key: K): Signal[Status[K, Either[ApiError, A]]] = {
    // A stream that never emits. Its only purpose is the pair of callbacks: `start` runs when something
    // subscribes to the signal built from it, `stop` when the last subscriber goes away. That is the
    // only hook Airstream offers for "somebody is now looking at this", and it is what makes the cache
    // demand-driven instead of a background poller.
    val lifecycle: EventStream[Unit] = EventStream.fromCustomSource[Unit](
      shouldStart = _ => true,
      start = (_, _, _, _) => acquire(key),
      stop = _ => release(key)
    )

    lifecycle
      .toSignal(())
      .flatMapSwitch(_ => statuses.signal.map(_.getOrElse(key, Pending(key))))
  }

  def invalidate(key: K): Unit = invalidateWhere(_ == key)

  def invalidateWhere(matches: K => Boolean): Unit =
    entries.foreach { (key, entry) =>
      if matches(key) then {
        entry.invalidated = true
        // Only what is on screen is refetched now. An entry nobody is watching is refetched when
        // somebody looks at it again, which is the difference between invalidating a cache and
        // reloading the application.
        if entry.watchers > 0 then startFetch(key, entry)
      }
    }

  def set(key: K, value: A): Unit = {
    val entry = entryFor(key)
    entry.invalidated = false
    store(key, entry, Right(value))
  }

  def fetchedAt(key: K): Signal[Option[js.Date]] =
    entryFor(key).cached.signal.map(_.map(_.fetchedAt))

  def state(key: K): Signal[QueryState[A]] =
    // Built on `get` rather than beside it, so the acquire/release bookkeeping that makes the cache
    // demand-driven happens exactly once and cannot drift between the two reads.
    get(key).combineWith(goodOf(key)).map { (status, good) =>
      val outcome = status match {
        case Resolved(_, value, _) => Some(value)
        case _ => None
      }
      QueryState(
        pending = outcome.isEmpty,
        outcome = outcome,
        lastGood = good.flatMap(_.value.toOption),
        lastGoodAt = good.map(_.fetchedAt)
      )
    }

  def lastGood(key: K): Signal[Option[A]] =
    state(key).map(_.lastGood)

  private def goodOf(key: K): Signal[Option[CacheEntry[A]]] = entryFor(key).good.signal

  /** Somebody started watching this key. Fetches only if what is held is missing or too old. */
  private def acquire(key: K): Unit = {
    val entry = entryFor(key)
    entry.watchers += 1
    if isStale(entry) && !entry.pending.now() then startFetch(key, entry)
  }

  private def release(key: K): Unit =
    entries.get(key).foreach { entry =>
      entry.watchers = (entry.watchers - 1).max(0)
      if entry.watchers == 0 then evictIfOverBound()
    }

  private def isStale(entry: Entry): Boolean =
    entry.cached.now() match {
      case None => true
      case Some(_) if entry.invalidated => true
      case Some(held) =>
        val ttl = if held.value.isLeft then QueryCache.NegativeStaleAfter else staleAfter
        (now().getTime() - held.fetchedAt.getTime()) >= ttl.toMillis.toDouble
    }

  /** Issues one request and files the answer.
    *
    * The subscription is owned by the page rather than by the component that asked, on purpose: a user who
    * navigates away mid-request should still have the answer cached when they come back, and a request whose
    * answer is thrown away was a request that should not have been made.
    */
  private def startFetch(key: K, entry: Entry): Unit = {
    entry.generation += 1
    val generation = entry.generation
    entry.pending.set(true)
    publish(key, Pending(key))
    fetch(key).foreach { outcome =>
      if entry.generation == generation then {
        entry.invalidated = false
        store(key, entry, outcome)
      }
    }(using unsafeWindowOwner): Unit
  }

  private def store(key: K, entry: Entry, value: Either[ApiError, A]): Unit = {
    val arrived = CacheEntry(value, now())
    entry.cached.set(Some(arrived))
    if value.isRight then entry.good.set(Some(arrived))
    entry.pending.set(false)
    entry.resolutions += 1
    publish(key, Resolved(key, value, entry.resolutions))
  }

  private def publish(key: K, status: Status[K, Either[ApiError, A]]): Unit =
    statuses.update(_.updated(key, status))

  private def entryFor(key: K): Entry =
    entries.getOrElse(
      key, {
        val created = new Entry
        entries = entries.updated(key, created)
        evictIfOverBound()
        created
      }
    )

  /** Drops unwatched entries, oldest answer first, until the bound is met.
    *
    * Watched entries are never evicted, whatever the bound says: throwing away something that is on screen
    * would blank a panel to save memory, which is not a trade anybody would choose.
    */
  private def evictIfOverBound(): Unit = {
    val evictable = entries.filter((_, entry) => entry.watchers == 0)
    val excess = entries.size - maxEntries
    if excess > 0 && evictable.nonEmpty then {
      val doomed = evictable.toList
        .sortBy((_, entry) => entry.cached.now().map(_.fetchedAt.getTime()).getOrElse(0.0))
        .take(excess)
        .map((key, _) => key)

      entries = entries.removedAll(doomed)
      statuses.update(_.removedAll(doomed))
    }
  }
}
