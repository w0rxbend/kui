package kui.cluster.app

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Resource}
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cluster.api.{ClusterApi, PrincipalVerification}
import kui.cluster.application.{CapabilityReportUseCase, PingUseCase}
import kui.cluster.domain.ClockPort
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.kernel.ClusterId
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
    * Nothing here contacts anything. The service has no upstream in M0 — M1 adds the Kafka `AdminClient` and
    * with it a readiness check that can fail — so `make` cannot fail for an external reason, and the resource
    * it returns holds one thing that has to be released: the background fiber behind the unsigned codec's
    * warning, which is owned by whoever built the codec and passed in here already running.
    *
    * ==It takes no configuration, and that is temporary==
    *
    * There is nothing in `ClusterServiceConfig` this layer reads in M0: the listener's settings belong to
    * `KuiServer`, the telemetry settings were spent building the `Telemetry` handed in here, and the signing
    * keys were spent building the codec. The parameter returns in M1 with `kui.clusters[]`, which is the
    * first setting the wiring itself has to act on. It is absent rather than ignored because `-Werror` with
    * `-Wunused` refuses an unused parameter — and rightly: a parameter that exists only to be dropped tells a
    * reader something false about what this function depends on.
    *
    * @param principals
    *   built by the caller rather than here, because whether this deployment is allowed to run without
    *   signing keys is a decision about the *process* (see [[PrincipalCodecs]]), and the all-in-one hands in
    *   a different codec entirely.
    */
  def make[F[_]: {Async, Parallel}](
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterServer[F]] = {
    val readiness = readinessChecks[F]
    val capabilities = capabilityUseCase[F]

    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(ClusterApi.interceptors[F](telemetry, rejections, logger))
    } yield ClusterServer(
      routes = ClusterApi.routes[F](
        PingUseCase.make[F](clock[F], logger),
        capabilities,
        readiness,
        principals,
        rejections,
        logger
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = ClusterApi.capabilityDocument[F](capabilities, logger)
    )
  }

  /** What this service checks before it says it can serve.
    *
    * One check, and it is not a tautology worth removing: `/health/ready` has to answer with a report, and a
    * report with no checks in it reads like a bug, while one naming `process` says plainly that this service
    * depends on nothing else to serve. M1 adds a broker reachability check per configured cluster.
    */
  def readinessChecks[F[_]: cats.Applicative]: List[ReadinessCheck[F]] =
    List(ReadinessCheck.always[F]("process"))

  /** The capability use case, over the clusters this deployment is configured with.
    *
    * That set is empty in M0 and the report is therefore empty, which is correct rather than a placeholder: a
    * KUI started before anyone has configured a cluster genuinely has no cluster-scoped capability to report,
    * and the gateway must render that as "nothing configured yet" and not as an outage. `kui.clusters[]`
    * becomes a real section in M1 and this is the line that will read it.
    */
  def capabilityUseCase[F[_]: cats.Applicative]: CapabilityReportUseCase[F] =
    CapabilityReportUseCase.constant[F](ConfiguredClusters)

  /** The clusters this deployment knows about. Empty until M1 reads `kui.clusters[]`. */
  val ConfiguredClusters: Set[ClusterId] = Set.empty

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
        "startedAt" -> at.toString
      )
    )(
      s"starting ${ClusterApi.ServiceName} ${ClusterBuildInfo.version} " +
        s"(${ClusterBuildInfo.gitCommitShort})"
    )
}
