package kui.allinone

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*

import kui.cluster.app.ClusterServiceConfig
import kui.config.{
  AlertThresholds,
  AlertsConfig,
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
      .flatMap(logger => AllInOneWiring.resource[IO](config, Telemetry.noop[IO], logger).tupleRight(logger))

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
    * *security* input, while the endpoint's own path stays on the ordinary one. Reading only the second would
    * report the gateway's `/api/v1/health/live` as a bare `/health/live`, which is exactly the unprefixed
    * service path the next test asserts is absent, and the test would fail on its own blind spot.
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
        // The ninth service, and the reason a named path is asserted here rather than only a count. Adding
        // a service to `Services` above without wiring one changes this list not at all -- the roster is a
        // list of ids and the router is built from the clients -- so the case below that compares the two
        // catches a roster that is short and not a roster that is long. This is the other direction: a
        // path only the alerts service publishes, served by the one listener this process binds.
        //
        // `pathSegments` reads the FIXED segments off the endpoint's input, so a capture contributes
        // nothing: `clusters / {clusterId} / alerts / events` renders here as the string below. That is
        // also why it cannot be confused with any other service's route -- no other contract has an
        // `alerts` segment at all.
        assert(
          paths.contains("/api/v1/clusters/alerts/events"),
          s"the in-process alerts service's feed was not proxied; served $paths"
        )
        assert(
          paths.contains("/api/v1/clusters/alerts/stream"),
          s"the public alerts stream relay was not mounted; served $paths"
        )
        // The tenth service, asserted by a path only it publishes, for the reason the alerts feed
        // above is: adding an id to `Services` without wiring one changes nothing this list can see.
        // `pathSegments` reads the FIXED segments off the input, so `clusters / {clusterId} /
        // connect / connectors` renders as the string below, and no other contract has a `connect`
        // segment at all.
        //
        // There is no `connect` STREAM to assert beside it and that is a decision rather than an
        // omission: the Kafka Connect REST API publishes no change feed, so `services/connect` ships
        // no ADR-035 endpoint, the gateway has nothing to relay, and the screens poll (ADR-054 §5).
        // House rule 16 asks that a stream ship its relay; this is the other answer to it.
        assert(
          paths.contains("/api/v1/clusters/connect/connectors"),
          s"the in-process connect service's connector list was not proxied; served $paths"
        )
        // The eleventh service, asserted the same way and for the same reason, and it needs BOTH of
        // its paths named. ksqlDB is the second service in this process to publish an ADR-035 stream,
        // and a stream is not a proxied route: `ContractRouting.derive` decodes and re-encodes JSON,
        // so it cannot carry one, and the relay has to be mounted by hand the way the alerts stream's
        // is. Asserting only the object listing would leave a binary whose ksqlDB screens list streams
        // and whose push query opens a socket to a 404 -- which is precisely the state wave 6 shipped
        // the alerts stream in, with every suite green.
        assert(
          paths.contains("/api/v1/clusters/ksql/objects"),
          s"the in-process ksql service's object listing was not proxied; served $paths"
        )
        assert(
          paths.contains("/api/v1/clusters/ksql/stream"),
          s"the public ksql push-query relay was not mounted; served $paths"
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
          alerts = AlertsConfig.Default,
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
          // Ten services now, and this string has broken every time one was added -- which is what it
          // is for. It is the first line of a KUI log and the one a reader checks against the roadmap
          // to find out which milestone's services are actually in the binary they are running.
          val expected = "alerts,cluster,connect,consumer,identity,ksql,message,metrics,schema,topic"
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

  test("theAlertsSectionSurvivesTheSliceRatherThanBecomingItsDefault") {
    // The twin of the metrics case above, and it exists because the alerts section fails MORE quietly than
    // the metrics one. A dropped `kui.metrics` makes a configured deployment report `not_configured`, which
    // an operator eventually argues with. A dropped `kui.alerts` changes no status anywhere: the feed still
    // answers `ok`, the rules still run, and the thresholds are silently the shipped defaults instead of
    // the ones somebody wrote -- so a cluster deliberately tuned to tolerate a migration starts opening
    // events again and nothing in the product says why.
    //
    // This closes the slice half of the journey and only that half, which its name says. The other half --
    // the argument `resource` passes to `services` -- is the case below, and it could not be written until
    // `AllInOneWiring.logAlertThresholds` existed: `AlertsWiring` still writes no start-up line naming what
    // it was configured with, so before that line a wiring that handed it `AlertsConfig.Default` produced a
    // process indistinguishable from a correct one until a threshold was crossed against a real broker.
    val configured = AlertsConfig.Default.copy(
      retention = 36.hours,
      thresholds = AlertThresholds.Default.copy(diskUsedWarningPercent = 55)
    )

    assertEquals(AllInOneConfig.from(KuiConfig.Default.copy(alerts = configured)).alerts, configured)
    // And the default is still the default, so a deployment that configured nothing is not made to look
    // like one that tuned something.
    assertEquals(AllInOneConfig.Default.alerts, AlertsConfig.Default)
  }

  test("theConfiguredAlertsSectionReachesTheAlertRulesAndNotJustTheSlice") {
    // The seam, mounted rather than composed, and the twin of the metrics case above. `resource` is what
    // the process runs, and the only thing between the operator's `kui.alerts` and the rules is the
    // argument it passes to `services`. Replacing it with `AlertsConfig.Default` -- which is what this
    // wiring did for a whole wave with nothing observing it -- leaves every other case in this file green,
    // including the slice case above, because none of them mounts the wiring with a tuned section and then
    // asks what the process made of it.
    //
    // EVERY FIELD OF THE LINE AND NOT ONE OF THEM. Asserting only the disk threshold was measured to
    // leave the other six replaceable by a constant with `./mill apps.allinone.test` green -- which is the
    // same shape of hole one level down as the one this case exists to close, and a threshold that reached
    // the rules while `rebalanceDuration` did not is exactly as silent as the whole section being dropped.
    // The fixture therefore moves all seven values off their defaults, so no default can satisfy any of
    // them. `alerts.source` is asserted beside them, because a line that reported the right numbers under
    // the wrong provenance would be the same defect again.
    wire(configuredWithTunedAlertThresholds).use { (_, logger) =>
      logger.entries.map { entries =>
        def logged(field: String): List[String] = entries.flatMap(_.context.get(field))

        assertEquals(logged("alerts.source"), List("kui.alerts"), clue = entries)
        assertEquals(logged("alerts.retention"), List("36 hours"), clue = entries)
        assertEquals(logged("alerts.evaluationInterval"), List("17 seconds"), clue = entries)
        assertEquals(logged("alerts.offlinePartitions"), List("3"), clue = entries)
        assertEquals(logged("alerts.underReplicatedPartitions"), List("7"), clue = entries)
        assertEquals(logged("alerts.rebalanceDuration"), List("11 minutes"), clue = entries)
        assertEquals(logged("alerts.diskUsedWarningPercent"), List("55"), clue = entries)
        assertEquals(logged("alerts.diskUsedCriticalPercent"), List("66"), clue = entries)
      }
    }
  }

  test("aDeploymentThatTunedNothingIsNotMadeToLookLikeOneThatDid") {
    // The other half, and the reason the case above cannot be satisfied by always claiming a tuned section:
    // the default deployment -- the quickstart, the demonstration, every stack in this repository that
    // writes no `kui.alerts` -- has to keep saying so. Both are true of `AlertsConfig.Default` and only one
    // is true of a wiring that lost the section.
    wire().use { (_, logger) =>
      logger.entries.map { entries =>
        def logged(field: String): List[String] = entries.flatMap(_.context.get(field))

        assertEquals(logged("alerts.source"), List("shipped defaults"), clue = entries)
        // The values are printed here too, and are asserted against the constants rather than against
        // literals: an operator comparing a tuned deployment with an untuned one reads both lines, so a
        // line that named the provenance and printed nothing would answer half the question.
        assertEquals(logged("alerts.retention"), List(AlertsConfig.DefaultRetention.toString), clue = entries)
        assertEquals(
          logged("alerts.evaluationInterval"),
          List(AlertsConfig.DefaultEvaluationInterval.toString),
          clue = entries
        )
        assertEquals(
          logged("alerts.diskUsedWarningPercent"),
          List(AlertThresholds.DefaultDiskUsedWarningPercent.toString),
          clue = entries
        )
      }
    }
  }

  test("aDeploymentThatTunedOnlyRetentionIsStillReadingItsOwnFile") {
    // THE PROVENANCE FLAG, WHICH IS THE ONE FIELD OF THIS LINE NOTHING LOOKED AT. The two cases above
    // exercise `alerts.source` at the only two points where `alerts != AlertsConfig.Default` and
    // `alerts.thresholds != AlertThresholds.Default` agree: everything tuned, and nothing tuned. Between
    // them sits the deployment that wrote a `kui.alerts` section containing no threshold at all --
    // retention, or the evaluation interval, or both -- and it is not a hypothetical shape:
    // `AlertsConfig` has five thresholds and two fields beside them, and "keep the events for three days"
    // is the first thing anybody changes.
    //
    // Measured before this case existed: `alerts != AlertsConfig.Default` ->
    // `alerts.thresholds != AlertThresholds.Default` left `./mill apps.allinone.test` at 3821/3821
    // SUCCESS. Under that mutation this operator reads "no kui.alerts section; the alert rules use the
    // shipped default thresholds" printed in the same line as their own 36-hour retention -- a line that
    // contradicts itself, and the one line in the process whose whole job is to say whose numbers these
    // are. Wave 7 closed the context map to all eight fields and left the flag that interprets them open.
    val retentionOnly = AllInOneConfig.Default.copy(
      clusters = List(unmeasuredCluster),
      alerts = AlertsConfig.Default.copy(retention = 36.hours)
    )

    wire(retentionOnly).use { (_, logger) =>
      logger.entries.map { entries =>
        def logged(field: String): List[String] = entries.flatMap(_.context.get(field))

        assertEquals(logged("alerts.source"), List("kui.alerts"), clue = entries)
        // The sentence as well as the field, because the flag drives both and an operator reads the
        // sentence first. Asserted as the whole message so that a line rewritten to hedge -- "possibly
        // tuned" -- is a change somebody has to make here on purpose.
        assertEquals(
          entries.filter(_.context.contains("alerts.source")).map(_.message),
          List("alert thresholds taken from kui.alerts"),
          clue = entries
        )
        // And the thresholds beside it really are the shipped ones, which is what makes this the middle
        // case rather than a third copy of the tuned one: the provenance is `kui.alerts` while every
        // number on the line is a default, and nothing about that is a contradiction.
        assertEquals(logged("alerts.retention"), List("36 hours"), clue = entries)
        assertEquals(
          logged("alerts.diskUsedWarningPercent"),
          List(AlertThresholds.DefaultDiskUsedWarningPercent.toString),
          clue = entries
        )
      }
    }
  }

  /** One cluster, so that the metrics service has a row to have an answer about.
    *
    * `ConfiguredClusterSources.profilesOf` maps over `kui.clusters[]`, so a deployment with no cluster
    * reports nothing about metrics whatever `kui.metrics.sources` says — which would make the two cases above
    * pass for the wrong reason. Nothing here contacts the broker: the address exists to be a legal
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

  /** A deployment whose operator tuned the alert thresholds — the migration case ADR-053 describes.
    *
    * EVERY value is moved off its default, and that is the whole design of the fixture: a field left at
    * `AlertsConfig.Default`'s value is a field the case above cannot tell apart from a wiring that dropped
    * the section, so it would be asserted and gated by nothing. Each value is also inside the bounds
    * `libs/config` enforces — 36h is within the 1h..90d retention window, 17s within 5s..1h, 11 minutes
    * within 10s..1h, and 66 stays above the 55 warning, which the loader requires.
    */
  private val configuredWithTunedAlertThresholds: AllInOneConfig =
    AllInOneConfig.Default.copy(
      clusters = List(unmeasuredCluster),
      alerts = AlertsConfig(
        retention = 36.hours,
        evaluationInterval = 17.seconds,
        thresholds = AlertThresholds(
          offlinePartitions = 3,
          underReplicatedPartitions = 7,
          rebalanceDuration = 11.minutes,
          diskUsedWarningPercent = 55,
          diskUsedCriticalPercent = 66
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
