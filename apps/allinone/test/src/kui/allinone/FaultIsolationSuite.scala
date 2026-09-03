package kui.allinone

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.IO
import cats.effect.kernel.Resource

import kui.cluster.api.{ClusterApi, PrincipalVerification}
import kui.cluster.application.{CapabilityReport, CapabilityReportUseCase, PingUseCase}
import kui.cluster.domain.ClockPort
import kui.contracts.capability.CapabilityKey
import kui.gateway.application.capability.{
  CapabilityRegistry,
  CapabilitySignals,
  ReadinessPoller,
  RegistryConfig
}
import kui.gateway.application.client.{ServiceClient, ServiceClients}
import kui.http.health.ReadinessCheck
import kui.kernel.error.InfrastructureError
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** That a broken service degrades the product rather than breaking it — and that this is reproducible on a
  * laptop without Docker.
  *
  * ADR-005 is explicit that the all-in-one process is a single failure domain, and nobody should read this
  * suite and conclude otherwise: one JVM dies and everything in it dies. What the ADR does promise is that
  * fault isolation still holds at the level of the *code* — a use case that fails is a `KuiError`, the
  * registry records it, and the UI dims one feature instead of blacking out — and that a developer can
  * exercise that degraded experience in seconds instead of starting containers and stopping one.
  *
  * That promise is only worth what the evidence for it is worth, because the failure it prevents is a quiet
  * one. A path that let a service's `InfrastructureError` escape as a gateway 500 would look perfect in every
  * happy path and would take the whole UI down the first time a broker went away.
  */
final class FaultIsolationSuite extends KuiIOSuite {

  /** The poller jitters its first poll across the interval, so a short interval is what makes the suite
    * quick, and a generous ceiling is what stops it being flaky on a loaded machine.
    */
  private val PollInterval: FiniteDuration = 20.millis
  private val SettleWithin: FiniteDuration = 15.seconds

  private val ClusterKey: CapabilityKey = CapabilityKey(ClusterApi.Id, None)

