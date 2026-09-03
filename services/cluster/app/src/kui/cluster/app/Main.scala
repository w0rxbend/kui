package kui.cluster.app

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.{Clock, Resource}
import cats.effect.{ExitCode, IO, IOApp}
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.api.ClusterApi
import kui.config.{ConfigErrors, KuiConfigSource}
import kui.http.KuiServer
import kui.observability.{KuiLogger, Telemetry}

/** The cluster service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). Every layer beneath is written against an
  * abstract `F[_]`, which is not an aesthetic preference: it is what lets a suite run the same code on a
  * deterministic clock, and what lets AIO-001 run the same wiring inside a different process.
  *
  * The program is six steps and each failure mode is deliberate:
  *
  *   1. **Load and validate the configuration.** A bad configuration stops the process with every problem
  *      listed at once and a non-zero exit code. Starting with a silently defaulted value is how a deployment
  *      ends up listening on the wrong port for a week (CFG-001).
  *   1. **Choose the log format.** Before any logger exists, because Logback configures itself on first use
  *      ([[LogbackSelection]]).
  *   1. **Build the principal codec.** No signing keys and no development escape hatch stops the process
  *      ([[PrincipalCodecs]]). A service that trusts an unsigned identity header is a service anyone on the
  *      network can impersonate a user to.
  *   1. **Start telemetry.** This one never stops the process: an unreachable collector is a monitoring
  *      outage, and turning it into a KUI outage would mean a Friday-evening collector restart takes the
  *      product down.
  *   1. **Wire the service.** Nothing is contacted and nothing is required (`ClusterWiring.make`).
  *   1. **Bind the port.** A port already in use stops the process with a message naming the port. KUI never
  *      quietly moves to another one: a server nothing can reach, whose health check passes, is far worse to
  *      diagnose than one that refused to start and said why.
  */
object Main extends IOApp {

  /** How long a stopping process waits for the requests already in flight.
    *
    * Fifteen seconds, so a rolling deployment finishes its responses rather than cutting people off
    * mid-answer. It is longer than `KuiServer`'s own default because a service call arrives through the
    * gateway, which is itself waiting on it, and dropping it strands two processes rather than one.
    */
  val DrainTimeout: FiniteDuration = 15.seconds

  def run(args: List[String]): IO[ExitCode] =
    KuiConfigSource.load[IO](args, files = Nil).flatMap {
      case Left(errors) => refuseToStart(configProblems(errors))
      case Right(loaded) => start(ClusterServiceConfig.from(loaded))
    }

  /** Everything after a successful configuration load. */
  private def start(config: ClusterServiceConfig): IO[ExitCode] =
    for {
      _ <- LogbackSelection[IO](config.telemetry.logFormat)
      logger <- KuiLogger.make[IO](ClusterApi.ServiceName)
      environment <- IO.delay(sys.env)
      exit <- PrincipalCodecs.make[IO](config.principalKeys, environment, logger) match {
        case Left(problem) => refuseToStart(s"${ClusterApi.ServiceName} cannot start; $problem")
        case Right(codec) => serve(config, codec, logger)
      }
    } yield exit

  /** Runs until the process is asked to stop.
    *
    * `useForever` is what holds it open: the server is a `Resource`, so the shutdown that cancels this fiber
    * on SIGTERM or SIGINT is also what closes the listener, and closing the listener is what drains the
    * requests still in flight.
    */
  private def serve(
      config: ClusterServiceConfig,
      principals: Resource[IO, kui.security.PrincipalCodec[IO]],
      logger: StructuredLogger[IO]
  ): IO[ExitCode] =
    server(config, principals, logger).useForever.as(ExitCode.Success)

  private def server(
      config: ClusterServiceConfig,
      principals: Resource[IO, kui.security.PrincipalCodec[IO]],
      logger: StructuredLogger[IO]
  ): Resource[IO, KuiServer.ServerBinding] =
    for {
      startedAt <- Resource.eval(Clock[IO].realTimeInstant)
      _ <- Resource.eval(ClusterWiring.startupLog[IO](logger, config, startedAt))
      codec <- principals
      telemetry <- Telemetry.resource[IO](ClusterApi.ServiceName, config.telemetry)
      cluster <- ClusterWiring.make[IO](telemetry, codec, logger)
      binding <- drainLogged(
        KuiServer.resource[IO](
          config.server,
          cluster.routes,
          cluster.interceptors,
          logger,
          DrainTimeout
        ),
        logger
      )
    } yield binding

  /** Wraps the server so that stopping it says how long the drain took.
    *
    * The number matters when a deployment is being tuned: a drain that regularly runs to the full timeout is
    * a service holding requests open longer than the orchestrator's grace period, and the only way anyone
    * finds that out is if the process says so on the way down.
    */
  private def drainLogged(
      server: Resource[IO, KuiServer.ServerBinding],
      logger: StructuredLogger[IO]
  ): Resource[IO, KuiServer.ServerBinding] =
    Resource
      .makeCase(server.allocatedCase) { case ((_, release), exit) =>
        for {
          began <- Clock[IO].monotonic
          _ <- release(exit)
          ended <- Clock[IO].monotonic
          drain = ended - began
          _ <- logger.info(Map("drainMs" -> drain.toMillis.toString))(
            s"${ClusterApi.ServiceName} stopped after draining for ${drain.toMillis}ms"
          )
        } yield ()
      }
      .map(_._1)

  /** Every problem at once, on standard error, with a non-zero exit.
    *
    * All of them rather than the first, because fixing configuration one message per restart is miserable and
    * slow. Standard error rather than the logger, because a configuration failure can happen before there is
    * a logger — and because an operator watching `docker logs` needs to see it whatever the configured log
    * format was going to be.
    */
  private def configProblems(errors: ConfigErrors): String =
    s"${ClusterApi.ServiceName} cannot start; the configuration has problems:\n${errors.render}"

  private def refuseToStart(message: String): IO[ExitCode] =
    IO.consoleForIO.errorln(message).as(ExitCode.Error)
}
