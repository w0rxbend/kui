package kui.ui.topics

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English and has no i18n
  * runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Topics"

  /** The feature's own half of the shell's fallback panel.
    *
    * Only the feature can write this sentence: the reason, the "since", the retry and the list of other
    * working features are the shell's, and it draws them around this. What belongs here is what a user can
    * still do while the topic service is down (ADR-032).
    */
  val UnavailableView: String =
    "Topic browsing is unavailable while the topic service is down. The dashboard, the brokers page and " +
      "your settings all still work, and nothing on your Kafka clusters is affected."

  // --- The list screen -------------------------------------------------------------------------

  val SearchPlaceholder: String = "Search topics"

  val ShowInternal: String = "Show internal topics"

  val Refresh: String = "Refresh"

  val TryAgain: String = "Try again"

  /** The tag beside an internal topic's name. Two letters, because the column is a list of names and the name
    * is what is being scanned.
    */
  val InternalTag: String = "IN"

  /** The chip beside an em dash in the Messages column. One word; the sentence is on the chip's title. */
  val OfflineChip: String = "unknown"

  /** The word `StaleDataOverlay` puts before the reason. */
  val StaleState: String = "Stale"

  val ColumnName: String = "Topic name"
  val ColumnPartitions: String = "Partitions"
  val ColumnReplicationFactor: String = "Replication factor"
  val ColumnOutOfSync: String = "Out of sync replicas"
  val ColumnMessages: String = "Messages"
  val ColumnSize: String = "Size"

  /** "8 topics", from `totalItems` — which the server counts after every filter, so it *is* the number of
    * topics being looked through. Deliberately not "8 of 12": a second number invites the reader to work out
    * the difference, and the difference is what the reference product gets wrong.
    */
  def topicCount(total: Option[Long]): String =
    total match {
      case None => ""
      case Some(1L) => "1 topic"
      case Some(count) => s"$count topics"
    }

  /** Why a message count is missing when partitions are offline. Names the number, because "some partitions
    * are offline" is a sentence an operator cannot act on and "3 partitions are offline" is one they can.
    */
  def countOfflinePartitions(count: Int): String =
    if count == 1 then "No count: 1 partition has no leader"
    else s"No count: $count partitions have no leader"

  /** The other absence, and a different sentence, because it calls for a different action: not a broken
    * cluster but a broker that would not report offsets, which is usually a permission or a version.
    */
  val CountNotReported: String = "No count: the broker did not report offsets"

  def favourite(topic: String): String = s"Add $topic to favourites"

  def unfavourite(topic: String): String = s"Remove $topic from favourites"

  val EmptyTitle: String = "No topics found"

  val EmptyDescription: String =
    "Nothing on this cluster matches. Try a different search, or turn on internal topics."

  val ForbiddenTitle: String = "You may not view topics on this cluster"

  val ForbiddenDescription: String =
    "Your account can see the cluster but not its topics. An administrator can grant TOPIC:VIEW."

  /** The reason as the server sent it, unedited. ADR-032 is explicit: an operator whose cluster is down needs
    * the string they can search for or paste into a message, not a friendlier paraphrase of it.
    */
  def unavailable(reason: String, message: String): String = s"Topics are unavailable ($reason): $message"

  // --- The detail screen -----------------------------------------------------------------------

  val TabOverview: String = "Overview"
  val TabSettings: String = "Settings"

  /** The link from a topic to its records. */
  val BrowseMessages: String = "Browse messages"

  val IndicatorPartitions: String = "Partitions"
  val IndicatorReplicationFactor: String = "Replication factor"
  val IndicatorOutOfSync: String = "Out of sync replicas"
  val IndicatorOfflinePartitions: String = "Offline partitions"
  val IndicatorInSyncReplicas: String = "In sync replicas"
  val IndicatorType: String = "Type"
  val IndicatorSize: String = "Size"
  val IndicatorSegments: String = "Segments"
  val IndicatorCleanupPolicy: String = "Cleanup policy"
  val IndicatorMessages: String = "Messages"

  val TypeInternal: String = "Internal"
  val TypeNormal: String = "Normal"

  /** "82 of 84". Two numbers, because the interesting thing is the gap between them. */
  def nOfM(part: Int, whole: Int): String = s"$part of $whole"

  val ColumnPartition: String = "Partition"
  val ColumnLeader: String = "Leader"
  val ColumnReplicas: String = "Replicas"
  val ColumnFirstOffset: String = "First offset"
  val ColumnNextOffset: String = "Next offset"

  /** What a partition with no leader says. Never Kafka's node id `-1`, which reads as a broker. */
  val Offline: String = "offline"

  def replicaLeader(broker: Int): String = s"$broker leader"

  /** The word as well as the colour: a chip that carried "out of sync" in its colour alone would be invisible
    * to about one man in twelve, and spotting the odd one out is what this column is for.
    */
  def replicaOutOfSync(broker: Int): String = s"$broker out of sync"

  val NoPartitionsTitle: String = "No partitions"

  val NoPartitions: String =
    "The broker reported no partitions for this topic, which is unusual — a topic always has at least one."

  /** The same table with no rows, when the data on screen came from the topic *list* snapshot because the
    * cluster could not be reached.
    *
    * It has to be a different sentence, because the fact is different and the other sentence is false here.
    * The snapshot behind the list holds counts, not partition assignments — half a million objects for ten
    * thousand topics of fifty partitions is why — so an empty table under a stale badge means "KUI has not
    * got this", not "the broker says there are none". Telling an operator during an outage that their topic
    * has no partitions is the worst moment to be wrong about it.
    */
  val NoPartitionsStaleTitle: String = "Partitions not available"

  val NoPartitionsStale: String =
    "KUI could not reach the cluster, and the partition assignment is not part of what it keeps between " +
      "scrapes. The counts above are from the last successful scrape; the table fills in as soon as the " +
      "cluster answers again."

  val ColumnSetting: String = "Setting"
  val ColumnValue: String = "Value"
  val ColumnDefault: String = "Default"

  val NoOverridesTitle: String = "No settings reported"

  val NoOverrides: String = "The broker reported no configuration keys for this topic."

  val ConfigNotPermittedTitle: String = "You may not view this topic's settings"

  /** The server's own sentence, unedited (ADR-032). An operator needs the string they can search for. */
  def configNotPermitted(detail: String): String =
    s"The cluster refused to describe this topic's configuration: $detail"

  val NoSuchTopicTitle: String = "No such topic"

  def noSuchTopic(topic: String): String =
    s"There is no topic called '$topic' on this cluster. It may have been deleted, or the name may be a typo."

  val TopicForbiddenTitle: String = "You may not view this topic"

  val TopicForbiddenDescription: String =
    "Your account can see the cluster but not this topic. An administrator can grant TOPIC:VIEW."

  // --- Administration (M5) ---------------------------------------------------------------------
  //
  // The wording rule for everything below: say what will happen to the operator's cluster, in their words,
  // before it happens. Nothing here paraphrases a server warning — those arrive as sentences and are shown
  // whole, so that an operator reading the API and an operator reading the screen are told the same thing.

  val Cancel: String = "Cancel"
  val Remove: String = "Remove"
  val Preview: String = "Preview"

  // Creating a topic

  val CreateTopic: String = "New topic"
  val CreateTopicTitle: String = "Create a topic"
  val CreateSubmit: String = "Create topic"

  val CreateHint: String =
    "Leave partitions or replication factor blank to use this cluster's own defaults. The topic is created " +
      "immediately and the result below reports what the brokers actually made."

  val CreateNameLabel: String = "Name"
  val CreateNameHint: String =
    "Letters, digits, dots, underscores and hyphens, up to 249 characters. Dots and underscores collide in " +
      "Kafka's own metrics, so a name should not mix them."
  val CreateNameInvalid: String =
    "that is not a name Kafka accepts: use letters, digits, '.', '_' and '-', up to 249 characters"

  val CreateBrokerDefault: String = "broker default"

  val CreatePartitionsLabel: String = "Partitions"
  val CreatePartitionsHint: String =
    "How many partitions the topic is split into. It can be raised later but never lowered, and raising it " +
      "changes which partition a given key lands on."
  val CreatePartitionsInvalid: String = "partitions must be a whole number of one or more, or left blank"

  val CreateReplicationLabel: String = "Replication factor"
  val CreateReplicationHint: String =
    "How many brokers hold a copy of each partition. It cannot exceed the number of brokers in the cluster."
  val CreateReplicationInvalid: String =
    "the replication factor must be a whole number of one or more, or left blank"

  val CreateConfigTitle: String = "Configuration"
  val CreateConfigHint: String =
    "Optional. Kafka's own setting names, such as retention.ms or cleanup.policy. Anything the brokers do " +
      "not recognise is refused by them, and their refusal is shown here."
  val CreateAddSetting: String = "Add a setting"
  val CreateSettingKey: String = "Setting"
  val CreateSettingValue: String = "Value"
  val CreateSettingNoKey: String = "a setting has a value but no name"

  def created(topic: String, partitions: Option[Int], replication: Option[Int]): String = {
    val shape = (partitions, replication) match {
      case (Some(count), Some(factor)) => s" with $count partitions and a replication factor of $factor"
      case (Some(count), None) => s" with $count partitions"
      case _ => ""
    }
    s"'$topic' was created$shape."
  }

  // Editing a setting

  val EditSetting: String = "Edit"
  val AddSetting: String = "Add a setting"
  val EditSettingTitle: String = "Change a setting"
  val EditSettingSubmit: String = "Save"
  val EditSettingKey: String = "Setting"
  val EditSettingValue: String = "Value"
  val EditSettingNoKey: String = "type the name of the setting to change"

  val EditSettingHint: String =
    "Only this setting is changed. Every other setting on the topic is left exactly as it is."

  val EditSettingReset: String = "Put this setting back to the broker's default"

  // Adding partitions

  val DangerTitle: String = "Changes that cannot be undone"
  val DangerHint: String =
    "Both of these are shown to you in full before anything happens, and both are applied against exactly " +
      "what you were shown."

  val DangerGone: String =
    "This topic has been deleted. What it held is gone; the record of what was destroyed is below."

  val AddPartitionsTitle: String = "Add partitions"
  val AddPartitionsLabel: String = "New partition count"
  val AddPartitionsConfirm: String = "Add the partitions"
  val AddPartitionsInvalid: String = "the new partition count must be a whole number greater than zero"
  val AddPartitionsUnknown: String = "KUI could not read how many partitions this topic currently has."

  def addPartitionsNow(current: Int): String =
    s"This topic has $current partition${if current == 1 then "" else "s"}. Kafka can add partitions and " +
      "can never remove one."

  def partitionPlan(current: Int, target: Int): String =
    s"$current partitions become $target: ${target - current} added."

  def partitionsApplied(current: Int, target: Int): String =
    s"$target partitions, up from $current. Records written from now on are routed across all $target."

  // Deleting a topic

  val DeleteTitle: String = "Delete this topic"
  val DeleteHint: String =
    "The topic and every record in it are removed. Preview it first: the preview counts what is there now " +
      "and checks whether this cluster will recreate the topic by itself."
  val DeleteConfirm: String = "Delete the topic"
  val DeleteConfirmTitle: String = "Delete this topic?"

  def deleteConfirmMessage(topic: String): String =
    s"'$topic' and every record in it will be deleted. This cannot be undone."

  def deletionPlan(topic: String, partitions: Int, records: Option[Long]): String = {
    val counted = records match {
      case Some(1L) => "1 record"
      case Some(count) => s"$count records"
      // Never a zero. At least one partition could not be counted, and a number smaller than the truth is
      // worse than no number to somebody deciding whether to delete.
      case None => "an unknown number of records"
    }
    s"'$topic' holds $counted across $partitions partition${if partitions == 1 then "" else "s"}."
  }

  def deleted(topic: String, records: Option[Long]): String =
    records match {
      case Some(count) => s"'$topic' was deleted, with $count record${if count == 1L then "" else "s"}."
      case None => s"'$topic' was deleted."
    }
}
