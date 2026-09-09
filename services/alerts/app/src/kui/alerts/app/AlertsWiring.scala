package kui.alerts.app

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.alerts.api.{AlertsApi, AlertsCapabilities}
import kui.alerts.application.*
import kui.alerts.domain.{AlertLimits, ClusterFactsPort}
import kui.alerts.infrastructure.{
  ConfiguredProfileSource,
  InMemoryAlertStore,
  KafkaClusterFacts,
  LoggingAcknowledgementSink
}
import kui.cache.CacheMetrics
import kui.config.{AlertThresholds, AlertsConfig, ClusterConfig}
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.{PrincipalVerification, RbacGuard}
import kui.kafka.admin.{KafkaClusterAdmin, KafkaGroupAdmin}
import kui.kafka.{AdminClientPool, AdminMetrics}
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.security.rbac.{ClusterFlags, RbacPolicy}

/** Everything the alerts service needs in order to be served, with no listener started.
  *
  * The same shape as every other service's (ADR-010): stopping one step short of a running server is what
  * lets the all-in-one deployment take these routes, add the rest, and start one listener over the lot.
  */
final case class AlertsServer[F[_]](
    routes: List[ServerEndpoint[Fs2Streams[F], F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The alerts service's composition root.
  *
  * ==What it contacts, and when==
  *
  * It opens no Kafka connection while it is being built. `AdminClientPool` creates a client on first use, and
  * the first use is the first evaluation pass, which starts inside the returned `Resource` and runs on its
  * own fibre. A broker that is down therefore delays nothing and fails nothing here: the service starts, the
  * feed answers, and every rule's row says that its facts could not be read.
  *
  * ==One fibre per configured cluster, and none if there are none==
  *
  * A deployment with no `kui.clusters[]` builds no pool and no fibre. There is nothing to evaluate, and a
  * loop that woke every minute to evaluate nothing would be an entry in every profile nobody could explain.
  *
  * ==Where the thresholds are mapped==
  *
  * `kui.alerts.thresholds` is a `kui.config.AlertThresholds`, and the rules take a
  * `kui.alerts.domain.AlertLimits` — the domain's own copy, because rule A1 forbids it `libs/config`. The
  * mapping is [[limitsOf]] and it is the one place the two spellings can disagree, which is why
  * `AlertsWiringSuite` pins them together field by field rather than trusting five assignments.
  */
object AlertsWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = AlertsService.Instrumentation

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`, read from the same file this process already loaded.
    *   They are the list of rows the capability report has to have an answer for, and one evaluation fibre
    *   each.
    * @param alerts
    *   the `kui.alerts` section: `retention` bounds the store, `evaluationInterval` is the cadence, and
    *   `thresholds` are the five numbers the rules compare against.
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      alerts: AlertsConfig,
      rbac: RbacPolicy,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, AlertsServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(AlertsApi.interceptors[F](telemetry, rejections, logger))

      profiles = new ConfiguredProfileSource[F](clusters)
      cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
      store <- InMemoryAlertStore.resource[F](alerts.retention, cacheMetrics)

      _ <- evaluators[F](clusters, alerts, store, telemetry, logger)

      audit = LoggingAcknowledgementSink.make[F](logger)
      // Who did it is not wired here. It is a parameter of every `guard` call, threaded from the
      // principal the gateway signed and the route verified (ADR-020), so an audit line names the person
      // who made the request rather than a constant this file chose.
      guard = MutationGuard.make[F](profiles, audit, logger)
      useCases = AlertUseCases.make[F](profiles, store, guard)
      capabilities = AlertsCapabilities.make[F](profiles)

      // Readiness is deliberately empty, for the reason the topic and metrics services give: "can this
      // service answer" is true as soon as it is wired. Every route answers a document whatever the state
      // of a broker, so there is nothing an upstream could make untrue. A check that waited for the first
      // pass would take the alerts service out of rotation whenever a broker was slow — which is exactly
      // when the feed is the screen somebody is looking at.
      readiness = List.empty[ReadinessCheck[F]]

      // The permission check this service runs for itself, over the same declaration on the same
      // endpoints the gateway read (ADR-021). Read-only comes from this process's own `kui.clusters[]`,
      // so an acknowledgement on a read-only cluster is refused here whether or not the gateway was asked.
      permissions = RbacGuard.fromPolicy[F](
        rbac,
        cluster => ClusterFlags(clusters.find(_.id == cluster).exists(_.readOnly)),
        logger
      )
    } yield AlertsServer(
      routes = AlertsApi.routes[F](
        useCases,
        store,
        readiness,
        capabilities,
        principals,
        rejections,
        telemetry,
        logger,
        permissions
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = AlertsApi.capabilityDocument[F](capabilities, logger)
    )

  /** One evaluation fibre per configured cluster, over one shared admin pool.
    *
    * The pool, the two admin ports and every fibre live for the life of the returned `Resource`, which is
    * what stops a pass in flight at shutdown from outliving the process. A deployment with no clusters gets
    * no pool at all.
    */
  private def evaluators[F[_]: {Async, Files}](
      clusters: List[ClusterConfig],
      alerts: AlertsConfig,
      store: AlertStore[F],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, Unit] =
    if clusters.isEmpty then
      Resource.eval(
        logger.info(
          "no cluster is configured, so the alerts service evaluates nothing and every feed it serves " +
            "is empty; this is a deployment with no clusters rather than a cluster with no alerts"
        )
      )
    else
      for {
        adminMetrics <- Resource.eval(AdminMetrics.otel[F](telemetry))
        pool <- AdminClientPool.resource[F](adminMetrics, Some(logger))
        clusterAdmin = KafkaClusterAdmin[F](pool, Some(logger))
        groupAdmin = KafkaGroupAdmin[F](pool, Some(logger))
        limits = limitsOf(alerts.thresholds)
        _ <- clusters.traverse_ { cluster =>
          val facts: ClusterFactsPort[F] =
            new KafkaClusterFacts[F](cluster.id, cluster.connection, clusterAdmin, groupAdmin, pool, logger)

          AlertEvaluationLoop.resource[F](
            cluster.id,
            EvaluateAlerts.make[F](facts, store, limits),
            alerts.evaluationInterval,
            logger
          )
        }
      } yield ()

  /** The operator's thresholds, as the rules see them.
    *
    * Field for field and nothing else: no widening, no clamping and no defaulting. Every bound is already
    * applied by `KuiConfigSource`'s loader, which refuses a file rather than accepting one it has to correct,
    * and a second opinion here would make a configuration the loader refuses behave as though it had been
    * accepted.
    */
  def limitsOf(thresholds: AlertThresholds): AlertLimits =
    AlertLimits(
      offlinePartitions = thresholds.offlinePartitions,
      underReplicatedPartitions = thresholds.underReplicatedPartitions,
      rebalanceDuration = thresholds.rebalanceDuration,
      diskUsedWarningPercent = thresholds.diskUsedWarningPercent,
      diskUsedCriticalPercent = thresholds.diskUsedCriticalPercent
    )
}
