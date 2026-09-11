package kui.config

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

/** That `kui.metrics` and `kui.alerts` load, that they default to something a deployment can live with, and
  * that a file which has never heard of either is unaffected.
  *
  * Both sections exist before the services that read them, which is the point of testing them here: M7 and M8
  * each need a section, and a section invented twice is a section spelled two ways. The case that matters
  * most is the *absent* one — a cluster with no metrics source is not a misconfiguration, it is the state
  * every metrics card on every screen is designed to render honestly (ADR-032), so "no source" has to be a
  * value the loader produces rather than an error it reports.
  */
final class MetricsAndAlertsConfigSuite extends KuiSuite {

  private def load(
      yaml: String,
      env: Map[String, String] = Map.empty
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def loaded(yaml: String, env: Map[String, String] = Map.empty): KuiConfig =
    load(yaml, env).fold(errors => fail(errors.render), identity)

  private def problems(yaml: String): List[ConfigProblem] =
    load(yaml) match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private val nothing: String =
    """kui:
      |  server:
      |    port: 8080
      |""".stripMargin

  test("a file that mentions neither section gets the documented defaults and no source at all") {
    val config = loaded(nothing)

    assertEquals(config.metrics, MetricsConfig.Default)
    assertEquals(config.alerts, AlertsConfig.Default)
    assertEquals(config.metrics.sources, Map.empty[kui.kernel.ClusterId, MetricsSourceSettings])
    assert(!config.metrics.isConfigured, "a file with no sources reported itself as configured")
  }

  test("the cadence and the window are read from the file") {
    val config = loaded("""kui:
                          |  metrics:
                          |    scrapeInterval: 15s
                          |    retention: 7d
                          |    maxSamplesPerSeries: 2000
                          |  alerts:
                          |    retention: 30d
                          |    evaluationInterval: 30s
                          |""".stripMargin)

    assertEquals(config.metrics.scrapeInterval, 15.seconds)
    assertEquals(config.metrics.retention, 7.days)
    assertEquals(config.metrics.maxSamplesPerSeries, 2000)
    assertEquals(config.alerts.retention, 30.days)
    assertEquals(config.alerts.evaluationInterval, 30.seconds)
  }

  test("a source is keyed by the id of the cluster it measures, and defaults to Prometheus") {
    val config = loaded("""kui:
                          |  metrics:
                          |    sources:
                          |      local:
                          |        url: "http://broker-1:9404/metrics"
                          |      staging-eu:
                          |        url: "http://jmx:9999"
                          |        kind: jmx
                          |        callTimeout: 5s
                          |""".stripMargin)

    assertEquals(config.metrics.sources.keys.map(_.value).toList.sorted, List("local", "staging-eu"))
    assert(config.metrics.isConfigured, "two configured sources reported themselves as none")

    val local = config.metrics
      .sourceFor(kui.kernel.ClusterId.unsafe("local"))
      .getOrElse(fail("the source for `local` was not read"))
    assertEquals(local.url.value, "http://broker-1:9404/metrics")
    assertEquals(local.kind, MetricsSourceKind.Prometheus)
    assertEquals(local.callTimeout, MetricsSourceSettings.DefaultCallTimeout)

    val staging = config.metrics
      .sourceFor(kui.kernel.ClusterId.unsafe("staging-eu"))
      .getOrElse(fail("the source for `staging-eu` was not read"))
    assertEquals(staging.kind, MetricsSourceKind.Jmx)
    assertEquals(staging.callTimeout, 5.seconds)
  }

  test("a cluster with no entry has no source, which is a value and not a failure") {
    val config = loaded("""kui:
                          |  metrics:
                          |    sources:
                          |      local:
                          |        url: "http://broker-1:9404/metrics"
                          |""".stripMargin)

    assertEquals(config.metrics.sourceFor(kui.kernel.ClusterId.unsafe("production")), None)
  }

  test("a source is spellable in the environment, which is how a container is configured") {
    val config = loaded(
      nothing,
      env = Map(
        "KUI_METRICS_SOURCES_LOCAL_URL" -> "http://exporter:9404/metrics",
        "KUI_METRICS_SCRAPEINTERVAL" -> "45s"
      )
    )

    assertEquals(config.metrics.scrapeInterval, 45.seconds)
    assertEquals(config.metrics.sources.keys.map(_.value).toList, List("local"))
  }

  test("a source protocol KUI does not speak is refused at load time, listing the ones it does") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "http://broker-1:9404/metrics"
                           |        kind: graphite
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.kind"))
    assert(found.head.problem.contains("prometheus"), found.head.problem)
  }

