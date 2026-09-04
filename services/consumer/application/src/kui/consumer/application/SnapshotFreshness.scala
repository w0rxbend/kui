package kui.consumer.application

import java.time.Instant

import kui.cache.{Snapshot, SnapshotStatus}
import kui.kernel.error.KuiError

/** How much a caller should trust what it is looking at.
  *
  * Its own declaration rather than an import of the cluster service's identical type: rule A11 makes one
  * service's application layer private to it, and the alternative — a shared module for a three-case enum —
  * would be a wire contract nobody asked for. The API layer maps this to the `Section` envelope, which is
  * where the two services' vocabularies do meet, in `libs/contracts-core`.
  */
enum SnapshotFreshness {

  /** Current, as of `at`. */
  case Fresh(at: Instant)

  /** Known to be out of date. The screen renders it greyed, stamped with `at`, and says why. */
  case Stale(at: Instant, reason: KuiError)

  /** Nothing has ever been loaded. The only state in which a screen genuinely has nothing to render. */
  case Unavailable(reason: KuiError)

  def observedAt: Option[Instant] = this match {
    case Fresh(at) => Some(at)
    case Stale(at, _) => Some(at)
    case Unavailable(_) => None
  }

  def isFresh: Boolean = this match {
    case Fresh(_) => true
    case _ => false
  }
}

object SnapshotFreshness {

  /** Reads the freshness out of a cache snapshot, so the mapping exists once.
    *
    * A snapshot with a value and an offline status is `Stale` and not `Unavailable`: data from the last
    * successful scrape, with the time it was taken beside it, is what the whole snapshot design exists to
    * keep on screen when a cluster stops answering.
    */
  def of[A](snapshot: Snapshot[A], fallback: KuiError): SnapshotFreshness =
    (snapshot.value, snapshot.status, snapshot.scrapedAt) match {
      case (Some(_), SnapshotStatus.Online, Some(at)) => Fresh(at)
      case (Some(_), SnapshotStatus.Offline(error, _), Some(at)) => Stale(at, error)
      case (Some(_), _, Some(at)) => Fresh(at)
      case (Some(_), SnapshotStatus.Offline(error, _), None) => Unavailable(error)
      case (Some(_), _, None) => Unavailable(fallback)
      case (None, SnapshotStatus.Offline(error, _), _) => Unavailable(error)
      case (None, _, _) => Unavailable(fallback)
    }

  given CanEqual[SnapshotFreshness, SnapshotFreshness] = CanEqual.derived
}
