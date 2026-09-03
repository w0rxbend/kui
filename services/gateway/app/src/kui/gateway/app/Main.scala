package kui.gateway.app

import cats.effect.kernel.{Clock, Resource}
import cats.effect.{ExitCode, IO, IOApp}

import kui.config.{ConfigErrors, KuiConfig, KuiConfigSource, UrlPolicy}
import kui.gateway.api.GatewayApi
import kui.http.KuiServer
import kui.observability.{KuiLogger, LogbackSelection, Telemetry}

/** The gateway process.
  *
  * `IO` appears here and nowhere else in the gateway (ADR-010). Every layer beneath is written against an
  * abstract `F[_]`, which is not an aesthetic preference: it is what lets a suite run the same code on a
  * deterministic clock, and what lets AIO-001 run the same wiring inside a different process.
  *
  * The program is four steps, and each failure mode is deliberate:
  *
  *   1. **Load and validate the configuration.** A bad configuration stops the process with every problem
  *      listed at once and a non-zero exit code. Starting with a silently defaulted value is how a deployment
  *      ends up listening on the wrong port for a week (CFG-001).
  *   1. **Choose the log format.** Before any logger exists, because Logback configures itself on first use
  *      (`LogbackSelection`).
  *   1. **Start telemetry.** This one never stops the process: an unreachable collector is a monitoring
  *      outage, and turning it into a KUI outage would mean a Friday-evening collector restart takes the
  *      product down (`Telemetry.resource`).
  *   1. **Wire the gateway.** No upstream is contacted and none is required (`GatewayWiring.make`).
  *   1. **Bind the port.** A port already in use stops the process with a message naming the port. KUI never
  *      quietly moves to another one: a server nothing can reach, whose health check passes, is far worse to
  *      diagnose than one that refused to start and said why.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    IO.delay(sys.env).flatMap(loadConfig(args, _)).flatMap {
      case Left(errors) => reportConfigProblems(errors)
      case Right(loaded) => serve(GatewayServiceConfig.from(loaded))
    }

  /** The configuration, with the URL policy chosen from the environment.
    *
    * Every URL an operator configures is a URL the gateway's own network position will fetch, so the default
    * refuses addresses that are not routable on the public internet -- loopback, the private ranges, and the
    * cloud metadata address inside the link-local range. That default used to be the only possibility, which
    * made three legitimate deployments impossible to configure: a developer running the gateway and a service
    * as two local processes, an OTLP collector running beside the gateway on `http://localhost:4317`, and a
    * Kubernetes ClusterIP address. `KUI_ALLOW_PRIVATE_UPSTREAMS=true` is the one deliberate way to relax it,
    * and `UrlPolicy.fromEnv` is where that decision lives so the gateway, the services and the all-in-one
    * image cannot answer it differently.
    *
    * Taking the environment as an argument, rather than reading `sys.env` here, is what makes the choice
    * testable without setting a variable for the whole test process.
    */
  private[app] def loadConfig(
      args: List[String],
      env: Map[String, String]
  ): IO[Either[ConfigErrors, KuiConfig]] =
    KuiConfigSource.loadFrom[IO](args, files = Nil, env, UrlPolicy.fromEnv(env))

  /** Runs until the process is asked to stop. `IO.never` is what holds it open: the server is a `Resource`,
    * so the shutdown hook that cancels this fiber is also what closes the listener gracefully.
    */
  private def serve(config: GatewayServiceConfig): IO[ExitCode] =
    server(config).useForever.as(ExitCode.Success)

  private def server(config: GatewayServiceConfig): Resource[IO, KuiServer.ServerBinding] =
    for {
      // Before any logger exists, because Logback configures itself on first use and cannot be moved
      // afterwards. Nothing between this line and `KuiLogger.make` may log.
      _ <- Resource.eval(LogbackSelection[IO](config.telemetry.logFormat))
      logger <- Resource.eval(KuiLogger.make[IO](GatewayApi.ServiceName))
      startedAt <- Resource.eval(Clock[IO].realTimeInstant)
      _ <- Resource.eval(GatewayWiring.startupLog[IO](logger, config, startedAt))
      telemetry <- Telemetry.resource[IO](GatewayApi.ServiceName, config.telemetry)
      gateway <- GatewayWiring.make[IO](config, telemetry, logger)
      binding <- KuiServer.resource[IO](config.server, gateway.routes, gateway.interceptors, logger)
    } yield binding

  /** Every problem at once, on standard error, with a non-zero exit.
    *
    * All of them rather than the first, because fixing configuration one message per restart is miserable and
    * slow. Standard error rather than the logger, because a configuration failure can happen before there is
    * a logger — and because an operator watching `docker logs` needs to see it whatever the configured log
    * format was going to be.
    */
  private def reportConfigProblems(errors: ConfigErrors): IO[ExitCode] =
    IO.consoleForIO
      .errorln(s"kui-gateway cannot start; the configuration has problems:\n${errors.render}")
      .as(ExitCode.Error)
}
