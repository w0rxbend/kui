package kui.topic.domain

import java.time.Instant

import kui.kernel.error.{DomainError, FieldError, KuiError}
import kui.kernel.{ClusterId, TopicName}

/** The four things M5 lets an operator do to a topic, and the rules that hold before any of them runs.
  *
  * They are gathered in one file because they share one rule, and the rule matters more than the grouping:
  * **every one of them is a mutation** in the sense of ADR-047. Every one is refused on a read-only cluster
  * before any Kafka client is touched, every one carries a marker the endpoint list can be enumerated
  * against, and every one writes exactly one audit record whether it succeeded or failed.
  *
  * Two of them additionally carry a plan (ADR-045), and which two is not a matter of taste. ADR-045 §4 says
  * an operation needs a plan when its effect is not a function of its request alone:
  *
  *   - **Increasing partitions** qualifies twice over. The request says "make it twelve"; the effect depends
  *     on how many there are now (Kafka refuses a target that is not greater), and it silently changes
  *     key-to-partition routing for every record produced from then on. ADR-045 names it explicitly.
  *   - **Deleting a topic** qualifies because of what the cluster, not the request, decides: how many records
  *     are about to be lost, and whether `auto.create.topics.enable` will let a producer recreate the topic
  *     seconds later. Neither is readable off `DELETE /topics/orders.v1`.
  *   - **Creating a topic** and **changing its configuration** do not. The request *is* the effect: the
  *     partitions, the replication factor and the settings are the numbers the operator typed, and the server
  *     answers with what it did. They carry the marker, the refusal and the audit record, and nothing to
  *     preview that the form does not already say.
  */
enum TopicMutation(val operation: String, val destructive: Boolean) {

  /** A new topic, with its partitions, replication factor and configuration (`MT-001`). */
  case Create extends TopicMutation("topic.create", destructive = false)

  /** A change to a topic's dynamic configuration (`MT-002`). Not destructive: it changes how records are
    * retained, not which records exist — although a retention shortened past the age of the log is a delete
    * on a delay, which is why the screen says so.
    */
  case AlterConfig extends TopicMutation("topic.config.alter", destructive = false)

  /** More partitions than the topic had (`MT-003`). Kafka has no way to remove one afterwards, and every
    * future record with an existing key may land on a different partition from the records already written
    * under that key. Irreversible, and therefore destructive even though it deletes nothing.
    */
  case IncreasePartitions extends TopicMutation("topic.partitions.increase", destructive = true)

  /** The topic and every record in it (`MT-004`). */
  case Delete extends TopicMutation("topic.delete", destructive = true)
}

object TopicMutation {
  val All: List[TopicMutation] = values.toList
  given CanEqual[TopicMutation, TopicMutation] = CanEqual.derived
}

/** A topic to create.
  *
  * `partitions` and `replicationFactor` are `Option` and an absent one is **not** a default this code
  * invents: it is passed to Kafka as `Optional.empty()`, which makes the broker apply its own
  * `num.partitions` and `default.replication.factor` (`research/kafka/admin-capabilities.md`, create-topic
  * row). A UI that substituted `1` here would create single-replica topics on a three-broker cluster whose
  * operator had deliberately configured a default of three.
  *
  * `config` is the dynamic configuration to set at creation time, exactly as Kafka spells it —
  * `retention.ms`, `cleanup.policy`. The keys are not validated against a list held here: Kafka's set of
  * topic-level settings changes with every release, and a list in KUI would be a list that goes stale and
  * starts refusing settings the broker accepts. An unknown key is refused by the broker, and that refusal is
  * reported as it stands.
  */
final case class NewTopicSpec private (
    name: TopicName,
    partitions: Option[Int],
    replicationFactor: Option[Short],
    config: Map[String, String]
) {
  val kind: TopicMutation = TopicMutation.Create
}

object NewTopicSpec {

  /** The largest partition count KUI will submit without the operator having gone somewhere else.
    *
    * Kafka itself has no limit; the practical one is that every partition costs file handles and memory on
    * every broker, and a mistyped `10000` is a cluster incident rather than a typo. It is a refusal and not a
    * clamp, for the same reason a produce count is: silently creating a hundred partitions when a thousand
    * were asked for is a topic the operator did not ask for and cannot tell apart from one they did.
    */
  val MaxPartitions: Int = 10000

  /** Kafka's own ceiling on a replication factor, which is a `short` on the wire. */
  val MaxReplicationFactor: Int = Short.MaxValue.toInt

