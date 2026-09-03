package kui.ui.clusters

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English in M0 and has no
  * i18n runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Clusters"

  val SummaryOnline: String = "online"

  val SummaryNotOnline: String = "not online"

  val UnavailableOnly: String = "Show unavailable only"

  val ReadOnly: String = "read only"

  /** What the controller cell says when a cluster reports no controller at all.
    *
    * A KRaft cluster mid-failover genuinely has none for a moment, which is a different statement from "we
    * could not read it" and is worth being able to tell apart on screen.
    */
  val NoController: String = "none"

  val ColumnCluster: String = "Cluster"

  val ColumnStatus: String = "Status"

  val ColumnVersion: String = "Version"

  val ColumnBrokers: String = "Brokers"

  val ColumnController: String = "Controller"

  val ColumnPartitions: String = "Partitions"

  val ColumnUnderReplicated: String = "Under-replicated"

  val ColumnDisk: String = "Disk"

  val ColumnTopics: String = "Topics"

  val BrokersTitle: String = "Brokers"

  def brokersHeading(cluster: String): String = s"Brokers - $cluster"

  val BrokersEmptyTitle: String = "No brokers to show yet"

  val BrokersEmptyDescription: String =
    "This cluster's brokers, their racks and their disk usage appear here."

  val UnknownFailure: String = "the reason was not reported"

  def listFailed(detail: String): String = s"The cluster list could not be read: $detail"

  val EmptyTitle: String = "No clusters yet"

  val EmptyDescription: String =
    "Clusters configured in this deployment appear here."

  /** What this feature says on the shell's fallback panel when its service is unavailable.
    *
    * The one sentence the shell cannot write, because it is about what this feature can still do.
    */
  val UnavailableView: String =
    "Cluster metadata is unavailable, so no cluster can be inspected or switched to. Pages that do not need " +
      "the cluster service — settings, and the component gallery — still work."
}
