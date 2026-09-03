package kui.gateway.api

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.model.Uri
import sttp.tapir.server.ServerEndpoint

import kui.config.ServerConfig
import kui.http.health.ReadinessCheck
import kui.http.{ErrorInterceptor, KuiServer}
import kui.kernel.{Host, Port}
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger

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
      backend: Backend[IO],
      logger: FakeStructuredLogger[IO]
  ) {

    def at(path: String): Uri = Uri.unsafeParse(s"http://localhost:${binding.port}$path")

    /** A `GET`, with any headers a test wants to forge. */
    def get(path: String, headers: Map[String, String] = Map.empty): IO[Response[String]] =
      headers
        .foldLeft(basicRequest.get(at(path)))((request, header) => request.header(header._1, header._2))
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
      extraRoutes: List[ServerEndpoint[Fs2Streams[IO], IO]] = Nil
  ): Resource[IO, Running] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      readiness = List(ReadinessCheck.always[IO]("process"))
      routes = GatewayApi.routes[IO](readiness, gatewayCapabilities) ++ extraRoutes
      interceptors = EdgeHeaders.interceptors[IO] ++ ErrorInterceptor.interceptors[IO](logger)
      config = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), basePath)
      binding <- KuiServer.resource[IO](config, routes, interceptors, logger, gracefulShutdown = 10.millis)
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield Running(binding, backend, logger)

  /** The same placeholder document `GatewayWiring` serves, so the suites exercise the shipped shape. */
  private def gatewayCapabilities: IO[kui.contracts.capability.ServiceCapabilities] =
    IO.pure(kui.contracts.capability.ServiceCapabilities(GatewayApi.Id, Map.empty))

  /** The telemetry a suite uses when it needs one at all: records nothing, costs nothing. */
  val noTelemetry: Telemetry[IO] = Telemetry.noop[IO]
}
