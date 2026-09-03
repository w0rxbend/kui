package kui.cache

import java.time.Instant

import kui.kernel.error.KuiError

/** Where a snapshot stands with the thing it was scraped from.
  *
  * Three states, and they are the three `ARCHITECTURE.md` §9 requires of every snapshot in KUI, so that "how
  * old is this and can I trust it" has the same answer shape on every screen rather than a per-feature
  * invention.
  */
enum SnapshotStatus {

  /** No successful load yet. There is no value to show, and saying so is better than pretending. */
  case Initializing

  /** The last refresh succeeded. */
  case Online

  /** The last refresh failed.
    *
    * `since` is the time of the **first** failure in this run of failures, not the most recent one. The
    * question a user asks about a greyed-out row is "how long has this been down", and a timestamp that
    * resets on every retry answers "thirty seconds" for ever. ADR-039 argues the same point for its own
    * sticky `since`.
    */
  case Offline(lastError: KuiError, since: Instant)

  def isOnline: Boolean = this match {
    case Online => true
    case Initializing | Offline(_, _) => false
  }

  def isOffline: Boolean = this match {
    case Offline(_, _) => true
    case Initializing | Online => false
  }
}

object SnapshotStatus {
  given CanEqual[SnapshotStatus, SnapshotStatus] = CanEqual.derived
}