  test("a member name that could never be a cluster id is refused, because it could never be matched") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      "Not An Id":
                           |        url: "http://broker-1:9404/metrics"
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.Not An Id"))
    assert(found.head.problem.contains("cluster this source measures"), found.head.problem)
  }

  test("a window shorter than the cadence retains nothing, and is refused naming both keys") {
    val found = problems("""kui:
                           |  metrics:
                           |    scrapeInterval: 10m
                           |    retention: 1m
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.retention"))
    assert(found.head.problem.contains("kui.metrics.scrapeInterval"), found.head.problem)
  }

  test("a scrape that outlives its interval is refused, and the message says which value is the default") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "http://broker-1:9404/metrics"
                           |        callTimeout: 45s
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.callTimeout"))
    assert(found.head.problem.contains("which is the default"), found.head.problem)
  }

  test("every threshold is bounded, and a value outside the bounds says what the bounds are") {
    val found = problems("""kui:
                           |  alerts:
                           |    thresholds:
                           |      offlinePartitions: 0
                           |      diskUsedWarningPercent: 120
                           |""".stripMargin)

    assertEquals(
      found.map(_.key),
      List("kui.alerts.thresholds.diskUsedWarningPercent", "kui.alerts.thresholds.offlinePartitions")
    )
  }

  test("a critical disk threshold at or below the warning one would mean the warning never fires") {
    val found = problems("""kui:
                           |  alerts:
                           |    thresholds:
                           |      diskUsedWarningPercent: 90
                           |      diskUsedCriticalPercent: 85
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.alerts.thresholds.diskUsedCriticalPercent"))
    assert(found.head.problem.contains("without ever having been a warning"), found.head.problem)
  }

  test("the thresholds an operator did not set keep their defaults, one key at a time") {
    val config = loaded("""kui:
                          |  alerts:
                          |    thresholds:
                          |      rebalanceDuration: 15m
                          |""".stripMargin)

    assertEquals(config.alerts.thresholds.rebalanceDuration, 15.minutes)
    assertEquals(
      config.alerts.thresholds.diskUsedWarningPercent,
      AlertThresholds.DefaultDiskUsedWarningPercent
    )
    assertEquals(config.alerts.thresholds.offlinePartitions, AlertThresholds.DefaultOfflinePartitions)
    assertEquals(config.alerts.retention, AlertsConfig.DefaultRetention)
  }

  test("a misspelled key under either section still fails startup, naming the key") {
    val found = problems("""kui:
                           |  metrics:
                           |    scrapeIntervals: 15s
                           |  alerts:
                           |    thresholds:
                           |      diskUsedWarning: 80
                           |""".stripMargin)

    assertEquals(
      found.map(_.key),
      List("kui.alerts.thresholds.diskUsedWarning", "kui.metrics.scrapeIntervals")
    )
    assert(found.forall(_.problem.contains("not a KUI configuration key")), found.map(_.render).mkString)
  }

  test("a metrics source is held to the same URL rule as every other upstream KUI calls") {
    val found = KuiConfigSource
      .loadFrom[IO](
        Nil,
        List(
          ConfigFixtures.yaml("""kui:
                                |  metrics:
                                |    sources:
                                |      local:
                                |        url: "http://169.254.169.254/latest/meta-data/"
                                |""".stripMargin)
        ),
        Map.empty,
        UrlPolicy.Strict
      )
      .unsafeRunSync() match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("a cloud metadata address was accepted as a metrics source")
    }

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.url"))
  }
}
