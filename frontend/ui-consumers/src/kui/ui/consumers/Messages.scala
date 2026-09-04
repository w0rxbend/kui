package kui.ui.consumers

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English and has no i18n
  * runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Consumers"

  /** The feature's own half of the shell's fallback panel.
    *
    * Only the feature can write this sentence: the reason, the "since", the retry and the list of other
    * working features are the shell's, and it draws them around this. What belongs here is what a user can
    * still do while the consumer service is down (ADR-032).
    */
  val UnavailableView: String =
    "Consumer groups are unavailable while the consumer service is down. The dashboard, the brokers page " +
      "and the topic explorer all still work, and nothing on your Kafka clusters is affected — your " +
      "consumers keep consuming while KUI cannot see them."

  // --- The list screen -------------------------------------------------------------------------

  val SearchPlaceholder: String = "Search consumer groups"

  val Refresh: String = "Refresh"

  val TryAgain: String = "Try again"

  val EmptyTitle: String = "No consumer groups"
  val EmptyDescription: String =
    "Nothing has consumed from this cluster, or every group that did has been deleted."

  val ForbiddenTitle: String = "You cannot see this cluster's consumer groups"
  val ForbiddenDescription: String =
    "Your account does not carry permission to read consumer groups on this cluster. Ask whoever " +
      "administers KUI if you need it."

  /** The one place the row count is worded. `null` is not zero, so the word "known" is doing work here. */
  def groupCount(total: Long): String = if total == 1L then "1 group" else s"$total groups"

  def unavailable(reason: String, detail: String): String =
    s"The consumer service could not answer ($reason). $detail"

  val StaleState: String = "Stale"

  // --- Columns and the state filter ------------------------------------------------------------

  val ColumnGroup: String = "Group"
  val ColumnState: String = "State"
  val ColumnMembers: String = "Members"
  val ColumnTopics: String = "Topics"
  val ColumnPartitions: String = "Partitions"
  val ColumnLag: String = "Lag"

  val StateFilterLabel: String = "State"
  val StateFilterAll: String = "Any state"

  /** What a lag cell says when the service could not compute one.
    *
    * Never a zero. A group whose lag could not be read is not a group that has caught up, and the whole
    * reason the wire carries `null` rather than `0` is that the two must not look the same on a screen.
    */
  val LagUnknown: String = "not known"

  /** The chip beside a group KUI could only partly read. One word; the sentence is on the chip's title. */
  val PartialChip: String = "partial"

  def excluded(partitions: Int): String =
    if partitions == 1 then "1 partition excluded from this figure"
    else s"$partitions partitions excluded from this figure"

  // --- The topic page's Consumers tab ----------------------------------------------------------

  /** The tab's own label on the topic page's strip. One word, because it sits beside "Overview" and
    * "Settings" and a longer phrase would make the strip wrap on a narrow window.
    */
  val TopicTabLabel: String = "Consumers"

  val ColumnTopicLag: String = "Lag on this topic"

  val TopicLoading: String = "Looking for the groups that read this topic…"

  val TopicEmptyTitle: String = "Nothing consumes this topic"
  val TopicEmptyDescription: String =
    "No consumer group holds a committed offset on it. A producer-only topic looks exactly like this."

  /** The section is `not_configured`: this deployment has no consumer service for this cluster. Not an error,
    * and deliberately not worded as one — an operator shown a permanent red panel stops reading red panels,
    * including the one that matters (ADR-032).
    */
  val TopicNotConfigured: String =
    "This deployment does not track consumer groups for this cluster, so there is nothing to show here."

  val TopicStale: String =
    "These are the last consumer groups KUI could read for this topic, not the current ones."

  /** A group with committed offsets on this topic and no members. */
  val DormantChip: String = "dormant"
  val DormantExplanation: String =
    "This group has committed offsets on this topic but nothing is connected. A batch job between runs " +
      "looks exactly like this."

  // --- The detail screen -----------------------------------------------------------------------

  val BackToList: String = "Consumer groups"

  val MembersHeading: String = "Members"
  val MembersEmpty: String = "This group has no members"
  val MembersEmptyDescription: String =
    "Nothing is connected. The group still exists because its committed offsets do."

  val AssignmentsHeading: String = "Assignments and lag"
  val AssignmentsEmpty: String = "No partitions"
  val AssignmentsEmptyDescription: String = "This group has committed no offsets on any topic."

  val ColumnMemberId: String = "Member"
  val ColumnClientId: String = "Client"
  val ColumnHost: String = "Host"
  val ColumnAssigned: String = "Assigned partitions"

  val ColumnPartition: String = "Partition"
  val ColumnCommitted: String = "Committed"
  val ColumnEnd: String = "End offset"
  val ColumnMember: String = "Held by"

  val TotalLagLabel: String = "Total lag"
  val ProtocolLabel: String = "Protocol"
  val AssignorLabel: String = "Assignor"
  val CoordinatorLabel: String = "Coordinator"
  val ObservedAtLabel: String = "Observed"

  val Rebalancing: String = "rebalancing"

  /** The sentence under the assignment tables when Kafka reported no assignments at all.
    *
    * During a rebalance the broker reports none, and drawing an empty table would tell an operator their
    * consumers had stopped — the opposite of what is happening.
    */
  val AssignmentsLastSeen: String =
    "The group is rebalancing, so these are the last assignments seen rather than the current ones."

  val AssignmentsUnknown: String =
    "The current assignments could not be read, so the partitions below may be held by somebody else now."

  // What is deliberately not here: any offset-reset wording. Resetting offsets is a mutation, governed by
  // ADR-045's plan-token confirmation and ADR-047's read-only refusal and audit. A control for it that this
  // screen cannot honour end to end would be a promise with a date on it (DEVPLAN §10 D8), so there is no
  // button and no sentence for one.
}