  /** The gateway's capability pipeline over one in-process service: registry, signals and readiness poller,
    * assembled exactly as `GatewayWiring.over` assembles them.
    *
    * This is the part of the gateway the assertion is about, and building only it — rather than the whole
    * route list — is what lets the suite read the registry's verdict directly instead of parsing it back out
    * of an HTTP response. Every component in it is the production one.
    */
  private def registryOver(
      service: Resource[IO, ServiceClient[IO]]
  ): Resource[IO, CapabilityRegistry[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      client <- service
      clients = ServiceClients.of[IO](List(client))
      registry <- CapabilityRegistry.resource[IO](RegistryConfig.Default, Telemetry.noop[IO], logger)
      signals <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, clients.all.map(_.service))
      )
      _ <- ReadinessPoller.resource[IO](clients, signals, PollInterval, logger)
    } yield registry

  /** The cluster service, in process, either working or with its infrastructure gone.
    *
    * It is assembled from `ClusterApi.routes` rather than through `ClusterWiring.make`, because the failure
    * has to be injected into a use case and a composition root that let a caller swap its use cases would not
    * be a composition root. Everything else — the routes, the interceptors, the error mapping, the in-process
    * client — is the production assembly.
    */
  private def clusterService(healthy: Boolean): Resource[IO, ServiceClient[IO]] = {
    val principals = PrincipalCodec.inProcess[IO]
    val telemetry = Telemetry.noop[IO]

    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      meter <- Resource.eval(telemetry.meter(ClusterWiringInstrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[IO](meter))
      interceptors <- Resource.eval(ClusterApi.interceptors[IO](telemetry, rejections, logger))
    } yield InProcessServiceClient.make[IO](
      ClusterApi.Id,
      ClusterApi.routes[IO](
        PingUseCase.make[IO](realClock, logger),
        if healthy then workingCapabilities else brokenCapabilities,
        List(if healthy then ReadinessCheck.always[IO]("process") else brokenReadiness),
        principals,
        rejections,
        logger
      ),
      interceptors,
      principals
    )
  }

  /** Waits until the registry reports the service as anything other than its starting state, then answers
    * with the status string the browser would see.
    *
    * Polling with a deadline rather than sleeping a fixed time: the poller's first run is jittered, so a
    * fixed sleep would be either slow or flaky, and there is no point pretending otherwise.
    */
  private def settledStatusOf(registry: CapabilityRegistry[IO], expected: String): IO[String] = {
    val read: IO[String] =
      registry.snapshot.map(_.get(ClusterKey).fold("absent")(_.status))

    read
      .iterateUntil(_ == expected)
      .timeoutTo(SettleWithin, read)
  }

  test("anInfrastructureErrorFromAnInProcessUseCaseMarksTheCapabilityUnavailable") {
    registryOver(clusterService(healthy = false)).use { registry =>
      settledStatusOf(registry, "unavailable").map(status =>
        assertEquals(
          status,
          "unavailable",
          "a service whose infrastructure has gone away must be reported as unavailable, so the " +
            "browser dims its feature instead of showing an error page"
        )
      )
    }
  }

  test("aHealthyInProcessServiceIsReportedAvailable") {
    // The control case. Without it, an assertion that a broken service reports `unavailable` would also
    // pass against a registry that reported everything as unavailable all the time.
    registryOver(clusterService(healthy = true)).use { registry =>
      settledStatusOf(registry, "available").map(status => assertEquals(status, "available"))
    }
  }

  test("aFailingServiceDoesNotAffectTheGatewaysOwnEndpoints") {
    // The gateway's own routes are assembled without consulting any service, and its readiness depends on
    // no upstream. That is what keeps `/api/v1/info` and the capability endpoints answering while a service
    // is down — which is precisely when a browser most needs them, because the capability document is how
    // the UI finds out what to dim.
    val product =
      Resource.eval(FakeStructuredLogger[IO]).flatMap { logger =>
        clusterService(healthy = false).flatMap(client =>
          kui.gateway.app.GatewayWiring
            .over[IO](
              AllInOneConfig.Default.gatewayView,
              Telemetry.noop[IO],
              logger,
              Resource.pure[IO, ServiceClients[IO]](ServiceClients.of[IO](List(client)))
            )
        )
      }

    product.use { gateway =>
      val paths =
        gateway.routes.map(route =>
          kui.gateway.api.routing.ContractRouting.pathSegments(route.input).mkString("/", "/", "")
        )

      IO {
        assert(paths.contains("/api/v1/info"), s"the gateway's build endpoint stopped being served: $paths")
        assert(
          paths.contains("/api/v1/capabilities"),
          s"the capability endpoints stopped being served: $paths"
        )
        assertEquals(
          gateway.readiness.map(_.name),
          List("process"),
          "the gateway's readiness must not depend on a service, or one outage becomes a total one"
        )
      }
    }
  }

  private val ClusterWiringInstrumentation: String = "kui.cluster"

  private def realClock: ClockPort[IO] = new ClockPort[IO] {
    def now: IO[java.time.Instant] = IO.realTimeInstant
  }

  private def workingCapabilities: CapabilityReportUseCase[IO] =
    CapabilityReportUseCase.constant[IO](Set.empty)

  /** A use case whose infrastructure has gone away — in M1 terms, a broker that stopped answering.
    *
    * It fails rather than returning an empty report, because those are different things and the gateway has
    * to tell them apart: an empty report is "this deployment has no clusters configured", and a failure is
    * "I cannot find out". Only the second one dims a feature.
    */
  private def brokenCapabilities: CapabilityReportUseCase[IO] =
    new CapabilityReportUseCase[IO] {
      def report: IO[CapabilityReport] =
        IO.raiseError(new RuntimeException(InfrastructureError.Unreachable("kafka", "no answer").message))
    }

  private def brokenReadiness: ReadinessCheck[IO] =
    ReadinessCheck.boolean[IO]("kafka", IO.pure(false), "the broker did not answer")
}
