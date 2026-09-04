package kui.http

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.{Clock, Resource}
import cats.effect.{ExitCode, IO}
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.config.{
  ConfigErrors,
  KuiConfig,
  KuiConfigSource,
  PrincipalKeyConfig,
  ServerConfig,
  TelemetryConfig
}
import kui.http.principal.ProcessPrincipalCodec
import kui.observability.{KuiLogger, LogbackSelection, Telemetry}
import kui.security.PrincipalCodec

/** The startup sequence every KUI service process runs, written once.
  *
  * ==Why this exists==
  *
  * Four services run as their own process, and each of their `Main`s has the same job: load the
  * configuration, choose a log format, build a logger, build the principal codec, start telemetry, wire the
  * service, bind the port, and drain on the way down. Written four times, those four copies drift — one gets
  * a longer drain timeout, one forgets to print every configuration problem at once, one starts anyway when
  * the signing keys are missing. The last of those is a security hole rather than an inconsistency, which is
  * why the sequence is a shared thing with one implementation rather than a convention four files try to
  * follow.
  *
  * A service's own `Main` is then a name, a config slice and a wiring function, and it is short enough to
  * read at a glance.
  *
  * ==The sequence, and why each failure mode is what it is==
  *
  *   1. **Load and validate the configuration.** A bad configuration stops the process with every problem
  *      listed at once and a non-zero exit code. Starting on a silently defaulted value is how a deployment
  *      ends up listening on the wrong port for a week.
  *   1. **Choose the log format**, before any logger exists, because Logback configures itself on first use
  *      ([[kui.observability.LogbackSelection]]).
  *   1. **Build the principal codec.** No signing keys and no development escape hatch stops the process
  *      ([[ProcessPrincipalCodec]]). A service that trusts an unsigned identity header is a service anyone
  *      who can reach its port can impersonate a user to.
  *   1. **Start telemetry.** This one never stops the process: an unreachable collector is a monitoring
  *      outage, and turning it into a KUI outage would mean a Friday-evening collector restart takes the
  *      product down.
  *   1. **Wire the service**, which is the part each service supplies.
  *   1. **Bind the port.** A port already in use stops the process with a message naming the port. KUI never
  *      quietly moves to another one: a server nothing can reach whose health check passes is far worse to
  *      diagnose than one that refused to start and said why.
  */
object ServiceMain {

  /** How long a stopping process waits for the requests already in flight.
    *
    * Fifteen seconds, so a rolling deployment finishes its responses rather than cutting people off
    * mid-answer. It is longer than [[KuiServer]]'s own default because a service call arrives through the
    * gateway, which is itself waiting on it, and dropping it strands two processes rather than one.
    */
  val DrainTimeout: FiniteDuration = 15.seconds

  /** The parts of a loaded configuration this shell itself reads.
    *
    * A service narrows `KuiConfig` to its own slice for its own settings; these four are the ones the startup
    * sequence needs whatever service it is starting, so they are named here instead of being reached for
    * through each service's config type.
    */
  final case class ProcessConfig(
      server: ServerConfig,
      telemetry: TelemetryConfig,
      principalKeys: List[PrincipalKeyConfig]
  )

  object ProcessConfig {

    /** The keys are read from `kui.gateway.principalKeys` and that is not a mistake. They look like a gateway
      * setting and they are the *shared* key set of one deployment: the gateway signs with the newest key
      * whose `notBefore` has passed and every service accepts any key in the set, which is what makes a
      * rotation a rolling change rather than an outage.
      */
    def from(config: KuiConfig): ProcessConfig =
      ProcessConfig(config.server, config.telemetry, config.gateway.principalKeys)
  }

  /** The two things a wired service hands to the listener.
    *
    * Every service already has a richer type of its own — `ClusterServer`, `TopicServer` and the rest, which
    * also carry readiness checks and a capability document. This is the narrow pair the process shell
    * actually needs, so that `libs/http` does not have to see any of those service types (rule A5 forbids it
    * anyway: a library may not depend on a service).
    */
  final case class Serving(
      routes: List[ServerEndpoint[Fs2Streams[IO], IO]],
      interceptors: List[Interceptor[IO]]
  )

  /** What a service supplies: how to turn a loaded configuration, a telemetry handle, a verified principal
    * codec and a logger into the routes and interceptors to serve.
    *
    * It returns a `Resource` because everything a service owns with a lifetime — the admin-client pool, the
    * background scrapes, the store's consumer — is one, and releasing this resource is what stops them in the
    * reverse of the order they started.
    */
  trait Wiring {
    def apply(
        config: KuiConfig,
        telemetry: Telemetry[IO],
        principals: PrincipalCodec[IO],
        logger: StructuredLogger[IO]
    ): Resource[IO, Serving]
  }

  /** Runs a service until the process is asked to stop.
    *
    * `useForever` is what holds it open: the server is a `Resource`, so the shutdown that cancels this fiber
    * on SIGTERM or SIGINT is also what closes the listener, and closing the listener is what drains the
    * requests still in flight.
    *
    * @param serviceName
    *   the name in the log, in the telemetry resource and in every startup failure message. It is the word an
    *   operator greps for.
    * @param args
    *   the process's command line, so `--kui.server.port=9090` works the same way in every service.
    */
  def run(serviceName: String, args: List[String], wiring: Wiring): IO[ExitCode] =
    KuiConfigSource.load[IO](args, files = Nil).flatMap {
      case Left(errors) => refuseToStart(configProblems(serviceName, errors))
      case Right(loaded) => start(serviceName, loaded, wiring)
    }

  private def start(serviceName: String, loaded: KuiConfig, wiring: Wiring): IO[ExitCode] = {
    val process = ProcessConfig.from(loaded)

    for {
      _ <- LogbackSelection[IO](process.telemetry.logFormat)
      logger <- KuiLogger.make[IO](serviceName)
      environment <- IO.delay(sys.env)
      exit <- ProcessPrincipalCodec.make[IO](process.principalKeys, environment, logger) match {
        case Left(problem) => refuseToStart(s"$serviceName cannot start; $problem")
        case Right(codec) =>
          server(serviceName, process, loaded, codec, wiring, logger).useForever.as(ExitCode.Success)
      }
    } yield exit
  }

  private def server(
      serviceName: String,
      process: ProcessConfig,
      loaded: KuiConfig,
      principals: Resource[IO, PrincipalCodec[IO]],
      wiring: Wiring,
      logger: StructuredLogger[IO]
  ): Resource[IO, KuiServer.ServerBinding] =
    for {
      codec <- principals
      telemetry <- Telemetry.resource[IO](serviceName, process.telemetry)
      serving <- wiring(loaded, telemetry, codec, logger)
      binding <- drainLogged(
        serviceName,
        KuiServer.resource[IO](process.server, serving.routes, serving.interceptors, logger, DrainTimeout),
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
      serviceName: String,
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
            s"$serviceName stopped after draining for ${drain.toMillis}ms"
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
  def configProblems(serviceName: String, errors: ConfigErrors): String =
    s"$serviceName cannot start; the configuration has problems:\n${errors.render}"

  def refuseToStart(message: String): IO[ExitCode] =
    IO.consoleForIO.errorln(message).as(ExitCode.Error)
}
