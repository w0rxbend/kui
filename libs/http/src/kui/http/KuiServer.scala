package kui.http

import java.net.BindException

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.cats.{NettyCatsServer, NettyCatsServerOptions}

import kui.config.ServerConfig

/** The HTTP server every KUI process runs.
  *
  * It is one function rather than something each `app` assembles for itself, because "how this process serves
  * HTTP" is a decision the product makes once: the same error body, the same base path handling, the same
  * interceptor order, the same behaviour when the port is taken. An operator who has learned one KUI service
  * has learned all of them, and that only stays true if there is one place where it is decided.
  *
  * The server is `tapir-netty-server-cats` (ADR-003). The long-lived streaming that KUI depends on was
  * measured on exactly this stack before it was committed to: `docs/spikes/M0-netty-sse.md` records a
  * connection held open past ten minutes with 612 events, no drift, individual flushing, and cancellation of
  * the producing fiber 8 ms after the client went away.
  */
object KuiServer {

  /** How long a stopping server waits for in-flight requests. See `resource`'s `gracefulShutdown`. */
  val DefaultGracefulShutdown: FiniteDuration = 10.seconds

  /** Where the server actually ended up listening.
    *
    * The port is worth returning rather than assuming: a test binds port `0` and asks the operating system to
    * choose, and this is how it learns which one it got.
    */
  final case class ServerBinding(host: String, port: Int, basePath: String)

  object ServerBinding {
    given CanEqual[ServerBinding, ServerBinding] = CanEqual.derived
  }

  /** Starts the server, and stops it when the resource closes.
    *
    * The endpoints are prefixed with the configured base path here, so no contract and no service has to know
    * it exists.
    *
    * @param endpoints
    *   the routes to serve, in the order they should be matched
    * @param interceptors
    *   the cross-cutting concerns, outermost first — correlation, tracing, metrics, then the error handling
    *   from [[ErrorInterceptor]]. They are supplied rather than assembled here so a composition root can add
    *   its own (authentication, in the gateway) without this module knowing about them.
    * @param gracefulShutdown
    *   how long a stopping server waits for the requests already in flight to finish. Ten seconds in
    *   production, so a rolling deployment does not cut anyone off mid-response; suites set it to a few
    *   milliseconds, because a suite that starts and stops thirty servers would otherwise spend five minutes
    *   waiting for connections that are already closed.
    */
  def resource[F[_]: Async](
      config: ServerConfig,
      endpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      interceptors: List[Interceptor[F]],
      logger: StructuredLogger[F],
      gracefulShutdown: FiniteDuration = DefaultGracefulShutdown
  ): Resource[F, ServerBinding] = {
    val basePath = BasePath.normalize(config.basePath)

    for {
      dispatcher <- Dispatcher.parallel[F]
      routes = BasePath.prefixAll(basePath, endpoints)
      options = NettyCatsServerOptions.default[F](dispatcher).copy(interceptors = interceptors)
      server = NettyCatsServer[F](options, NettyConfig.default.withGracefulShutdownTimeout(gracefulShutdown))
        .host(config.host.value)
        .port(config.port.value)
        .addEndpoints(routes)
      binding <- Resource.make(start(server, config, logger))(b => Async[F].defer(b.stop()))
      _ <- Resource.eval(
        logger.info(
          Map("host" -> binding.hostName, "port" -> binding.port.toString, "basePath" -> basePath)
        )(s"listening on http://${binding.hostName}:${binding.port}${basePath}")
      )
    } yield ServerBinding(binding.hostName, binding.port, basePath)
  }

  /** Starts, and turns "the port is in use" into a message that says which port.
    *
    * The process then exits non-zero. It never retries on another port: a server that quietly moved to 8081
    * is a server whose health check passes, whose logs look normal, and which nothing can reach — far worse
    * to diagnose than a process that refused to start and said why.
    */
  private def start[F[_]: Async](
      server: NettyCatsServer[F],
      config: ServerConfig,
      logger: StructuredLogger[F]
  ): F[sttp.tapir.server.netty.cats.NettyCatsServerBinding[F]] =
    server.start().handleErrorWith {
      case bind: BindException =>
        val message =
          s"cannot bind ${config.host.value}:${config.port.value} — the port is already in use. " +
            "KUI does not fall back to another port; free this one or configure kui.server.port."
        logger.error(bind)(message) *> Async[F].raiseError(new BindException(message))
      case other => Async[F].raiseError(other)
    }
}
