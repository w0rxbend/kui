package kui.kafka.admin

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.effect.Async
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{DescribeClusterOptions, DescribeConfigsOptions, DescribeLogDirsOptions}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.{
  ClusterAuthorizationException,
  InvalidRequestException,
  UnknownTopicOrPartitionException,
  UnsupportedVersionException
}
import org.typelevel.log4cats.Logger

import kui.kafka.*
import kui.kernel.BrokerId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError

/** `ClusterAdmin` over the raw `Admin` client, through `AdminClientPool`.
  *
  * The plumbing lives here and the rules live in `AdminConversions`, which is where the `null`s that mean
  * "electing", the `-1`s that mean "not reported" and the sensitive values that must stay absent are handled
  * — and where they are tested.
  */
object KafkaClusterAdmin {

  /** The `inter.broker.protocol.version` broker setting, the fallback version source. */
  private val InterBrokerProtocolVersion: String = "inter.broker.protocol.version"

  /** The finalized feature that carries a KRaft cluster's metadata level. */
  private val MetadataVersionFeature: String = "metadata.version"

  private val DeleteTopicEnable: String = "delete.topic.enable"

  def apply[F[_]: Async](
      pool: AdminClientPool[F],
      log: Option[Logger[F]] = None
  ): ClusterAdmin[F] = new Impl[F](pool, log)

