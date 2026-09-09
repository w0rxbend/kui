package kui.metrics.app

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*

import kui.cache.CacheMetrics
import kui.config.*
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}
import kui.metrics.application.{MetricsReading, MetricsUseCases}
import kui.metrics.domain.{BrokerSample, ThroughputRange}
import kui.metrics.infrastructure.*
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The collector, assembled the way `MetricsWiring` assembles it, asked the way the route asks it.
  *
  * Nothing here composes an answer by hand. A body an exporter would serve goes through the real parser,
  * the real buffer and the real scrape pass, and the question is put to `MetricsUseCases` — the object
  * `MetricsRoutes` calls — through `ConfiguredClusterSources`, which is the lookup the wiring builds. That
  * is the whole reason this suite is in `app`: it is the deepest module that can see both the adapter and
  * the use case, and a case that stops short of the seam is a case that asserts its own arrangement.
  */
final class BrokerScrapeLoopSuite extends KuiIOSuite {

  private val quickstart = ClusterId.unsafe("quickstart")
  private val unmeasured = ClusterId.unsafe("legacy")

  /** An exposition body of the shape a JMX exporter serves, with `rate` as the bytes-in figure. */
  private def exposition(rate: Double): String =
    s"""# HELP kafka_server_brokertopicmetrics_bytesinpersec Attribute exposed for management
       |kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate $rate
       |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate ${rate * 2}
       |kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate ${rate / 100}
       |""".stripMargin

  private def cluster(id: ClusterId): ClusterConfig =
    ClusterConfig(
      id = id,
      name = id.value.capitalize,
      bootstrapServers = BootstrapServers.unsafe("localhost:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default
    )

  private def metrics(kind: MetricsSourceKind = MetricsSourceKind.Prometheus): MetricsConfig =
    MetricsConfig.Default.copy(sources =
      Map(quickstart -> MetricsSourceSettings(SafeUrl.unsafe("http://kafka-metrics:5556/metrics"), kind))
    )

  private def buffer(step: FiniteDuration = 30.seconds): IO[MetricsBuffer[IO]] =
    IO.realTimeInstant.flatMap(now =>
      MetricsBuffer
        .create[IO](quickstart, step, 24.hours, 5000, now.minusSeconds(86400), CacheMetrics.noop[IO])
    )

  /** The use case exactly as `MetricsWiring` builds it: profiles from the configuration, ports from the
    * collectors that were actually created.
    */
  private def useCase(
      clusters: List[ClusterConfig],
      configured: MetricsConfig,
      ports: Map[ClusterId, MetricsBuffer[IO]]
  ): MetricsUseCases[IO] =
    MetricsUseCases.make[IO](
      new ConfiguredClusterSources[IO](
        ConfiguredClusterSources.profilesOf(clusters, configured),
        ports.view.mapValues(identity).toMap
      ),
      // The same value `MetricsWiring` passes, from the same configuration: a point-in-time reading older
      // than the scrape that should have replaced it is drawn as last-known-good rather than as current.
      configured.scrapeInterval
    )

  /** A source that serves whatever the test queued, one answer per scrape. */
  private def scraping(answers: Ref[IO, List[Either[KuiError, String]]]): BrokerScrape[IO] =
    (at: Instant) =>
      answers.modify {
        case head :: rest => (rest, head)
        case Nil => (Nil, Right(exposition(0.0)))
      }.map(_.flatMap(body => PrometheusExposition.brokerSampleAt(at, body).left.map(malformed)))

  private def malformed(why: String): KuiError =
    InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, why, Nil)

  private def logger: IO[FakeStructuredLogger[IO]] = FakeStructuredLogger[IO]

  test("a body the exporter served becomes a sample with both rates, all the way to the reading") {
    for {
      log <- logger
      held <- buffer()
      answers <- Ref.of[IO, List[Either[KuiError, String]]](List(Right(exposition(124800.5))))
      _ <- BrokerScrapeLoop.pass[IO](quickstart, scraping(answers), held, log)
      reading <- useCase(List(cluster(quickstart)), metrics(), Map(quickstart -> held))
        .throughput(quickstart, ThroughputRange.Last24Hours)
    } yield reading match {
      case Right(MetricsReading.Measured(series, _)) =>
        val measured = series.buckets.filter(!_.isAbsent)
        assertEquals(measured.map(_.bytesInPerSecond), List(Some(124800.5)))
        assertEquals(measured.map(_.bytesOutPerSecond), List(Some(249601.0)))
      case other => fail(s"expected a Measured reading, got $other")
    }
  }

