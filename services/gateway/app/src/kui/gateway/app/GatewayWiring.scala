package kui.gateway.app

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.api.{CapabilityRoutes, EdgeHeaders, GatewayApi, InfoRoutes}
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, RegistryConfig, Trigger}
import kui.gateway.application.session.{InMemorySessionStore, SessionConfig}
import kui.http.health.ReadinessCheck
import kui.http.{BasePath, Cors, ErrorInterceptor}
import kui.kernel.ServiceId
import kui.observability.{KuiInterceptors, Telemetry}

/** Everything a gateway needs in order to be served, with no listener started.
  *
  * Stopping one step short of a running server is what lets the all-in-one deployment (AIO-001) reuse this:
  * it takes these routes, adds the routes of every in-process service, and starts a single listener over the
  * lot. If `make` bound a port, the all-in-one would have to either start twelve servers or reimplement the
  * assembly.
  *
  * @param routes
  *   the endpoints, in match order, with `/api/v1` already applied but not the deployment's base path — that
  *   is `KuiServer`'s job, applied once over whatever list it is finally given
  * @param interceptors
  *   the cross-cutting chain, outermost first
  * @param readiness
  *   the checks behind `/api/v1/health/ready`, exposed so that a composition root that adds upstreams can add
  *   their checks too
  */
final case class GatewayServer[F[_]](
    routes: List[ServerEndpoint[Fs2Streams[F], F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]]
)

/** The gateway's composition root (ADR-010).
  *
  * It is the only place in the gateway that constructs anything concrete. Every layer below it takes its
  * collaborators as constructor parameters, which is what makes them testable with hand-written fakes rather
  * than with a mocking framework, and what makes this file the one place to look to find out what is actually
  * wired to what.
  */
object GatewayWiring {

  /** Builds the gateway.
    *
    * ==It has no mandatory upstream, and that is a requirement rather than an accident==
    *
    * Nothing in here fails because a service is unreachable, and nothing in here waits for one. The gateway
    * is Core tier (PLAN §15): when it is down the browser has nowhere to go and shows the single full-screen
    * "cannot reach gateway" state (UI-011), so a gateway that refused to start until the schema service
    * answered would convert one optional service's outage into a total blackout — precisely when an operator
    * most needs a working UI to see what is wrong. Upstreams are polled after startup and their absence is
    * reported as capability state (GW-003), never as a failure to boot.
    *
    * ==Interceptor order==
    *
    * The chain is the edge policy, then instrumentation, then error handling, and each position is load
    * bearing:
    *
    *   1. CORS outermost when a deployment has enabled it, because a preflight `OPTIONS` has to be answered
    *      before anything tries to route it. It is off by default and the shipped deployment serves the shell
    *      from this same origin, so most installations have no CORS layer at all (ADR-019).
    *   1. `EdgeHeaders` next, because it decides what everything else is even allowed to see. If tracing ran
    *      ahead of it, a span would carry a correlation id a browser chose (ADR-040). `Cookie` is not an
    *      `X-Kui-*` header, so it survives this step untouched for the session middleware to read.
    *   1. `SessionMiddleware` next: attaches a session (creating one if the request had none) and, on a
    *      mutation, applies the CSRF verdict before any endpoint's own logic runs (ADR-019). This has to sit
    *      ahead of tracing and metrics for the same reason `EdgeHeaders` sits ahead of it — a request CSRF
    *      rejects should not be traced as if some endpoint's logic had run at all.
    *   1. `KuiInterceptors` next: correlation, tracing, metrics, in that order, so that a failing request is
    *      still inside a span and still records its duration.
    *   1. `ErrorInterceptor` last, so it is innermost and sees the failure closest to where it happened. It
    *      is appended here rather than inside `KuiInterceptors` because `libs/http` depends on
    *      `libs/observability` and so the dependency cannot point the other way.
    */
  def make[F[_]: {Async, Parallel}](
      config: GatewayServiceConfig,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, GatewayServer[F]] = {
    val readiness = readinessChecks[F]

    for {
      _ <- Resource.eval(warnIfInsecureCookies[F](logger, config))
      sessions <- InMemorySessionStore.resource[F](SessionConfig.Default)
      registry <- CapabilityRegistry.resource[F](RegistryConfig.Default, telemetry, logger)
      _ <- Resource.eval(
        CapabilitySignals.make[F](RegistryConfig.Default, registry, config.gateway.services.keys.toList)
      )
      instrumentation <- Resource.eval(
        KuiInterceptors.serverInterceptors[F](telemetry, GatewayApi.ServiceName)
      )
    } yield GatewayServer(
      routes = GatewayApi.routes[F](
        config.view,
        readiness,
        sessions,
        CapabilityRoutes[F](registry, probeThrough(registry), telemetry, logger)
      ),
      interceptors = Cors.interceptor[F](config.gateway.cors).toList ++
        EdgeHeaders.interceptors[F] ++
        SessionMiddleware.interceptors[F](
          sessions,
          logger,
          BasePath.normalize(config.server.basePath),
          secureCookies = !config.gateway.devInsecureCookies
        ) ++
        instrumentation ++
        ErrorInterceptor.interceptors[F](logger),
      readiness = readiness
    )
  }

