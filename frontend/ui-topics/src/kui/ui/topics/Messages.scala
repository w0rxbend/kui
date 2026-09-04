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
}