  def of(
      name: TopicName,
      partitions: Option[Int],
      replicationFactor: Option[Int],
      config: Map[String, String]
  ): Either[KuiError, NewTopicSpec] = {
    def refuse(rule: String, field: String, expected: String): Either[KuiError, NewTopicSpec] =
      Left(DomainError.InvariantViolation(rule, List(FieldError.of(field, expected))))

    if partitions.exists(count => count < 1 || count > MaxPartitions) then
      refuse(
        s"a topic has between 1 and $MaxPartitions partitions",
        "partitions",
        s"between 1 and $MaxPartitions, or absent to use the broker's num.partitions"
      )
    else if replicationFactor.exists(factor => factor < 1 || factor > MaxReplicationFactor) then
      refuse(
        s"a replication factor is between 1 and $MaxReplicationFactor",
        "replicationFactor",
        s"between 1 and $MaxReplicationFactor, or absent to use the broker's default.replication.factor"
      )
    else if config.keys.exists(_.isBlank) then
      refuse("a configuration entry has no key", "config", "a non-empty key for every entry")
    else
      Right(
        NewTopicSpec(
          name = name,
          partitions = partitions,
          replicationFactor = replicationFactor.map(_.toShort),
          config = config
        )
      )
  }

  given CanEqual[NewTopicSpec, NewTopicSpec] = CanEqual.derived
}

/** A change to a topic's dynamic configuration.
  *
  * Two maps rather than one, because "set this key to this value" and "put this key back to the broker
  * default" are different operations on Kafka's side — `AlterConfigOp.OpType.SET` and `DELETE` — and a single
  * map cannot express the second at all. A key whose value is an empty string is a legal Kafka setting, so
  * mapping "" to "remove it" would make a real value unreachable.
  *
  * The alternative shape, which the reference product uses, is "here is the whole dynamic set, replace it":
  * every key not in the submission is deleted. That is a worse contract for an API, because a client that
  * sends one field it wants to change silently reverts every other override the topic had.
  */
final case class TopicConfigChange private (set: Map[String, String], remove: Set[String]) {
  val kind: TopicMutation = TopicMutation.AlterConfig

  def isEmpty: Boolean = set.isEmpty && remove.isEmpty
}

object TopicConfigChange {

  def of(set: Map[String, String], remove: Set[String]): Either[KuiError, TopicConfigChange] = {
    val overlap = set.keySet.intersect(remove)

    if set.keys.exists(_.isBlank) || remove.exists(_.isBlank) then
      Left(
        DomainError.InvariantViolation(
          "a configuration entry has no key",
          List(FieldError.of("config", "a non-empty key for every entry"))
        )
      )
    else if overlap.nonEmpty then
      // Sending both to Kafka in one `incrementalAlterConfigs` is an error whose message names neither
      // the key nor the caller's mistake. Refusing here names both.
      Left(
        DomainError.InvariantViolation(
          s"${overlap.toList.sorted.mkString(", ")} is both set and removed by the same change",
          List(FieldError.of("config", "a key in either 'set' or 'remove', not both"))
        )
      )
    else if set.isEmpty && remove.isEmpty then
      Left(
        DomainError.InvariantViolation(
          "the change alters nothing",
          List(FieldError.of("config", "at least one key to set or to remove"))
        )
      )
    else Right(TopicConfigChange(set, remove))
  }

  given CanEqual[TopicConfigChange, TopicConfigChange] = CanEqual.derived
}

/** One reason an operator should read the rest of a plan before confirming it.
  *
  * Display text, one sentence, safe to put on a screen. It is computed on the server and carried on the plan
  * rather than composed in the browser, so that a `curl` user and a browser user are warned about the same
  * thing — ADR-045's stated consequence that "an API user gets the same protection as a browser user".
  */
final case class PlanWarning(code: String, message: String)

object PlanWarning {

  /** The one that changes behaviour for ever, and the reason the partition increase is classified
    * destructive. Kafka's default partitioner is `hash(key) % partitionCount`, so raising the count moves
    * most keys to a different partition. Records already written stay where they are, and per-key ordering —
    * which is the only ordering Kafka offers — is broken across the boundary.
    */
  val KeyRouting: String = "KEY_ROUTING_CHANGES"

  /** The topic can come straight back. `auto.create.topics.enable` is `true` on the broker, so the first
    * producer or consumer to name the topic after the delete recreates it with the broker's defaults — a
    * different partition count and none of its configuration. KUI's own message browser hit exactly this.
    */
  val AutoCreate: String = "AUTO_CREATE_ENABLED"

  /** How many records the delete throws away, when KUI could count them. */
  val RecordsLost: String = "RECORDS_LOST"

  /** KUI could not read the broker's `auto.create.topics.enable`, so it cannot say whether the topic will
    * come back. Reported rather than assumed: "we did not check" and "it is off" are different statements and
    * only one of them is a promise.
    */
  val AutoCreateUnknown: String = "AUTO_CREATE_UNKNOWN"

  given CanEqual[PlanWarning, PlanWarning] = CanEqual.derived
}

/** What increasing a topic's partitions would do, resolved against the cluster as it is now.
  *
  * `current` is read from the broker at plan time and is what makes the plan more than an echo of the
  * request: an operator who thinks a topic has three partitions and asks for six is shown that it already has
  * six, and the plan refuses rather than the broker doing so a screen later.
  */
