package kui.message.contract

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{Offset, PartitionId, TopicName}

// The wire shapes of the purge plan (`MS-008`, ADR-045). `PurgeResultDto` and its parts already live in
// `ProduceDtos` beside the other things a mutation answers with; what is here is the *offer* — what a purge
// would destroy, and the token that authorises destroying exactly that.

/** One partition of a purge plan: where its log starts now, where it ends, and how much is about to go.
  *
  * `records` is written as well as derived so a client rendering the document — a script, a log line — does
  * not have to do the arithmetic, and it is `high - low` on both sides so the two can never disagree.
  */
final case class PurgePartitionPlanDto(
    partition: PartitionId,
    lowWatermark: Offset,
    highWatermark: Offset
) {
  def records: Long = highWatermark.value - lowWatermark.value
}

object PurgePartitionPlanDto {

  given Codec[PurgePartitionPlanDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        partition <- cursor.get[PartitionId]("partition")
        lowWatermark <- cursor.get[Offset]("lowWatermark")
        highWatermark <- cursor.get[Offset]("highWatermark")
      } yield PurgePartitionPlanDto(partition, lowWatermark, highWatermark),
    (dto: PurgePartitionPlanDto) =>
      Json.obj(
        "partition" -> dto.partition.asJson,
        "lowWatermark" -> dto.lowWatermark.asJson,
        "highWatermark" -> dto.highWatermark.asJson,
        "records" -> dto.records.asJson
      )
  )

  given Schema[PurgePartitionPlanDto] = Schema
    .derived[PurgePartitionPlanDto]
    .description("One partition's current offset window, and how many records a purge would delete from it")

  given CanEqual[PurgePartitionPlanDto, PurgePartitionPlanDto] = CanEqual.derived
}

/** One sentence an operator should read before confirming a purge.
  *
  * The same shape as the topic service's `PlanWarningDto` and deliberately a separate type: neither service's
  * contract may depend on the other's, and a shared one would have to live in a library where neither
  * service's vocabulary belongs. The `code` is stable so a screen can decide how loudly to render it; the
  * `message` is the server's own sentence, complete, so an API user is warned about exactly what a browser
  * user is.
  */
final case class PurgeWarningDto(code: String, message: String)

object PurgeWarningDto {

  given Codec[PurgeWarningDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        code <- cursor.get[String]("code")
        message <- cursor.get[String]("message")
      } yield PurgeWarningDto(code, message),
    (dto: PurgeWarningDto) => Json.obj("code" -> dto.code.asJson, "message" -> dto.message.asJson)
  )

  given Schema[PurgeWarningDto] =
    Schema.derived[PurgeWarningDto].description("Something about this purge the operator should read first")

  given CanEqual[PurgeWarningDto, PurgeWarningDto] = CanEqual.derived
}

/** What emptying a topic would destroy, resolved against the cluster as it is now.
  *
  * @param token
  *   the confirmation the purge endpoint accepts, valid for five minutes. Absent on the document the purge
  *   answers with, because by then it has been spent: that document is a receipt, not an offer
  * @param records
  *   every record the purge destroys, summed over the partitions. After the purge this number cannot be read
  *   off the cluster at all — the log's start offset *is* its end offset — so this document and the audit
  *   record are the only places it survives
  */
final case class PurgePlanDto(
    topic: TopicName,
    partitions: List[PurgePartitionPlanDto],
    warnings: List[PurgeWarningDto],
    token: Option[String],
    expiresAt: Option[Instant],
    computedAt: Instant
) {
  def records: Long = partitions.map(_.records).sum

  /** True when there is nothing to delete. A screen says so and offers no confirmation: a confirmation
    * dialogue for an operation that changes nothing teaches operators to click through confirmations.
    */
  def isNoOp: Boolean = records <= 0L
}

object PurgePlanDto {

  given Codec[PurgePlanDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[TopicName]("topic")
        partitions <- cursor.getOrElse[List[PurgePartitionPlanDto]]("partitions")(Nil)
        warnings <- cursor.getOrElse[List[PurgeWarningDto]]("warnings")(Nil)
        token <- cursor.get[Option[String]]("token")
        expiresAt <- cursor.get[Option[Instant]]("expiresAt")
        computedAt <- cursor.get[Instant]("computedAt")
      } yield PurgePlanDto(topic, partitions, warnings, token, expiresAt, computedAt),
    (dto: PurgePlanDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "partitions" -> dto.partitions.asJson,
        "records" -> dto.records.asJson,
        "warnings" -> dto.warnings.asJson,
        "token" -> dto.token.asJson,
        "expiresAt" -> dto.expiresAt.asJson,
        "computedAt" -> dto.computedAt.asJson
      )
  )

  given Schema[PurgePlanDto] = Schema
    .derived[PurgePlanDto]
    .description("What emptying this topic would destroy, and the token that confirms it")

  given CanEqual[PurgePlanDto, PurgePlanDto] = CanEqual.derived
}

/** The only input the purge endpoint takes (ADR-045).
  *
  * No topic, no partitions, no offsets: everything the operation will do was fixed when the plan was computed
  * and signed. A request that could carry those again is a request in which they could differ from what the
  * operator was shown — and on this operation the difference would be records that arrived after they read
  * the figure, deleted without their ever having seen them.
  */
final case class PurgeConfirmRequest(token: String)

object PurgeConfirmRequest {

  given Codec[PurgeConfirmRequest] = Codec.from(
    (cursor: HCursor) => cursor.get[String]("token").map(PurgeConfirmRequest(_)),
    (request: PurgeConfirmRequest) => Json.obj("token" -> request.token.asJson)
  )

  given Schema[PurgeConfirmRequest] = Schema
    .derived[PurgeConfirmRequest]
    .description("The plan token to apply. Nothing else: the plan already fixed what will be deleted")

  given CanEqual[PurgeConfirmRequest, PurgeConfirmRequest] = CanEqual.derived
}

/** What a purge did: the plan that was applied, and what the broker reported per partition.
  *
  * Both halves, because they answer different questions. The plan says what the operator agreed to lose,
  * which after the purge cannot be recovered from the cluster; the result says what the broker actually did,
  * partition by partition, including the ones it refused.
  */
final case class PurgeReceiptDto(plan: PurgePlanDto, result: PurgeResultDto)

object PurgeReceiptDto {

  given Codec[PurgeReceiptDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        plan <- cursor.get[PurgePlanDto]("plan")
        result <- cursor.get[PurgeResultDto]("result")
      } yield PurgeReceiptDto(plan, result),
    (dto: PurgeReceiptDto) => Json.obj("plan" -> dto.plan.asJson, "result" -> dto.result.asJson)
  )

  given Schema[PurgeReceiptDto] =
    Schema.derived[PurgeReceiptDto].description("What a purge was agreed to delete, and what the broker did")

  given CanEqual[PurgeReceiptDto, PurgeReceiptDto] = CanEqual.derived
}
