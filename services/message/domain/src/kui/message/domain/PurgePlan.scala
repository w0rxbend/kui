package kui.message.domain

import java.time.Instant

import kui.kernel.{Offset, PartitionId, TopicName}

/** One partition of a purge: where its log starts now, where it ends, and how much is about to go.
  *
  * `deleteBefore` is the end offset, so a purge deletes everything the partition currently holds and leaves
  * the partition itself in place with its low watermark moved up to that point. `records` is `end - start`,
  * derived rather than reported, so the number on the screen and the number in the token cannot disagree.
  */
final case class PlannedPurge(
    partition: PartitionId,
    lowWatermark: Offset,
    highWatermark: Offset
) {

  /** How many records this partition loses. Zero for a partition that is already empty, which is a fact and
    * not a refusal: a topic where three partitions hold data and one does not is purged in full.
    */
  def records: Long = highWatermark.value - lowWatermark.value

  def isEmpty: Boolean = records <= 0L

  /** The offset Kafka is asked to delete before. The end offset: everything below it goes. */
  def deleteBefore: Offset = highWatermark
}

object PlannedPurge {
  given CanEqual[PlannedPurge, PlannedPurge] = CanEqual.derived
}

/** What emptying a topic would destroy, resolved against the cluster as it is now (`MS-008`, ADR-045).
  *
  * ==Why this operation has a plan at all==
  *
  * ADR-045's test is whether an operation's effect is a function of its request alone. `POST …/purge` says
  * nothing about what it destroys: the answer is the number of records the topic is holding at the moment the
  * broker is asked, which only the broker knows and which changes while the operator is deciding. This is the
  * operation ADR-045 was written for — it is irreversible in the strongest sense the product has. Kafka's
  * `deleteRecords` moves a log's low watermark and the records below it are gone; there is no undo, no
  * tombstone and no copy.
  *
  * ==Why a purge is not a delete-and-recreate==
  *
  * The reference product empties a topic by deleting it and creating it again with the same configuration,
  * retrying while the deletion is still in flight. That loses everything the topic *is* — its id, its
  * consumer groups' committed offsets stay pointed at a log that no longer exists, and on a cluster with
  * automatic creation the recreate can race a producer. `deleteRecords` empties the log and leaves the topic,
  * its configuration and its partition count exactly as they were, which is what an operator asking to purge
  * a topic means.
  */
final case class PurgePlan(
    topic: TopicName,
    partitions: List[PlannedPurge],
    warnings: List[PurgeWarning],
    computedAt: Instant
) {

  /** Every record the purge destroys, summed. */
  def records: Long = partitions.map(_.records).sum

  /** True when there is nothing to delete. The screen says so and offers no confirmation: a confirmation
    * dialogue for an operation that changes nothing teaches operators to click through confirmations.
    */
  def isNoOp: Boolean = partitions.forall(_.isEmpty)

  /** What the apply step asks Kafka to do: delete before this offset, per partition. Partitions that are
    * already empty are left out — asking Kafka to delete before a partition's own low watermark is a call
    * that does nothing and can still fail.
    */
  def deletions: Map[PartitionId, Offset] =
    partitions.filterNot(_.isEmpty).map(one => one.partition -> one.deleteBefore).toMap
}

/** One sentence an operator should read before confirming a purge.
  *
  * The same shape as the topic service's `PlanWarning` and deliberately a separate type: rule A11 forbids
  * either service seeing the other's domain, and a shared one would have to live in a library that neither
  * service's vocabulary belongs in. What must not drift — the wire shape — is asserted by the contract suites
  * on both sides.
  */
final case class PurgeWarning(code: String, message: String)

object PurgeWarning {

  /** The one that is always true, and the reason this operation has a plan. */
  val RecordsLost: String = "RECORDS_LOST"

  /** A compacted topic without `delete` in its `cleanup.policy`. Kafka's broker-side policy refuses
    * `deleteRecords` on one, and the refusal arrives as a `PolicyViolationException` naming nothing an
    * operator can act on. Saying so in the plan turns a failed apply into a decision not to try.
    */
  val Compacted: String = "COMPACTED_TOPIC"

  /** Committed offsets are not moved by a purge. A consumer group sitting below the new low watermark will be
    * reset by its own `auto.offset.reset` on its next fetch, which for the default `latest` means it silently
    * skips to the end. Operators are routinely surprised by this.
    */
  val ConsumerOffsets: String = "CONSUMER_OFFSETS_UNCHANGED"

  given CanEqual[PurgeWarning, PurgeWarning] = CanEqual.derived
}

object PurgePlan {

  /** Builds the plan from what the broker reported.
    *
    * @param cleanupPolicy
    *   the topic's `cleanup.policy`, when it could be read. `None` means KUI could not read it and the plan
    *   says nothing about compaction rather than guessing — the same three-answer rule the topic service's
    *   deletion plan uses for automatic topic creation.
    */
  def of(
      topic: TopicName,
      partitions: List[PlannedPurge],
      cleanupPolicy: Option[String],
      computedAt: Instant
  ): PurgePlan = {
    val ordered = partitions.sortBy(_.partition.value)
    val total = ordered.map(_.records).sum

    val lost =
      if total <= 0L then Nil
      else
        List(
          PurgeWarning(
            PurgeWarning.RecordsLost,
            s"$total record${if total == 1L then "" else "s"} across " +
              s"${ordered
                  .count(!_.isEmpty)} partition${if ordered.count(!_.isEmpty) == 1 then "" else "s"} " +
              "are deleted and cannot be recovered. The topic, its configuration and its partitions stay " +
              "exactly as they are; only the records go."
          )
        )

    val compacted =
      cleanupPolicy
        .filter(policy => policy.contains("compact") && !policy.contains("delete"))
        .map(policy =>
          PurgeWarning(
            PurgeWarning.Compacted,
            s"This topic's cleanup.policy is '$policy'. Kafka refuses to delete records from a topic that " +
              "is only compacted, so this purge will very likely be rejected by the broker."
          )
        )
        .toList

    val offsets =
      if total <= 0L then Nil
      else
        List(
          PurgeWarning(
            PurgeWarning.ConsumerOffsets,
            "Committed consumer offsets are not moved by a purge. A group sitting below the new start of " +
              "the log follows its own auto.offset.reset on the next fetch, which by default means it " +
              "jumps to the end and skips whatever arrives in between."
          )
        )

    PurgePlan(topic, ordered, lost ++ compacted ++ offsets, computedAt)
  }

  given CanEqual[PurgePlan, PurgePlan] = CanEqual.derived
}
