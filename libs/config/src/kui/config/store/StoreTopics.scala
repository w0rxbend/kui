package kui.config.store

import kui.config.StoreConfig

/** Which milestone creates a topic.
  *
  * Data rather than a condition in code, so that the milestone which first writes an audit record adds a row
  * to a list instead of inventing a second bootstrap mechanism beside this one.
  */
enum CreatedBy {
  case M1
  case M5
}

object CreatedBy {
  given CanEqual[CreatedBy, CreatedBy] = CanEqual.derived
}

/** One `__kui_*` topic: its name, its partition count, and the configuration KUI creates and validates.
  *
  * The split between `required` and `advisory` is the whole design of the bootstrap. A required setting is
  * one whose wrong value silently breaks KUI — compaction turned off loses records, a second partition
  * destroys the total order the concurrency design rests on — so a difference there stops start-up. An
  * advisory setting is one an operator may reasonably have opinions about, so a difference is logged and KUI
  * carries on. KUI never rewrites an existing topic's configuration either way: someone else's retention
  * setting is someone else's decision, and silently changing it is how a management tool loses an operator's
  * trust.
  */
final case class StoreTopic(
    name: String,
    partitions: Int,
    createdBy: CreatedBy,
    required: Map[String, String],
    advisory: Map[String, String]
) {

  /** What `createTopics` sets: everything, required and advisory alike. */
  def creationConfig: Map[String, String] = required ++ advisory
}

/** The three topics ADR-042 names, derived from the configured prefix. */
final case class StoreTopics(config: StoreTopic, files: StoreTopic, audit: StoreTopic) {

  /** Only the topics M1 creates and validates.
    *
    * `__kui_audit` is deliberately absent (DEVPLAN §10 D7). Creating a retention-based topic that nothing
    * produces to would leave an operator wondering why it is empty, and would fix its retention settings
    * before the feature that needs them exists. M5 adds it to this list.
    */
  def managedNow: List[StoreTopic] = List(config, files).filter(_.createdBy == CreatedBy.M1)

  def all: List[StoreTopic] = List(config, files, audit)
}

object StoreTopics {

  /** How much room a record needs beyond the file it carries: the JSON envelope, the base64 expansion and the
    * GCM tag. One mebibyte is generous, and being generous here costs nothing while being tight costs a
    * runtime produce failure with a broker-side message nobody can read.
    */
  val FileOverheadBytes: Long = 1024L * 1024L

  /** Advisory settings shared by both topics.
    *
    * `retention.ms = -1` because `cleanup.policy=compact` is what keeps the data; `delete.retention.ms`
    * governs only how long a tombstone stays visible to a consumer that is catching up.
    */
  private val sharedAdvisory: Map[String, String] = Map(
    "retention.ms" -> "-1",
    "delete.retention.ms" -> "86400000",
    "min.compaction.lag.ms" -> "0",
    "segment.ms" -> "604800000",
    "min.cleanable.dirty.ratio" -> "0.1"
  )

  private def sharedRequired(minInSyncReplicas: Int): Map[String, String] = Map(
    "cleanup.policy" -> "compact",
    "min.insync.replicas" -> minInSyncReplicas.toString
  )

  def of(config: StoreConfig): StoreTopics = {
    val isr = config.minInSyncReplicas.value
    StoreTopics(
      config = StoreTopic(
        name = config.configTopic,
        partitions = 1,
        createdBy = CreatedBy.M1,
        required = sharedRequired(isr),
        advisory = sharedAdvisory
      ),
      files = StoreTopic(
        name = config.filesTopic,
        partitions = 1,
        createdBy = CreatedBy.M1,
        required =
          sharedRequired(isr) + ("max.message.bytes" -> (config.maxFileBytes + FileOverheadBytes).toString),
        advisory = sharedAdvisory
      ),
      audit = StoreTopic(
        name = config.auditTopic,
        partitions = 1,
        createdBy = CreatedBy.M5,
        required = Map("cleanup.policy" -> "delete", "min.insync.replicas" -> isr.toString),
        advisory = Map("retention.ms" -> "7776000000")
      )
    )
  }

  /** The literal setting name used when the *partition count* is what differs.
    *
    * Partitions are not a topic config in Kafka's sense — they come back from `describeTopics`, not from
    * `describeConfigs` — but an operator reading a failure message does not care about that distinction, and
    * one message shape for "this topic is not what KUI needs" is easier to act on than two.
    */
  val PartitionsSetting: String = "partitions"

  /** Compares one required setting as the bootstrap does.
    *
    * `max.message.bytes` is satisfied by *at least* the expected value, because an operator whose broker
    * allows larger messages than KUI needs has a topic that works. Everything else must match exactly.
    */
  def satisfies(setting: String, expected: String, found: String): Boolean =
    if setting == "max.message.bytes" then
      (found.toLongOption, expected.toLongOption) match {
        case (Some(actual), Some(minimum)) => actual >= minimum
        case _ => found == expected
      }
    else found == expected
}
