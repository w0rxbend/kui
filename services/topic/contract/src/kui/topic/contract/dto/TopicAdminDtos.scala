package kui.topic.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{ClusterId, TopicName}

// The wire shapes of topic administration (M5): create, configure, grow, delete.
//
// Two request shapes, and the destructive ones look deliberately strange. `CreateTopicRequest` and
// `UpdateTopicConfigRequest` carry what the operator typed. `ConfirmRequest` carries *only a token*:
// ADR-045 says a destructive operation is confirmed against a server-computed plan, so the apply
// endpoints have no other input to accept. There is no field anywhere in this file by which a client
// could ask to delete a topic it was never shown a plan for, or to grow a topic to a count the server
// did not compute. That is the protection, and it applies to `curl` exactly as it applies to the
// browser.

/** A topic to create.
  *
  * `partitions` and `replicationFactor` are optional and an absent one is not a default this contract
  * invents: it means "use the broker's". The broker's `num.partitions` and `default.replication.factor` are
  * frequently set deliberately — three replicas on a three-broker cluster — and a client that substituted `1`
  * would create single-replica topics on a cluster whose operator had configured otherwise. The response
  * reports what the broker actually made.
  *
  * `config` is dynamic topic configuration exactly as Kafka spells it: `retention.ms`, `cleanup.policy`,
  * `min.insync.replicas`. The keys are not checked against a list held in KUI, because Kafka's set changes
  * with every release and a list here would go stale and start refusing settings the broker accepts. An
  * unknown key is refused by the broker, and that refusal is reported as it stands.
  */
final case class CreateTopicRequest(
    name: TopicName,
    partitions: Option[Int],
    replicationFactor: Option[Int],
    config: Map[String, String]
)

object CreateTopicRequest {

  given Codec[CreateTopicRequest] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[TopicName]("name")
        partitions <- cursor.get[Option[Int]]("partitions")
        replicationFactor <- cursor.get[Option[Int]]("replicationFactor")
        config <- cursor.getOrElse[Map[String, String]]("config")(Map.empty)
      } yield CreateTopicRequest(name, partitions, replicationFactor, config),
    (request: CreateTopicRequest) =>
      Json.obj(
        "name" -> request.name.asJson,
        "partitions" -> request.partitions.asJson,
        "replicationFactor" -> request.replicationFactor.asJson,
        "config" -> request.config.asJson
      )
  )

  given Schema[CreateTopicRequest] = Schema
    .derived[CreateTopicRequest]
    .description(
      "A topic to create. An absent partitions or replicationFactor means the broker's own default"
    )

  given CanEqual[CreateTopicRequest, CreateTopicRequest] = CanEqual.derived
}

/** What the cluster made, read back after the create rather than echoed from the request.
  *
  * That is the whole reason this response has fields at all. A topic created with the broker's defaults is
  * the common case, and the operator has no other way to learn what those defaults turned out to be. `None`
  * on either number means the create succeeded and the read-back that follows it did not — reported honestly
  * rather than as the numbers that were asked for.
  */
final case class CreatedTopicDto(
    cluster: ClusterId,
    name: TopicName,
    partitions: Option[Int],
    replicationFactor: Option[Int]
)

object CreatedTopicDto {

  given Codec[CreatedTopicDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[ClusterId]("cluster")
        name <- cursor.get[TopicName]("name")
        partitions <- cursor.get[Option[Int]]("partitions")
        replicationFactor <- cursor.get[Option[Int]]("replicationFactor")
      } yield CreatedTopicDto(cluster, name, partitions, replicationFactor),
    (dto: CreatedTopicDto) =>
      Json.obj(
        "cluster" -> dto.cluster.asJson,
        "name" -> dto.name.asJson,
        "partitions" -> dto.partitions.asJson,
        "replicationFactor" -> dto.replicationFactor.asJson
      )
  )

  given Schema[CreatedTopicDto] =
    Schema.derived[CreatedTopicDto].description("The topic as the cluster reports it after the create")

  given CanEqual[CreatedTopicDto, CreatedTopicDto] = CanEqual.derived
}

