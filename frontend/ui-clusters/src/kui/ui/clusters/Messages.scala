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

  /** What a screen reader hears beside an out-of-sync count that is not zero. The number on its own says
    * nothing about whether the number is a problem.
    */
  val UnderReplicatedAnnouncement: String = "partitions are under-replicated"

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

  val SummaryReplicas: String = "replicas held"

  val NoActiveController: String = "No active controller"

  val ControllerTag: String = "controller"

  val ColumnBroker: String = "Broker"

  val ColumnHost: String = "Host"

  val ColumnPort: String = "Port"

  val ColumnRack: String = "Rack"

  val ColumnLeaders: String = "Leaders"

  val ColumnLeaderSkew: String = "Leader skew"

  val ColumnReplicas: String = "Replicas"

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

  // --- The administration screen ---------------------------------------------------------------

  val AdminTitle: String = "Manage clusters"

  val AdminDescription: String =
    "Which Kafka clusters this KUI knows about, and how it reaches them. Changes take effect without a " +
      "restart and are shared by every KUI replica reading the same metadata store."

  val AddCluster: String = "Add a cluster"
  val EditCluster: String = "Edit"
  val DeleteCluster: String = "Delete"
  val SaveCluster: String = "Save"
  val Cancel: String = "Cancel"
  val TestConnection: String = "Test the connection"

  val AdminEmptyTitle: String = "No clusters are configured"

  val AdminEmptyDescription: String =
    "Add one here, or declare it under kui.clusters in this deployment's configuration file."

  val OriginStatic: String = "from the configuration file"
  val OriginStored: String = "added here"
  val OriginStaticThenStored: String = "from the configuration file, edited here"

  /** Why a statically configured cluster has no buttons.
    *
    * Said before the operator opens a form rather than as a 409 after they have filled one in: the store
    * record would go and the next resolve would put the configured profile straight back.
    */
  val ClusterIsStatic: String =
    "This cluster is declared in the configuration file, so it cannot be changed here. Edit kui.clusters " +
      "and restart."

  val FieldName: String = "Name"

  val FieldNameHint: String =
    "The URL id is derived from the name, so renaming a cluster creates a new one and leaves the old."

  val FieldBootstrap: String = "Broker addresses"

  val FieldBootstrapHint: String = "host:port, comma separated. Two or three is enough; KUI finds the rest."

  val FieldReadOnly: String = "Read-only — refuse every write KUI can make against this cluster"

  val FieldProtocol: String = "Security protocol"
  val FieldMechanism: String = "SASL mechanism"
  val FieldUsername: String = "Username"
  val FieldPassword: String = "Password"
  val FieldVerifyHostname: String = "Verify the broker's hostname against its certificate"

  /** The gap, said out loud rather than left for someone to discover.
    *
    * A cluster whose certificate authority the JVM already trusts — which is every managed service — works
    * from this form. A private CA still needs a truststore, and this form has no way to give KUI one.
    */
  val TlsMaterialNote: String =
    "A truststore or keystore for a private certificate authority cannot be uploaded here yet; set it " +
      "under kui.clusters in the configuration file. Clusters whose certificate the JVM already trusts " +
      "need nothing."

  val AdminTuningHeading: String = "Admin client timeouts"

  val FieldTimeout: String = "Timeout for one admin call (ms)"
  val FieldBatchSize: String = "Topics per describe batch"
  val FieldParallelism: String = "Concurrent admin calls"

  val VerdictReachable: String = "KUI reached this cluster."
  val VerdictRefused: String = "KUI reached this cluster and it refused the credentials."
  val VerdictUnreachable: String = "KUI could not reach this cluster."

  val DeleteClusterConfirmTitle: String = "Remove this cluster from KUI?"

  val DeleteClusterConfirmMessage: String =
    "KUI forgets this cluster's address and credentials. Nothing on the Kafka cluster itself is touched, " +
      "and it can be added again — but the credentials would have to be typed in afresh."

  def clusterSaved(name: String): String = s"'$name' was saved."

  val ClusterDeleted: String = "The cluster was removed from KUI."

  // --- The KRaft metadata quorum -----------------------------------------------------------------

  val QuorumHeading: String = "Metadata quorum"

  val QuorumLeader: String = "Leader"
  val QuorumEpoch: String = "Epoch"
  val QuorumHighWatermark: String = "Committed up to"
  val QuorumVoterCount: String = "Voters"

  val QuorumVoters: String = "Voters"
  val QuorumObservers: String = "Observers"

  val QuorumNoObservers: String =
    "No observers: every node in this cluster votes, which is what a combined controller-and-broker " +
      "deployment looks like."

  val QuorumNoMembers: String = "The cluster reported none."

  val QuorumLeaderTag: String = "leader"

  val QuorumColumnNode: String = "Node"
  val QuorumColumnLogEnd: String = "Log end offset"
  val QuorumColumnLag: String = "Behind by"
  val QuorumColumnLastFetch: String = "Last fetch"
  val QuorumColumnLastCaughtUp: String = "Last caught up"

  /** Why a cell has no time in it. The leader does not fetch from anyone; that is not a follower that has
    * stopped, and the two must not read the same.
    */
  val QuorumTimeUnknown: String = "the cluster reported no time for this"

  def quorumHealthy(caughtUp: Int, voters: Int): String =
    s"$caughtUp of $voters voters are level with the leader, so metadata changes can still be committed."

  /** The sentence a panel of numbers cannot say for itself.
    *
    * Without a majority of voters level with the leader, a metadata write cannot commit — which means every
    * topic create, every configuration change and every ACL is about to start timing out. Saying so here is
    * the difference between noticing it now and debugging it from the other end an hour later.
    */
  def quorumAtRisk(caughtUp: Int, voters: Int): String =
    s"Only $caughtUp of $voters voters are level with the leader. Metadata changes — creating a topic, " +
      "changing a configuration, altering an ACL — may not be able to commit."
}