  final private class Impl[F[_]: Async](pool: AdminClientPool[F], log: Option[Logger[F]])
      extends ClusterAdmin[F] {

    // ---------------------------------------------------------------- describeCluster

    def describeCluster(
        connection: ClusterConnection
    ): F[Either[KuiError, ClusterDescription]] =
      describeClusterWith(connection, includeAuthorizedOperations = true)
        .recoverWith {
          // A broker older than 2.3 does not know the option. This is the one retry in the whole
          // module, and it is a capability downgrade rather than a failure retry: the second call
          // asks a smaller question, not the same question again.
          case failure if isUnsupported(failure) =>
            logged(_.debug(s"cluster ${connection.id.value} does not support authorized operations")) >>
              describeClusterWith(connection, includeAuthorizedOperations = false)
        }
        .attemptMapped("describeCluster", connection)

    private def describeClusterWith(
        connection: ClusterConnection,
        includeAuthorizedOperations: Boolean
    ): F[ClusterDescription] =
      pool.run(connection, "describeCluster") { admin =>
        val result = admin.describeCluster(
          new DescribeClusterOptions().includeAuthorizedOperations(includeAuthorizedOperations)
        )

        for {
          nodes <- KafkaFutures.fromFuture[F, java.util.Collection[org.apache.kafka.common.Node]](
            Async[F].delay(result.nodes)
          )
          controller <- KafkaFutures.fromNullableFuture(Async[F].delay(result.controller))
          clusterId <- KafkaFutures.fromNullableFuture(Async[F].delay(result.clusterId))
          operations <-
            if includeAuthorizedOperations then
              KafkaFutures.fromNullableFuture(Async[F].delay(result.authorizedOperations))
            else Async[F].pure(None)
        } yield AdminConversions.clusterDescription(
          clusterId.orNull,
          controller.orNull,
          nodes,
          operations.orNull
        )
      }

    // ---------------------------------------------------------------- version

    def version(connection: ClusterConnection): F[Either[KuiError, BrokerVersion]] =
      fromFeatures(connection).flatMap {
        case Left(failure) if isReconnect(failure) =>
          Async[F].pure(Left(KafkaErrorMapper.map("describeFeatures", failure, apiTimeout(connection))))
        case Left(_) => fromBrokerConfig(connection)
        case Right(detected) if detected.version.isDefined => announce(connection, detected).map(Right(_))
        // The level resolved to nothing — KUI's table is older than the cluster. Falling through to
        // the config is strictly better than reporting no version at all, and the WARN says why.
        case Right(unresolved) =>
          logged(
            _.warn(
              s"cluster ${connection.id.value} reports metadata.version " +
                s"${unresolved.raw.getOrElse("?")}, which is newer than this build of KUI knows " +
                s"about (highest known level ${MetadataVersions.highestKnownLevel})"
            )
          ) >> fromBrokerConfig(connection)
      }

    private def fromFeatures(connection: ClusterConnection): F[Either[Throwable, BrokerVersion]] =
      pool
        .run(connection, "describeFeatures") { admin =>
          KafkaFutures
            .fromFuture(Async[F].delay(admin.describeFeatures().featureMetadata()))
            .map { metadata =>
              Option(metadata.finalizedFeatures.get(MetadataVersionFeature)) match {
                case Some(range) =>
                  BrokerVersion(
                    MetadataVersions.release(range.maxVersionLevel),
                    Some(s"level ${range.maxVersionLevel}"),
                    VersionSource.Features
                  )
                case None => BrokerVersion(None, None, VersionSource.Unknown)
              }
            }
        }
        .attempt

    /** The fallback: read `inter.broker.protocol.version` from the controller, or from the lowest-numbered
      * node when the cluster has no controller right now.
      */
    private def fromBrokerConfig(
        connection: ClusterConnection
    ): F[Either[KuiError, BrokerVersion]] =
      describeCluster(connection).flatMap {
        case Left(error) => Async[F].pure(Left(error))
        case Right(description) =>
          description.controller.orElse(description.nodes.minByOption(_.id.value)) match {
            case None => Async[F].pure(Right(BrokerVersion(None, None, VersionSource.Unknown)))
            case Some(node) =>
              brokerConfigs(connection, node.id, includeDocs = false).flatMap {
                case Left(error) => Async[F].pure(Left(error))
                case Right(entries) =>
                  val raw = entries.find(_.name == InterBrokerProtocolVersion).flatMap(_.value)

                  val detected = raw match {
                    case Some(value) =>
                      BrokerVersion(
                        KafkaVersion.parse(value),
                        Some(value),
                        VersionSource.InterBrokerProtocol
                      )
                    // A managed service that reveals no version is not a broken cluster. This is a
                    // success, and CL-009's cell renders an em dash.
                    case None => BrokerVersion(None, None, VersionSource.Unknown)
                  }

                  announce(connection, detected).map(Right(_))
              }
          }
      }

    /** The line an operator quotes in a bug report, plus ADR-030's below-minimum warning. */
    private def announce(connection: ClusterConnection, detected: BrokerVersion): F[BrokerVersion] =
      logged(
        _.info(
          s"cluster ${connection.id.value} reports Kafka " +
            s"${detected.version.fold("an undetectable version")(_.render)} " +
            s"(source ${detected.source}, raw ${detected.raw.getOrElse("none")})"
        )
      ) >> {
        if detected.meetsMinimum.contains(false) then
          logged(
            _.warn(
              s"cluster ${connection.id.value} reports Kafka " +
                s"${detected.version.fold("?")(_.render)}, below the supported minimum " +
                s"${KafkaVersion.minimumSupported.render} (ADR-030); some features will be " +
                "unavailable"
            )
          )
        else Async[F].unit
      }.as(detected)

    // ---------------------------------------------------------------- brokerConfigs

    def brokerConfigs(
        connection: ClusterConnection,
        broker: BrokerId,
        includeDocs: Boolean
    ): F[Either[KuiError, List[ConfigEntry]]] = {
      val resource = new ConfigResource(ConfigResource.Type.BROKER, broker.value.toString)

      pool
        .run(connection, "describeConfigs") { admin =>
          val options = new DescribeConfigsOptions()
            .includeSynonyms(true)
            .includeDocumentation(includeDocs)

          KafkaFutures
            .fromFuture(Async[F].delay(admin.describeConfigs(List(resource).asJava, options).all()))
            .map(configs =>
              Option(configs.get(resource)).fold(List.empty[ConfigEntry])(AdminConversions.config)
            )
        }
        .attempt
        .flatMap {
          case Right(entries) => Async[F].pure(Right(entries))
          // The three managed-service downgrades. MSK Serverless answers `InvalidRequestException`,
          // Azure Event Hubs answers `UnknownTopicOrPartitionException`, and a cluster where KUI
          // lacks DESCRIBE_CONFIGS answers `ClusterAuthorizationException`. All three mean "this
          // cluster does not offer broker configuration to you", which is an empty list and a true
          // statement — not an error, and at DEBUG rather than WARN because on a managed service
          // this is the permanent steady state and a WARN every thirty seconds is not a signal.
          case Left(failure) if isConfigDowngrade(failure) =>
            logged(
              _.debug(
                s"cluster ${connection.id.value} does not expose broker configuration " +
                  s"(${KafkaFutures.unwrap(failure).getClass.getSimpleName})"
              )
            ).as(Right(Nil))
          case Left(failure) =>
            Async[F].pure(
              Left(KafkaErrorMapper.map("describeConfigs", failure, apiTimeout(connection)))
            )
        }
    }

    // ---------------------------------------------------------------- describeLogDirs

    def describeLogDirs(
        connection: ClusterConnection,
        brokers: Set[BrokerId]
    ): F[Either[KuiError, BatchResult[BrokerId, List[LogDir]]]] =
      if brokers.isEmpty then Async[F].pure(Right(BatchResult.empty[BrokerId, List[LogDir]]))
      else
        AdminBatch
          .perBroker[F, BrokerId, List[LogDir]](
            brokers.toList.sortBy(_.value),
            connection.admin.parallelism,
            "describeLogDirs"
          )(broker => logDirsOf(connection, broker))
          .map(result => Right(result))
          .recover {
            // The whole call is unavailable — a broker before 1.0, or a managed service that hides
            // log directories. Every broker is skipped with a stated reason, so the page says "not
            // available on this cluster" per row, which is true, rather than showing an error.
            case failure if isUnsupported(failure) =>
              Right(
                BatchResult
                  .allSkipped[BrokerId, List[LogDir]](brokers, SkipReason.Unsupported("logDirs"))
              )
          }

    /** One call per broker.
      *
      * A single call covering every broker is stalled by one slow disk, and the timeout then loses every
      * broker's data (`research/kafka/admin-capabilities.md` §1, "Log dirs"). `descriptions()` rather than
      * `allDescriptions()`, for the same reason one layer down.
      */
    private def logDirsOf(connection: ClusterConnection, broker: BrokerId): F[List[LogDir]] =
      pool.run(connection, "describeLogDirs") { admin =>
        val result =
          admin.describeLogDirs(List(Integer.valueOf(broker.value)).asJava, new DescribeLogDirsOptions())

        Option(result.descriptions.get(Integer.valueOf(broker.value))) match {
          case Some(future) =>
            KafkaFutures.fromFuture(Async[F].delay(future)).map(AdminConversions.logDirs)
          case None => Async[F].pure(List.empty[LogDir])
        }
      }

    // ---------------------------------------------------------------- describeQuorum

    def describeQuorum(connection: ClusterConnection): F[Either[KuiError, Option[QuorumInfo]]] =
      pool
        .run(connection, "describeMetadataQuorum") { admin =>
          KafkaFutures
            .fromFuture(Async[F].delay(admin.describeMetadataQuorum().quorumInfo()))
            .map(quorum =>
              Option(
                QuorumInfo(
                  leaderId = BrokerId.unsafe(quorum.leaderId),
                  leaderEpoch = quorum.leaderEpoch,
                  highWatermark = quorum.highWatermark,
                  voters = quorum.voters.asScala.toList.map(voter),
                  observers = quorum.observers.asScala.toList.map(voter)
                )
              )
            )
        }
        .attempt
        .flatMap {
          case Right(quorum) => Async[F].pure(Right(quorum))
          // A ZooKeeper cluster, or a broker before 3.3: absence is the answer, not a failure.
          case Left(failure) if isUnsupported(failure) => Async[F].pure(Right(None))
          // KUI may not ask, so KUI does not know. Dimming a page for a missing ACL on an optional
          // panel is worse than an empty panel.
          case Left(failure) if isClusterAuthorization(failure) =>
            logged(
              _.debug(s"cluster ${connection.id.value} did not allow describeMetadataQuorum")
            ).as(Right(None))
          case Left(failure) =>
            Async[F].pure(
              Left(KafkaErrorMapper.map("describeMetadataQuorum", failure, apiTimeout(connection)))
            )
        }

    private def voter(raw: org.apache.kafka.clients.admin.QuorumInfo.ReplicaState): QuorumVoter =
      QuorumVoter(
        replicaId = BrokerId.unsafe(raw.replicaId),
        logEndOffset = raw.logEndOffset,
        // `-1` is the sentinel for "never fetched", and it renders as a date in 1970.
        lastFetchTimestamp = raw.lastFetchTimestamp.toScala.filter(_ >= 0L),
        lastCaughtUpTimestamp = raw.lastCaughtUpTimestamp.toScala.filter(_ >= 0L)
      )

    // ---------------------------------------------------------------- capabilities

    def capabilities(connection: ClusterConnection): F[ClusterFeatures] =
      for {
        detected <- version(connection).map(_.getOrElse(BrokerVersion(None, None, VersionSource.Unknown)))
        described <- describeCluster(connection)
        deleteEnabled <- topicDeletionSetting(connection, described.toOption)
        features <- CapabilityProbe.probe[F](
          pool,
          connection,
          detected,
          described.toOption,
          deleteEnabled
        )
        _ <- logged(
          _.info(
            s"cluster ${connection.id.value} supports [${render(features.present)}]; " +
              s"not supported [${render(features.absent)}]; undetermined [${render(features.unknown)}]"
          )
        )
      } yield features

    /** `delete.topic.enable`, read from the controller's configuration.
      *
      * `None` when the configuration could not be read at all, which is the managed-service downgrade and
      * means "we cannot tell" rather than "deletion is off".
      */
    private def topicDeletionSetting(
        connection: ClusterConnection,
        description: Option[ClusterDescription]
    ): F[Option[Boolean]] =
      description.flatMap(d => d.controller.orElse(d.nodes.minByOption(_.id.value))) match {
        case None => Async[F].pure(None)
        case Some(node) =>
          brokerConfigs(connection, node.id, includeDocs = false).map {
            case Right(entries) =>
              entries.find(_.name == DeleteTopicEnable).flatMap(_.value).map(_ == "true")
            case Left(_) => None
          }
      }

    private def render(features: Set[ClusterFeature]): String =
      features.toList.map(_.toString).sorted.mkString(", ")

    // ---------------------------------------------------------------- helpers

    private def apiTimeout(connection: ClusterConnection): Long =
      connection.admin.apiTimeout.toMillis

    private def isUnsupported(failure: Throwable): Boolean =
      KafkaFutures.unwrap(failure) match {
        case _: UnsupportedVersionException => true
        case _ => false
      }

    private def isClusterAuthorization(failure: Throwable): Boolean =
      KafkaFutures.unwrap(failure) match {
        case _: ClusterAuthorizationException => true
        case _ => false
      }

    private def isConfigDowngrade(failure: Throwable): Boolean =
      KafkaFutures.unwrap(failure) match {
        case _: InvalidRequestException => true
        case _: UnknownTopicOrPartitionException => true
        case _: ClusterAuthorizationException => true
        case _ => false
      }

    private def isReconnect(failure: Throwable): Boolean =
      AdminInvalidation.isReconnectClass(failure)

    private def logged(write: Logger[F] => F[Unit]): F[Unit] =
      log.fold(Async[F].unit)(write)

    extension [A](fa: F[A]) {

      /** The adapter boundary: a Kafka exception becomes a `KuiError`, once, here. */
      private def attemptMapped(
          operation: String,
          connection: ClusterConnection
      ): F[Either[KuiError, A]] =
        fa.attempt.map(_.leftMap(KafkaErrorMapper.map(operation, _, apiTimeout(connection))))
    }
  }
}
