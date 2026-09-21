package kui.config.store

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}

import kui.config.StoreConfig
import kui.kernel.error.KuiError

/** Builds the configured metadata store with the bootstrap ordering every writable consumer requires.
  *
  * A Kafka-backed service must create or validate the compacted topics before replaying them. Keeping that
  * sequence here prevents the cluster, alerts and future metadata owners from growing subtly different
  * startup behavior or encryption handling.
  */
object ConfigStoreResource {

  def resource[F[_]: {Async, Parallel, Files, LoggerFactory}](
      config: StoreConfig,
      clientId: String,
      logger: StructuredLogger[F]
  ): Resource[F, ConfigStore[F]] =
    config.kafka match {
      case Some(kafka) =>
        for {
          keyring <- Resource.eval(keyringOf[F](config))
          _ <- StoreClients
            .admin[F](kafka, s"$clientId-bootstrap")
            .evalMap(admin =>
              StoreBootstrap
                .ensureTopics[F](
                  admin,
                  StoreTopics.of(config),
                  config.replicationFactor,
                  kafka.bootstrapServers.value
                )
                .flatMap {
                  case Right(()) => Async[F].unit
                  case Left(error) => Async[F].raiseError[Unit](asThrowable(error))
                }
            )
          store <- KafkaConfigStore.resource[F](config, kafka, FieldCrypto[F](keyring), clientId)
          _ <- Resource.eval(
            logger.info(s"metadata store: client=$clientId replay complete, following the log")
          )
        } yield store

      case None =>
        config.dir match {
          case Some(dir) => FileConfigStore.resource[F](dir)
          case None =>
            Resource.eval(
              logger
                .info("no metadata store is configured; using read-only empty metadata")
                .as(ConfigStore.empty[F])
            )
        }
    }

  /** A Kafka store cannot safely read or write secret-bearing records without a keyring. */
  private def keyringOf[F[_]: Async](config: StoreConfig): F[EncryptionKeyring] =
    config.encryption match {
      case None =>
        Async[F].raiseError(
          new IllegalStateException(
            "kui.store.kafka.* is configured but kui.store.encryption is not; stored secrets cannot be " +
              "read or written without a key"
          )
        )
      case Some(encryption) =>
        encryption.keys.toList
          .traverse((id, material) => EncryptionKey.fromBase64(id, material.value))
          .flatMap(EncryptionKeyring.of(_, encryption.activeKeyId))
          .leftMap(asThrowable)
          .liftTo[F]
    }

  private def asThrowable(error: StoreError): Throwable = {
    val kui: KuiError = StoreError.toKuiError(error)
    new IllegalStateException(s"${kui.code.wire}: ${kui.message}")
  }
}