  test("a range whose buffer holds three samples answers bucketCount buckets, not three") {
    // **The rule this packet owns**, asserted where the product applies it: the answer the route hands a
    // browser, built by the collector this wave added rather than by a fixture.
    //
    // `bucketCount` is constant per range and the design rests on it — a quiet hour has to draw the same
    // axis as a busy one, or two clusters side by side are two different pictures. The failure it rules
    // out is the obvious implementation of an adapter: answer the buckets you have. Three scrapes into a
    // fresh process, that answers a chart three bars wide labelled "24 hours".
    for {
      held <- buffer(step = 5.minutes)
      now <- IO.realTimeInstant
      // Three scrapes, ten, twenty and thirty minutes ago, each one a body the exporter served folded by
      // the real parser rather than a sample written out beside the assertion.
      _ <- List(1800L, 1200L, 600L).traverse_(ago =>
        IO.fromEither(
          PrometheusExposition
            .brokerSampleAt(now.minusSeconds(ago), exposition(100.0 * ago))
            .leftMap(why => new IllegalStateException(why))
        ).flatMap(held.record)
      )
      reading <- useCase(List(cluster(quickstart)), metrics(), Map(quickstart -> held))
        .throughput(quickstart, ThroughputRange.Last24Hours)
    } yield reading match {
      case Right(MetricsReading.Measured(series, _)) =>
        assertEquals(series.buckets.size, ThroughputRange.Last24Hours.bucketCount)
        assertEquals(series.buckets.count(!_.isAbsent), 3)
        assertEquals(series.range, ThroughputRange.Last24Hours)
      case other => fail(s"expected a Measured reading, got $other")
    }
  }

