package kui.gateway.app

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.contracts.capability.ServiceCapabilities
import kui.gateway.api.{EdgeHeaders, GatewayApi, InfoRoutes}
import kui.http.health.ReadinessCheck
import kui.http.{Cors, ErrorInterceptor}
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
    *      ahead of it, a span would carry a correlation id a browser chose (ADR-040).
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

    Resource.eval(
      KuiInterceptors
        .serverInterceptors[F](telemetry, GatewayApi.ServiceName)
        .map { instrumentation =>
          GatewayServer(
            routes = GatewayApi.routes[F](config.view, readiness, capabilities[F]),
            interceptors = Cors.interceptor[F](config.gateway.cors).toList ++
              EdgeHeaders.interceptors[F] ++
              instrumentation ++
              ErrorInterceptor.interceptors[F](logger),
            readiness = readiness
          )
        }
    )
  }

  /** What the gateway checks before it says it is ready.
    *
    * Empty of upstream checks on purpose — see `make`'s note. The single check that the process is running is
    * not a tautology worth removing: `/health/ready` must answer with a report, and a report with no checks
    * in it reads like a bug, while one that names `process` says plainly that this service depends on nothing
    * to serve.
    */
  def readinessChecks[F[_]: cats.Applicative]: List[ReadinessCheck[F]] =
    List(ReadinessCheck.always[F]("process"))

  /** The gateway's own capability document.
    *
    * A placeholder in M0: the gateway is either running, in which case it can do everything it offers, or it
    * is not, in which case nobody is reading this. GW-003 replaces it with the fold over every upstream's
    * readiness, which is the document the browser's sidebar is actually derived from (ADR-039).
    */
  def capabilities[F[_]: cats.Applicative]: F[ServiceCapabilities] =
    ServiceCapabilities(GatewayApi.Id, Map.empty).pure[F]

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