  /** The prominent warning ADR-019 requires whenever `server.devInsecureCookies` strips `Secure` off the
    * session cookie. It is a `WARN` and not a `DEBUG` on purpose: it is the one line in a deployment's log
    * that says its session cookie can be read by anyone on the same network, and it must be visible without
    * anyone having had to turn on verbose logging first to notice.
    */
  def warnIfInsecureCookies[F[_]: cats.Applicative](
      logger: StructuredLogger[F],
      config: GatewayServiceConfig
  ): F[Unit] =
    if config.gateway.devInsecureCookies then
      logger.warn(
        "server.devInsecureCookies=true: the session cookie is served without Secure. " +
          "This is meant for local development over plain HTTP only and must never be set in a " +
          "deployment reachable over the network."
      )
    else cats.Applicative[F].unit

  /** What the gateway checks before it says it is ready.
    *
    * Empty of upstream checks on purpose — see `make`'s note. The single check that the process is running is
    * not a tautology worth removing: `/health/ready` must answer with a report, and a report with no checks
    * in it reads like a bug, while one that names `process` says plainly that this service depends on nothing
    * to serve.
    */
  def readinessChecks[F[_]: cats.Applicative]: List[ReadinessCheck[F]] =
    List(ReadinessCheck.always[F]("process"))

  /** The probe the capability routes call, routed back through the registry.
    *
    * The registry forwards it to whatever poller was attached to it (GW-004). Going through the registry
    * rather than holding the poller's trigger directly means that a deployment with no poller yet -- which is
    * what this is until GW-006 wires the service clients -- answers the retry button successfully and does
    * nothing, instead of failing in the browser.
    */
  def probeThrough[F[_]](registry: CapabilityRegistry[F]): Trigger[F] =
    new Trigger[F] {
      def probe(service: ServiceId): F[Unit] = registry.probeNow(service)
    }

  /** The one INFO line every KUI process writes as it starts.
    *
    * The build fields are the same values `GET /api/v1/info` reports, from the same object, so a log line and
    * the endpoint can be cross-checked rather than compared and hoped about. That matters in exactly the
    * situation the fields exist for: someone has two log files and an endpoint response and is trying to work
    * out which of three containers is the old one.
    */
  def startupLog[F[_]](
      logger: StructuredLogger[F],
      config: GatewayServiceConfig,
      at: Instant
  ): F[Unit] =
    logger.info(
      Map(
        "service" -> GatewayApi.ServiceName,
        "port" -> config.server.port.value.toString,
        "basePath" -> config.server.basePath,
        "services" -> config.gateway.services.keys.map(_.value).toList.sorted.mkString(","),
        "version" -> InfoRoutes.buildInfo.version,
        "gitCommit" -> InfoRoutes.buildInfo.gitCommitShort,
        "gitDirty" -> InfoRoutes.buildInfo.gitDirty.toString,
        "builtAt" -> InfoRoutes.buildInfo.builtAt.toString,
        "startedAt" -> at.toString
      )
    )(
      s"starting ${GatewayApi.ServiceName} ${InfoRoutes.buildInfo.version} (${InfoRoutes.buildInfo.gitCommitShort})"
    )
}
