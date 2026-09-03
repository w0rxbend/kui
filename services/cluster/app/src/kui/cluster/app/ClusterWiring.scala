package kui.cluster.app

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Resource}
import fs2.io.file.Files
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cluster.api.{ClusterApi, PrincipalVerification}
import kui.cluster.domain.ClockPort
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.observability.Telemetry
import kui.security.PrincipalCodec

/** Everything the cluster service needs in order to be served, with no listener started.
  *
  * Stopping one step short of a running server is ADR-010's exact requirement, and it is what lets the
  * all-in-one deployment (AIO-001) reuse this file unchanged: it takes these routes, adds the gateway's and
  * every other service's, and starts a single listener over the lot. If `make` bound a port, the all-in-one
  * would have to either run twelve servers in one process or reimplement the assembly — and the moment it
  * reimplemented it, the two deployment shapes would start to drift.
  *
  * @param routes
  *   the endpoints, in match order, without the deployment's base path — that is `KuiServer`'s job, applied
  *   once over whatever list it is finally given
  * @param interceptors
  *   the cross-cutting chain, outermost first
  * @param readiness
  *   the checks behind `/health/ready`, exposed so a composition root that adds upstreams can add their
  *   checks too
  * @param capabilities
  *   this service's capability document, recomputed each time it is asked for
  */
final case class ClusterServer[F[_]](
    routes: List[ServerEndpoint[Fs2Streams[F], F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The cluster service's composition root (ADR-010).
  *
  * It is the only place in the service that constructs anything concrete. Every layer below takes its
  * collaborators as parameters, which is what makes them testable with hand-written fakes rather than with a
  * mocking framework, and what makes this one file the answer to "what is actually wired to what".
  */
object ClusterWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.cluster"

  /** Builds everything except the listener.
    *
    * ==What it does contact==
    *
    * M0's version contacted nothing. This one does: it opens the metadata store's Kafka clients, creates or
    * validates the store topics and replays `__kui_config` to its end offset before it returns
    * ([[ClusterBootstrap]] holds the ordering and the reasoning). That is the point at which a
    * misconfiguration becomes visible, and it is deliberately the point *before* the listener binds — a
    * service that answered while it was still reading its own configuration would report an empty cluster
    * list that is indistinguishable from a KUI nobody has configured.
    *
    * It still binds no port, starts nothing outside the returned `Resource`, and is reusable unchanged by the
    * all-in-one deployment, which is ADR-010's requirement and the reason `make` stops here.
    *
    * @param config
    *   the process's slice of the loaded configuration. `kui.clusters[]` is the registry's static base and
    *   `kui.store.*` decides whether there is a metadata store at all
    * @param principals
    *   built by the caller rather than here, because whether this deployment is allowed to run without
    *   signing keys is a decision about the *process* (see [[PrincipalCodecs]]), and the all-in-one hands in
    *   a different codec entirely.
    */
  def make[F[_]: {Async, Parallel, Files}](
      config: ClusterServiceConfig,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterServer[F]] = {
    // The store's own components ask for a logger through log4cats' factory rather than taking one as a
    // parameter, so the process's single logger is published as that factory here. Two logging paths in one
    // process is how half the lines end up in a different format from the other half.
    given LoggerFactory[F] = AppLoggerFactory.of(logger)

    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(ClusterApi.interceptors[F](telemetry, rejections, logger))
      bootstrapped <- ClusterBootstrap.resource[F](config.clusters, config.store, telemetry, logger)
      readiness = ClusterBootstrap.readiness[F](bootstrapped)
    } yield ClusterServer(
      routes = ClusterApi.routes[F](
        bootstrapped.registry,
        bootstrapped.topology,
        bootstrapped.brokers,
        bootstrapped.write,
        bootstrapped.capabilities,
        readiness,
        principals,
        rejections,
        telemetry,
        logger
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = ClusterApi.capabilityDocument[F](bootstrapped.capabilities, logger)
    )
  }

  /** Which metadata store this deployment has: `kafka`, `file`, or none at all. */
  def storeModeOf(config: ClusterServiceConfig): String =
    if config.store.kafka.isDefined then "kafka"
    else if config.store.dir.isDefined then "file"
    else "none"

  /** The domain's clock port, over the effect's own clock. */
  def clock[F[_]: Clock]: ClockPort[F] = new ClockPort[F] {
    def now: F[Instant] = Clock[F].realTimeInstant
  }

  /** The one INFO line this process writes as it starts.
    *
    * The build fields are here for one situation, and it is a common one: someone has two log files and is
    * trying to work out which of three containers is running the old build. A version and a commit on the
    * first line of the log answers that without anyone having to exec into anything.
    */
  def startupLog[F[_]](
      logger: StructuredLogger[F],
      config: ClusterServiceConfig,
      at: Instant
  ): F[Unit] =
    logger.info(
      Map(
        "service" -> ClusterApi.ServiceName,
        "host" -> config.server.host.value,
        "port" -> config.server.port.value.toString,
        "basePath" -> config.server.basePath,
        "logFormat" -> config.telemetry.logFormat.wire,
        "version" -> ClusterBuildInfo.version,
        "gitCommit" -> ClusterBuildInfo.gitCommitShort,
        "gitDirty" -> ClusterBuildInfo.gitDirty.toString,
        "builtAt" -> ClusterBuildInfo.builtAt,
        "startedAt" -> at.toString,
        // Which store this process is running against, so that "why is my cluster list empty" can be
        // answered from the first line of the log rather than from the configuration file.
        "storeMode" -> storeModeOf(config)
      )
    )(
      s"starting ${ClusterApi.ServiceName} ${ClusterBuildInfo.version} " +
        s"(${ClusterBuildInfo.gitCommitShort})"
    )
}
