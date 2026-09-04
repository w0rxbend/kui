package kui.consumer.domain

import kui.kernel.TopicPartition

/** Which parts of a group's picture were actually obtained.
  *
  * Without this type, "this group has no members" and "KUI was not allowed to ask about members" render
  * identically, and they mean opposite things: one is a group nobody is running, the other is a permission an
  * operator has to go and grant. Each flag is `true` when that part is known and `false` when the call was
  * skipped, refused or failed.
  *
  * `excludedPartitions` is what lets a screen say "of 12 partitions, 3 have no computable lag, because they
  * have no leader" rather than showing a total that quietly means less than it looks like.
  */
final case class GroupCompleteness(
    membersKnown: Boolean,
    committedOffsetsKnown: Boolean,
    endOffsetsKnown: Boolean,
    excludedPartitions: Map[TopicPartition, String]
) {

  def isComplete: Boolean =
    membersKnown && committedOffsetsKnown && endOffsetsKnown && excludedPartitions.isEmpty

  def excluding(partition: TopicPartition, reason: String): GroupCompleteness =
    copy(excludedPartitions = excludedPartitions + (partition -> reason))

  def withoutMembers: GroupCompleteness = copy(membersKnown = false)
  def withoutCommittedOffsets: GroupCompleteness = copy(committedOffsetsKnown = false)
  def withoutEndOffsets: GroupCompleteness = copy(endOffsetsKnown = false)
}

object GroupCompleteness {

  val Complete: GroupCompleteness =
    GroupCompleteness(
      membersKnown = true,
      committedOffsetsKnown = true,
      endOffsetsKnown = true,
      excludedPartitions = Map.empty
    )

  given CanEqual[GroupCompleteness, GroupCompleteness] = CanEqual.derived
}
