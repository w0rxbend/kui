package kui.schema.app

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.Backend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import cats.Parallel
import cats.data.NonEmptyList

import kui.config.{ClusterConfig, RegistryAuthConfig, SchemaRegistrySettings, UrlPolicy}
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.http.upstream.{UpstreamClient, UpstreamConfig}
import kui.kernel.{ClusterId, PositiveInt}
import kui.observability.Telemetry
import kui.observability.audit.LoggingAuditSink
import kui.schema.api.{SchemaApi, SchemaCapabilities}
import kui.schema.application.*
import kui.schema.domain.SchemaRegistryPort
import kui.schema.infrastructure.{ConfiguredClusterRegistries, RegistryCredentials, RegistryHttp}
import kui.security.PrincipalCodec

/** Everything the schema service needs in order to be served, with no listener started.
  *
  * The same shape as every other service's (ADR-010): stopping one step short of a running server is what
  * lets the all-in-one deployment take these routes, add the rest, and start one listener over the lot.
  */
final case class SchemaServer[F[_]](
    routes: List[ServerEndpoint[Any, F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The schema service's composition root.
  *
  * ==What it contacts, and when==
  *
  * Nothing. Building this opens no connection to any registry: an `UpstreamClient` is a circuit breaker, a
  * bulkhead and a failover list around a connection pool, and the pool dials on first use. A registry that is
  * down therefore delays no start-up and fails no start-up — the service starts, the capability report says
  * that cluster is degraded, and the screen says why.
  *
  * ==What it builds per cluster, and what it does not==
  *
  * A cluster with no `schemaRegistry` block gets **no client at all**: no HTTP pool, no circuit breaker, no
  * upstream metric series. That is not a micro-optimisation. An idle upstream publishes a permanently zero
  * series on every dashboard that charts it, and a metric nothing can ever make non-zero is one an operator
  * learns to ignore. The cluster still appears in the capability report, as `not_configured`, which is the
  * fact the browser needs in order to hide the feature rather than break on it.
  *
  * Each configured registry gets its **own** upstream client, named after the cluster. One shared breaker
  * across every cluster's registry would mean one broken registry pausing KUI's calls to the healthy ones,
  * which is precisely the coupling the breaker exists to prevent.
  */
object SchemaWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.schema"

  /** How many requests may be in flight to one registry at once.
    *
    * Small on purpose. A Schema Registry is a single-writer JVM in front of a Kafka topic, not a scalable
    * cluster, and it is the component of a deployment most likely to be the slow one. Sixteen concurrent
    * requests is more than a screen can generate and few enough that KUI cannot be the reason a registry
    * falls over.
    */
  val MaxConcurrentPerRegistry: PositiveInt = PositiveInt.unsafe(16)

  /** How many times a read is repeated when an address refuses a connection.
    *
    * Everything this service sends is idempotent — the compatibility writes included: setting a level to
    * `BACKWARD` twice leaves it `BACKWARD` — so a retry can never apply something twice.
    */
  val MaxRetries: Int = 2

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`, read from the same file this process already loaded.
    *   Unlike the other services this one needs no credentials from the cluster service: a registry's address
    *   and credentials are its own configuration block. See [[ConfiguredClusterRegistries]].
    * @param policy
    *   the address restriction applied to every registry URL. It is a parameter rather than
    *   `UrlPolicy.Strict`, because a Schema Registry is very often the one upstream that legitimately lives
    *   on a private network — `http://schema-registry:8081` inside a Compose network is the ordinary
    *   arrangement — and the operator's `KUI_ALLOW_PRIVATE_UPSTREAMS` is what decides it.
    */
  def make[F[_]: {Async, Parallel}](
      clusters: List[ClusterConfig],
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, SchemaServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(SchemaApi.interceptors[F](telemetry, rejections, logger))

      // One connection pool for the process, and none at all when no cluster configures a registry.
      backend <- httpBackend[F](clusters)
      ports <- portsFor[F](clusters, backend, policy, telemetry, logger)
      _ <- Resource.eval(startupLog[F](clusters, logger))

      registries = new ConfiguredClusterRegistries[F](
        ConfiguredClusterRegistries.profilesOf(clusters),
        ports
      )

      audit = LoggingAuditSink.make[F](logger)

      subjects = SubjectListUseCase.make[F](registries)
      versions = SubjectVersionsUseCase.make[F](registries)
      schema = SchemaVersionUseCase.make[F](registries)
      compatibility = CompatibilityReadUseCase.make[F](registries, logger)
      set = SetCompatibilityUseCase.make[F](registries, audit, logger)
      check = CompatibilityCheckUseCase.make[F](registries)
      capabilities = SchemaCapabilities.make[F](registries, logger)

      // Readiness is deliberately empty. "Can this service answer" is true as soon as it is wired, and a
      // check that waited for a registry would take the schema service out of rotation whenever an
      // *optional* dependency was slow — turning the one component KUI treats as hostile into a reason
      // for KUI itself to be restarted.
      readiness = List.empty[ReadinessCheck[F]]
    } yield SchemaServer(
      routes = SchemaApi.routes[F](
        subjects,
        versions,
        schema,
        compatibility,
        set,
        check,
        readiness,
        capabilities,
        principals,
        rejections,
        logger
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = SchemaApi.capabilityDocument[F](capabilities, logger)
    )

  /** The process's one HTTP connection pool, or none at all. */
  private def httpBackend[F[_]: Async](clusters: List[ClusterConfig]): Resource[F, Option[Backend[F]]] =
    if clusters.exists(_.schemaRegistry.isDefined) then
      HttpClientFs2Backend.resource[F]().map(backend => Some(backend: Backend[F]))
    else Resource.pure[F, Option[Backend[F]]](None)

  /** One registry client per configured cluster, each with its own breaker, bulkhead and failover list. */
  private def portsFor[F[_]: Async](
      clusters: List[ClusterConfig],
      backend: Option[Backend[F]],
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, Map[ClusterId, SchemaRegistryPort[F]]] =
    backend match {
      case None => Resource.pure(Map.empty)
      case Some(transport) =>
        clusters
          .flatMap(cluster => cluster.schemaRegistry.map(cluster.id -> _))
          .traverse((cluster, settings) =>
            registryFor[F](cluster, settings, transport, policy, telemetry, logger).map(cluster -> _)
          )
          .map(_.toMap)
    }

  private def registryFor[F[_]: Async](
      cluster: ClusterId,
      settings: SchemaRegistrySettings,
      transport: Backend[F],
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, SchemaRegistryPort[F]] =
    for {
      upstream <- UpstreamClient.resource[F](
        upstreamConfig(cluster, settings, policy),
        transport,
        telemetry,
        SchemaApi.Id,
        logger
      )
      tokenBackend <- tokenBackendFor[F](cluster, settings, transport, policy, telemetry, logger)
      credentials <- RegistryCredentials.fromConfig[F](settings.auth, tokenBackend, logger)
      // The first URL only decides how each path is joined; which host a request actually goes to is the
      // resilient backend's decision, because failover may send it to the second address.
    } yield new RegistryHttp[F](upstream.backend, settings.urls.head, credentials)

  private def upstreamConfig(
      cluster: ClusterId,
      settings: SchemaRegistrySettings,
      policy: UrlPolicy
  ): UpstreamConfig =
    UpstreamConfig(
      // Named per cluster, so a dashboard shows which registry is failing rather than "the registry".
      name = s"${RegistryHttp.UpstreamName}.${cluster.value}",
      urls = settings.urls,
      callTimeout = settings.callTimeout,
      maxConcurrent = MaxConcurrentPerRegistry,
      maxRetries = MaxRetries,
      urlPolicy = policy
    )

  /** The transport for an OAuth token endpoint, which is **not** the registry's resilient backend.
    *
    * That one fails over between the registry's own addresses; a token request routed to a registry replica
    * because the issuer was briefly slow would be a request carrying a client secret, sent to the wrong
    * system. It gets its own upstream client, with the same protections and its own name.
    *
    * `None` for the two mechanisms that need no issuer, so a deployment using basic or no authentication
    * opens nothing.
    */
  private def tokenBackendFor[F[_]: Async](
      cluster: ClusterId,
      settings: SchemaRegistrySettings,
      transport: Backend[F],
      policy: UrlPolicy,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, Option[Backend[F]]] =
    settings.auth match {
      case RegistryAuthConfig.OAuth(endpoint, _, _, _) =>
        UpstreamClient
          .resource[F](
            UpstreamConfig(
              name = s"${RegistryCredentials.TokenUpstreamName}.${cluster.value}",
              urls = NonEmptyList.one(endpoint),
              callTimeout = settings.callTimeout,
              maxConcurrent = MaxConcurrentPerRegistry,
              // A token request is a POST, and a duplicate one costs an extra token rather than an extra
              // side effect: issuers treat client-credentials grants as repeatable.
              maxRetries = 1,
              urlPolicy = policy
            ),
            transport,
            telemetry,
            SchemaApi.Id,
            logger
          )
          .map(client => Some(client.backend))
      case _ => Resource.pure(None)
    }

  /** One INFO line per cluster that configures a registry, and one that says when none does.
    *
    * "Which registry is this cluster reading?" is the first question asked when the Schemas screen shows
    * something unexpected, and after the fact it is unanswerable unless the process said so at startup. The
    * address is safe to log — it is the operator's own text — and the mechanism is named without its secret.
    *
    * The "no registry configured" line matters as much as the others: it is what tells an operator who
    * expected a Schemas tab that KUI is behaving as configured rather than failing.
    */
  private def startupLog[F[_]: Async](
      clusters: List[ClusterConfig],
      logger: StructuredLogger[F]
  ): F[Unit] = {
    val configured = clusters.filter(_.schemaRegistry.isDefined)

    if configured.isEmpty then
      logger.info(
        "no cluster configures kui.clusters.<n>.schemaRegistry.url, so every cluster reports the schema " +
          "feature as not configured and no registry client is opened"
      )
    else
      configured.traverse_(cluster =>
        cluster.schemaRegistry.traverse_(settings =>
          logger.info(
            Map(
              "cluster.id" -> cluster.id.value,
              "schemaRegistry.urls" -> settings.urls.toList.map(_.value).mkString(","),
              "schemaRegistry.auth" -> settings.auth.describe
            )
          )(s"cluster ${cluster.id.value} reads schemas from ${settings.urls.head.value}")
        )
      )
  }
}
