package kui.cache

import java.time.Instant

import kui.kernel.error.{InfrastructureError, KuiError}

/** A value, when it was seen, and whether it is current.
  *
  * `value` and `status` are independent, and that independence is the entire point of the type. The
  * interesting state is `Some(value)` with `Offline`: data from the last successful scrape, known to be out
  * of date, still worth showing with a timestamp on it. `Initializing` with `None` is the only combination in
  * which a caller genuinely has nothing to render.
  */
final case class Snapshot[A](
    value: Option[A],
    status: SnapshotStatus,
    /** When `value` was produced.
      *
      * `None` only while `Initializing`. Never advanced by a failed refresh: a timestamp that moves while the
      * data does not is a lie told once a minute, and it is a lie the UI would repeat in the words "as of
      * thirty seconds ago".
      */
    scrapedAt: Option[Instant]
) {

  /** There is something to show, and it is known to be out of date. */
  def isStale: Boolean = value.isDefined && status.isOffline

  def map[B](f: A => B): Snapshot[B] = Snapshot(value.map(f), status, scrapedAt)

  /** For a caller that genuinely cannot render stale data — a write that has to be based on current state,
    * rather than a screen.
    */
  def toEither: Either[KuiError, A] = (value, status) match {
    case (Some(a), SnapshotStatus.Online) => Right(a)
    case (_, SnapshotStatus.Offline(error, _)) => Left(error)
    case (Some(a), SnapshotStatus.Initializing) => Right(a)
    case (None, _) =>
      Left(InfrastructureError.Unreachable("snapshot", "no value has been loaded yet"))
  }
}

object Snapshot {

  def initializing[A]: Snapshot[A] = Snapshot(None, SnapshotStatus.Initializing, None)

  def online[A](value: A, at: Instant): Snapshot[A] =
    Snapshot(Some(value), SnapshotStatus.Online, Some(at))

  given [A] => CanEqual[Snapshot[A], Snapshot[A]] = CanEqual.derived
}