final case class PartitionPlan(
    topic: TopicName,
    current: Int,
    target: Int,
    warnings: List[PlanWarning],
    computedAt: Instant
) {
  val kind: TopicMutation = TopicMutation.IncreasePartitions

  def added: Int = target - current
}

object PartitionPlan {

  /** Builds the plan, or refuses.
    *
    * Kafka's `createPartitions` refuses a target that is not strictly greater than the current count with
    * `InvalidPartitionsException`, and it has no way at all to reduce one. Both refusals are made here so
    * that the operator reads a sentence about their topic rather than a broker exception's class name, and so
    * that the wizard never renders a plan the apply step is certain to fail.
    */
  def of(
      topic: TopicName,
      current: Int,
      target: Int,
      computedAt: Instant
  ): Either[KuiError, PartitionPlan] =
    if target <= current then
      Left(
        DomainError.InvariantViolation(
          s"'${topic.value}' already has $current partitions, and Kafka cannot remove one; a partition " +
            "count can only be increased",
          List(FieldError.of("partitions", s"more than $current"))
        )
      )
    else if target > NewTopicSpec.MaxPartitions then
      Left(
        DomainError.InvariantViolation(
          s"a topic has at most ${NewTopicSpec.MaxPartitions} partitions",
          List(FieldError.of("partitions", s"between ${current + 1} and ${NewTopicSpec.MaxPartitions}"))
        )
      )
    else
      Right(
        PartitionPlan(
          topic = topic,
          current = current,
          target = target,
          warnings = List(
            PlanWarning(
              PlanWarning.KeyRouting,
              s"records are routed by hash(key) % partitions, so raising the count from $current to " +
                s"$target sends most keys to a different partition from the records already stored under " +
                "them. Per-key ordering is broken across the change, and it cannot be undone: Kafka has no " +
                "way to remove a partition."
            )
          ),
          computedAt = computedAt
        )
      )

  given CanEqual[PartitionPlan, PartitionPlan] = CanEqual.derived
}

/** What deleting a topic would destroy, and what would happen next.
  *
  * @param records
  *   how many records are in the topic, when every partition answered. `None` means at least one partition
  *   could not be counted, and the plan says "unknown" rather than a sum over the partitions that did answer
  *   — a number smaller than the truth, shown to somebody deciding whether to delete, is worse than no number
  * @param autoCreateEnabled
  *   whether the broker's `auto.create.topics.enable` will recreate the topic the moment anything names it.
  *   `None` means KUI could not read the broker configuration, which is a third answer and not a `false`
  */
final case class DeletionPlan(
    topic: TopicName,
    partitions: Int,
    records: Option[Long],
    autoCreateEnabled: Option[Boolean],
    warnings: List[PlanWarning],
    computedAt: Instant
) {
  val kind: TopicMutation = TopicMutation.Delete
}

object DeletionPlan {

  def of(
      topic: TopicName,
      partitions: Int,
      records: Option[Long],
      autoCreateEnabled: Option[Boolean],
      computedAt: Instant
  ): DeletionPlan = {
    val lost = records.toList.map(count =>
      PlanWarning(
        PlanWarning.RecordsLost,
        s"$count record${if count == 1L then "" else "s"} across $partitions partition" +
          s"${if partitions == 1 then "" else "s"} are deleted with the topic and cannot be recovered."
      )
    )

    val recreation = autoCreateEnabled match {
      case Some(true) =>
        List(
          PlanWarning(
            PlanWarning.AutoCreate,
            s"this cluster has auto.create.topics.enable=true, so the first producer or consumer to name " +
              s"'${topic.value}' will recreate it — with the broker's default partition count and none of " +
              "this topic's configuration. The data is still gone; only the name comes back."
          )
        )
      case Some(false) => Nil
      case None =>
        List(
          PlanWarning(
            PlanWarning.AutoCreateUnknown,
            "KUI could not read auto.create.topics.enable from this cluster, so it cannot say whether the " +
              "topic will be recreated by the next client that names it."
          )
        )
    }

    DeletionPlan(
      topic = topic,
      partitions = partitions,
      records = records,
      autoCreateEnabled = autoCreateEnabled,
      warnings = lost ++ recreation,
      computedAt = computedAt
    )
  }

  given CanEqual[DeletionPlan, DeletionPlan] = CanEqual.derived
}

/** What KUI did, so a screen can say it without asking again.
  *
  * A receipt rather than an echo: `partitions` and `replicationFactor` are what the cluster reports *after*
  * the create, which is how a topic created with the broker's defaults tells the operator what those defaults
  * turned out to be.
  */
final case class CreatedTopic(
    cluster: ClusterId,
    topic: TopicName,
    partitions: Option[Int],
    replicationFactor: Option[Int]
)

object CreatedTopic {
  given CanEqual[CreatedTopic, CreatedTopic] = CanEqual.derived
}