/** A change to a topic's dynamic configuration.
  *
  * Two fields, not one map, because "set this key" and "put this key back to the broker default" are
  * different operations on Kafka's side and one map cannot express the second: an empty string is a legal
  * value for several settings, so mapping `""` to "remove it" would make a real value unreachable.
  *
  * Keys not named in either field are left exactly as they are. The reference product's endpoint replaces the
  * whole dynamic set instead, which silently reverts every override a client did not resend.
  */
final case class UpdateTopicConfigRequest(set: Map[String, String], remove: List[String])

object UpdateTopicConfigRequest {

  given Codec[UpdateTopicConfigRequest] = Codec.from(
    (cursor: HCursor) =>
      for {
        set <- cursor.getOrElse[Map[String, String]]("set")(Map.empty)
        remove <- cursor.getOrElse[List[String]]("remove")(Nil)
      } yield UpdateTopicConfigRequest(set, remove),
    (request: UpdateTopicConfigRequest) =>
      Json.obj("set" -> request.set.asJson, "remove" -> request.remove.asJson)
  )

  given Schema[UpdateTopicConfigRequest] = Schema
    .derived[UpdateTopicConfigRequest]
    .description("Keys to set, and keys to reset to the broker default. Anything else is left alone")

  given CanEqual[UpdateTopicConfigRequest, UpdateTopicConfigRequest] = CanEqual.derived
}

/** How many partitions the topic should end up with. */
final case class PartitionIncreaseRequest(partitions: Int)

object PartitionIncreaseRequest {

  given Codec[PartitionIncreaseRequest] = Codec.from(
    (cursor: HCursor) => cursor.get[Int]("partitions").map(PartitionIncreaseRequest(_)),
    (request: PartitionIncreaseRequest) => Json.obj("partitions" -> request.partitions.asJson)
  )

  given Schema[PartitionIncreaseRequest] = Schema
    .derived[PartitionIncreaseRequest]
    .description("The partition count the topic should end up with. It must be greater than it has now")

  given CanEqual[PartitionIncreaseRequest, PartitionIncreaseRequest] = CanEqual.derived
}

/** One sentence an operator should read before confirming.
  *
  * The `code` is stable and machine-readable so a screen can decide how loudly to render it — the one about
  * key routing is the reason a partition increase is classified destructive — and the `message` is the
  * server's own sentence, complete, so an API user is warned about exactly what a browser user is.
  */
final case class PlanWarningDto(code: String, message: String)

object PlanWarningDto {

  given Codec[PlanWarningDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        code <- cursor.get[String]("code")
        message <- cursor.get[String]("message")
      } yield PlanWarningDto(code, message),
    (dto: PlanWarningDto) => Json.obj("code" -> dto.code.asJson, "message" -> dto.message.asJson)
  )

  given Schema[PlanWarningDto] =
    Schema.derived[PlanWarningDto].description("Something about this change the operator should read first")

  given CanEqual[PlanWarningDto, PlanWarningDto] = CanEqual.derived
}

/** What raising a topic's partition count would do, resolved against the cluster as it is now.
  *
  * @param current
  *   how many partitions the topic has, read from the broker at plan time. It is what makes this document
  *   more than an echo: an operator who believes a topic has three partitions and asks for six is shown that
  *   it already has six
  * @param token
  *   the confirmation the apply endpoint accepts, valid for five minutes. Absent on the document the apply
  *   endpoint answers with, because by then it has been spent — that document is a receipt, not an offer
  */
final case class PartitionPlanDto(
    topic: TopicName,
    current: Int,
    target: Int,
    warnings: List[PlanWarningDto],
    token: Option[String],
    expiresAt: Option[Instant],
    computedAt: Instant
) {

  /** How many partitions the change adds. Derived, so it cannot disagree with the two counts. */
  def added: Int = target - current
}

object PartitionPlanDto {

