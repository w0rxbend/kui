package kui.topic.application

import java.time.Instant

import cats.effect.IO

import kui.cache.{Snapshot, SnapshotCell, SnapshotStatus}
import kui.kernel.error.InfrastructureError
import kui.kernel.ClusterId
import kui.topic.domain.{ClusterProfiles, ClusterRef, TopicSnapshot}

/** The per-cluster capability fold, and the isolation it exists to provide. */
final class TopicCapabilitySuite extends munit.CatsEffectSuite {

  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")
  private val failedAt: Instant = Instant.parse("2026-09-04T08:00:00Z")
  private val failure = InfrastructureError.Unreachable("the cluster", "connection refused")

  private def cellHolding(snapshot: Snapshot[TopicSnapshot]): SnapshotCell[IO, TopicSnapshot] =
    new SnapshotCell[IO, TopicSnapshot] {
      def get: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def refresh: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def invalidate: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def updates: fs2.Stream[IO, Snapshot[TopicSnapshot]] = fs2.Stream.emit(snapshot)
    }

  private def profilesOf(ids: List[ClusterId]): ClusterProfiles[IO] = new ClusterProfiles[IO] {
    def all: IO[List[ClusterRef]] = IO.pure(ids.map(id => ClusterRef(id, id.value, readOnly = false)))
    def get(id: ClusterId): IO[Option[ClusterRef]] = all.map(_.find(_.id == id))
    def onChange(handler: Set[ClusterId] => IO[Unit]): IO[IO[Unit]] = IO.pure(IO.unit)
  }

  private def snapshotsOf(cells: Map[ClusterId, Snapshot[TopicSnapshot]]): TopicSnapshots[IO] =
    new TopicSnapshots[IO] {
      def of(id: ClusterId): IO[Option[SnapshotCell[IO, TopicSnapshot]]] =
        IO.pure(cells.get(id).map(cellHolding))
      def requestRefresh(id: ClusterId): IO[Boolean] = IO.pure(cells.contains(id))
    }

  private val scraped: Snapshot[TopicSnapshot] = Snapshot.online(TopicSnapshot.empty(at), at)
  private val stale: Snapshot[TopicSnapshot] =
    Snapshot(Some(TopicSnapshot.empty(at)), SnapshotStatus.Offline(failure, failedAt), Some(at))
  private val neverScraped: Snapshot[TopicSnapshot] =
    Snapshot(None, SnapshotStatus.Offline(failure, failedAt), None)

  test("availableAfterASuccessfulScrape") {
    assertEquals(TopicCapabilityUseCase.fold(scraped), TopicCapability.Available(at))
  }

  test("degradedWhileServingAStaleSnapshot") {
    TopicCapabilityUseCase.fold(stale) match {
      case TopicCapability.Degraded(reason, since, lastScrapedAt) =>
        assertEquals(since, failedAt, "`since` is when the failures began, not when the latest one happened")
        assertEquals(lastScrapedAt, Some(at))
        assert(reason.nonEmpty)
      case other => fail(s"a failing scrape with data in hand is Degraded, got $other")
    }
  }

  test("theDegradedSinceDoesNotMoveOnRepeatedFailures") {
    // `SnapshotStatus.Offline` carries a sticky `since`, and the fold must pass it through rather than
    // stamping "now". An operator's question is "how long has this been broken".
    val later = stale.copy(status = SnapshotStatus.Offline(failure, failedAt))

    assertEquals(TopicCapabilityUseCase.fold(stale), TopicCapabilityUseCase.fold(later))
  }

  test("unavailableWhenNothingWasEverScraped") {
    TopicCapabilityUseCase.fold(neverScraped) match {
      case TopicCapability.Unavailable(_, since) => assertEquals(since, failedAt)
      case other => fail(s"no value at all is Unavailable, got $other")
    }
  }

  test("aSnapshotStillInitialisingIsUnavailableAndNotAvailable") {
    TopicCapabilityUseCase.fold(Snapshot.initializing[TopicSnapshot]) match {
      case TopicCapability.Unavailable(reason, _) => assert(reason.contains("first scrape"))
      case other => fail(s"expected Unavailable, got $other")
    }
  }

  test("oneClustersFailureDoesNotChangeAnothersReport") {
    // DEVPLAN §10 D11, asserted. Dimming the whole Topics feature because one of two clusters is down would
    // hide a screen that works perfectly.
    val healthy = ClusterId.unsafe("healthy")
    val broken = ClusterId.unsafe("broken")
    val useCase = TopicCapabilityUseCase.make[IO](
      profilesOf(List(healthy, broken)),
      snapshotsOf(Map(healthy -> scraped, broken -> neverScraped))
    )

    useCase.report.map { report =>
      assertEquals(report.map(_._1), List(healthy, broken))
      assertEquals(report.head._2, TopicCapability.Available(at))
      assert(report(1)._2 match {
        case TopicCapability.Unavailable(_, _) => true
        case _ => false
      })
    }
  }

  test("aClusterWithNoSnapshotYetIsStillReported") {
    val configured = ClusterId.unsafe("fresh")
    val useCase = TopicCapabilityUseCase.make[IO](profilesOf(List(configured)), snapshotsOf(Map.empty))

    useCase.report.map { report =>
      assertEquals(report.size, 1, "a cluster must never vanish from the report because it has no data")
    }
  }
}
