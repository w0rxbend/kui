package kui.topic.api

import java.time.Instant

import kui.cache.{Snapshot, SnapshotStatus}
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.*
import kui.topic.application.Fresh

/** The staleness contract: how "how fresh is this?" becomes a section of a response.
  *
  * `libs/cache` gives a snapshot three states and a `scrapedAt`; `libs/contracts-core` gives a response five
  * section states. This is the whole translation between them, written once so that the topic list, the
  * detail page and the settings tab cannot disagree about what a greyed-out screen means.
  *
  * | Snapshot                          | Section                                        |
  * |:----------------------------------|:-----------------------------------------------|
  * | value, `Online`                   | `Ok(data, scrapedAt)`                          |
  * | value, `Offline(error, since)`    | `Stale(data, scrapedAt, reasonOf(error))`      |
  * | value, `Initializing`             | `Stale(data, now, Starting)`                   |
  * | no value, `Offline(error, since)` | `Unavailable(reasonOf(error), message, since)` |
  * | no value, `Initializing`          | `Unavailable(Starting, …, now)`                |
  * | no value, `Online`                | `Unavailable(Starting, …, now)`                |
  *
  * ==Carry the code, not just the fact==
  *
  * A timeout, an authentication failure and a circuit breaker opening are three different things an operator
  * does three different things about. M1's cluster service collapsed all of them to `UPSTREAM_UNAVAILABLE`,
  * because the type it read freshness from had already flattened the error into a sentence written for a
  * person (CLAPI-004 deviation 2, still open in `STATUS.md`). `SnapshotStatus.Offline` carries the `KuiError`
  * itself, so [[reasonOf]] can classify it, and this task does not repeat that.
  *
  * ==Age is deliberately not an input==
  *
  * A snapshot older than the refresh interval is not stale; it is a snapshot from a service whose refresh
  * loop is working exactly as designed. Only a *failing* upstream makes data stale. If age were the test,
  * every response in a perfectly healthy KUI would be marked stale within a minute and the marking would stop
  * meaning anything. How old the data is travels as `fetchedAt`, and the browser shows it so a user can judge
  * for themselves.
  */
object TopicSections {

  /** What a section says when the first scrape has not finished. */
  val StartingMessage: String = "the first scrape of this cluster's topics has not completed yet"

  /** One snapshot's data and status, as a section, with the value rendered on the way out.
    *
    * @param at
    *   now. Used only where the snapshot itself carries no instant — a cell that has never completed a scrape
    *   has no `scrapedAt`, so there is no other honest time to report
    */
  def of[A, B](snapshot: Snapshot[A], at: Instant)(render: A => B): Section[B] =
    (snapshot.value, snapshot.status) match {
      case (Some(value), SnapshotStatus.Online) =>
        Section.Ok(render(value), snapshot.scrapedAt.getOrElse(at))

      case (Some(value), SnapshotStatus.Offline(error, _)) =>
        Section.Stale(render(value), snapshot.scrapedAt.getOrElse(at), reasonOf(error))

      case (Some(value), SnapshotStatus.Initializing) =>
        // A value with no completed scrape means a forced refresh is in flight over data that was
        // already there. Showing it marked is better than either hiding it or calling it current.
        Section.Stale(render(value), snapshot.scrapedAt.getOrElse(at), ReasonCode.Starting)

      case (None, SnapshotStatus.Offline(error, since)) =>
        Section.Unavailable(reasonOf(error), error.message, Some(since))

      case (None, _) =>
        // Including a scrape that succeeded and produced nothing, which the domain cannot express. It is
        // reported as "not there yet" rather than as an empty page: an empty page from a cluster with ten
        // thousand topics is a lie that looks like data, and it is the exact failure M1's dashboard shipped.
        Section.Unavailable(ReasonCode.Starting, StartingMessage, Some(snapshot.scrapedAt.getOrElse(at)))
    }

  /** A live read's result, as a section.
    *
    * `Fresh.FromSnapshot` is `Stale` and nothing else — the use case already decided that the live call
    * failed and that it is serving the snapshot instead, and it recorded why. Re-deciding it here would mean
    * two layers with an opinion about the same fact.
    */
  def ofFresh[A, B](fresh: Fresh[A], at: Instant)(render: A => B): Section[B] = fresh match {
    case Fresh.Live(value) => Section.Ok(render(value), at)
    case Fresh.FromSnapshot(value, scrapedAt, reason) =>
      Section.Stale(render(value), scrapedAt, reasonFor(reason))
  }

  /** Why a section is not `Ok`, from the error that made it so.
    *
    * Classified by failure *case* rather than by error code, because "could not connect" and "the breaker is
    * open" share a code and mean different things on a screen.
    */
  def reasonOf(error: KuiError): ReasonCode = error match {
    case ApplicationError.Forbidden(_) => ReasonCode.Forbidden
    case ApplicationError.Unsupported(_) => ReasonCode.NotConfigured
    case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
    case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
    case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
    case InfrastructureError.Unreachable(_, _) | InfrastructureError.Upstream(_, _) =>
      ReasonCode.UpstreamUnavailable
    case _ => ReasonCode.Unknown
  }

  /** The same classification for a reason the use case has already reduced to a sentence.
    *
    * `Fresh.FromSnapshot` carries a `String`, so the code has to be recovered from what it says. That is a
    * weaker join than [[reasonOf]]'s and it is written here, in one place, rather than at three call sites —
    * and it is the reason the equivalent for a snapshot takes the `KuiError` itself. When `Fresh` grows a
    * typed reason, this function is what deletes.
    */
  def reasonFor(reason: String): ReasonCode = {
    val lowered = reason.toLowerCase
    if lowered.contains("timeout") || lowered.contains("timed out") then ReasonCode.UpstreamTimeout
    else if lowered.contains("breaker") || lowered.contains("circuit") then ReasonCode.CircuitOpen
    else if lowered.contains("authoriz") || lowered.contains("authentic") then ReasonCode.UpstreamAuth
    else ReasonCode.UpstreamUnavailable
  }
}
