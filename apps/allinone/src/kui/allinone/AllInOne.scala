package kui.allinone

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.Clock
import cats.effect.{ExitCode, IO, IOApp, Resource}

import kui.config.{ConfigErrors, KuiConfigSource}
import kui.http.KuiServer
import kui.observability.{KuiLogger, LogbackSelection, Telemetry}

/** The all-in-one process: the whole product on one port.
  *
  * `IO` appears here and nowhere else in this module (ADR-010). Everything beneath is written against an
  * abstract `F[_]`, which is what lets `AllInOneWiringSuite` start and stop the entire product three times in
  * a few milliseconds on a deterministic runtime.
  *
  * The program is five steps and each failure mode is deliberate:
  *
  *   1. **Load and validate the configuration.** A bad configuration stops the process with every problem
  *      listed at once and a non-zero exit code. Starting with a silently defaulted value is how a deployment
  *      ends up listening on the wrong port for a week (CFG-001). Unlike a single service, a *missing*
  *      configuration is fine here: this shape has nothing it must be told before it can serve, which is what
  *      makes `./mill apps.allinone.run` work on a fresh clone.
  *   1. **Choose the log format.** Before any logger exists, because Logback configures itself on first use
  *      (`LogbackSelection`).
  *   1. **Start telemetry.** One provider for the whole process. This step never stops the process: an
  *      unreachable collector is a monitoring outage, and turning it into a KUI outage would mean a
  *      Friday-evening collector restart takes the product down.
  *   1. **Wire the gateway and every service.** Nothing is contacted and nothing is required
  *      (`AllInOneWiring.resource`).
  *   1. **Bind the one port.** A port already in use stops the process with a message naming the port. KUI
  *      never quietly moves to another one: a server nothing can reach, whose health check passes, is far
  *      worse to diagnose than one that refused to start and said why.
  *
  * ==One listener, and only the gateway's==
  *
  * The services wired into this process bind nothing and are mounted on nothing. They are reachable only
  * through the gateway's proxied routes, which is precisely the rule a distributed deployment enforces with a
  * network policy. `curl localhost:8081/health/live` is therefore refused in both shapes, for the same
  * reason, and a habit learned locally stays correct in production.
  */
object AllInOne extends IOApp {

  /** The process's name, as it appears in `service.name` on every log line, span and metric this process
    * writes on its own behalf. The services inside it keep their own names on the lines they write, so a log
    * filter that works against the distributed deployment works against this one too.
    */
  val ServiceName: String = "kui-allinone"

  /** How long a stopping process waits for the requests already in flight.
    *
    * Fifteen seconds, matching `kui-cluster`'s own drain. A request being served here is being served by the
    * gateway *and* by a service at the same time, so cutting it off strands the same work two layers of the
    * product were in the middle of, and the number should not be the smaller of the two.
    */
  val DrainTimeout: FiniteDuration = 15.seconds

  def run(args: List[String]): IO[ExitCode] =
    KuiConfigSource.load[IO](args, files = Nil).flatMap {
      case Left(errors) => refuseToStart(configProblems(errors))
      case Right(loaded) => serve(AllInOneConfig.from(loaded))
    }

  /** Runs until the process is asked to stop.
    *
    * `useForever` is what holds it open: the server is a `Resource`, so the shutdown that cancels this fiber
    * on SIGTERM or SIGINT is also what closes the listener, and closing the listener is what drains the
    * requests still in flight.
    */
  private def serve(config: AllInOneConfig): IO[ExitCode] =
    server(config).useForever.as(ExitCode.Success)

  private def server(config: AllInOneConfig): Resource[IO, KuiServer.ServerBinding] =
    for {
      _ <- Resource.eval(LogbackSelection[IO](config.telemetry.logFormat))
      logger <- Resource.eval(KuiLogger.make[IO](ServiceName))
      startedAt <- Resource.eval(Clock[IO].realTimeInstant)
      telemetry <- Telemetry.resource[IO](ServiceName, config.telemetry)
      gateway <- AllInOneWiring.resource[IO](config, telemetry, logger)
      // Logged after wiring rather than before it, because the list of services this build actually
      // contains is a property of the wiring and not of the configuration — in this shape there is no
      // configured list to read, which is the whole point.
      _ <- Resource.eval(AllInOneWiring.startupLog[IO](logger, config, startedAt))
      binding <- KuiServer.resource[IO](
        config.server,
        gateway.routes,
        gateway.interceptors,
        logger,
        DrainTimeout
      )
    } yield binding

  /** Every problem at once, on standard error, with a non-zero exit.
    *
    * All of them rather than the first, because fixing configuration one message per restart is miserable and
    * slow. Standard error rather than the logger, because a configuration failure can happen before there is
    * a logger — and because an operator watching `docker logs` needs to see it whatever the configured log
    * format was going to be.
    */
  private def configProblems(errors: ConfigErrors): String =
    s"$ServiceName cannot start; the configuration has problems:\n${errors.render}"

  private def refuseToStart(message: String): IO[ExitCode] =
    IO.consoleForIO.errorln(message).as(ExitCode.Error)
}
