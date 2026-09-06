package kui.metrics.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.syntax.all.*

import kui.cache.CacheMetrics
import kui.kernel.ClusterId
import kui.metrics.domain.{ThroughputRange, ThroughputSample}
import kui.testkit.KuiIOSuite

/** The retention half of the collector, and the axis rule it exists to keep.
  *
  * Everything here goes through `ThroughputBuffer.throughput`, which is the method the endpoint reaches
  * through `ThroughputUseCase` — not through the domain's fold underneath it. The distinction is the whole
  * point: the fold has been gated since wave 1 and the buffer is the thing that could quietly stop calling
  * it, which is precisely the shape of an adapter that returns "the buckets I have".
  */
final class ThroughputBufferSuite extends KuiIOSuite {

  private val cluster = ClusterId.unsafe("quickstart")
  private val range = ThroughputRange.Last24Hours

  /** A clock instant on a bucket boundary of the 24h range, so "the bucket a sample lands in" is a thing a
    * reader can follow.
    */
  private val noon = Instant.parse("2026-09-06T12:00:00Z")

  private def buffer(startedAt: Instant = noon.minusSeconds(3600)): IO[ThroughputBuffer[IO]] =
    ThroughputBuffer.create[IO](
      cluster = cluster,
      step = 30.seconds,
      retention = 24.hours,
      maxSamples = 5000,
      startedAt = startedAt,
      metrics = CacheMetrics.noop[IO]
    )

  private def sample(at: Instant, bytesIn: Double): ThroughputSample =
    ThroughputSample(at, Some(bytesIn), Some(bytesIn * 2), Some(bytesIn / 100))

  test("a range with three samples answers bucketCount buckets of which three carry values") {
    // The rule this packet owns. A quiet hour has to draw the same axis as a busy one: `bucketCount` is a
    // property of the range and never of what was sampled, so a window holding three readings answers 288
    // buckets with 285 gaps in them — not three buckets, and not a shorter axis.
    //
    // It is asserted here, at the port the use case holds, because this is where an adapter gets it wrong:
    // the obvious implementation of "answer the throughput" is to hand back the samples that exist.
    buffer().flatMap { held =>
      val samples = List(
        sample(noon.minusSeconds(1800), 100.0),
        sample(noon.minusSeconds(1200), 200.0),
        sample(noon.minusSeconds(600), 300.0)
      )

      samples.traverse_(held.record) *> held.throughput(range, noon).map {
        case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
        case Right(series) =>
          assertEquals(series.buckets.size, range.bucketCount)
          assertEquals(series.buckets.count(!_.isAbsent), 3)
          assertEquals(series.buckets.count(_.isAbsent), range.bucketCount - 3)
          assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(100.0, 200.0, 300.0))
      }
    }
  }

  test("every range answers its own bucketCount from the same three samples") {
    // The axis is the range's, so the same window answers 288, 168 and 120 without being told twice.
    buffer(noon.minusSeconds(30.days.toSeconds)).flatMap { held =>
      List(600L, 1200L, 1800L)
        .traverse_(ago => held.record(sample(noon.minusSeconds(ago), 10.0))) *>
        ThroughputRange.All.traverse_(each =>
          held.throughput(each, noon).map {
            case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
            case Right(series) => assertEquals(series.buckets.size, each.bucketCount, each.wire)
          }
        )
    }
  }

  test("a bucket nothing landed in is absent, and a measured zero is not") {
    // The two facts a chart draws differently, arriving through the buffer rather than through the fold.
    buffer().flatMap { held =>
      held.record(sample(noon.minusSeconds(60), 0.0)) *> held.throughput(range, noon).map {
        case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
        case Right(series) =>
          val measured = series.buckets.filter(!_.isAbsent)
          assertEquals(measured.size, 1)
          assertEquals(measured.head.bytesInPerSecond, Some(0.0))
          assert(series.buckets.head.isAbsent)
      }
    }
  }

  test("a window younger than the range answers the full axis rather than refusing") {
    // `SeriesWindowCell.bucketsOver` answers `None` until it has been collecting for the whole period,
    // which is the right refusal for a percentage over a day and the wrong one for an axis: a process one
    // minute old has a day-shaped chart with one minute of it filled in, and nothing to say if it refuses.
    buffer(startedAt = noon.minusSeconds(60)).flatMap { held =>
      held.record(sample(noon.minusSeconds(30), 42.0)) *> held.throughput(range, noon).map {
        case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
        case Right(series) =>
          assertEquals(series.buckets.size, range.bucketCount)
          assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(42.0))
      }
    }
  }

  test("a retention shorter than one scrape interval builds a window rather than refusing to start") {
    // `kui.metrics.retention` and `kui.metrics.scrapeInterval` are bounded independently — the loader
    // accepts one minute beside one hour — and `SeriesWindow.empty` requires `maxAge >= step`. Unwidened,
    // that pair is an `IllegalArgumentException` inside the composition root, which is a process that will
    // not start over a configuration the loader said was fine. The honest reading of "keep a minute of
    // hourly samples" is "keep the one you have".
    ThroughputBuffer
      .create[IO](cluster, 1.hour, 1.minute, 5000, noon.minusSeconds(86400), CacheMetrics.noop[IO])
      .flatMap { held =>
        held.record(sample(noon.minusSeconds(60), 3.0)) *> held.throughput(range, noon).map {
          case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
          case Right(series) =>
            assertEquals(series.buckets.size, range.bucketCount)
            assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(3.0))
        }
      }
  }

  test("a sample older than the retention window is dropped and does not shorten the axis") {
    ThroughputBuffer
      .create[IO](cluster, 30.seconds, 10.minutes, 5000, noon.minusSeconds(86400), CacheMetrics.noop[IO])
      .flatMap { held =>
        held.record(sample(noon.minusSeconds(3600), 99.0)) *>
          held.record(sample(noon.minusSeconds(60), 7.0)) *>
          held.throughput(range, noon).map {
            case Left(failure) => fail(s"a buffer never refuses a read; got $failure")
            case Right(series) =>
              assertEquals(series.buckets.size, range.bucketCount)
              assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(7.0))
          }
      }
  }
}
