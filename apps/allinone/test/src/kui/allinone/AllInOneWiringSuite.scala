package kui.allinone

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*

import kui.cluster.app.ClusterServiceConfig
import kui.config.{
  AuthConfig,
  ConsumersConfig,
  GatewayConfig,
  PrincipalKeyConfig,
  SafeUrl,
  ServerConfig,
  StoreConfig,
  StreamingConfig,
  TopicsConfig,
  UpstreamServiceConfig
}
import kui.gateway.api.routing.ContractRouting
import kui.gateway.app.GatewayServer
import kui.http.KuiServer
import kui.kernel.{Host, Port, PositiveInt, Secret, ServiceId}
import kui.observability.Telemetry
import kui.security.rbac.RbacPolicy
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** That the whole product can be assembled, served and taken down again, repeatedly.
  *
  * The all-in-one process is what a developer runs on a laptop and what a small installation runs in
  * production, so the properties asserted here are the ones that make it usable rather than the ones that
  * make it clever: it starts with no configuration at all, it exposes exactly one door, it can be started and
  * stopped without leaking anything, and it says out loud which configured keys it is not going to obey.
  */
final class AllInOneWiringSuite extends KuiIOSuite {

  private val ephemeral: ServerConfig = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), "/")

  private def wire(
      config: AllInOneConfig = AllInOneConfig.Default
  ): Resource[IO, (GatewayServer[IO], FakeStructuredLogger[IO])] =
    Resource
      .eval(FakeStructuredLogger[IO])
      .flatMap(logger =>
        AllInOneWiring.resource[IO](config, Telemetry.noop[IO], logger).tupleRight(logger)
      )

  /** The public path of every route the process would serve, which is the honest answer to "what does this
    * listener expose".
    *
    * `showShort` is no use for this: Tapir prints an endpoint's *name* when it has one, and every KUI
    * endpoint is named. `ContractRouting.pathSegments` reads the fixed path segments straight off the input
    * description instead, which is the same function the gateway itself uses to rewrite `/internal/v1` into
    * `/api/v1`, so this sees exactly what the router will.
    *
    * Both inputs have to be read, and it is worth saying why. A prefix applied with `prependIn` — which is
    * how `BasePath.prefixAll` puts `/api/v1` in front of the shared health endpoints — lands on the
    * *security* input, while the endpoint's own path stays on the ordinary one. Reading only the second
    * would report the gateway's `/api/v1/health/live` as a bare `/health/live`, which is exactly the
    * unprefixed service path the next test asserts is absent, and the test would fail on its own blind spot.
    */
  private def servedPaths(gateway: GatewayServer[IO]): List[String] =
    gateway.routes.map { route =>
      val segments =
        ContractRouting.pathSegments(route.securityInput) ++ ContractRouting.pathSegments(route.input)
      segments.mkString("/", "/", "")
    }

  test("startsAndServesEveryGatewayAndProxiedRoute") {
    wire().use { (gateway, _) =>
      val paths = servedPaths(gateway)

      IO {
        assert(paths.contains("/api/v1/info"), s"the gateway's own routes are missing from $paths")
        assert(paths.contains("/api/v1/capabilities"), s"the capability routes are missing from $paths")
        assert(
          paths.contains("/api/v1/clusters"),
          s"the in-process cluster service's route was not proxied; served $paths"
        )
        assert(paths.contains("/api/v1/health/live"), s"the process's own probes are missing from $paths")
      }
    }
  }

  test("theWiredServiceListMatchesTheDeclaredOne") {
    // `AllInOneWiring.Services` is what the startup log names and what a reader checks against the roadmap.
    // Nothing forces it to agree with `services`, so this is what forces it.
    FakeStructuredLogger[IO].flatMap { logger =>
      AllInOneWiring
        .services[IO](
          ClusterServiceConfig.Default,
          clusters = Nil,
          topics = TopicsConfig.Default,
          consumers = ConsumersConfig.Default,
          streaming = StreamingConfig.Default,
          auth = AuthConfig.Default,
          rbac = RbacPolicy.Disabled,
          store = StoreConfig.Default,
          Telemetry.noop[IO],
          AllInOneFixture.principals,
          logger
        )
        .use(clients => IO(assertEquals(clients.all.map(_.service), AllInOneWiring.Services)))
    }
  }

  test("bindsExactlyOnePortAndMountsNoServiceRouteOnIt") {
    // "Services bind no listeners" is the ADR-005 requirement, and this is its checkable form. Counting
    // sockets would test the operating system; what actually has to hold is that the one listener this
    // process starts serves the gateway's public API and nothing a service publishes for the gateway's
    // private use. A service route that leaked onto this list would be reachable from a browser with no
    // signed principal in front of it — which is the outcome `ARCHITECTURE.md` §14 forbids.
    wire().use { (gateway, _) =>
      val paths = servedPaths(gateway)

      IO {
        assert(
          !paths.exists(_.startsWith("/internal/")),
          s"a service's internal routes were mounted on the public listener: $paths"
        )
        assertEquals(
          paths.count(_ == "/health/live"),
          0,
          "a service's unprefixed health path must not be mounted; only the gateway's /api/v1 one is"
        )
      }
    }
  }

  test("isResourceSafe") {
    // Three complete start-and-stop cycles, each binding a real listener on an ephemeral port. A resource
    // that leaked a fiber, a background poller or a bound socket would fail the second or third round
    // rather than the first, which is why once is not enough.
    def cycle: IO[Int] =
      FakeStructuredLogger[IO].flatMap { logger =>
        AllInOneWiring
          .resource[IO](AllInOneConfig.Default, Telemetry.noop[IO], logger)
          .flatMap(gateway =>
            KuiServer.resource[IO](ephemeral, gateway.routes, gateway.interceptors, logger, 10.millis)
          )
          .use(binding => IO.pure(binding.port))
      }

    cycle.replicateA(3).timeout(60.seconds).map { ports =>
      assert(ports.forall(_ > 0), s"every cycle must bind a real port, got $ports")
    }
  }

  test("warnsThatPrincipalKeysAreIgnored") {
    wire(configuredForTheOtherDeploymentShape).use { (_, logger) =>
      logger.entries.map { entries =>
        val warnings = entries.filter(_.level == "warn").map(_.message)
        assert(
          warnings.exists(_.startsWith("kui.gateway.principalKeys is ignored in all-in-one mode")),
          s"the ignored signing keys must be reported; warnings were $warnings"
        )
        assert(
          warnings.exists(_.startsWith("kui.gateway.services is ignored in all-in-one mode")),
          s"the ignored upstream addresses must be reported; warnings were $warnings"
        )
      }
    }
  }

  test("saysNothingAboutKeysThatWereNotConfigured") {
    // The other half of the previous case. A warning that appears whatever the configuration says is a
    // warning everybody learns to scroll past, and then the one that mattered goes unread too.
    wire().use { (_, logger) =>
      logger.entries.map { entries =>
        val warnings = entries.filter(_.level == "warn").map(_.message)
        assert(
          !warnings.exists(_.contains("is ignored in all-in-one mode")),
          s"nothing was configured, so nothing should be reported as ignored; got $warnings"
        )
      }
    }
  }

  test("startupLogNamesTheDeploymentShapeAndTheServices") {
    // The first line of a KUI log has to answer "which of the two shapes am I looking at", because almost
    // every other question a reader has depends on the answer.
    FakeStructuredLogger[IO].flatMap { logger =>
      AllInOneWiring
        .startupLog[IO](logger, AllInOneConfig.Default, Instant.parse("2026-09-03T10:11:12Z"))
        .flatMap(_ => logger.entries)
        .map { entries =>
          val context = entries.headOption.map(_.context).getOrElse(Map.empty)
          assertEquals(context.get("deployment"), Some("all-in-one"))
          assertEquals(context.get("services"), Some("cluster,consumer,identity,message,schema,topic"))
        }
    }
  }

  /** A configuration written for the distributed deployment and handed to this one by mistake — which is
    * exactly what happens when someone points the all-in-one image at `deployment/compose/kui.yaml`.
    */
  private val configuredForTheOtherDeploymentShape: AllInOneConfig =
    AllInOneConfig.Default.copy(
      gateway = GatewayConfig.Default.copy(
        services = Map(
          ServiceId.unsafe("cluster") -> UpstreamServiceConfig(
            SafeUrl.unsafe("http://kui-cluster:8080"),
            10.seconds,
            PositiveInt.unsafe(32)
          )
        ),
        principalKeys = List(
          PrincipalKeyConfig("compose-1", Secret("a-key-long-enough-for-hs256-signing"), Instant.EPOCH)
        )
      )
    )
}
