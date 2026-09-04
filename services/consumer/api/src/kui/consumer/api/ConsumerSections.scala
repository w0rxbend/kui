package kui.consumer.api

import kui.consumer.application.SnapshotFreshness
import kui.contracts.Section
import kui.contracts.capability.ReasonCode

/** How "how fresh is this group list?" becomes a section of a response.
  *
  * The consumer service's application layer already decides freshness, in [[SnapshotFreshness]], and it does
  * so from the snapshot cell that fed the answer. This is only the translation of that verdict into the wire
  * vocabulary `libs/contracts-core` defines, written once so that the list and anything that follows it
  * cannot disagree about what a greyed-out table means.
  *
  * | Freshness            | Section                                         |
  * |:---------------------|:------------------------------------------------|
  * | `Fresh(at)`          | `Ok(data, at)`                                  |
  * | `Stale(at, error)`   | `Stale(data, at, ReasonCode.of(error))`         |
  * | `Unavailable(error)` | `Unavailable(ReasonCode.of(error), message, …)` |
  *
  * `Unavailable` deliberately drops the rows, and that is not the same choice as `Stale`'s. `Unavailable`
  * means nothing has ever been loaded, so the "rows" are an empty page the use case fabricated to have
  * something to return — sending them as data would tell the browser that this cluster has no consumer
  * groups, which is a lie that looks exactly like a fact.
  *
  * ==Age is not an input, on purpose==
  *
  * A snapshot older than the refresh interval is not stale; it is a snapshot from a refresh loop that is
  * working. Only a *failing* upstream makes data stale, which is what `SnapshotFreshness.of` decides from the
  * cell's status. If age were the test, every response in a perfectly healthy KUI would be marked within a
  * minute and the marking would stop meaning anything. How old the rows are travels as `fetchedAt` and the
  * browser shows it, so a user can judge for themselves.
  */
object ConsumerSections {

  /** One freshness verdict and the value it was reached about, as a section. */
  def of[A](freshness: SnapshotFreshness, data: => A): Section[A] = freshness match {
    case SnapshotFreshness.Fresh(at) => Section.Ok(data, at)
    case SnapshotFreshness.Stale(at, reason) => Section.Stale(data, at, ReasonCode.of(reason))
    case SnapshotFreshness.Unavailable(reason) =>
      Section.Unavailable(ReasonCode.of(reason), reason.message, freshness.observedAt)
  }
}
