package kui.ui.clusters.brokers

import kui.contracts.cluster.LogDirDto

/** One log directory of one broker, ready to draw.
  *
  * The per-directory `error` is the field that shapes the screen. Kafka reports a failed disk by attaching an
  * error to *that directory* while the rest of the answer is perfectly good, so a broker with one dead disk
  * and three healthy ones has to render three directories and one error — not one page-level failure, which
  * would hide exactly the fact the operator opened the page to find.
  *
  * @param usedBytes
  *   what is on the disk, derived from its size and what is still free. `None` on a broker older than 3.3,
  *   which reports neither, and on an offline directory, which reports nothing at all.
  */
final case class LogDirView(
    path: String,
    error: Option[String],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    usedBytes: Option[Long],
    topicCount: Int,
    partitionCount: Int
) {

  /** How full the disk is, 0…1, or `None` when either figure is missing.
    *
    * `None` and not zero: an unmeasured disk drawn as an empty bar reads as a disk with room on it, which is
    * the opposite of a safe assumption to leave on an operator's screen.
    */
  def usedFraction: Option[Double] =
    for {
      total <- totalBytes if total > 0
      used <- usedBytes
    } yield (used.toDouble / total.toDouble).max(0.0).min(1.0)
}

object LogDirView {

  given CanEqual[LogDirView, LogDirView] = CanEqual.derived

  /** In the order the broker reported them, which is the order they are configured in. */
  def of(dirs: List[LogDirDto]): List[LogDirView] =
    dirs.map { dto =>
      LogDirView(
        path = dto.path,
        error = dto.error,
        totalBytes = dto.totalBytes,
        usableBytes = dto.usableBytes,
        usedBytes = for {
          total <- dto.totalBytes
          usable <- dto.usableBytes
        } yield (total - usable).max(0L),
        topicCount = dto.topicCount,
        partitionCount = dto.partitionCount
      )
    }

  /** Only this broker's directories.
    *
    * The endpoint answers for a whole cluster when no broker is named, and this page always names one — but
    * filtering here as well means a response that carries more than was asked for renders correctly instead
    * of showing another machine's disks under this broker's heading.
    */
  def forBroker(dirs: List[LogDirDto], broker: kui.kernel.BrokerId): List[LogDirDto] =
    dirs.filter(_.brokerId == broker)
}
