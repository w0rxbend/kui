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
  * | Freshness                    | Data    | Section                                                      |
  * |:-----------------------------|:--------|:-------------------------------------------------------------|
  * | `Fresh(at)`                  | present | `Ok(data, at)`                                               |
  * | `Stale(at, reason, since)`   | present | `Stale(data, at, reason)`                                    |
  * | `Stale(...)`                 | absent  | `Unavailable(reason, message, since)`                        |
  * | `Unavailable(reason, since)` | either  | `Unavailable(reason, message, since)`                        |
  * | `Loading`                    | absent  | `Unavailable(Starting, ..., now)`                            |
  * | `Loading`                    | present | `Stale(data, now, Starting)` — a forced refresh is in flight |
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
      case (Some(value), SnapshotFreshness.Stale(scrapedAt, reason, _)) =>
        Section.Stale(render(value), scrapedAt, ScrapeFailureReason)
      case (Some(value), SnapshotFreshness.Loading) =>
        Section.Stale(render(value), at, ReasonCode.Starting)
      case (Some(value), SnapshotFreshness.Unavailable(_, _)) =>
        // Data with an "unavailable" freshness cannot be produced by the use cases - `Unavailable` means
        // nothing was ever fetched. Showing the data is still the better of the two answers if it ever is.
        Section.Stale(render(value), at, ReasonCode.UpstreamUnavailable)
      case (None, SnapshotFreshness.Unavailable(reason, since)) =>
        Section.Unavailable(ScrapeFailureReason, reason, Some(since))
      case (None, SnapshotFreshness.Stale(_, reason, since)) =>
        Section.Unavailable(ScrapeFailureReason, reason, Some(since))
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

  /** The reason reported when all that is known about a failure is the message it left behind.
    *
    * `SnapshotFreshness` flattens the `KuiError` that caused it into a human-readable message before this
    * layer sees it, so the difference between a timeout and an authentication failure is not recoverable
    * here. Every failing upstream is therefore reported as `UPSTREAM_UNAVAILABLE`, which is true of all of
    * them, rather than guessed from the wording of a sentence written for a person. Recovering the
    * distinction means `SnapshotFreshness` carrying the error's code, which is another area's type; the
    * message itself still reaches the client, so nothing is lost to an operator, only to a `switch`.
    */
  val ScrapeFailureReason: ReasonCode = ReasonCode.UpstreamUnavailable
}
