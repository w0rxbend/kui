package kui.gateway.api

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.model.Uri
import sttp.tapir.server.ServerEndpoint

import kui.config.{AuthConfig, GatewayConfig, ServerConfig}
import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.application.session.{InMemorySessionStore, SessionConfig, SessionStore}
import kui.http.health.ReadinessCheck
import kui.http.{ErrorInterceptor, KuiServer}
import kui.kernel.{Host, Port}
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger
import kui.security.rbac.RbacPolicy

/** A gateway on a real port, for the assertions that only a real server can make.
  *
  * A stub interpreter would be faster and would prove less than nothing here. The whole subject of these
  * suites is the wiring: whether a forged header is really removed before a handler runs, whether the
  * correlation id really appears in a response *header*, whether an unmatched path really reaches the reject
  * handler. A stub would assert the wiring it replaced.
  *
  * It is written here rather than reused from `libs/http` because that module's own `TestServer` belongs to
  * its test module, and a test module is not a published artefact for other modules to build on.
  */
object GatewayTestServer {

  /** One bound gateway, plus everything a test needs to talk to it and to read what it logged. */
  final case class Running(
      binding: KuiServer.ServerBinding,
      // Typed as a *stream* backend so that the SSE suites can read a live response body rather than
      // waiting for one that never ends. Every other suite uses it as a plain backend, which it also is.
      backend: StreamBackend[IO, Fs2Streams[IO]],
      logger: FakeStructuredLogger[IO],
      sessions: SessionStore[IO]
  ) {

    def at(path: String): Uri = Uri.unsafeParse(s"http://localhost:${binding.port}$path")

    /** A `GET`, with any headers a test wants to forge. */
    def get(path: String, headers: Map[String, String] = Map.empty): IO[Response[String]] =
      request(basicRequest.get(at(path)), headers)

    def post(path: String, headers: Map[String, String] = Map.empty): IO[Response[String]] =
      request(basicRequest.post(at(path)), headers)

    private def request(
        builder: Request[Either[String, String]],
        headers: Map[String, String]
    ): IO[Response[String]] =
      headers
        .foldLeft(builder)((request, header) => request.header(header._1, header._2))
        .response(asStringAlways)
        .send(backend)
  }

  /** The real gateway: the real routes, the real interceptor chain, on a port the operating system picks.
    *
    * Port `0` means "any free port", so suites never collide with each other or with a gateway a developer
    * happens to be running.
    */
  def resource(
      basePath: String = "/",
      extraRoutes: List[ServerEndpoint[Fs2Streams[IO], IO]] = Nil,
      devInsecureCookies: Boolean = true
  ): Resource[IO, Running] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      sessions <- InMemorySessionStore.resource[IO](SessionConfig.Default)
      readiness = List(ReadinessCheck.always[IO]("process"))
      routes = GatewayApi.routes[IO](configView(basePath), readiness, sessions, extraRoutes)
      interceptors = EdgeHeaders.interceptors[IO] ++
        SessionMiddleware.interceptors[IO](sessions, logger, basePath, secureCookies = !devInsecureCookies) ++
        ErrorInterceptor.interceptors[IO](logger)
      config = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), basePath)
      binding <- KuiServer.resource[IO](config, routes, interceptors, logger, gracefulShutdown = 10.millis)
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield Running(binding, backend, logger, sessions)

  /** The configuration the routes read: this deployment's server settings, and no upstream services. */
  private def configView(basePath: String): GatewayServiceConfigView =
    GatewayServiceConfigView(
      ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), basePath),
      GatewayConfig.Default,
      AuthConfig.Default,
      secureCookies = true,
      RbacPolicy.Disabled
    )

  /** The telemetry a suite uses when it needs one at all: records nothing, costs nothing. */
  val noTelemetry: Telemetry[IO] = Telemetry.noop[IO]
}
