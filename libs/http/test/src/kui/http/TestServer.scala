package kui.http

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.config.{CorsConfig, ServerConfig}
import kui.kernel.{Host, Port}
import kui.testkit.fakes.FakeStructuredLogger

/** A real server on a real port, for the assertions that only a real server can make.
  *
  * A stub interpreter would be faster, and for most of `libs/http` it would be enough. It would
  * not be enough for the ones that matter here: whether an unmatched route reaches the reject
  * handler at all, whether the correlation id really appears in a response *header*, and whether
  * `basePath` changes what the operating system's socket answers. Those are properties of the
  * wiring, and a stub would assert the wiring it replaced.
  */
object TestServer {

  /** One bound server plus everything a test needs to talk to it and to read what it logged. */
  final case class Running(
      binding: KuiServer.ServerBinding,
      backend: Backend[IO],
      logger: FakeStructuredLogger[IO]
  ) {

    def baseUri: Uri = Uri.unsafeParse(s"http://localhost:${binding.port}")

    /** `baseUri` with a path appended. The interpolator will not splice a path into a URI that
      * already has an authority, so the whole thing is parsed from text instead.
      */
    def at(path: String): Uri = Uri.unsafeParse(s"http://localhost:${binding.port}$path")

    /** A `GET` against a path, with the base path already applied. */
    def get(path: String, headers: Map[String, String] = Map.empty): IO[Response[String]] =
      send(basicRequest.get(at(path)), headers)

    def request(builder: Request[Either[String, String]]): IO[Response[Either[String, String]]] =
      builder.send(backend)

    private def send(
        builder: Request[Either[String, String]],
        headers: Map[String, String]
    ): IO[Response[String]] =
      headers
        .foldLeft(builder)((request, header) => request.header(header._1, header._2))
        .response(asStringAlways)
        .send(backend)
  }

  /** Binds port 0 — the operating system picks a free one — so suites never collide. */
  def resource(
      endpoints: List[ServerEndpoint[Fs2Streams[IO], IO]],
      interceptors: FakeStructuredLogger[IO] => List[Interceptor[IO]] = defaultInterceptors,
      basePath: String = "/",
      cors: CorsConfig = CorsConfig.Default
  ): Resource[IO, Running] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      config = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), basePath)
      all = Cors.interceptor[IO](cors).toList ++ interceptors(logger)
      binding <- KuiServer.resource[IO](config, endpoints, all, logger, gracefulShutdown = 10.millis)
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield Running(binding, backend, logger)

  def defaultInterceptors(logger: StructuredLogger[IO]): List[Interceptor[IO]] =
    ErrorInterceptor.interceptors[IO](logger)

  /** `StatusCode` as a plain number, which is what an assertion reads more easily. */
  def statusOf(status: StatusCode): Int = status.code
}
