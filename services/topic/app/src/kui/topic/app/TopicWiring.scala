package kui.topic.app

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.StructuredLogger
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cache.CacheMetrics
import kui.config.ClusterConfig
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.kafka.{AdminClientPool, AdminMetrics}
import kui.kernel.Secret
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.security.audit.MutationRecord
import kui.topic.api.{TopicApi, TopicErrors}
import kui.topic.application.{
  MutationGuard,
  TopicAdminUseCase,
  TopicCapabilityUseCase,
  TopicConfigUseCase,
  TopicDetailUseCase,
  TopicPlanToken
}
import kui.topic.domain.ClockPort
import kui.topic.infrastructure.{
  ConfiguredClusterProfiles,
  KafkaTopicAdmin,
  KafkaTopicWriter,
  LiveTopicSnapshots,
  LoggingAuditSink
}

/** Everything the topic service needs in order to be served, with no listener started.
  *
  * The same shape as `ClusterWiring` and for the same reason (ADR-010): stopping one step short of a running
  * server is what lets the all-in-one deployment take these routes, add every other service's, and start one
  * listener over the lot.
  */
final case class TopicServer[F[_]](
    routes: List[ServerEndpoint[Any, F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The topic service's composition root.
  *
  * ==What it contacts, and when==
  *
  * It opens no Kafka connection while it is being built. `AdminClientPool` creates a client on first use, and
  * the first use is the first background scrape, which starts inside the returned `Resource` and runs on its
  * own fiber. So a broker that is down delays nothing and fails nothing here: the service starts, the topics
  * screen renders, and the cluster's snapshot reports itself `Unavailable` with the reason. That is the whole
  * design position of the product, and it would be lost if this method waited for a scrape.
  */
object TopicWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.topic"

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`. In the all-in-one deployment this is the same list the
    *   cluster service was given, read once from the same file — see [[ConfiguredClusterProfiles]] for why
    *   this shape does not go through the HTTP profile client.
    * @param scrapeInterval
    *   how often each cluster's topic list is refreshed in the background. There is no TTL: a snapshot older
    *   than this is shown and marked stale, never withheld.
    * @param internalPrefix
    *   `kui.topics.internalPrefix`, passed to the adapter, which is the only place that also holds Kafka's
    *   own `isInternal` flag. A topic is internal when either says so.
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      scrapeInterval: FiniteDuration,
      internalPrefix: String,
      cursorKey: Option[Secret[String]],
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, TopicServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(TopicApi.interceptors[F](telemetry, rejections, logger))
      profiles <- Resource.eval(ConfiguredClusterProfiles.of[F](clusters))
      adminMetrics <- Resource.eval(AdminMetrics.otel[F](telemetry))
      pool <- AdminClientPool.resource[F](adminMetrics, Some(logger))
      admin = new KafkaTopicAdmin[F](pool, profiles.connectionFor, internalPrefix, logger)
      writer = new KafkaTopicWriter[F](pool, profiles.connectionFor, logger)
      cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
      snapshots <- LiveTopicSnapshots.resource[F](profiles.ids, admin, scrapeInterval, cacheMetrics, logger)
      detail = TopicDetailUseCase.make[F](admin, snapshots)
      config = TopicConfigUseCase.make[F](admin)

      // The plan-signing key (ADR-045), which is ADR-026's streaming cursor key: one secret, one
      // rotation procedure, and this service's use kept apart from the consumer service's by the
      // operation name inside the payload.
      //
      // Configured, because a plan token that only the replica that minted it can verify is a
      // confirmation dialog that fails at the last step behind a load balancer, and a key regenerated
      // on restart is a plan an operator has to compose twice. It falls back to a fresh random rather
      // than refusing to start: one process with no replicas — the quickstart, a laptop — needs no
      // shared secret, and demanding one there would be a worse first five minutes for nothing gained.
      key <- Resource.eval(signingKey[F](cursorKey, logger))
      tokens = TopicPlanToken.make[F](key)
      audit = LoggingAuditSink.make[F](logger)
      guard = MutationGuard.make[F](
        profiles,
        snapshots,
        audit,
        logger,
        Async[F].pure(AuditPrincipal),
        TopicErrors.toKui
      )
      topicAdmin = TopicAdminUseCase
        .make[F](admin, writer, profiles, guard, tokens, logger, TopicErrors.toKui)
      capabilities = TopicCapabilityUseCase.make[F](profiles, snapshots)
      // Readiness is deliberately empty. "Can this service answer" is true as soon as it is wired: it
      // serves snapshots, and a snapshot that has not been taken yet is a state the screen renders
      // rather than an outage. A readiness check that waited for the first scrape would take the topic
      // service out of rotation whenever a broker was slow to answer, which is exactly the coupling the
      // snapshot exists to break.
      readiness = List.empty[ReadinessCheck[F]]
    } yield TopicServer(
      routes = TopicApi
        .routes[F](
          snapshots,
          detail,
          config,
          topicAdmin,
          capabilities,
          readiness,
          principals,
          rejections,
          logger
        ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = TopicApi.capabilityDocument[F](capabilities, logger)
    )

  /** Who an audit record names while KUI has no authentication.
    *
    * A literal rather than a blank, because a record whose actor field is empty reads as a bug in the audit
    * trail rather than as a fact about the deployment. Authentication is M6; when it arrives, this is the
    * value the route's verified `Principal` replaces. It is `MutationRecord.SystemPrincipal`'s neighbour and
    * says the same thing in a sentence an operator reading an audit line can act on.
    */
  val AuditPrincipal: String = s"${MutationRecord.SystemPrincipal} (authentication is not enabled)"

  /** The key plan tokens are signed with: the configured one, or a fresh one for this process.
    *
    * The fallback is logged loudly rather than being silent, because its consequence is invisible until a
    * second replica exists: a plan minted by one process is refused by the other, and the operator sees a
    * confirmation that will not confirm.
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
                "this process. A restart invalidates an open confirmation, and a second replica rejects " +
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

  /** The domain's clock port, over the effect's own clock. */
  def clock[F[_]: Clock]: ClockPort[F] = new ClockPort[F] {
    def now: F[Instant] = Clock[F].realTimeInstant
  }
}
