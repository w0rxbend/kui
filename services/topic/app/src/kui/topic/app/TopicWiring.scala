package kui.topic.app

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Resource}
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
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.topic.api.TopicApi
import kui.topic.application.{TopicCapabilityUseCase, TopicConfigUseCase, TopicDetailUseCase}
import kui.topic.domain.ClockPort
import kui.topic.infrastructure.{ConfiguredClusterProfiles, KafkaTopicAdmin, LiveTopicSnapshots}

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
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      scrapeInterval: FiniteDuration,
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
      admin = new KafkaTopicAdmin[F](pool, profiles.connectionFor, logger)
      cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
      snapshots <- LiveTopicSnapshots.resource[F](profiles.ids, admin, scrapeInterval, cacheMetrics, logger)
      detail = TopicDetailUseCase.make[F](admin, snapshots)
      config = TopicConfigUseCase.make[F](admin)
      capabilities = TopicCapabilityUseCase.make[F](profiles, snapshots)
      // Readiness is deliberately empty. "Can this service answer" is true as soon as it is wired: it
      // serves snapshots, and a snapshot that has not been taken yet is a state the screen renders
      // rather than an outage. A readiness check that waited for the first scrape would take the topic
      // service out of rotation whenever a broker was slow to answer, which is exactly the coupling the
      // snapshot exists to break.
      readiness = List.empty[ReadinessCheck[F]]
    } yield TopicServer(
      routes = TopicApi
        .routes[F](snapshots, detail, config, capabilities, readiness, principals, rejections, logger),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = TopicApi.capabilityDocument[F](capabilities, logger)
    )

  /** The domain's clock port, over the effect's own clock. */
  def clock[F[_]: Clock]: ClockPort[F] = new ClockPort[F] {
    def now: F[Instant] = Clock[F].realTimeInstant
  }
}
