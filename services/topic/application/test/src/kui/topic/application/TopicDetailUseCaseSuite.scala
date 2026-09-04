package kui.topic.application

import java.time.Instant

import cats.effect.{Deferred, IO}
import org.scalacheck.Prop.forAll

import kui.cache.{Snapshot, SnapshotCell}
import kui.kernel.{ClusterId, PartitionId, TopicName}
import kui.testkit.KuiSuite
import kui.topic.domain.*

/** The detail page's use case: live when it can be, the snapshot when it must be, and never both silently. */
final class TopicDetailUseCaseSuite extends munit.CatsEffectSuite {

  import TopicGenerators.validPartition

  private val cluster: ClusterId = FakeTopicAdmin.cluster
  private val orders: TopicName = TopicName.unsafe("orders")
  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")

  private val live: TopicDetail =
    TopicDetail.of(
      orders,
      isInternal = false,
      List(validPartition(0, Some(1), List(1, 2), List(1, 2), earliest = Some(0L), latest = Some(10L)))
    )

  private val stale: TopicSummary = TopicSummary.of(orders, isInternal = false, Nil)

  /** A `TopicSnapshots` backed by whatever a test wants the cell to hold. */
  private def snapshotsOf(cell: Option[SnapshotCell[IO, TopicSnapshot]]): TopicSnapshots[IO] =
    new TopicSnapshots[IO] {
      def of(id: ClusterId): IO[Option[SnapshotCell[IO, TopicSnapshot]]] =
        IO.pure(if id == cluster then cell else None)
      def requestRefresh(id: ClusterId): IO[Boolean] = IO.pure(cell.isDefined)
    }

  private def holding(snapshot: Snapshot[TopicSnapshot]): SnapshotCell[IO, TopicSnapshot] =
    new SnapshotCell[IO, TopicSnapshot] {
      def get: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def refresh: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def invalidate: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
      def updates: fs2.Stream[IO, Snapshot[TopicSnapshot]] = fs2.Stream.emit(snapshot)
    }

  private val withStaleRow: SnapshotCell[IO, TopicSnapshot] =
    holding(Snapshot.online(TopicSnapshot.of(Vector(stale), at), at))

  /** An admin whose live read always fails the way an unreachable cluster does. */
  private val unreachable: TopicAdmin[IO] = new TopicAdmin[IO] {
    def scrape(id: ClusterId) = IO.pure(Left(TopicError.Unreachable("timed out", retryable = true)))
    def detail(id: ClusterId, topic: TopicName) =
      IO.pure(Left(TopicError.Unreachable("timed out", retryable = true)))
    def config(id: ClusterId, topic: TopicName) =
      IO.pure(Left(TopicError.Unreachable("timed out", retryable = true)))
  }

  test("aLiveReadIsReportedAsLive") {
    FakeTopicAdmin
      .of(List(live))
      .flatMap(admin => TopicDetailUseCase.make[IO](admin, snapshotsOf(Some(withStaleRow))).detail(cluster, orders))
      .map {
        case Right(Fresh.Live(detail)) => assertEquals(detail.partitions.size, 1)
        case other => fail(s"expected a live read, got $other")
      }
  }

  test("aFailedLiveReadFallsBackToTheSnapshotAndSaysSo") {
    TopicDetailUseCase.make[IO](unreachable, snapshotsOf(Some(withStaleRow))).detail(cluster, orders).map {
      case Right(Fresh.FromSnapshot(detail, taken, reason)) =>
        assertEquals(detail.summary.name, orders)
        assertEquals(taken, at, "the badge shows when the data was seen, not when the request was made")
        assert(reason.contains("timed out"), s"the reason must survive to the screen: $reason")
      case other => fail(s"expected a snapshot fallback, got $other")
    }
  }

  test("aFailedLiveReadWithNoSnapshotIsAnError") {
    TopicDetailUseCase.make[IO](unreachable, snapshotsOf(None)).detail(cluster, orders).map { result =>
      assertEquals(result, Left(TopicError.Unreachable("timed out", retryable = true)))
    }
  }

  test("aFailedLiveReadWithAnEmptySnapshotIsAnError") {
    val loading = holding(Snapshot.initializing[TopicSnapshot])

    TopicDetailUseCase.make[IO](unreachable, snapshotsOf(Some(loading))).detail(cluster, orders).map { result =>
      assertEquals(result, Left(TopicError.Unreachable("timed out", retryable = true)))
    }
  }

  test("anUnknownTopicIsNotFoundEvenWhenTheSnapshotIsStale") {
    // A topic deleted since the last scrape must not be resurrected by its own fallback: the page would show
    // partitions for something that is gone, which is worse than a 404.
    FakeTopicAdmin
      .of(Nil)
      .flatMap(admin => TopicDetailUseCase.make[IO](admin, snapshotsOf(Some(withStaleRow))).detail(cluster, orders))
      .map(result => assertEquals(result, Left(TopicError.NotFound(orders))))
  }

  test("aTopicMissingFromTheFallbackSnapshotIsNotFound") {
    val empty = holding(Snapshot.online(TopicSnapshot.empty(at), at))

    TopicDetailUseCase.make[IO](unreachable, snapshotsOf(Some(empty))).detail(cluster, orders).map { result =>
      assertEquals(result, Left(TopicError.NotFound(orders)))
    }
  }

  test("anUnknownClusterIsClusterNotFound") {
    val elsewhere = ClusterId.unsafe("elsewhere")

    FakeTopicAdmin
      .of(List(live))
      .flatMap(admin => TopicDetailUseCase.make[IO](admin, snapshotsOf(None)).detail(elsewhere, orders))
      .map(result => assertEquals(result, Left(TopicError.ClusterNotFound(elsewhere))))
  }

  test("cancellingTheRequestCancelsTheAdminCall") {
    for {
      started <- Deferred[IO, Unit]
      finalised <- Deferred[IO, Unit]
      admin = new TopicAdmin[IO] {
        def scrape(id: ClusterId) = IO.pure(Right(ScrapeResult.empty))
        def detail(id: ClusterId, topic: TopicName) =
          started.complete(()).productR(IO.never).guarantee(finalised.complete(()).void)
        def config(id: ClusterId, topic: TopicName) = IO.never
      }
      fiber <- TopicDetailUseCase.make[IO](admin, snapshotsOf(None)).detail(cluster, orders).start
      _ <- started.get
      _ <- fiber.cancel
      _ <- finalised.get
    } yield ()
  }
}

/** The pure half, stated without an effect in the way. */
final class TopicDetailFreshnessSuite extends KuiSuite {

  import TopicGenerators.instant

  private val snapshot: TopicSnapshot = TopicSnapshot.empty(Instant.EPOCH)

  test("anInitialisingSnapshotIsNotAFallback") {
    assert(!TopicDetailUseCase.hasFallback(Snapshot.initializing[TopicSnapshot]))
  }

  property("aLoadedSnapshotIsAlwaysAFallback") {
    forAll(instant) { at =>
      assert(TopicDetailUseCase.hasFallback(Snapshot.online(snapshot, at)))
    }
  }

  test("partitionsAreOrderedByPartitionId") {
    val detail = TopicDetail.of(
      TopicName.unsafe("orders"),
      isInternal = false,
      List(2, 0, 1).map(id =>
        TopicGenerators.validPartition(id, Some(1), List(1), List(1), earliest = Some(0L), latest = Some(1L))
      )
    )

    assertEquals(detail.partitions.map(_.partition.value), List(0, 1, 2))
    assertEquals(detail.partitions.map(_.partition), List(0, 1, 2).map(PartitionId.unsafe))
  }
}