  test("a scrape that fails leaves the previous samples in place") {
    // The honest answer to "the exporter died ten minutes ago" is the last ten minutes of data, with a
    // gap on the end. Clearing the window would replace it with an empty chart, which reads as a cluster
    // that stopped rather than as a collector that did.
    for {
      log <- logger
      held <- buffer()
      answers <- Ref.of[IO, List[Either[KuiError, String]]](
        List(Right(exposition(500.0)), Left(InfrastructureError.Unreachable("metrics-exporter", "refused")))
      )
      scrape = scraping(answers)
      _ <- BrokerScrapeLoop.pass[IO](quickstart, scrape, held, log)
      _ <- BrokerScrapeLoop.pass[IO](quickstart, scrape, held, log)
      reading <- useCase(List(cluster(quickstart)), metrics(), Map(quickstart -> held))
        .throughput(quickstart, ThroughputRange.Last24Hours)
      entries <- log.entries
    } yield {
      reading match {
        case Right(MetricsReading.Measured(series, _)) =>
          assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(500.0))
          assertEquals(series.buckets.size, ThroughputRange.Last24Hours.bucketCount)
        case other => fail(s"expected the earlier sample to survive, got $other")
      }
      // And the failure is not silent: an operator asking why the line stops has one line to find.
      assert(
        entries.exists(entry => entry.level == "warn" && entry.context.get("cluster").contains("quickstart")),
        s"a failed scrape must say so; entries were $entries"
      )
    }
  }

  test("a scrape that raises is logged and does not end the loop") {
    // A port that throws instead of answering `Left` is an adapter bug, and it must not be able to leave
    // a cluster unmeasured for the life of the process with nothing on any screen saying so.
    val raising: BrokerScrape[IO] = (_: Instant) => IO.raiseError(new RuntimeException("boom"))

    for {
      log <- logger
      held <- buffer()
      _ <- BrokerScrapeLoop.pass[IO](quickstart, raising, held, log)
      entries <- log.entries
    } yield assert(
      entries.exists(entry => entry.level == "error" && entry.throwable.isDefined),
      s"a raised scrape must be logged as the bug it is; entries were $entries"
    )
  }

  test("a cluster with no sources entry answers not_configured, unchanged by the collector existing") {
    useCase(List(cluster(quickstart), cluster(unmeasured)), metrics(), Map.empty)
      .throughput(unmeasured, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          // The sentence names the key, because the person reading it is the one wondering why this card
          // shows prose while the cluster above it shows a chart.
          assert(explanation.contains("kui.metrics.sources"), explanation)
        case other => fail(s"expected NotMeasured, got $other")
      }
  }

  test("a cluster whose kind is jmx answers the stated refusal and not an exception") {
    useCase(List(cluster(quickstart)), metrics(MetricsSourceKind.Jmx), Map.empty)
      .throughput(quickstart, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          // Not "nothing is configured", which would send an operator to re-read their own YAML: the
          // address is fine and this build has no JMX client (ADR-050).
          assert(explanation.contains("kind: jmx"), explanation)
          assert(explanation.contains("Prometheus text exposition only"), explanation)
        case other => fail(s"expected NotMeasured, got $other")
      }
  }


  // -----------------------------------------------------------------------------------------------
  // The fibre itself. `pass` is what every case above exercises; `resource` is what the composition
  // root actually allocates, and until this section nothing asserted that it repeats, that it repeats
  // at the interval, that its first pass is immediate or that it runs in the background — all four of
  // which the file's own scaladoc argues for at length.
  // -----------------------------------------------------------------------------------------------

  /** A scrape that counts its calls and answers instantly. */
  private def counting(calls: Ref[IO, Int]): BrokerScrape[IO] =
    (at: Instant) => calls.update(_ + 1).as(Right(BrokerSample.empty(at).copy(bytesInPerSecond = Some(1.0))))

  private def loop(scrape: BrokerScrape[IO], interval: FiniteDuration): Resource[IO, Unit] =
    Resource
      .eval((buffer(step = 1.second), logger).tupled)
      .flatMap((held, log) => BrokerScrapeLoop.resource[IO](quickstart, scrape, held, interval, log))

  test("the loop's first pass is immediate rather than one interval away") {
    // The operator watching a fresh deployment is the one most likely to be looking, and a process that
    // slept for its interval first would answer every request in its first `scrapeInterval` with an empty
    // axis. The interval here is a hundred times the window the case waits, so a loop that slept first
    // would be counted at zero.
    for {
      calls <- Ref.of[IO, Int](0)
      seen <- loop(counting(calls), 30.seconds).use(_ => IO.sleep(300.millis) *> calls.get)
    } yield assertEquals(seen, 1, "the first pass must run before the first wait")
  }

  test("the loop repeats, at the interval it was given") {
    // Two claims in one count. A loop that ran once would answer 1; a loop that waited sixty times its
    // interval — the mutation this packet reports — would also answer 1. Six hundred milliseconds at a
    // fifty-millisecond cadence is a dozen passes, so the bound is generous in both directions.
    for {
      calls <- Ref.of[IO, Int](0)
      seen <- loop(counting(calls), 50.millis).use(_ => IO.sleep(600.millis) *> calls.get)
    } yield {
      assert(seen >= 4, s"expected the loop to repeat at 50ms over 600ms; it scraped $seen times")
      assert(seen <= 40, s"expected roughly one scrape per interval; it scraped $seen times")
    }
  }

  test("the loop runs in the background, so an exporter that never answers delays no start-up") {
    // The scrape is bounded by `callTimeout` inside the upstream client, but the composition root must
    // not be waiting on it at all: a `Resource` that ran the loop in the foreground would never yield,
    // and a process whose exporter is unreachable would fail to start rather than start unmeasured.
    val never: BrokerScrape[IO] = (_: Instant) => IO.never

    loop(never, 50.millis).use(_ => IO.pure("allocated")).timeout(5.seconds).map(assertEquals(_, "allocated"))
  }

  test("the loop stops when the resource that owns it is released") {
    // The other half of `background`: a fibre that outlived its `Resource` would keep a socket open and
    // keep filing samples into a window nobody can read, for the life of the process.
    for {
      calls <- Ref.of[IO, Int](0)
      _ <- loop(counting(calls), 50.millis).use(_ => IO.sleep(300.millis))
      afterRelease <- calls.get
      _ <- IO.sleep(300.millis)
      later <- calls.get
    } yield assertEquals(later, afterRelease, "a released loop must stop scraping")
  }
}
