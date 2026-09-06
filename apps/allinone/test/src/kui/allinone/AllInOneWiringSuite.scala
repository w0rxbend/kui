package kui.allinone

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*

import kui.cluster.app.ClusterServiceConfig
import kui.config.{
  AuthConfig,
  ClusterConfig,
  ConsumersConfig,
  GatewayConfig,
  KuiConfig,
  MetricsConfig,
  MetricsSourceSettings,
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
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.{ClusterId, Host, Port, PositiveInt, Secret, ServiceId}
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
          metrics = MetricsConfig.Default,
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
          val expected = "cluster,consumer,identity,message,metrics,schema,topic"
          assertEquals(context.get("services"), Some(expected))
        }
    }
  }

  test("theMetricsSectionSurvivesTheSliceRatherThanBecomingItsDefault") {
    // The shape of the defect this closes is the one `kui.clusters[]` already had: a section that loads,
    // and a slice that quietly drops it. This case is about the slice only, which its name says and which
    // is half the journey: `AllInOneConfig.from` is one of the two places the section can be lost. The
    // other is the wiring's call, and it is gated by the case below — this one passes whatever `resource`
    // does with the value, which is exactly why it was not enough on its own.
    val configured = MetricsConfig.Default.copy(
      sources = Map(
        ClusterId.unsafe("prod-eu") -> MetricsSourceSettings(SafeUrl.unsafe("http://exporter:9404/metrics"))
      )
    )

    assertEquals(AllInOneConfig.from(KuiConfig.Default.copy(metrics = configured)).metrics, configured)
    // And the default is still the default, so a deployment that configured nothing is not made to look
    // like one that configured something.
    assertEquals(AllInOneConfig.Default.metrics, MetricsConfig.Default)
  }

  test("theConfiguredMetricsSectionReachesTheMetricsServiceAndNotJustTheSlice") {
    // The seam, mounted rather than composed. `AllInOneWiring.resource` is what the process runs, and the
    // only thing between the operator's `kui.metrics` and the metrics service is the argument `resource`
    // passes to `services`. Replacing it with `MetricsConfig.Default` — the defect this repository already
    // shipped once — leaves every other case in this file green, including the slice case above, because
    // none of them mounts the wiring with a configured section and then asks what the service made of it.
    //
    // What is asserted is the start-up line, and it is worth saying why that rather than the capability
    // row. `MetricsCapabilities.stateOf` puts the distinction in `reason`, and neither `CapabilityState`
    // nor `Section` carries a reason for `not_configured` on the wire — both encode that case as the
    // status alone — so the gateway's public API genuinely cannot tell the two situations apart today.
    // The start-up log is where the product does tell them apart, and it is the one an operator reads
    // when the answer they get is "nothing is configured" and their YAML says otherwise (ADR-005's
    // say-which-keys-are-ignored rule). It is produced inside `MetricsWiring` from the section that
    // crossed the seam, so it cannot be true of a wiring that dropped it.
    wire(configuredWithAMetricsSource).use { (_, logger) =>
      logger.entries.map { entries =>
        val declared = entries.flatMap(_.context.get("metrics.declaredSources"))

        assertEquals(
          declared,
          List("prod-eu"),
          s"the metrics service was not told which cluster configures a source; entries were $entries"
        )
      }
    }
  }

  test("aDeploymentThatConfiguredNoMetricsSourceIsNotMadeToLookLikeOneThatDid") {
    // The other half, and the reason the case above cannot be satisfied by always reporting a source: a
    // deployment that configured nothing has to keep saying so. This is the sentence that is true of every
    // stack in the repository today, and it is INFO rather than WARN because nothing is wrong.
    wire(AllInOneConfig.Default.copy(clusters = List(unmeasuredCluster))).use { (_, logger) =>
      logger.entries.map { entries =>
        assertEquals(
          entries.flatMap(_.context.get("metrics.declaredSources")),
          Nil,
          s"nothing configured a metrics source, so nothing should be reported as declaring one: $entries"
        )
        assert(
          entries.exists(_.message.startsWith("no cluster configures kui.metrics.sources")),
          s"the no-source sentence is what a card's 'not measured' rendering rests on; got $entries"
        )
      }
    }
  }

  /** One cluster, so that the metrics service has a row to have an answer about.
    *
    * `ConfiguredClusterSources.profilesOf` maps over `kui.clusters[]`, so a deployment with no cluster
    * reports nothing about metrics whatever `kui.metrics.sources` says — which would make the two cases
    * above pass for the wrong reason. Nothing here contacts the broker: the address exists to be a legal
    * `ClusterConfig`, and the metrics section is keyed by this cluster's id.
    */
  private val unmeasuredCluster: ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe("prod-eu"),
      name = "Production EU",
      bootstrapServers = BootstrapServers.unsafe("localhost:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default
    )

  /** The same deployment, with an exporter written against that cluster — an operator's own YAML. */
  private val configuredWithAMetricsSource: AllInOneConfig =
    AllInOneConfig.Default.copy(
      clusters = List(unmeasuredCluster),
      metrics = MetricsConfig.Default.copy(
        sources = Map(
          unmeasuredCluster.id -> MetricsSourceSettings(SafeUrl.unsafe("http://exporter:9404/metrics"))
        )
      )
    )

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
