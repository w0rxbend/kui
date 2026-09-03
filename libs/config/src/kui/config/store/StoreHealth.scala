package kui.config.store

import java.time.Instant

/** What the store can currently do.
  *
  * Reads always work — every adapter serves them from a map filled before the store was handed to anyone — so
  * this type says two other things: how stale those reads may be, and whether a write would be accepted. The
  * three cases are deliberately different kinds of statement. `Degraded` is a failure and dims a capability;
  * `ReadOnly` is a choice the deployment made and must not (ADR-039 §6).
  */
enum StoreHealth {

  /** Live and caught up. `lastAppliedOffset` is `-1` for a store that has no log. */
  case Healthy(lastAppliedOffset: Long, since: Instant)

  /** Reachable no more. Reads serve the last replayed state; writes are rejected rather than buffered,
    * because a buffered write has no version to check against and would silently overwrite whatever another
    * replica did in the meantime.
    */
  case Degraded(reason: String, since: Instant, lastAppliedOffset: Long, unreadable: List[StoreKey])

  /** No writable store is configured. Not a failure: the deployment chose this. */
  case ReadOnly(reason: String, unreadable: List[StoreKey])

  def writable: Boolean = this match {
    case Healthy(_, _) => true
    case Degraded(_, _, _, _) => false
    case ReadOnly(_, _) => false
  }

  /** Keys that are in the log, or in the directory, but could not be decoded or decrypted.
    *
    * One unreadable record must not stop KUI from serving the other ninety-nine, so a reader skips it and
    * names it here instead. An operator who sees a non-empty list has a concrete key to go and look at; a
    * reader that had thrown would have given them a stack trace and no list.
    */
  def unreadableKeys: List[StoreKey] = this match {
    case Healthy(_, _) => Nil
    case Degraded(_, _, _, unreadable) => unreadable
    case ReadOnly(_, unreadable) => unreadable
  }
}

object StoreHealth {
  given CanEqual[StoreHealth, StoreHealth] = CanEqual.derived
}