  given Codec[PartitionPlanDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[TopicName]("topic")
        current <- cursor.get[Int]("current")
        target <- cursor.get[Int]("target")
        warnings <- cursor.getOrElse[List[PlanWarningDto]]("warnings")(Nil)
        token <- cursor.get[Option[String]]("token")
        expiresAt <- cursor.get[Option[Instant]]("expiresAt")
        computedAt <- cursor.get[Instant]("computedAt")
      } yield PartitionPlanDto(topic, current, target, warnings, token, expiresAt, computedAt),
    (dto: PartitionPlanDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "current" -> dto.current.asJson,
        "target" -> dto.target.asJson,
        // Written as well as derived: a client that renders the document without re-deriving it —
        // a script, a log line — should not have to do the arithmetic to read the plan.
        "added" -> dto.added.asJson,
        "warnings" -> dto.warnings.asJson,
        "token" -> dto.token.asJson,
        "expiresAt" -> dto.expiresAt.asJson,
        "computedAt" -> dto.computedAt.asJson
      )
  )

  given Schema[PartitionPlanDto] = Schema
    .derived[PartitionPlanDto]
    .description("What raising this topic's partition count would do, and the token that confirms it")

  given CanEqual[PartitionPlanDto, PartitionPlanDto] = CanEqual.derived
}

/** What deleting a topic would destroy, and what would happen afterwards.
  *
  * @param records
  *   how many records the topic holds, when every partition answered. `null` means at least one partition
  *   could not be counted — never a sum over the partitions that did, because a number smaller than the truth
  *   shown to somebody deciding whether to delete is worse than no number
  * @param autoCreateEnabled
  *   whether the cluster's `auto.create.topics.enable` will recreate the topic the moment anything names it.
  *   `null` means KUI could not read the broker setting, which is a third answer and not a `false`
  */
final case class DeletionPlanDto(
    topic: TopicName,
    partitions: Int,
    records: Option[Long],
    autoCreateEnabled: Option[Boolean],
    warnings: List[PlanWarningDto],
    token: Option[String],
    expiresAt: Option[Instant],
    computedAt: Instant
)

object DeletionPlanDto {

  given Codec[DeletionPlanDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[TopicName]("topic")
        partitions <- cursor.get[Int]("partitions")
        records <- cursor.get[Option[Long]]("records")
        autoCreateEnabled <- cursor.get[Option[Boolean]]("autoCreateEnabled")
        warnings <- cursor.getOrElse[List[PlanWarningDto]]("warnings")(Nil)
        token <- cursor.get[Option[String]]("token")
        expiresAt <- cursor.get[Option[Instant]]("expiresAt")
        computedAt <- cursor.get[Instant]("computedAt")
      } yield DeletionPlanDto(
        topic,
        partitions,
        records,
        autoCreateEnabled,
        warnings,
        token,
        expiresAt,
        computedAt
      ),
    (dto: DeletionPlanDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "partitions" -> dto.partitions.asJson,
        "records" -> dto.records.asJson,
        "autoCreateEnabled" -> dto.autoCreateEnabled.asJson,
        "warnings" -> dto.warnings.asJson,
        "token" -> dto.token.asJson,
        "expiresAt" -> dto.expiresAt.asJson,
        "computedAt" -> dto.computedAt.asJson
      )
  )

  given Schema[DeletionPlanDto] = Schema
    .derived[DeletionPlanDto]
    .description("What deleting this topic would destroy, and the token that confirms it")

  given CanEqual[DeletionPlanDto, DeletionPlanDto] = CanEqual.derived
}

/** The only input an apply endpoint takes (ADR-045).
  *
  * It carries no topic name, no partition count and no flags, because everything the operation will do was
  * fixed when the plan was computed and signed. A request that could carry those again is a request in which
  * they could differ from what the operator was shown, which is the substitution the two-phase flow exists to
  * make impossible.
  */
final case class ConfirmRequest(token: String)

object ConfirmRequest {

  given Codec[ConfirmRequest] = Codec.from(
    (cursor: HCursor) => cursor.get[String]("token").map(ConfirmRequest(_)),
    (request: ConfirmRequest) => Json.obj("token" -> request.token.asJson)
  )

  given Schema[ConfirmRequest] = Schema
    .derived[ConfirmRequest]
    .description("The plan token to apply. Nothing else: the plan already fixed what will happen")

  given CanEqual[ConfirmRequest, ConfirmRequest] = CanEqual.derived
}
