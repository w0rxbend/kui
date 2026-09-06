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
final class ThroughputUseCaseSuite extends CatsEffectSuite {

  private val local = ClusterId.unsafe("local")
  private val other = ClusterId.unsafe("other")

  private def profile(cluster: ClusterId, hasSource: Boolean): SourceProfile =
    SourceProfile(cluster, cluster.value, hasSource)

  private def useCase(
      profiles: List[SourceProfile],
      ports: Map[ClusterId, MetricsSourcePort[IO]] = Map.empty
  ): ThroughputUseCase[IO] =
    ThroughputUseCase.make[IO](new FakeSources(profiles, ports))

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
    useCase(List(profile(local, hasSource = true)))
      .throughput(local, ThroughputRange.Last24Hours)
      .map {
        case Right(MetricsReading.NotMeasured(explanation)) =>
          // Not "nothing is configured": the operator configured something, and telling them otherwise
          // would send them to re-read their own YAML instead of to the milestone that adds the collector.
          assert(explanation.contains("no collector"), explanation)
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
}
