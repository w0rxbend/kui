package kui.cache

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO

import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeClock

/** What the cell adds to the window: a `Ref`, and the four counters ADR-016 requires of every cache.
  *
  * The arithmetic is `SeriesWindowSuite`'s subject. This suite only asserts that an operator reading
  * `kui.cache.hits` and `kui.cache.misses` can tell a window that is filling up from one that is being read
  * successfully, and both from one whose collector has stopped.
  */
final class SeriesWindowCellSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod")
  private val name = "metrics.throughput"
  private val step: FiniteDuration = 1.minute
  private val start: Instant = FakeClock.Epoch

  private def cellOf(
      metrics: CacheMetrics[IO],
      startedAt: Instant = FakeClock.Epoch
  ): IO[SeriesWindowCell[IO, Int]] =
    SeriesWindowCell.create[IO, Int](name, cluster, step, 1.hour, 60, startedAt, metrics)

  test("recordedSamplesComeBackAsBucketsAndCountAsAHit") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(3600L))
      _ <- cell.record(start, 1)
      _ <- cell.record(start.plusSeconds(120L), 3)
      buckets <- cell.bucketsOver(3.minutes, start.plusSeconds(120L))
      latest <- cell.latest(start.plusSeconds(120L))
      hits <- metrics.countOf("hit")
      misses <- metrics.countOf("miss")
    } yield {
      assertEquals(buckets.map(_.map(_.value)), Some(Vector(Some(1), None, Some(3))))
      assertEquals(latest, Some(SeriesSample(start.plusSeconds(120L), 3)))
      assertEquals(hits, 2)
      assertEquals(misses, 0)
    }
  }

  test("aReadTheWindowCannotSpanIsAMissNotAnEmptyAnswer") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      // Collecting since `start`, asked at `start` + 1 minute for a day.
      cell <- cellOf(metrics)
      _ <- cell.record(start, 1)
      answer <- cell.bucketsOver(24.hours, start.plusSeconds(60L))
      misses <- metrics.countOf("miss")
      hits <- metrics.countOf("hit")
    } yield {
      assertEquals(answer, None)
      // The counter is what tells an operator the window is still filling rather than broken.
      assertEquals(misses, 1)
      assertEquals(hits, 0)
    }
  }

  test("aCollectorThatStoppedMakesEveryReadAStaleRead") {
    val later = start.plusSeconds(600L)

    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(3600L))
      _ <- cell.record(start, 1)
      _ <- cell.bucketsOver(5.minutes, start)
      freshReads <- metrics.countOf("stale")
      // Ten minutes later nothing has been recorded: the answer's tail is gaps, and it says so.
      _ <- cell.bucketsOver(5.minutes, later)
      staleReads <- metrics.countOf("stale")
      hits <- metrics.countOf("hit")
    } yield {
      assertEquals(freshReads, 0)
      assertEquals(staleReads, 1)
      // A stale read is a hit as well: it did serve data, exactly as `SnapshotCell` counts it.
      assertEquals(hits, 2)
    }
  }

  test("aSampleOlderThanTheHorizonIsCountedAsAFailedRefresh") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(3600L))
      _ <- cell.record(start, 1)
      // A clock corrected backwards by a day. It cannot land, and silence would be the wrong report.
      _ <- cell.record(start.minusSeconds(86400L), 9)
      window <- cell.read(start)
      failures <- metrics.countOf("refreshFailed")
    } yield {
      assertEquals(window.size, 1)
      assertEquals(failures, 1)
    }
  }

  test("readEvictsSoAWindowNobodyIsFeedingEmptiesOnItsOwn") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(3600L))
      _ <- cell.record(start, 1)
      // Two hours later, with a one-hour retention: the read prunes, so nothing hands out an old sample
      // merely because no scrape arrived to prune it.
      window <- cell.read(start.plusSeconds(7200L))
      latest <- cell.latest(start.plusSeconds(7200L))
      misses <- metrics.countOf("miss")
    } yield {
      assert(window.isEmpty)
      assertEquals(latest, None)
      assertEquals(misses, 2)
    }
  }

  test("aWindowWhoseSamplesHaveAllBeenEvictedIsAMissEvenThoughItStillSpansThePeriod") {
    // The one read where the window's shape and its contents disagree. It has been collecting since
    // yesterday, so it spans any period up to its retention and `bucketsOver` answers; retention is an hour
    // and the only sample is three hours old, so every bucket of that answer is a gap. This is precisely
    // the exporter that died overnight, and counting it as a fresh hit is the reading that hides the outage.
    val threeHoursLater = start.plusSeconds(10800L)

    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(86400L))
      _ <- cell.record(start, 1)
      answer <- cell.bucketsOver(30.minutes, threeHoursLater)
      hits <- metrics.countOf("hit")
      misses <- metrics.countOf("miss")
      stale <- metrics.countOf("stale")
    } yield {
      assertEquals(answer.map(_.size), Some(30))
      assert(answer.exists(_.forall(_.value.isEmpty)))
      assertEquals(misses, 1)
      // Not a hit and not a stale read either: `isStaleAt` is false for an empty window, so counting the
      // answer as a hit would leave the outage invisible on both counters at once.
      assertEquals(hits, 0)
      assertEquals(stale, 0)
    }
  }

  test("clearStartsTheCoverageClockAgain") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      cell <- cellOf(metrics, start.minusSeconds(7200L))
      _ <- cell.record(start, 1)
      before <- cell.bucketsOver(30.minutes, start)
      // A profile change: the samples describe a cluster KUI is no longer talking to, and the new one has
      // not been collecting for thirty minutes however long the old one had.
      _ <- cell.clear(start)
      after <- cell.bucketsOver(30.minutes, start)
      window <- cell.read(start)
    } yield {
      assert(before.isDefined)
      assertEquals(after, None)
      assert(window.isEmpty)
      assertEquals(window.startedAt, start)
    }
  }
}
