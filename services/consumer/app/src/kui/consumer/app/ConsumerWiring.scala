package kui.consumer.app

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

import scala.concurrent.duration.*

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.StructuredLogger
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cache.CacheMetrics
import kui.config.{ClusterConfig, ConsumersConfig}
import kui.consumer.api.{ConsumerApi, ConsumerCapabilities}
import kui.consumer.application.*
import kui.consumer.domain.{ConsumerGroup, GroupAdminPort, GroupListingPage, OffsetWindow, ResetScope}
import kui.consumer.infrastructure.{ConfiguredProfileSource, KafkaGroupAdminPort, LoggingAuditSink}
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.kafka.admin.{KafkaGroupAdmin, OffsetLookup}
import kui.kafka.{AdminClientPool, AdminMetrics}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, GroupId, Offset, Secret, TopicPartition}
import kui.observability.Telemetry
import kui.security.PrincipalCodec

/** Everything the consumer service needs in order to be served, with no listener started.
  *
  * The same shape as `ClusterWiring` and `TopicWiring`, and for the same reason (ADR-010): stopping one step
  * short of a running server is what lets the all-in-one deployment take these routes, add every other
  * service's, and start one listener over the lot.
  */
final case class ConsumerServer[F[_]](
    routes: List[ServerEndpoint[Any, F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The consumer service's composition root.
  *
  * ==What it contacts, and when==
  *
  * It opens no Kafka connection while it is being built. `AdminClientPool` creates a client on first use, and
  * the first use is the first background pass, which starts inside the returned `Resource` and runs on its
  * own fiber. A broker that is down therefore delays nothing and fails nothing here: the service starts, the
  * Consumers screen renders, and the cluster's row says why it is empty.
  */
object ConsumerWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.consumer"

  /** How often each cluster's consumer groups are described in the background, when nothing says otherwise.
    *
    * The value and the reasoning now live in `kui.config.ConsumersConfig`, which is where an operator can
    * turn it; this alias exists so that a caller with no configuration in hand — a test, a tool — still gets
    * the product's answer rather than inventing one.
    */
  val DefaultRefreshInterval: FiniteDuration = ConsumersConfig.DefaultRefreshInterval

  /** How many groups' last-known assignments are kept per process, and for how long.
    *
    * A group's members are a few hundred bytes; two thousand of them are about a megabyte. A cluster with
    * more groups than that is one where an operator opening a detail page during a rebalance is rarer than
    * the memory is expensive.
    */
  val AssignmentCacheSize: Long = 2000L
  val AssignmentCacheTtl: FiniteDuration = 30.minutes

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`. In the all-in-one deployment this is the same list the
    *   cluster service was given, read once from the same file — see [[ConfiguredProfileSource]] for why this
    *   shape does not go through the HTTP profile client.
    * @param refreshInterval
    *   how often each cluster's group snapshot is refreshed in the background, from
    *   `kui.consumers.refreshInterval`.
    * @param cursorKey
    *   the shared key plan tokens are signed with, from `kui.streaming.cursorKey`. `None` means this
    *   deployment configured none, and a fresh one is generated for this process.
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      refreshInterval: FiniteDuration,
      cursorKey: Option[Secret[String]],
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, ConsumerServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(ConsumerApi.interceptors[F](telemetry, rejections, logger))

      profiles = new ConfiguredProfileSource[F](clusters)
      adminMetrics <- Resource.eval(AdminMetrics.otel[F](telemetry))
      pool <- AdminClientPool.resource[F](adminMetrics, Some(logger))
      groupAdmin = KafkaGroupAdmin[F](pool, Some(logger))
      offsets = OffsetLookup.make[F](pool, Some(logger))
      ports = portsFor[F](profiles, groupAdmin, offsets, logger)

      cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
      snapshots <- GroupSnapshots.resource[F](profiles, ports, refreshInterval, cacheMetrics, logger)
      lastSeen <- LastSeenAssignments
        .resource[F](AssignmentCacheScope, AssignmentCacheSize, AssignmentCacheTtl, cacheMetrics)

      // The plan-signing key (ADR-045), which is ADR-026's streaming cursor key: one secret, one
      // rotation procedure, and the two uses kept apart by the payload's own version prefix.
      //
      // Configured, because a plan token that only the replica that minted it can verify is a wizard
      // that fails at the last step behind a load balancer, and a key regenerated on restart is a
      // wizard an operator has to compose twice. It falls back to a fresh random rather than refusing
      // to start: one process with no replicas — the quickstart, a laptop — needs no shared secret,
      // and demanding one there would be a worse first five minutes for nothing gained.
      key <- Resource.eval(signingKey[F](cursorKey, logger))
      tokens = PlanToken.make[F](key)

      audit = LoggingAuditSink.make[F](logger)
      guard = MutationGuard.make[F](profiles, audit, snapshots, logger, Async[F].pure(AuditPrincipal))

      list = GroupListUseCase.make[F](snapshots)
      detail = GroupDetailUseCase.make[F](snapshots, ports, lastSeen, logger)
      // No degraded hint in M4: this service has no source of one yet, and inventing a slower interval
      // from the snapshot's own state would be a policy nothing asked for. The browser then falls back
      // to the refresh interval, which is what the use case answers with.
      lag = LagPollUseCase.make[F](snapshots, refreshInterval, Async[F].pure(None))
      forTopic = GroupsForTopicUseCase.make[F](snapshots)
      reset = OffsetResetUseCase.make[F](ports, guard, profiles, tokens, logger)
      deleteGroup = DeleteGroupUseCase.make[F](ports, guard, logger)
      deleteOffsets = DeleteOffsetsUseCase.make[F](ports, guard, logger)
      capabilities = ConsumerCapabilities.make[F](profiles, snapshots)

      // Readiness is deliberately empty, for the reason the topic service gives: "can this service
      // answer" is true as soon as it is wired. A check that waited for the first pass would take the
      // consumer service out of rotation whenever a coordinator was slow, which is exactly the coupling
      // the snapshot exists to break.
      readiness = List.empty[ReadinessCheck[F]]
    } yield ConsumerServer(
      routes = ConsumerApi.routes[F](
        list,
        detail,
        lag,
        forTopic,
        snapshots,
        reset,
        deleteGroup,
        deleteOffsets,
        readiness,
        capabilities,
        principals,
        rejections,
        logger
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = ConsumerApi.capabilityDocument[F](capabilities, logger)
    )

  /** Who an audit record names while KUI has no authentication.
    *
    * A literal rather than a blank, because a record whose actor field is empty reads as a bug in the audit
    * trail rather than as a fact about the deployment. Authentication is M6; when it arrives, this is the
    * value the route's verified `Principal` replaces.
    */
  val AuditPrincipal: String = "anonymous (authentication is not enabled)"

  /** The cluster label the assignment cache's metrics carry.
    *
    * The cache is keyed by `(cluster, group)` and holds every cluster's assignments, so there is no single
    * real cluster to name. `CacheMetrics` wants one, and a made-up slug that says what it is beats picking an
    * arbitrary configured cluster and mislabelling every other cluster's hits as belonging to it.
    */
  private val AssignmentCacheScope: ClusterId = ClusterId.unsafe("all-clusters")

  /** One `GroupAdminPort` per cluster, over the shared admin pool.
    *
    * A function rather than a map so that a cluster the profile source does not know about still produces a
    * port — one that refuses every call with `KUI-CLUSTER-NOT-FOUND`. The alternative, a `Map.apply`, throws,
    * and the one caller that reaches this with an unchecked id is the offset reset: a
    * `NoSuchElementException` there would become a 500 where the honest answer is a 404.
    */
  private def portsFor[F[_]: Async](
      profiles: ConfiguredProfileSource[F],
      admin: kui.kafka.admin.GroupAdmin[F],
      offsets: OffsetLookup[F],
      logger: StructuredLogger[F]
  ): ClusterId => GroupAdminPort[F] =
    cluster =>
      profiles.connectionFor(cluster) match {
        case Some(connection) => KafkaGroupAdminPort.make[F](admin, offsets, connection, logger)
        case None => notConfigured[F](cluster)
      }

  /** The port for a cluster KUI has never been told about. Every method is the same 404. */
  private def notConfigured[F[_]: Async](cluster: ClusterId): GroupAdminPort[F] = {
    val error: KuiError =
      ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)

    def refuse[A]: F[Either[KuiError, A]] = error.asLeft[A].pure[F]

    new GroupAdminPort[F] {
      def list(states: Set[GroupState]): F[Either[KuiError, GroupListingPage]] = refuse
      def describe(ids: List[GroupId]): F[Either[KuiError, Map[GroupId, ConsumerGroup]]] = refuse
      def exists(id: GroupId): F[Either[KuiError, Boolean]] = refuse
      def offsetWindow(
          group: GroupId,
          scope: ResetScope,
          at: Option[java.time.Instant]
      ): F[Either[KuiError, OffsetWindow]] = refuse
      def applyOffsets(group: GroupId, offsets: Map[TopicPartition, Offset]): F[Either[KuiError, Unit]] =
        refuse
      def deleteOffsets(group: GroupId, partitions: Set[TopicPartition]): F[Either[KuiError, Unit]] = refuse
      def deleteGroup(id: GroupId): F[Either[KuiError, Unit]] = refuse
    }
  }

  /** The configured key, or a fresh one, saying out loud which of the two happened.
    *
    * The log line is not noise. "Your reset wizard stopped working after a deploy" and "your reset wizard
    * stops working every time it lands on the other replica" are both this line's absence, and an operator
    * reading a startup log is the only person positioned to notice before a user does.
    */
  private def signingKey[F[_]: Async](
      configured: Option[Secret[String]],
      logger: StructuredLogger[F]
  ): F[Secret[Array[Byte]]] =
    configured match {
      case Some(secret) =>
        Async[F]
          .pure(Secret(secret.value.getBytes(StandardCharsets.UTF_8)))
          .flatTap(_ => logger.info("plan tokens are signed with the configured kui.streaming.cursorKey"))
      case None =>
        Async[F]
          .delay(Secret(randomKey()))
          .flatTap(_ =>
            logger.info(
              "no kui.streaming.cursorKey is configured; plan tokens are signed with a key generated for " +
                "this process. A restart invalidates an open reset wizard, and a second replica rejects " +
                "this one's tokens. Configure the key before running more than one."
            )
          )
    }

  /** 256 bits from the platform's secure source. */
  private def randomKey(): Array[Byte] = {
    val bytes = new Array[Byte](32)
    SecureRandom.getInstanceStrong.nextBytes(bytes)
    bytes
  }
}
