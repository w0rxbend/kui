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
      env: Map[String, String] = Map.empty,
      args: List[String] = Nil
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](args, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
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

  test("nested environment leaves belong to the direct metrics source rather than inventing a child") {
    val config = loaded(
      nothing,
      env = Map(
        "KUI_METRICS_SOURCES_PRODUCTION_EU_URL" -> "https://prometheus:9090",
        "KUI_METRICS_SOURCES_PRODUCTION_EU_KIND" -> "prometheus-api",
        "KUI_METRICS_SOURCES_PRODUCTION_EU_AUTH_TYPE" -> "none"
      )
    )

    assertEquals(config.metrics.sources.keys.map(_.value).toList, List("production-eu"))
  }

  test("an orphaned nested environment setting reports the direct metrics source id") {
    val result = load(
      nothing,
      env = Map("KUI_METRICS_SOURCES_PRODUCTION_EU_AUTH_TYPE" -> "anonymous")
    )

    val found = result match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("an orphaned nested source setting was accepted")
    }
    assertEquals(
      found.map(_.key),
      List("kui.metrics.sources.production-eu.url", "kui.metrics.sources.production-eu.auth.type")
    )
  }

  test("an orphaned nested key-store password reports the direct metrics source id") {
    val result = load(
      nothing,
      env = Map("KUI_METRICS_SOURCES_PRODUCTION_EU_TLS_KEYSTORE_KEYPASSWORD" -> "env:KEY_PASSWORD")
    )

    val found = result match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("an orphaned nested key-store setting was accepted")
    }
    assertEquals(
      found.map(_.key),
      List("kui.metrics.sources.production-eu.url", "kui.metrics.sources.production-eu.tls")
    )
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

  test("an invalid source kind does not trigger misleading kind-dependent budget errors") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "http://broker-1:9404/metrics"
                           |        kind: prometheus_api
                           |        queryTimeout: 5s
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.kind"))
  }

  test("a Prometheus API source gets the documented bounded query defaults") {
    val source = loaded("""kui:
                          |  metrics:
                          |    sources:
                          |      local:
                          |        url: "https://prometheus:9090/prometheus"
                          |        kind: prometheus-api
                          |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.kind, MetricsSourceKind.PrometheusApi)
    assertEquals(source.queryTimeout, 8.seconds)
    assertEquals(source.maxConcurrentQueries, 4)
    assertEquals(source.maxSeriesPerQuery, 200)
    assertEquals(source.maxPointsPerSeries, 600)
    assertEquals(source.maxResponseBytes, 4 * 1024 * 1024)
    assertEquals(source.maxCacheBytes, 64 * 1024 * 1024)
    assertEquals(source.cacheTtl, 15.seconds)
    assertEquals(source.staleTtl, 2.minutes)
  }

  test("a Prometheus API source reads every query budget") {
    val source = loaded("""kui:
                          |  metrics:
                          |    sources:
                          |      local:
                          |        url: "https://prometheus:9090"
                          |        kind: prometheus-api
                          |        callTimeout: 20s
                          |        queryTimeout: 12s
                          |        maxConcurrentQueries: 8
                          |        maxSeriesPerQuery: 350
                          |        maxPointsPerSeries: 900
                          |        maxResponseBytes: 8388608
                          |        maxCacheBytes: 134217728
                          |        cacheTtl: 30s
                          |        staleTtl: 5m
                          |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.queryTimeout, 12.seconds)
    assertEquals(source.maxConcurrentQueries, 8)
    assertEquals(source.maxSeriesPerQuery, 350)
    assertEquals(source.maxPointsPerSeries, 900)
    assertEquals(source.maxResponseBytes, 8 * 1024 * 1024)
    assertEquals(source.maxCacheBytes, 128 * 1024 * 1024)
    assertEquals(source.cacheTtl, 30.seconds)
    assertEquals(source.staleTtl, 5.minutes)
  }

  test("query-only settings are refused for exposition and JMX sources") {
    List("prometheus", "jmx").foreach { kind =>
      val found = problems(s"""kui:
                              |  metrics:
                              |    sources:
                              |      local:
                              |        url: "http://metrics:9404/metrics"
                              |        kind: $kind
                              |        queryTimeout: 5s
                              |""".stripMargin)

      assertEquals(found.map(_.key), List("kui.metrics.sources.local.queryTimeout"))
      assert(found.head.problem.contains("prometheus-api"), found.head.problem)
    }
  }

  test("an API query timeout must be shorter than its whole-call timeout") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "https://prometheus:9090"
                           |        kind: prometheus-api
                           |        callTimeout: 8s
                           |        queryTimeout: 8s
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.queryTimeout"))
    assert(found.head.problem.contains("callTimeout"), found.head.problem)
  }

  test("an API source is not constrained by the exposition scrape interval") {
    val source = loaded("""kui:
                          |  metrics:
                          |    scrapeInterval: 5s
                          |    sources:
                          |      local:
                          |        url: "https://prometheus:9090"
                          |        kind: prometheus-api
                          |        callTimeout: 10s
                          |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.callTimeout, 10.seconds)
  }

  test("the stale window cannot be shorter than the fresh cache window") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "https://prometheus:9090"
                           |        kind: prometheus-api
                           |        cacheTtl: 2m
                           |        staleTtl: 1m
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.staleTtl"))
    assert(found.head.problem.contains("cacheTtl"), found.head.problem)
  }

  test("every Prometheus API query budget accepts its exact lower and upper boundary") {
    val lower = loaded("""kui:
                         |  metrics:
                         |    sources:
                         |      local:
                         |        url: "https://prometheus:9090"
                         |        kind: prometheus-api
                         |        callTimeout: 60s
                         |        queryTimeout: 1s
                         |        maxConcurrentQueries: 1
                         |        maxSeriesPerQuery: 1
                         |        maxPointsPerSeries: 60
                         |        maxResponseBytes: 65536
                         |        maxCacheBytes: 4194304
                         |        cacheTtl: 1s
                         |        staleTtl: 1s
                         |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    assertEquals(lower.queryTimeout, 1.second)
    assertEquals(lower.maxConcurrentQueries, 1)
    assertEquals(lower.maxSeriesPerQuery, 1)
    assertEquals(lower.maxPointsPerSeries, 60)
    assertEquals(lower.maxResponseBytes, 65536)
    assertEquals(lower.maxCacheBytes, 4194304)
    assertEquals(lower.cacheTtl, 1.second)
    assertEquals(lower.staleTtl, 1.second)

    val upper = loaded("""kui:
                         |  metrics:
                         |    sources:
                         |      local:
                         |        url: "https://prometheus:9090"
                         |        kind: prometheus-api
                         |        callTimeout: 60s
                         |        queryTimeout: 55s
                         |        maxConcurrentQueries: 32
                         |        maxSeriesPerQuery: 1000
                         |        maxPointsPerSeries: 2000
                         |        maxResponseBytes: 33554432
                         |        maxCacheBytes: 536870912
                         |        cacheTtl: 5m
                         |        staleTtl: 30m
                         |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    assertEquals(upper.queryTimeout, 55.seconds)
    assertEquals(upper.maxConcurrentQueries, 32)
    assertEquals(upper.maxSeriesPerQuery, 1000)
    assertEquals(upper.maxPointsPerSeries, 2000)
    assertEquals(upper.maxResponseBytes, 33554432)
    assertEquals(upper.maxCacheBytes, 536870912)
    assertEquals(upper.cacheTtl, 5.minutes)
    assertEquals(upper.staleTtl, 30.minutes)
  }

  test("every Prometheus API query budget rejects values immediately outside its bounds") {
    val invalid = List(
      "queryTimeout" -> List("999ms", "56s"),
      "maxConcurrentQueries" -> List("0", "33"),
      "maxSeriesPerQuery" -> List("0", "1001"),
      "maxPointsPerSeries" -> List("59", "2001"),
      "maxResponseBytes" -> List("65535", "33554433"),
      "maxCacheBytes" -> List("4194303", "536870913"),
      "cacheTtl" -> List("999ms", "6m"),
      "staleTtl" -> List("999ms", "31m")
    )

    invalid.foreach { (key, values) =>
      values.foreach { value =>
        val found = problems(s"""kui:
                                |  metrics:
                                |    sources:
                                |      local:
                                |        url: "https://prometheus:9090"
                                |        kind: prometheus-api
                                |        callTimeout: 60s
                                |        $key: $value
                                |""".stripMargin)
        assertEquals(found.map(_.key), List(s"kui.metrics.sources.local.$key"))
      }
    }
  }

  test("every query budget is rejected when written on an exposition source") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: "http://metrics:9404/metrics"
                           |        queryTimeout: 5s
                           |        maxConcurrentQueries: 2
                           |        maxSeriesPerQuery: 2
                           |        maxPointsPerSeries: 60
                           |        maxResponseBytes: 65536
                           |        maxCacheBytes: 4194304
                           |        cacheTtl: 1s
                           |        staleTtl: 1s
                           |""".stripMargin)

    assertEquals(
      found.map(_.key),
      List(
        "kui.metrics.sources.local.cacheTtl",
        "kui.metrics.sources.local.maxCacheBytes",
        "kui.metrics.sources.local.maxConcurrentQueries",
        "kui.metrics.sources.local.maxPointsPerSeries",
        "kui.metrics.sources.local.maxResponseBytes",
        "kui.metrics.sources.local.maxSeriesPerQuery",
        "kui.metrics.sources.local.queryTimeout",
        "kui.metrics.sources.local.staleTtl"
      )
    )
  }

  test("Prometheus API sources support anonymous, basic, bearer and OAuth authentication") {
    val basic = loaded(
      """kui:
        |  metrics:
        |    sources:
        |      local:
        |        url: https://prometheus:9090
        |        kind: prometheus-api
        |        auth:
        |          type: basic
        |          username: kui
        |          password: env:PROM_PASSWORD
        |""".stripMargin,
      Map("PROM_PASSWORD" -> "basic-canary")
    ).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    basic.auth match {
      case UpstreamAuthConfig.Basic("kui", password) => assertEquals(password.value, "basic-canary")
      case other => fail(s"basic auth did not decode: $other")
    }

    val bearer = loaded(
      """kui:
        |  metrics:
        |    sources:
        |      local:
        |        url: https://prometheus:9090
        |        kind: prometheus-api
        |        auth:
        |          type: bearer
        |          token: env:PROM_TOKEN
        |""".stripMargin,
      Map("PROM_TOKEN" -> "bearer-canary")
    ).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    bearer.auth match {
      case UpstreamAuthConfig.Bearer(token) => assertEquals(token.value, "bearer-canary")
      case other => fail(s"bearer auth did not decode: $other")
    }

    val oauth = loaded(
      """kui:
        |  metrics:
        |    sources:
        |      local:
        |        url: https://prometheus:9090
        |        kind: prometheus-api
        |        auth:
        |          type: oauth
        |          tokenEndpoint: https://issuer.example/token
        |          clientId: kui
        |          clientSecret: env:PROM_CLIENT_SECRET
        |          scope: metrics.read
        |""".stripMargin,
      Map("PROM_CLIENT_SECRET" -> "oauth-canary")
    ).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    oauth.auth match {
      case UpstreamAuthConfig.OAuth(endpoint, "kui", secret, Some("metrics.read")) =>
        assertEquals(endpoint.value, "https://issuer.example/token")
        assertEquals(secret.value, "oauth-canary")
      case other => fail(s"OAuth auth did not decode: $other")
    }
  }

  test("omitted Prometheus API authentication is anonymous") {
    val source = loaded("""kui:
                          |  metrics:
                          |    sources:
                          |      local:
                          |        url: https://prometheus:9090
                          |        kind: prometheus-api
                          |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.auth, UpstreamAuthConfig.Anonymous)
  }

  test("authentication keys are refused for exposition and JMX sources") {
    List("prometheus", "jmx").foreach { kind =>
      val found = problems(s"""kui:
                              |  metrics:
                              |    sources:
                              |      local:
                              |        url: http://metrics:9404/metrics
                              |        kind: $kind
                              |        auth:
                              |          type: bearer
                              |          token: should-not-be-read
                              |""".stripMargin)
      assertEquals(found.map(_.key), List("kui.metrics.sources.local.auth.type"))
      assert(!found.head.render.contains("should-not-be-read"), found.head.render)
    }
  }

  test("a Prometheus OAuth token endpoint must use HTTPS") {
    val found = problems("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: https://prometheus:9090
                           |        kind: prometheus-api
                           |        auth:
                           |          type: oauth
                           |          tokenEndpoint: http://issuer.example/token
                           |          clientId: kui
                           |          clientSecret: secret-canary
                           |""".stripMargin)

    assertEquals(found.map(_.key), List("kui.metrics.sources.local.auth.tokenEndpoint"))
    assert(!found.head.render.contains("secret-canary"), found.head.render)
  }

  test("authenticated Prometheus API sources require HTTPS while anonymous HTTP remains available") {
    val authenticated = problems("""kui:
                                 |  metrics:
                                 |    sources:
                                 |      local:
                                 |        url: http://prometheus:9090
                                 |        kind: prometheus-api
                                 |        auth:
                                 |          type: bearer
                                 |          token: bearer-secret-canary
                                 |""".stripMargin)
    assertEquals(authenticated.map(_.key), List("kui.metrics.sources.local.url"))
    assert(!authenticated.head.render.contains("bearer-secret-canary"), authenticated.head.render)

    val anonymous = loaded("""kui:
                           |  metrics:
                           |    sources:
                           |      local:
                           |        url: http://prometheus:9090
                           |        kind: prometheus-api
                           |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))
    assertEquals(anonymous.auth, UpstreamAuthConfig.Anonymous)
  }

  test("Prometheus API URLs are server bases without query, fragment or endpoint suffixes") {
    val invalid = List(
      "https://prometheus:9090/prometheus?tenant=one",
      "https://prometheus:9090/prometheus#fragment",
      "https://prometheus:9090/api/v1/query",
      "https://prometheus:9090/prometheus/api/v1/query_range/",
      "https://user:password@prometheus:9090/prometheus"
    )

    invalid.foreach { url =>
      val found = problems(s"""kui:
                              |  metrics:
                              |    sources:
                              |      local:
                              |        url: $url
                              |        kind: prometheus-api
                              |""".stripMargin)
      assertEquals(found.map(_.key), List("kui.metrics.sources.local.url"), clue = url)
      assert(!found.head.render.contains("user:password"), found.head.render)
    }
  }

  test("a Prometheus API reverse-proxy base path is preserved") {
    val source = loaded("""kui:
                        |  metrics:
                        |    sources:
                        |      local:
                        |        url: https://prometheus:9090/tenant-a/prometheus/
                        |        kind: prometheus-api
                        |""".stripMargin).metrics.sources(kui.kernel.ClusterId.unsafe("local"))

    assertEquals(source.url.value, "https://prometheus:9090/tenant-a/prometheus/")
  }

  test("malformed Prometheus authentication fails startup without disclosing secret references") {
    val missing = problems("""kui:
                             |  metrics:
                             |    sources:
                             |      local:
                             |        url: https://prometheus:9090
                             |        kind: prometheus-api
                             |        auth:
                             |          type: bearer
                             |""".stripMargin)
    assertEquals(missing.map(_.key), List("kui.metrics.sources.local.auth.token"))

    val surplus = problems("""kui:
                             |  metrics:
                             |    sources:
                             |      local:
                             |        url: https://prometheus:9090
                             |        kind: prometheus-api
                             |        auth:
                             |          type: bearer
                             |          token: bearer-secret-canary
                             |          username: unused-user
                             |""".stripMargin)
    assertEquals(surplus.map(_.key), List("kui.metrics.sources.local.auth.type"))
    assert(!surplus.head.render.contains("bearer-secret-canary"), surplus.head.render)
  }

  test("nested source settings merge across file, environment and CLI with normal precedence") {
    val config = load(
      """kui:
        |  metrics:
        |    sources:
        |      production-eu:
        |        url: https://file-prometheus:9090
        |        kind: prometheus-api
        |        auth:
        |          type: bearer
        |          token: env:FILE_TOKEN
        |""".stripMargin,
      env = Map(
        "FILE_TOKEN" -> "file-canary",
        "ENV_TOKEN" -> "env-canary",
        "CLI_TOKEN" -> "cli-canary",
        "KUI_METRICS_SOURCES_PRODUCTION_EU_AUTH_TOKEN" -> "env:ENV_TOKEN"
      ),
      args = List("--kui.metrics.sources.production-eu.auth.token=env:CLI_TOKEN")
    ).fold(errors => fail(errors.render), identity)

    val sources = config.metrics.sources
    assertEquals(sources.keys.map(_.value).toList, List("production-eu"))
    sources(kui.kernel.ClusterId.unsafe("production-eu")).auth match {
      case UpstreamAuthConfig.Bearer(token) => assertEquals(token.value, "cli-canary")
      case other => fail(s"the merged source did not use bearer auth: $other")
    }
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
