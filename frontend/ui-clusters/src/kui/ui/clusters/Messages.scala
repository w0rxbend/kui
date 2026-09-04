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

  val ColumnConsumerGroups: String = "Consumer groups"

  val ColumnTopics: String = "Topics"

  val BrokersTitle: String = "Brokers"

  def brokersHeading(cluster: String): String = s"Brokers - $cluster"

  val BrokersEmptyTitle: String = "No brokers to show yet"

  val BrokersEmptyDescription: String =
    "This cluster's brokers, their racks and their disk usage appear here."

  val SummaryBrokers: String = "brokers"

  val SummaryController: String = "controller"

  val SummaryControllerType: String = "controller type"

  val SummaryVersion: String = "version"

  val SummaryPartitions: String = "online / total partitions"

  val SummaryInSync: String = "in-sync / total replicas"

  val NoActiveController: String = "No active controller"

  val ControllerTag: String = "controller"

  val ColumnBroker: String = "Broker"

  val ColumnHost: String = "Host"

  val ColumnPort: String = "Port"

  val ColumnRack: String = "Rack"

  val ColumnLeaders: String = "Leaders"

  val ColumnLeaderSkew: String = "Leader skew"

  val ColumnReplicas: String = "Replicas"

  val ColumnInSync: String = "In sync"

  val ColumnReplicaSkew: String = "Replica skew"

  /** What the skew columns measure.
    *
    * Shown on the figure itself rather than only in documentation: a bare "12.4 %" in a column called "Leader
    * skew" is a number nobody can act on until they are told what it is a percentage of.
    */
  val SkewExplanation: String = "How far this broker's count is above the average across brokers."

  def segments(count: Int): String = if count == 1 then "1 segment" else s"$count segments"

  def brokersUnavailable(detail: String): String =
    s"This cluster's brokers could not be read: $detail"

  val BrokersForbidden: String = "You do not have permission to see this cluster's brokers."

  def brokerHeading(cluster: String, broker: Int): String = s"Broker $broker - $cluster"

  val BrokerEmptyTitle: String = "Nothing to show for this broker yet"

  val BrokerEmptyDescription: String =
    "This broker's log directories and its settings appear here."

  // --- Broker detail: the two tabs -----------------------------------------------------------------

  val Segments: String = "segments"

  val ColumnSetting: String = "Setting"

  val ColumnValue: String = "Value"

  val ColumnSource: String = "Source"

  val ColumnReadOnly: String = ""

  val ConfigsSearchLabel: String = "Search settings"

  val ConfigsSearchPlaceholder: String = "e.g. log.retention"

  val ConfigsNoMatchTitle: String = "No setting matches"

  val ConfigsNoMatchDescription: String = "Clear the search box to see every setting again."

  val ConfigsEmptyTitle: String = "This broker reported no settings"

  val ConfigsEmptyDescription: String =
    "That is unusual: a running broker always has settings. It is worth checking what KUI is allowed to " +
      "read on this cluster."

  def configsUnavailable(detail: String): String = s"This broker's settings could not be read: $detail"

  val ConfigsForbidden: String = "You do not have permission to see this broker's settings."

  val RedactedMask: String = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

  /** Why the value is not there, said on the screen.
    *
    * The distinction is worth spelling out: the value was withheld by the server and never sent, rather than
    * being held by the browser and hidden. Anyone who has used an interface that merely masks a value it has
    * in memory has reason to want to know which of the two this is.
    */
  val RedactedExplanation: String = "Redacted by the server. KUI never receives this value."

  val EmptyValueExplanation: String = "Set to the empty string"

  val LogDirOffline: String = "offline"

  val LogDirUsed: String = "Used"

  val LogDirTopics: String = "topics"

  val LogDirPartitions: String = "partitions"

  val LogDirFree: String = "free"

  def ofDisk(total: String): String = s"of $total"

  def logDirFailed(detail: String): String = s"This directory could not be read: $detail"

  def logDirsUnavailable(detail: String): String =
    s"This broker's log directories could not be read: $detail"

  val LogDirsForbidden: String = "You do not have permission to see this broker's log directories."

  val LogDirsEmptyTitle: String = "This broker reported no log directories"

  val LogDirsEmptyDescription: String =
    "A running broker always has at least one. It is worth checking what KUI is allowed to read on this " +
      "cluster."

  // --- The forced refresh ---------------------------------------------------------------------------

  val Refresh: String = "Refresh"

  val RefreshRunning: String = "Asking the server to read this cluster now\u2026"

  val RefreshCompleted: String = "Updated."

  /** What is said when the schedule runs out with no new data.
    *
    * Every clause is deliberate: it is true, it does not claim the refresh failed, and it does not put the
    * screen back as though the button had never been pressed.
    */
  val RefreshTimedOut: String =
    "The refresh was accepted but the data has not been updated yet. It may still be running."

  def refreshRejected(detail: String): String = s"The refresh was not accepted: $detail"

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
