package kui.metrics.app

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.config.{ClusterConfig, MetricsConfig}
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.metrics.api.{MetricsApi, MetricsCapabilities}
import kui.metrics.application.{SourceProfile, ThroughputUseCase}
import kui.metrics.infrastructure.ConfiguredClusterSources
import kui.observability.Telemetry
import kui.security.PrincipalCodec

/** Everything the metrics service needs in order to be served, with no listener started.
  *
  * The same shape as every other service's (ADR-010): stopping one step short of a running server is what
  * lets the all-in-one deployment take these routes, add the rest, and start one listener over the lot.
  */
final case class MetricsServer[F[_]](
    routes: List[ServerEndpoint[Any, F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The metrics service's composition root.
  *
  * ==What it contacts, and when==
  *
  * Nothing, and today it could not if it wanted to: this build has no collector. When one arrives it is
  * constructed here, beside the profiles, and every other line in this file stays as it is — which is the
  * property the whole packet exists to buy. Adding a service to this repository touches seven places outside
  * the service, and three later milestones each add one; doing it once for a service that measures nothing is
  * what makes the next three a day's work rather than a week's.
  *
  * ==Why it starts at all with nothing configured==
  *
  * A deployment where no cluster names a metrics source still wires this service, still serves its routes and
  * still reports every cluster as `not_configured`. "This deployment measures nothing" is an answer the
  * browser needs in order to keep the cards' written sentences; a service left out of the process altogether
  * would instead look like a service that is down, and the dashboard would show six red panels for a
  * deployment behaving exactly as configured.
  */
object MetricsWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.metrics"

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`, read from the same file this process already loaded.
    *   They are the list of rows the capability report has to have an answer for, source or not.
    * @param metrics
    *   the `kui.metrics` section. Only `sources` is read today — it is what decides whether a cluster has
    *   anything to measure. The cadence and the retention window are the collector's dials and are read by
    *   the collector, when there is one.
    */
  def make[F[_]: {Async, Parallel}](
      clusters: List[ClusterConfig],
      metrics: MetricsConfig,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, MetricsServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(MetricsApi.interceptors[F](telemetry, rejections, logger))

      profiles = ConfiguredClusterSources.profilesOf(clusters, metrics)
      _ <- Resource.eval(startupLog[F](profiles, logger))

      sources = new ConfiguredClusterSources[F](profiles)
      throughput = ThroughputUseCase.make[F](sources)
      capabilities = MetricsCapabilities.make[F](sources)

      // Readiness is deliberately empty. "Can this service answer" is true as soon as it is wired: every
      // route answers a `Section` whatever the state of a source, so there is nothing an upstream could
      // make untrue. A check that waited for an exporter would take this service out of rotation because
      // an *optional* dependency was slow, which is the schema service's argument and holds here too.
      readiness = List.empty[ReadinessCheck[F]]
    } yield MetricsServer(
      routes = MetricsApi.routes[F](throughput, readiness, capabilities, principals, rejections, logger),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = MetricsApi.capabilityDocument[F](capabilities, logger)
    )

  /** What this process will and will not measure, said out loud once at start-up.
    *
    * "Why is the throughput card showing a sentence?" is the first question this service will be asked, and
    * after the fact it is unanswerable unless the process said so when it started. Two lines, because two
    * situations need different actions from whoever is reading:
    *
    *   - no cluster configured a source — INFO, because nothing is wrong;
    *   - a cluster configured one and this build has no collector — WARN, because an operator has written
    *     configuration that is having no effect, and that is exactly the case ADR-005's "say which keys are
    *     ignored" rule exists for.
    */
  private def startupLog[F[_]: Async](
      profiles: List[SourceProfile],
      logger: StructuredLogger[F]
  ): F[Unit] = {
    val declared = profiles.filter(_.hasSource).map(_.cluster.value)

    logger
      .info(
        "no cluster configures kui.metrics.sources, so every cluster reports metrics as not configured " +
          "and the dashboard's metrics cards keep their 'not measured' sentence"
      )
      .whenA(declared.isEmpty) *>
      logger
        .warn(Map("metrics.declaredSources" -> declared.mkString(",")))(
          s"${declared.size} cluster(s) configure kui.metrics.sources, but this build has no collector " +
            "to read them; the JMX and Prometheus adapters arrive with the metrics milestone, and until " +
            "then those clusters report metrics as not configured"
        )
        .whenA(declared.nonEmpty)
  }
}
