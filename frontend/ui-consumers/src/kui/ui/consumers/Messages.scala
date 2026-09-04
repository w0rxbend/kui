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

  /** What the badge says when the browser's own request failed and the server never got to have an opinion.
    * Distinct from [[StaleState]], which is the server telling us its scrape of the cluster failed.
    */
  val NotRefreshed: String = "KUI could not refresh this list."

  val Forbidden: String = ForbiddenDescription

  val NotConfigured: String = "This deployment does not serve consumer groups for this cluster."

  // --- Columns and the state filter ------------------------------------------------------------

  val ColumnGroup: String = "Group"
  val ColumnState: String = "State"
  val ColumnMembers: String = "Members"
  val ColumnTopics: String = "Topics"
  val ColumnPartitions: String = "Partitions"
  val ColumnLag: String = "Lag"
  val ColumnPace: String = "Pace"

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

  /** What a pace cell says when there is no rate yet.
    *
    * Three different reasons, one dash, and the title says which. The server sends `null` for all three —
    * only one snapshot so far, an unreadable committed total, or a partition set that changed between the two
    * samples — and only the last one is worth explaining, because it is the one that makes the number
    * disappear on a screen an operator is watching.
    */
  val PaceUnknown: String = "no rate yet: it needs two readings of the same partitions"

  /** The unit, spelled out. `rec/s` saves four characters and costs a reader a guess. */
  val PaceUnit: String = "records/s"

  val PaceStalled: String = "committing nothing"

  val PaceBackwards: String = "committed offsets are moving backwards, which is what a reset looks like"

  def excluded(partitions: Int): String =
    if partitions == 1 then "1 partition excluded from this figure"
    else s"$partitions partitions excluded from this figure"

  // --- Throwing a group's state away -----------------------------------------------------------

  val DangerHeading: String = "Delete"

  val DangerDescription: String =
    "These remove committed offsets. Nothing here can be undone, and a consumer that starts again " +
      "afterwards reads from wherever its own auto.offset.reset setting says."

  val ForgetOffsetsHeading: String = "Forget this group's offsets on one topic"

  val ForgetOffsetsDescription: String =
    "For a topic this group has stopped reading. The group and its offsets on every other topic stay."

  val ForgetOffsets: String = "Forget offsets"

  val ForgetOffsetsNone: String =
    "This group holds no committed offsets on any topic, so there is nothing to forget."

  val ForgetOffsetsConfirmTitle: String = "Forget the offsets on this topic?"

  def forgetOffsetsConfirmMessage(topic: String): String =
    s"This group's committed offsets on '$topic' will be deleted. A consumer that starts again on this " +
      "topic reads from wherever its own auto.offset.reset setting says, which is usually the end."

  /** The receipt. The partition count is the point: zero means the group held no offsets there, and that is a
    * different outcome from having deleted some, though both are successes.
    */
  def forgotOffsets(topic: String, partitions: Int): String =
    if partitions == 0 then s"This group held no committed offsets on '$topic'. Nothing was deleted."
    else if partitions == 1 then s"Deleted this group's committed offset on 1 partition of '$topic'."
    else s"Deleted this group's committed offsets on $partitions partitions of '$topic'."

  val DeleteGroupHeading: String = "Delete the group"

  val DeleteGroupDescription: String =
    "Removes the group and everything it has committed, on every topic. Kafka refuses while the group " +
      "still has members, so stop its consumers first."

  val DeleteGroup: String = "Delete the group"

  val DeleteGroupConfirmTitle: String = "Delete this consumer group?"

  def deleteGroupConfirmMessage(group: String): String =
    s"The group '$group' and every offset it has committed will be deleted. Consumers that use this " +
      "group id again will start from wherever their own auto.offset.reset setting says."

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

  // --- The offset-reset wizard (ADR-045) -------------------------------------------------------

  val ResetHeading: String = "Reset offsets"

  val ResetOpen: String = "Reset offsets…"
  val ResetClose: String = "Cancel"

  /** The sentence above the form. It says what the two steps are, because a two-step flow the user was not
    * told about reads as a button that did not work.
    */
  val ResetIntro: String =
    "Choose where this group should start reading from. KUI works out the exact offset for every partition " +
      "and shows you what it would write; nothing changes until you confirm that."

  val ResetTopicLabel: String = "Topic"
  val ResetTargetLabel: String = "Move to"
  val ResetOffsetLabel: String = "Offset"
  val ResetTimestampLabel: String = "Point in time"
  val ResetShiftLabel: String = "Records to shift by"
  val ResetDurationLabel: String = "Minutes to rewind"

  val ResetOffsetHint: String = "The same offset on every partition in scope."
  val ResetTimestampHint: String =
    "In your own time zone. Each partition moves to its first record at or after this moment."
  val ResetShiftHint: String = "Negative rewinds, positive skips forward."
  val ResetDurationHint: String = "Counted back from now, at the moment you ask for the plan."

  val ResetPreview: String = "Show me what this would do"
  val ResetPlanning: String = "Working out the offsets…"
  val ResetApply: String = "Apply this plan"
  val ResetApplying: String = "Writing the offsets…"
  val ResetStartAgain: String = "Start again"
  val ResetDone: String = "Done"

  val ResetPlanHeading: String = "What this would do"
  val ResetReceiptHeading: String = "What was written"

  val ResetColumnPartition: String = "Partition"
  val ResetColumnFrom: String = "From"
  val ResetColumnTo: String = "To"
  val ResetColumnChange: String = "Change"

  /** For a partition the group has never committed on. Never a zero, which would say the consumer is at the
    * beginning of the log when in fact nobody knows where it is.
    */
  val ResetNoCurrent: String = "This group has never committed an offset on this partition"

  val ResetNoOp: String =
    "Every partition is already exactly where this would put it, so there is nothing to apply."

  val ResetApplied: String =
    "The offsets below were written. The group will read from them the next time it starts."

  def resetExpires(at: String): String = s"This plan can be applied until $at. After that, ask for a new one."

  val ResetExpired: String =
    "This plan has expired, so it was not applied. Ask for a new one — the cluster may have moved since."

  // --- What the form refuses before it is a request ---------------------------------------------

  val NoTopic: String = "Choose which topic to reset this group on."
  val NoPartitions: String = "This group holds no partitions on that topic, so there is nothing to reset."
  val BadOffset: String = "An offset is a whole number, and never negative."
  val BadTimestamp: String = "Give a date and a time to move to."
  val BadShift: String = "A shift is a whole number of records. Negative rewinds; positive skips forward."
  val BadDuration: String = "Give a whole number of minutes to rewind, greater than zero."

  // What is deliberately not here: any delete-group or delete-offsets wording. Resetting offsets is a mutation, governed by
  // ADR-045's plan-token confirmation and ADR-047's read-only refusal and audit. A control for it that this
  // screen cannot honour end to end would be a promise with a date on it (DEVPLAN §10 D8), so there is no
  // button and no sentence for one.
}
