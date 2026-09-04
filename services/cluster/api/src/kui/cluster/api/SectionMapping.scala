package kui.cluster.api

import java.time.Instant

import kui.cluster.application.SnapshotFreshness
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.*

/** The staleness contract, in one place: how "how fresh is this?" becomes a section of a response.
  *
  * ADR-027 gives a snapshot three states and a `scrapedAt`; `libs/contracts-core` gives a response five
  * section states. This is the whole translation between them, written once so that the brokers page, the
  * dashboard and the log-directories tab cannot disagree about what a greyed-out row means.
  *
  * | Freshness                   | Data    | Section                                                      |
  * |:----------------------------|:--------|:-------------------------------------------------------------|
  * | `Fresh(at)`                 | present | `Ok(data, at)`                                               |
  * | `Stale(at, error, since)`   | present | `Stale(data, at, reasonOf(error))`                           |
  * | `Stale(...)`                | absent  | `Unavailable(reasonOf(error), message, since)`               |
  * | `Unavailable(error, since)` | either  | `Unavailable(reasonOf(error), message, since)`               |
  * | `Loading`                   | absent  | `Unavailable(Starting, ..., now)`                            |
  * | `Loading`                   | present | `Stale(data, now, Starting)` — a forced refresh is in flight |
  *
  * **Age is deliberately not an input.** A snapshot older than the refresh interval is not stale; it is a
  * snapshot from a service whose refresh loop is working exactly as designed. Only a failing upstream makes
  * data stale. If age were the test, every response in a perfectly healthy KUI would be marked stale within
  * thirty seconds and the marking would stop meaning anything to anyone. How old the data is travels as
  * `fetchedAt`, which the browser shows so a user can judge for themselves (DEVPLAN D10).
  */
object SectionMapping {

  /** What a section says when the first scrape has not finished. */
  val StartingMessage: String = "the first refresh of this cluster has not completed yet"

  /** One view's data and freshness, as a section.
    *
    * @param at
    *   now. Used only where the freshness itself carries no instant — a `Loading` snapshot has never been
    *   scraped, so there is no other honest time to report
    */
  def of[A, B](data: Option[A], freshness: SnapshotFreshness, at: Instant)(render: A => B): Section[B] =
    (data, freshness) match {
      case (Some(value), SnapshotFreshness.Fresh(scrapedAt)) => Section.Ok(render(value), scrapedAt)
      case (Some(value), SnapshotFreshness.Stale(scrapedAt, error, _)) =>
        Section.Stale(render(value), scrapedAt, reasonOf(error))
      case (Some(value), SnapshotFreshness.Loading) =>
        Section.Stale(render(value), at, ReasonCode.Starting)
      case (Some(value), SnapshotFreshness.Unavailable(error, _)) =>
        // Data with an "unavailable" freshness cannot be produced by the use cases - `Unavailable` means
        // nothing was ever fetched. Showing the data is still the better of the two answers if it ever is.
        Section.Stale(render(value), at, reasonOf(error))
      case (None, SnapshotFreshness.Unavailable(error, since)) =>
        Section.Unavailable(reasonOf(error), error.message, Some(since))
      case (None, SnapshotFreshness.Stale(_, error, since)) =>
        Section.Unavailable(reasonOf(error), error.message, Some(since))
      case (None, SnapshotFreshness.Fresh(scrapedAt)) =>
        // A successful scrape that produced nothing is not something the domain can express; treat it as
        // "not there yet" rather than inventing an empty value the caller would render as fact.
        Section.Unavailable(ReasonCode.Starting, StartingMessage, Some(scrapedAt))
      case (None, SnapshotFreshness.Loading) =>
        Section.Unavailable(ReasonCode.Starting, StartingMessage, Some(at))
    }

  /** Why a section is not `Ok`, from the error that made it so.
    *
    * Used where a `KuiError` is still in hand — a live call that failed — and it is the same classification
    * `Section.fromEither` performs, by failure *case* rather than by error code, because "could not connect"
    * and "the breaker is open" share a code and mean different things on a screen.
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

  /** The reason reported for a failure whose cause is not known at all.
    *
    * It used to be the reason reported for *every* failing scrape, because `SnapshotFreshness` flattened the
    * `KuiError` that caused it into a sentence written for a person before this layer ever saw it. A timeout
    * and a rejected credential both arrived here as prose and both left as `UPSTREAM_UNAVAILABLE`, so the one
    * field a screen or a script can switch on could not tell an operator whether to look at the network or at
    * the credentials. The freshness carries the error itself now and [[reasonOf]] classifies it; this
    * constant remains as the honest answer where there is genuinely nothing to classify.
    */
  val ScrapeFailureReason: ReasonCode = ReasonCode.UpstreamUnavailable
}
