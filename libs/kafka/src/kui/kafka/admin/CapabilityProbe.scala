package kui.kafka.admin

import java.time.Instant

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.apache.kafka.common.acl.AclBindingFilter
import org.apache.kafka.common.errors.{
  ClusterAuthorizationException,
  InvalidRequestException,
  SecurityDisabledException,
  UnsupportedVersionException
}

import kui.kafka.{AdminClientPool, KafkaFutures}
import kui.kernel.cluster.{AdminTuning, ClusterConnection}

/** Asks a cluster what it supports.
  *
  * The single most important property of this object is that **it never returns a failed effect**. A
  * capability probe is a diagnostic, and a diagnostic that can take the page down with it is worse than no
  * diagnostic at all. Every failure becomes `absent` or `unknown`.
  *
  * The rule that decides which, stated once: `UnsupportedVersionException`, `InvalidRequestException`,
  * `SecurityDisabledException` and `ClusterAuthorizationException` mean **absent** — the cluster answered,
  * and the answer was no. Everything else — a timeout, a broken connection, anything unmapped — means
  * **unknown**: KUI could not ask, which is not the same as the cluster saying no. MSK Serverless answers
  * `InvalidRequestException` to questions it does not implement, and treating that as an outage would make
  * every managed cluster look broken.
  */
object CapabilityProbe {

  /** The per-probe bound.
    *
    * A quarter of the request timeout, floored at two seconds. Probing a dozen features must not cost a dozen
    * request timeouts on a cluster that is answering slowly, and a probe is by definition something the
    * product can do without.
    */
  def probeTimeout(tuning: AdminTuning): FiniteDuration =
    (tuning.requestTimeout / 4).max(2.seconds)

  /** The version bounds. Each is the release the feature arrived in, per
    * `research/kafka/admin-capabilities.md` §0.
    */
  private val versionBounds: List[(ClusterFeature, KafkaVersion)] = List(
    ClusterFeature.IncrementalAlterConfigs -> KafkaVersion(2, 3, 0),
    ClusterFeature.AuthorizedOperations -> KafkaVersion(2, 3, 0),
    ClusterFeature.ConfigDocumentation -> KafkaVersion(2, 6, 0),
    ClusterFeature.ClientQuotas -> KafkaVersion(2, 6, 0),
    ClusterFeature.ProducersAndTransactions -> KafkaVersion(2, 8, 0),
    ClusterFeature.TieredStorage -> KafkaVersion(3, 6, 0),
    ClusterFeature.NewGroupProtocol -> KafkaVersion(4, 0, 0)
  )

  def probe[F[_]: Async](
      pool: AdminClientPool[F],
      connection: ClusterConnection,
      version: BrokerVersion,
      description: Option[ClusterDescription],
      topicDeletionEnabled: Option[Boolean]
  ): F[ClusterFeatures] = {
    val budget = probeTimeout(connection.admin)

    for {
      // The three call probes run together, bounded, each under its own budget. Serially they would
      // cost three timeouts on a cluster that has stopped answering.
      called <- List(
        ClusterFeature.AclManagement -> aclProbe(pool, connection),
        ClusterFeature.LogDirs -> logDirsProbe(pool, connection, description),
        ClusterFeature.KRaftQuorum -> quorumProbe(pool, connection)
      ).parTraverseN(connection.admin.parallelism.max(1)) { (feature, call) =>
        outcomeOf(call, budget).map(feature -> _)
      }
      now <- Async[F].realTime.map(elapsed => Instant.ofEpochMilli(elapsed.toMillis))
    } yield {
      val fromVersion = versionBounds.map((feature, bound) => feature -> versionOutcome(version, bound))
      val fromCalls = called.toMap
      val aclManagement = fromCalls.getOrElse(ClusterFeature.AclManagement, Outcome.Unknown)

      val derived = List(
        ClusterFeature.AclEdit -> aclEditOutcome(aclManagement, description),
        ClusterFeature.TopicDeletion -> booleanOutcome(topicDeletionEnabled)
      )

      collect(fromVersion ++ called ++ derived, now)
    }
  }

  // ------------------------------------------------------------------ outcomes

  /** What one probe concluded. Deliberately three-valued, for the reason `ClusterFeatures` records. */
  private enum Outcome {
    case Present
    case Absent
    case Unknown
  }

