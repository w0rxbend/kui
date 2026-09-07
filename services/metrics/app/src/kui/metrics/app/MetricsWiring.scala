package kui.metrics.app

import java.time.Instant

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Meter
import sttp.client4.Backend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cache.CacheMetrics
import kui.config.{ClusterConfig, MetricsConfig, MetricsSourceSettings, UrlPolicy}
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.http.upstream.{UpstreamClient, UpstreamConfig}
import kui.kernel.{ClusterId, PositiveInt}
import kui.metrics.api.{MetricsApi, MetricsCapabilities}
import kui.metrics.application.{MetricsUseCases, SourceAccess, SourceProfile}
import kui.metrics.domain.MetricsSourcePort
import kui.metrics.infrastructure.{ConfiguredClusterSources, MetricsBuffer, PrometheusBrokerScrape}
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
  * One HTTP connection pool, and only when at least one cluster names a readable metrics source. Building it
  * dials nothing: an `UpstreamClient` is a circuit breaker, a bulkhead and a failover list around a pool that
  * connects on first use, so an exporter that is down delays no start-up and fails no start-up. What does
  * begin here is one scrape fibre per such cluster, under this `Resource`'s lifetime, which is what makes the
  * first chart after a restart a chart rather than an empty axis.
  *
  * A deployment where no cluster names a source builds **no** pool, no breaker and no fibre — the same
  * argument the schema service makes for a cluster with no registry. An idle upstream publishes a permanently
  * zero series on every dashboard that charts it, and a metric nothing can make non-zero is one an operator
  * learns to ignore.
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

  /** How many scrapes may be in flight to one exporter at once.
    *
    * One, and that is not a typo. There is exactly one caller — this cluster's scrape fibre — and it asks
    * once per `scrapeInterval`. A wider bulkhead would let a run of slow scrapes overlap into a pile against
    * a component KUI does not control, which is the failure `kui.topics.scrapeTimeout` exists to prevent one
    * service over.
    */
  val MaxConcurrentPerExporter: PositiveInt = PositiveInt.unsafe(1)

  /** How many times a scrape is repeated when an address refuses a connection.
    *
    * Zero. A scrape is a poll: the next one is already scheduled, and a retry inside the interval buys one
    * sample at the cost of doubling the load on an exporter that is already struggling. The failover list is
    * still one address long for the same reason — `kui.metrics.sources.<id>.url` is a single address.
    */
  val MaxRetries: Int = 0

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`, read from the same file this process already loaded.
    *   They are the list of rows the capability report has to have an answer for, source or not.
    * @param metrics
    *   the `kui.metrics` section, all four keys of it: `sources` decides which clusters can be measured,
    *   `scrapeInterval` is both the cadence and the step the samples are bucketed under, and `retention` and
    *   `maxSamplesPerSeries` bound what each cluster's window keeps.
    */
  def make[F[_]: {Async, Parallel}](
      clusters: List[ClusterConfig],
      metrics: MetricsConfig,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, MetricsServer[F]] =
    // The address rule is read here rather than taken as a parameter of this method, so that the all-in-one
    // and the stand-alone process cannot apply two different ones — widening `make` would move a seam
    // `apps/allinone` codes against. A JMX exporter at `http://kafka-metrics:5556/metrics` inside a Compose
    // network is the ordinary arrangement, and `KUI_ALLOW_PRIVATE_UPSTREAMS` is what admits it.
    Resource
      .eval(Async[F].delay(UrlPolicy.fromEnv(sys.env)))
      .flatMap(policy => makeWith[F](clusters, metrics, policy, telemetry, principals, logger))

  /** The same wiring with the address rule handed in.
    *
    * `make` is this with `UrlPolicy.fromEnv(sys.env)`, and the split exists so that both halves of the rule
    * can be exercised on one machine: a suite can build the wiring against a loopback exporter under
    * `UrlPolicy.Dev` and watch it measure, and build the same wiring under `UrlPolicy.Strict` and watch every
    * scrape be refused by name. Nothing outside this module calls it — `apps/allinone` and `Main` both call
    * `make`, so the seam they code against has not moved.
    */
  private[app] def makeWith[F[_]: {Async, Parallel}](
      clusters: List[ClusterConfig],
      metrics: MetricsConfig,
      policy: UrlPolicy,
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

      buffers <- collectors[F](clusters, metrics, policy, telemetry, meter, logger)

      sources = new ConfiguredClusterSources[F](profiles, buffers.toMap[ClusterId, MetricsSourcePort[F]])
      useCases = MetricsUseCases.make[F](sources)
      capabilities = MetricsCapabilities.make[F](sources)

      // Readiness is deliberately empty. "Can this service answer" is true as soon as it is wired: every
      // route answers a `Section` whatever the state of a source, so there is nothing an upstream could
      // make untrue. A check that waited for an exporter would take this service out of rotation because
      // an *optional* dependency was slow, which is the schema service's argument and holds here too.
      readiness = List.empty[ReadinessCheck[F]]
    } yield MetricsServer(
      routes = MetricsApi.routes[F](useCases, readiness, capabilities, principals, rejections, logger),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = MetricsApi.capabilityDocument[F](capabilities, logger)
    )

  /** One collector per cluster that names a readable source, or nothing at all.
    *
    * The `Resource` returned owns everything the collectors need: the process's one connection pool, one
    * circuit breaker per exporter, one retention window per cluster and one scrape fibre per cluster. All of
    * it ends when the process does, which is why a scrape in flight at shutdown is cancelled rather than left
    * holding a socket.
    *
    * It hands back the buffers rather than the ports it widens them into, because the four numbers this
    * method decides — the bucket step, the retention, the sample ceiling and which clusters get a collector
    * at all — are only observable through `record`. A suite that could only see `MetricsSourcePort` would
    * have to wait for a real scrape to land before it could ask about any of them, and would then be
    * asserting a clock rather than a configuration.
    */
  private[app] def collectors[F[_]: Async](
      clusters: List[ClusterConfig],
      metrics: MetricsConfig,
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      meter: Meter[F],
      logger: StructuredLogger[F]
  ): Resource[F, Map[ClusterId, MetricsBuffer[F]]] = {
    val readable = ConfiguredClusterSources.scrapable(clusters, metrics)

    if readable.isEmpty then Resource.pure[F, Map[ClusterId, MetricsBuffer[F]]](Map.empty)
    else
      for {
        transport <- HttpClientFs2Backend.resource[F]()
        cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
        // One instant for every window, taken before any of them collects, so that two clusters
        // configured together report the same coverage rather than one of them looking a scrape younger.
        startedAt <- Resource.eval(Async[F].realTimeInstant)
        ports <- readable.traverse((cluster, settings) =>
          collectorFor[F](
            cluster,
            settings,
            metrics,
            startedAt,
            transport,
            policy,
            telemetry,
            cacheMetrics,
            logger
          ).map(cluster -> _)
        )
      } yield ports.toMap
  }

  private def collectorFor[F[_]: Async](
      cluster: ClusterId,
      settings: MetricsSourceSettings,
      metrics: MetricsConfig,
      startedAt: Instant,
      transport: Backend[F],
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      cacheMetrics: CacheMetrics[F],
      logger: StructuredLogger[F]
  ): Resource[F, MetricsBuffer[F]] =
    for {
      upstream <- UpstreamClient.resource[F](
        upstreamConfig(cluster, settings, policy),
        transport,
        telemetry,
        MetricsApi.Id,
        logger
      )
      buffer <- Resource.eval(
        MetricsBuffer.create[F](
          cluster = cluster,
          // The bucket width the samples are filed under is the cadence they arrive at: two scrapes inside
          // one step are one reading, so a clock that drifts by a second does not produce two points a
          // second apart.
          step = metrics.scrapeInterval,
          // Passed through exactly as the operator wrote it, and not widened. `retention < scrapeInterval`
          // is refused at load by `KuiConfigSource.checkMetricsRules`, so the pair that would need widening
          // cannot reach this line; widening it here would make a configuration the loader refuses behave
          // as though it had been accepted.
          retention = metrics.retention,
          maxSamples = metrics.maxSamplesPerSeries,
          startedAt = startedAt,
          metrics = cacheMetrics
        )
      )
      scrape = new PrometheusBrokerScrape[F](upstream.backend, settings.url)
      _ <- BrokerScrapeLoop.resource[F](cluster, scrape, buffer, metrics.scrapeInterval, logger)
    } yield buffer

  /** The resilience an exporter is called behind. One address, one call at a time, no retry — see the two
    * constants above for why each of those is the number it is.
    */
  private[app] def upstreamConfig(
      cluster: ClusterId,
      settings: MetricsSourceSettings,
      policy: UrlPolicy
  ): UpstreamConfig =
    UpstreamConfig(
      name = s"${PrometheusBrokerScrape.UpstreamName}-${cluster.value}",
      urls = NonEmptyList.one(settings.url),
      // The whole-call budget the operator configured. It is bounded below the scrape interval by the
      // loader, so a scrape cannot outlive the interval and overlap the next one.
      callTimeout = settings.callTimeout,
      maxConcurrent = MaxConcurrentPerExporter,
      maxRetries = MaxRetries,
      urlPolicy = policy
    )

  /** What this process will and will not measure, said out loud once at start-up.
    *
    * "Why is the throughput card showing a sentence?" is the first question this service will be asked, and
    * after the fact it is unanswerable unless the process said so when it started. Two lines, because two
    * situations need different actions from whoever is reading:
    *
    *   - no cluster configured a source — INFO, because nothing is wrong;
    *   - a cluster configured one this process will scrape — INFO, naming it, because "which clusters is this
    *     process measuring" is otherwise unanswerable after the fact;
    *   - a cluster configured one this build cannot read — WARN, because an operator has written
    *     configuration that is having no effect, and that is exactly the case ADR-005's "say which keys are
    *     ignored" rule exists for.
    */
  private def startupLog[F[_]: Async](
      profiles: List[SourceProfile],
      logger: StructuredLogger[F]
  ): F[Unit] = {
    val measured = profiles.filter(_.isMeasurable).map(_.cluster.value)
    val unreadable = profiles.filter(profile => profile.hasSource && !profile.isMeasurable)

    logger
      .info(
        "no cluster configures kui.metrics.sources, so every cluster reports metrics as not configured " +
          "and the dashboard's metrics cards keep their 'not measured' sentence"
      )
      .whenA(profiles.forall(!_.hasSource)) *>
      logger
        .info(Map("metrics.declaredSources" -> measured.mkString(",")))(
          s"${measured.size} cluster(s) name a Prometheus metrics source and will be scraped every " +
            "kui.metrics.scrapeInterval; their throughput cards draw a series rather than a sentence"
        )
        .whenA(measured.nonEmpty) *>
      unreadable.traverse_(profile =>
        logger.warn(Map("metrics.unreadableSource" -> profile.cluster.value))(
          profile.unreadableReason.getOrElse(SourceAccess.unreadableSource(profile.cluster))
        )
      )
  }
}
