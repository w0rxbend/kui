package kui.metrics.application

import java.time.Instant

import cats.effect.IO
import munit.CatsEffectSuite

import kui.kernel.ClusterId
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}
import kui.metrics.domain.*

/** A source that answers from a fixture, or refuses, on demand.
  *
  * Hand-written rather than mocked, because what this suite is about is *which* of the three answers comes
  * back, and a stub whose behaviour is written out is the only kind whose answers can be read beside the
  * assertions that depend on them.
  */
final class FakeSource(failure: Option[KuiError] = None) extends MetricsSourcePort[IO] {

  def throughput(range: ThroughputRange, endingAt: Instant): IO[Either[KuiError, ThroughputSeries]] =
    IO.pure(failure.toLeft(ThroughputSeries.absent(range, endingAt)))

  def latency(range: ThroughputRange, endingAt: Instant): IO[Either[KuiError, LatencySeries]] =
    IO.pure(failure.toLeft(LatencySeries.absent(range, endingAt)))

  def requestHandlers(asOf: Instant): IO[Either[KuiError, RequestHandlerReading]] =
    IO.pure(failure.toLeft(RequestHandlerReading(Some(0.64d), Some(0.71d), List(PurgatoryQueue("Fetch", 961)))))

  def producers(count: Int, asOf: Instant): IO[Either[KuiError, TopProducers]] =
    IO.pure(failure.toLeft(TopProducers.of(List(TopicProducer("orders.v1", 98000.0d)), count)))

  def recordSize(asOf: Instant): IO[Either[KuiError, RecordSizeReading]] =
    IO.pure(failure.toLeft(RecordSizeReading.from(Some(1024.0d), Some(8.0d))))
}

/** Which clusters this fixture knows, and which of them have a collector behind them. */
final class FakeSources(profiles: List[SourceProfile], ports: Map[ClusterId, MetricsSourcePort[IO]])
    extends ClusterSources[IO] {

  private val byId = profiles.map(profile => profile.cluster -> profile).toMap

  def all: IO[List[SourceProfile]] = IO.pure(profiles)
  def profile(cluster: ClusterId): IO[Option[SourceProfile]] = IO.pure(byId.get(cluster))
  def source(cluster: ClusterId): IO[Option[MetricsSourcePort[IO]]] = IO.pure(ports.get(cluster))
}

/** The three-way answer, which every endpoint this service grows will copy.
  *
  * The cases that matter are the two that are *not* failures. A deployment with no metrics source and a
  * deployment whose exporter is down both have no number to show, and the screens draw them differently: one
  * keeps a written sentence, the other offers a retry. Collapsing them is the failure this service is shaped
  * to avoid, so it is asserted here rather than left to the route.
  */
final class MetricsUseCasesSuite extends CatsEffectSuite {

  private val local = ClusterId.unsafe("local")
  private val other = ClusterId.unsafe("other")

  private def profile(cluster: ClusterId, hasSource: Boolean): SourceProfile =
    SourceProfile(cluster, cluster.value, hasSource)

  private def useCase(
      profiles: List[SourceProfile],
      ports: Map[ClusterId, MetricsSourcePort[IO]] = Map.empty
  ): MetricsUseCases[IO] =
    MetricsUseCases.make[IO](new FakeSources(profiles, ports))

  test("a cluster KUI has never heard of is a 404 and not an empty chart") {
    useCase(List(profile(local, hasSource = false)))
      .throughput(other, ThroughputRange.Last24Hours)
      .map {
        case Left(error) => assertEquals(error.code, ErrorCode.ClusterNotFound)
        case Right(reading) => fail(s"expected a not-found error, got $reading")
      }
  }

  test("a cluster with no source configured is NotMeasured, which is not a failure") {
    useCase(List(profile(local, hasSource = false)))
      .throughput(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          // The sentence has to name the key, because the person reading it is the one wondering why the
          // throughput card is showing prose instead of a chart.
          assert(explanation.contains("kui.metrics.sources"), explanation)
        case other => fail(s"expected NotMeasured, got $other")
      }
  }

  test("a cluster that configured a source this build cannot read says so differently") {
    val jmx = "kui.metrics.sources.local.kind: jmx, and this build reads Prometheus only"

    useCase(List(SourceProfile(local, local.value, hasSource = true, unreadableReason = Some(jmx))))
      .throughput(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          // Not "nothing is configured": the operator configured something, and telling them otherwise
          // would send them to re-read their own YAML instead of to the line that says what is missing.
          // The sentence is the adapter's, carried through unchanged rather than rewritten here — this
          // layer does not know which protocols exist.
          assertEquals(explanation, jmx)
        case other => fail(s"expected NotMeasured, got $other")
      }
  }

