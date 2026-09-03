package kui.allinone

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.api.ClusterApi
import fs2.io.file.Files

import kui.cluster.app.{ClusterServiceConfig, ClusterWiring}
import kui.gateway.api.InfoRoutes
import kui.gateway.app.{GatewayServer, GatewayWiring}
import kui.gateway.application.client.{ServiceClient, ServiceClients}
import kui.kernel.ServiceId
import kui.observability.Telemetry
import kui.security.PrincipalCodec

/** The all-in-one deployment's composition root (ADR-005, ADR-010).
  *
  * One process, one `IO` runtime, one otel4s provider, one Netty listener, and every KUI service inside it.
  * It is the shape a laptop and a small installation run, and it exists so that "what works locally works in
  * production" is a fact about the code rather than a hope: the gateway's own composition root is reused
  * whole, and the only thing that differs is where a service call lands.
  *
  * ==The wiring order, because it is easy to get wrong==
  *
  * {{{
  * telemetry ──▶ logger ──▶ config ──▶ PrincipalCodec.inProcess
  *                                          │
  *                                          ├──▶ ClusterWiring.make ──▶ InProcessServiceClient ──┐
  *                                          │                                                    │
  *                                          └────────────────────────────────────────────────────┤
  *                                                                                               ▼
  *                                                                                       ServiceClients
  *                                                                                               │
  *                                                                                               ▼
  *                                                             GatewayWiring.over (registry, poller,
  *                                                             circuit feed, proxy routes, documentation)
  *                                                                                               │
  *                                                                                               ▼
  *                                                                                     one Netty listener
  * }}}
  *
  * Two orderings in there are load bearing. The codec is built before any service, because both the caller
  * and the callee must hold the *same* one — a service handed a second instance would be verifying tokens
  * against claims it agreed with only by coincidence. And every service is wired before the gateway, because
  * the gateway's readiness poller starts polling as soon as it is constructed, and it can only poll something
  * that already exists.
  *
  * ==What this file does not do==
  *
  * It starts no listener, exactly like the two composition roots it composes. `AllInOne` binds the port. That
  * is what lets a suite start the whole product, ask it questions and stop it again in milliseconds, without
  * a socket and without a port to collide over — which is why `AllInOneWiringSuite` can afford to do it three
  * times in a row.
  */
object AllInOneWiring {

  /** The exact sentence ADR-005 requires when a configuration carries signing keys this shape will not use.
    *
    * It names the key so it can be searched for, and it says what to do about it, because the operator most
    * likely to see it is one who pointed the all-in-one image at the Compose configuration file — where those
    * keys are genuinely needed by the *other* deployment shape and genuinely useless here.
    */
  val IgnoredPrincipalKeys: String =
    "kui.gateway.principalKeys is ignored in all-in-one mode (in-process principal). Nothing is " +
      "signed because nothing leaves this process; the keys matter only when the gateway and the " +
      "services run as separate containers. Remove them, or run the distributed deployment."

  /** The same, for upstream addresses. A separate sentence rather than one combined warning, because the two
    * keys are set for different reasons and an operator may well have exactly one of them.
    */
  val IgnoredServiceUrls: String =
    "kui.gateway.services is ignored in all-in-one mode. Every service runs inside this process and is " +
      "called in memory, so no address is dialled and no container has to be reachable. Remove the " +
      "section, or run the distributed deployment."

  /** Builds the whole product, with no listener started.
    *
    * @param config
    *   the one loaded configuration, narrowed to what this shape reads
    * @param telemetry
    *   the single otel4s provider. One provider and not one per service, so a browser request produces one
    *   trace containing the gateway's span and the service's span in the same tree — the shape a developer
    *   will also see in production, learned here without Docker.
    * @param logger
    *   the process logger. `service.name` stays distinct per service on the log lines the services themselves
    *   write, so filtering by service works identically in both deployment shapes.
    */
  def resource[F[_]: {Async, Parallel, Files}](
      config: AllInOneConfig,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, GatewayServer[F]] =
    for {
      _ <- Resource.eval(warnAboutIgnoredKeys[F](logger, config))
      principals = PrincipalCodec.inProcess[F]
      // The cluster service's own configuration. The all-in-one does not yet read `kui.clusters[]` or
      // `kui.store.*` - CFGOP-006 adds them to `AllInOneConfig` - so it starts with no configured cluster
      // and no metadata store, which is the shape the fault-isolation suites exercise.
      clients <- services[F](ClusterServiceConfig.Default, telemetry, principals, logger)
      gateway <- GatewayWiring.over[F](
        config.gatewayView,
        telemetry,
        logger,
        Resource.pure[F, ServiceClients[F]](clients)
      )
    } yield gateway

