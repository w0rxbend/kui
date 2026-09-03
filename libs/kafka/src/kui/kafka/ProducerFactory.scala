package kui.kafka

import cats.effect.{Async, Resource}
import fs2.io.file.Files
import fs2.kafka.{ProducerSettings, Serializer}
import org.typelevel.log4cats.Logger

import kui.kafka.auth.{ClientPurpose, ConnectionProperties}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError

/** Turns a typed connection into fs2-kafka producer settings.
  *
  * KUI produces in exactly two places: the metadata store's writes (ADR-042) and, from M3, a message an
  * operator sends by hand. Both want the same two guarantees, and neither wants to argue about them at a call
  * site.
  */
object ProducerFactory {

  /** `acks=all` and `enable.idempotence=true`.
    *
    * `acks=all` means a write is acknowledged only once every in-sync replica has it, which is the difference
    * between "the store said it saved my cluster" and "the store said it saved my cluster and then the leader
    * died". `enable.idempotence=true` means a retry inside the producer cannot write the record twice — and a
    * duplicated record on a compacted configuration topic is an optimistic-version conflict that never really
    * happened.
    *
    * Both are Kafka's own defaults in recent releases. They are set explicitly anyway, because a default is a
    * thing that changes and these two are load-bearing.
    */
  def settings[F[_]: {Async, Files}](
      connection: ClusterConnection,
      log: Option[Logger[F]] = None
  ): Resource[F, Either[KuiError, ProducerSettings[F, Array[Byte], Array[Byte]]]] =
    ConnectionProperties
      .resource[F](connection, ClientPurpose.Producer, "", log)
      .map(
        _.map(properties =>
          ProducerSettings[F, Array[Byte], Array[Byte]](
            Serializer.identity[F],
            Serializer.identity[F]
          )
            .withProperties(properties.unsafeValues)
            .withAcks(fs2.kafka.Acks.All)
            .withEnableIdempotence(true)
        )
      )
}