  test("a configured source with no reason given still does not read as an empty deployment") {
    // The fallback. A profile that says "configured" and carries no explanation is a bug in an adapter,
    // and the failure it must not produce is the sentence for a deployment that configured nothing: an
    // operator sent to re-read a YAML file that is correct.
    useCase(List(profile(local, hasSource = true)))
      .throughput(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          assert(explanation.contains("cannot read"), explanation)
          assert(!explanation.contains("no metrics source is configured"), explanation)
        case other => fail(s"expected NotMeasured, got $other")
      }
  }

  test("a source that answers yields a Measured reading with the moment it was taken") {
    useCase(List(profile(local, hasSource = true)), Map(local -> new FakeSource()))
      .throughput(local, ThroughputRange.Last7Days)
      .map {
        case Right(MetricsReading.Measured(series, _)) =>
          assertEquals(series.range, ThroughputRange.Last7Days)
          assertEquals(series.buckets.size, ThroughputRange.Last7Days.bucketCount)
        case other => fail(s"expected Measured, got $other")
      }
  }

  test("a source that refuses is Unreadable, not NotMeasured and not a 500") {
    val down = InfrastructureError.Unreachable("metrics-exporter", "connection refused")

    useCase(List(profile(local, hasSource = true)), Map(local -> new FakeSource(Some(down))))
      .throughput(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.Unreadable(failure, _)) => assertEquals(failure.code, down.code)
        case other => fail(s"expected Unreadable, got $other")
      }
  }

  test("every endpoint answers NotMeasured for a cluster with no source, not just throughput") {
    // The three-way rule is written once, in `ask`, and this is what says every method goes through it.
    // A fifth endpoint bound directly to the port would answer an `unavailable` section for a deployment
    // that simply configured nothing, which is the red panel this service exists to avoid.
    val cases = useCase(List(profile(local, hasSource = false)))

    for {
      throughput <- cases.throughput(local, ThroughputRange.Last24Hours)
      latency <- cases.latency(local, ThroughputRange.Last24Hours)
      handlers <- cases.requestHandlers(local)
      producers <- cases.producers(local, 5)
      recordSize <- cases.recordSize(local)
    } yield List(throughput, latency, handlers, producers, recordSize).foreach {
      case Right(MetricsReading.NotMeasured(explanation)) =>
        assert(explanation.contains("kui.metrics.sources"), explanation)
      case other => fail(s"expected NotMeasured from every endpoint, got $other")
    }
  }

  test("every endpoint is a 404 for a cluster KUI has never heard of") {
    val cases = useCase(List(profile(local, hasSource = false)))

    for {
      latency <- cases.latency(other, ThroughputRange.Last24Hours)
      handlers <- cases.requestHandlers(other)
      producers <- cases.producers(other, 5)
      recordSize <- cases.recordSize(other)
    } yield List(latency, handlers, producers, recordSize).foreach {
      case Left(error) => assertEquals(error.code, ErrorCode.ClusterNotFound)
      case Right(reading) => fail(s"expected a not-found error, got $reading")
    }
  }

  test("a source that refuses one family is Unreadable on that endpoint and not a 500") {
    // The refusal that is not a deployment choice: the exporter answered and publishes no such family, so
    // the card names a whitelist rather than waiting for an axis to fill.
    val absent = InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, "no RequestMetrics family", Nil)

    useCase(List(profile(local, hasSource = true)), Map(local -> new FakeSource(Some(absent))))
      .latency(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.Unreadable(failure, _)) => assertEquals(failure.code, absent.code)
        case other => fail(s"expected Unreadable, got $other")
      }
  }

  test("the top count asked for reaches the port rather than being decided here") {
    // `?top=` is the caller's, and a use case that clamped or ignored it would answer a five-row card for
    // a request for one and nothing on the screen would say so.
    val counting = new MetricsSourcePort[IO] {
      def throughput(range: ThroughputRange, endingAt: Instant) =
        IO.pure(Right(ThroughputSeries.absent(range, endingAt)))
      def latency(range: ThroughputRange, endingAt: Instant) =
        IO.pure(Right(LatencySeries.absent(range, endingAt)))
      def requestHandlers(asOf: Instant) = IO.pure(Right(RequestHandlerReading.Empty))
      def producers(count: Int, asOf: Instant) =
        IO.pure(Right(TopProducers(List.tabulate(count)(index => TopicProducer(s"topic-$index", 1.0d)))))
      def recordSize(asOf: Instant) = IO.pure(Right(RecordSizeReading.Empty))
    }

    useCase(List(profile(local, hasSource = true)), Map(local -> counting)).producers(local, 3).map {
      case Right(MetricsReading.Measured(producers, _)) => assertEquals(producers.topics.size, 3)
      case other => fail(s"expected Measured, got $other")
    }
  }
}