  /** The services this build contains, in the order they were added.
    *
    * It is written out rather than derived from the wiring because the startup log has to name them before
    * anything has been constructed, and because it is the list a reader checks against `ROADMAP.md` to see
    * which milestone's services are actually in this binary. [[services]] must agree with it, and
    * `AllInOneWiringSuite` asserts that it does rather than leaving the two to drift.
    */
  val Services: List[ServiceId] = List(ClusterApi.Id)

  /** Every KUI service, wired in this process and reachable in memory.
    *
    * This list is the one place all-in-one grows as services arrive: M1 adds the topic service, M2 the schema
    * service, and each is three lines — call its `<Name>Wiring.make`, turn the result into a client, add it
    * here. Nothing else in this file or in the gateway changes, which is the property ADR-005 was written to
    * buy.
    */
  def services[F[_]: {Async, Parallel, Files}](
      cluster: ClusterServiceConfig,
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F]
  ): Resource[F, ServiceClients[F]] =
    ClusterWiring
      .make[F](cluster, telemetry, principals, logger)
      .map(cluster =>
        ServiceClients.of[F](
          List[ServiceClient[F]](
            InProcessServiceClient.make[F](
              ClusterApi.Id,
              cluster.routes,
              cluster.interceptors,
              principals
            )
          )
        )
      )

  /** Says out loud which configured keys this deployment shape is not going to act on.
    *
    * `WARN` and not `DEBUG`, for the same reason the gateway warns about insecure cookies: it is the line
    * that explains why a setting an operator deliberately wrote had no effect, and they must see it without
    * having had to turn on verbose logging first in order to suspect it.
    */
  def warnAboutIgnoredKeys[F[_]: cats.Applicative](
      logger: StructuredLogger[F],
      config: AllInOneConfig
  ): F[Unit] =
    logger.warn(IgnoredPrincipalKeys).whenA(config.hasIgnoredPrincipalKeys) *>
      logger.warn(IgnoredServiceUrls).whenA(config.hasIgnoredServiceUrls)

  /** The one INFO line this process writes as it starts.
    *
    * It reports the deployment shape as well as the build, because the first question anyone debugging a KUI
    * installation has to answer is which of the two shapes they are looking at, and the answer belongs on the
    * first line of the log rather than in whatever the reporter remembers about how they started it.
    */
  def startupLog[F[_]](
      logger: StructuredLogger[F],
      config: AllInOneConfig,
      at: Instant
  ): F[Unit] = {
    val services = Services.map(_.value)

    logger.info(
      Map(
        "service" -> AllInOne.ServiceName,
        "deployment" -> "all-in-one",
        "host" -> config.server.host.value,
        "port" -> config.server.port.value.toString,
        "basePath" -> config.server.basePath,
        "logFormat" -> config.telemetry.logFormat.wire,
        "services" -> services.sorted.mkString(","),
        "version" -> InfoRoutes.buildInfo.version,
        "gitCommit" -> InfoRoutes.buildInfo.gitCommitShort,
        "gitDirty" -> InfoRoutes.buildInfo.gitDirty.toString,
        "builtAt" -> InfoRoutes.buildInfo.builtAt.toString,
        "startedAt" -> at.toString
      )
    )(
      s"starting ${AllInOne.ServiceName} ${InfoRoutes.buildInfo.version} " +
        s"(${InfoRoutes.buildInfo.gitCommitShort}) with ${services.size} in-process service(s)"
    )
  }
}