  private def collect(
      outcomes: List[(ClusterFeature, Outcome)],
      at: Instant
  ): ClusterFeatures = {
    val byFeature = outcomes.toMap

    // Every feature lands in exactly one set, and a feature nobody probed is unknown rather than
    // missing: a feature in none of the three sets is a feature that silently disappears from the
    // UI.
    val (present, absent, unknown) =
      ClusterFeature.all.foldLeft(
        (Set.empty[ClusterFeature], Set.empty[ClusterFeature], Set.empty[ClusterFeature])
      ) { case ((yes, no, dunno), feature) =>
        byFeature.getOrElse(feature, Outcome.Unknown) match {
          case Outcome.Present => (yes + feature, no, dunno)
          case Outcome.Absent => (yes, no + feature, dunno)
          case Outcome.Unknown => (yes, no, dunno + feature)
        }
      }

    ClusterFeatures(present, absent, unknown, at)
  }

  /** An undetected version makes a version-derived feature **unknown**, never absent.
    *
    * That distinction is the reason the third set exists. KUI does not get to claim a cluster lacks a feature
    * just because KUI could not read the cluster's version — or because KUI's own metadata-level table is
    * older than the cluster.
    */
  private def versionOutcome(version: BrokerVersion, bound: KafkaVersion): Outcome =
    version.version match {
      case Some(detected) if detected >= bound => Outcome.Present
      case Some(_) => Outcome.Absent
      case None => Outcome.Unknown
    }

  private def booleanOutcome(value: Option[Boolean]): Outcome = value match {
    case Some(true) => Outcome.Present
    case Some(false) => Outcome.Absent
    case None => Outcome.Unknown
  }

  /** Editing ACLs needs both: a cluster that manages ACLs at all, and a principal allowed to alter them.
    *
    * When `authorizedOperations` is `None` the cluster has no authorizer configured, which means ACLs are off
    * — and "we cannot tell whether you may edit" is the honest answer, not "you may not". Asserting that it
    * is `unknown` rather than `absent` is the point of the case.
    */
  private def aclEditOutcome(
      aclManagement: Outcome,
      description: Option[ClusterDescription]
  ): Outcome = aclManagement match {
    case Outcome.Absent => Outcome.Absent
    case Outcome.Unknown => Outcome.Unknown
    case Outcome.Present =>
      description.flatMap(_.authorizedOperations) match {
        case None => Outcome.Unknown
        case Some(operations) =>
          if operations.contains(ClusterOperation.Alter) || operations.contains(ClusterOperation.All)
          then Outcome.Present
          else Outcome.Absent
      }
  }

  /** The whole downgrade rule, in one place. */
  private def outcomeOf[F[_]: Async, A](call: F[A], budget: FiniteDuration): F[Outcome] =
    call.timeout(budget).attempt.map {
      case Right(_) => Outcome.Present
      case Left(failure) =>
        KafkaFutures.unwrap(failure) match {
          case _: UnsupportedVersionException => Outcome.Absent
          case _: InvalidRequestException => Outcome.Absent
          case _: SecurityDisabledException => Outcome.Absent
          case _: ClusterAuthorizationException => Outcome.Absent
          // A timeout, a broken connection, anything unmapped: KUI could not ask.
          case _ => Outcome.Unknown
        }
    }

  // ------------------------------------------------------------------ the call probes

  private def aclProbe[F[_]: Async](
      pool: AdminClientPool[F],
      connection: ClusterConnection
  ): F[Unit] =
    pool
      .run(connection, "describeAcls") { admin =>
        KafkaFutures
          .fromFuture(Async[F].delay(admin.describeAcls(AclBindingFilter.ANY).values()))
          .void
      }

  private def logDirsProbe[F[_]: Async](
      pool: AdminClientPool[F],
      connection: ClusterConnection,
      description: Option[ClusterDescription]
  ): F[Unit] =
    description.flatMap(_.nodes.minByOption(_.id.value)) match {
      // Nothing to ask about: the cluster could not be described, so this is "could not ask".
      case None => Async[F].raiseError(new IllegalStateException("no node to probe"))
      case Some(node) =>
        pool.run(connection, "describeLogDirs") { admin =>
          KafkaFutures
            .fromFuture(
              Async[F].delay(
                admin.describeLogDirs(List(Integer.valueOf(node.id.value)).asJava).allDescriptions()
              )
            )
            .void
        }
    }

  private def quorumProbe[F[_]: Async](
      pool: AdminClientPool[F],
      connection: ClusterConnection
  ): F[Unit] =
    pool.run(connection, "describeMetadataQuorum") { admin =>
      KafkaFutures.fromFuture(Async[F].delay(admin.describeMetadataQuorum().quorumInfo())).void
    }
}
