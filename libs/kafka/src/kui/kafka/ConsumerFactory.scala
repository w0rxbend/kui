package kui.kafka

import cats.effect.{Async, Resource}
import fs2.io.file.Files
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer}
import org.typelevel.log4cats.Logger

import kui.kafka.auth.{ClientPurpose, ConnectionProperties}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError

/** Turns a typed connection into fs2-kafka consumer settings.
  *
  * The security half comes from `ConnectionProperties` (KAFKA-003), the same function the admin client uses,
  * so a consumer and an admin client for the same cluster cannot end up authenticating differently — which is
  * the kind of divergence that produces a bug report saying "the broker list loads but the messages do not".
  *
  * The result is a `Resource` because the settings may name a materialized keystore, and those files exist
  * only for as long as the resource is open.
  */
object ConsumerFactory {

  /** Settings with KUI's defaults applied, before any caller-specific tuning.
    *
    * Five of those defaults are decisions rather than conveniences, and every one of them is a decision the
    * reference implementations also had to make (`research/kafka/admin-capabilities.md` §4):
    *
    *   - `enable.auto.commit=false`. KUI reads; it does not own anybody's consumer group offsets, and an
    *     auto-commit would silently move a group an operator is looking at.
    *   - `auto.offset.reset=none`. Every KUI consumer seeks deliberately — to an offset, a timestamp, or the
    *     end. Falling back to "earliest" on a bad seek would read a whole topic by accident.
    *   - `allow.auto.create.topics=false`. This one is not a tuning knob, it is a correctness bug waiting to
    *     happen. A broker left at Kafka's default `auto.create.topics.enable=true` creates a topic the moment
    *     a consumer asks for its metadata, so a user who mistyped a topic name in the message browser got
    *     "topic 'ordrs.v1' does not exist" *and* a brand new empty `ordrs.v1` on their cluster. A read-only
    *     tool must never write, least of all while telling the user it found nothing.
    *   - `group.id` only when one is given. A consumer without a group does not join a rebalance and cannot
    *     disturb one.
    *   - `Array[Byte]` on both sides. Deserialization is a separate concern with its own error handling (M3's
    *     serdes); a consumer that fails on a malformed record cannot show the operator the malformed record.
    */
  def settings[F[_]: {Async, Files}](
      connection: ClusterConnection,
      groupId: Option[String],
      log: Option[Logger[F]] = None
  ): Resource[F, Either[KuiError, ConsumerSettings[F, Array[Byte], Array[Byte]]]] =
    ConnectionProperties
      .resource[F](connection, ClientPurpose.Consumer, "", log)
      .map(_.map { properties =>
        val base = ConsumerSettings[F, Array[Byte], Array[Byte]](
          Deserializer.identity[F],
          Deserializer.identity[F]
        )
          .withProperties(properties.unsafeValues)
          .withEnableAutoCommit(false)
          .withAutoOffsetReset(AutoOffsetReset.None)
          .withProperty(AllowAutoCreateTopicsKey, "false")

        groupId.fold(base)(base.withGroupId)
      })

  /** `allow.auto.create.topics`, the consumer-side switch that stops a metadata request from creating a
    * topic. Named here rather than written as a literal so the suite that pins it and the setting itself
    * cannot drift apart.
    */
  val AllowAutoCreateTopicsKey: String = "allow.auto.create.topics"
}
