package kui.metrics.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.syntax.all.*

import kui.cache.CacheMetrics
import kui.kernel.ClusterId
import kui.kernel.error.ErrorCode
import kui.metrics.domain.*
import kui.testkit.KuiIOSuite

/** The retention half of the collector, the axis rule it exists to keep, and the two refusals it tells apart.
  *
  * Everything here goes through the methods the endpoints reach through `MetricsUseCases` — not through the
  * domain's folds underneath them. The distinction is the whole point: the folds have been gated since wave 1
  * and the buffer is the thing that could quietly stop calling them, which is precisely the shape of an
  * adapter that returns "the buckets I have".
  */
final class MetricsBufferSuite extends KuiIOSuite {

  private val cluster = ClusterId.unsafe("quickstart")
  private val range = ThroughputRange.Last24Hours

  /** A clock instant on a bucket boundary of the 24h range, so "the bucket a sample lands in" is a thing a
    * reader can follow.
    */
  private val noon = Instant.parse("2026-09-06T12:00:00Z")

  private def buffer(startedAt: Instant = noon.minusSeconds(3600)): IO[MetricsBuffer[IO]] =
    MetricsBuffer.create[IO](
      cluster = cluster,
      step = 30.seconds,
      retention = 24.hours,
      maxSamples = 5000,
      startedAt = startedAt,
      metrics = CacheMetrics.noop[IO]
    )

  /** A scrape that carried the throughput family and nothing else, which is the shape of a deployment whose
    * exporter whitelists the three byte-rate rules and no more.
    */
  private def sample(at: Instant, bytesIn: Double): BrokerSample =
    BrokerSample
      .empty(at)
      .copy(
        bytesInPerSecond = Some(bytesIn),
        bytesOutPerSecond = Some(bytesIn * 2),
        recordsPerSecond = Some(bytesIn / 100)
      )

  /** A scrape that carried everything, which is the shape of a widened whitelist. */
  private def full(at: Instant, bytesIn: Double, produceP99: Double): BrokerSample =
    sample(at, bytesIn).copy(
      produceP99Millis = Some(produceP99),
      fetchP99Millis = Some(produceP99 * 4),
      requestHandlerIdleRatio = Some(0.8912),
      networkProcessorIdleRatio = Some(0.7104),
      purgatory = List(PurgatoryQueue("Fetch", 481L), PurgatoryQueue("Produce", 0L)),
      topicBytesInPerSecond = Some(List(TopicProducer("orders.v1", 10.0), TopicProducer("audit.log", 90.0)))
    )

  // -----------------------------------------------------------------------------------------------
  // The axis
  // -----------------------------------------------------------------------------------------------

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

      samples.traverse_(held.record(_)) *> held.throughput(range, noon).map {
        case Left(failure) => fail(s"throughput never refuses a read; got $failure")
        case Right(series) =>
          assertEquals(series.buckets.size, range.bucketCount)
          assertEquals(series.buckets.count(!_.isAbsent), 3)
          assertEquals(series.buckets.count(_.isAbsent), range.bucketCount - 3)
          assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(100.0, 200.0, 300.0))
      }
    }
  }

  test("a latency window with three samples answers bucketCount buckets of which three carry values") {
    buffer().flatMap { held =>
      List(1800L, 1200L, 600L).traverse_(ago => held.record(full(noon.minusSeconds(ago), 10.0, 9.0))) *>
        held.latency(range, noon).map {
          case Left(failure) => fail(s"a served family must not refuse; got $failure")
          case Right(series) =>
            assertEquals(series.buckets.size, range.bucketCount)
            assertEquals(series.buckets.count(!_.isAbsent), 3)
            assertEquals(series.buckets.flatMap(_.produceP99Millis), List(9.0, 9.0, 9.0))
            assertEquals(series.buckets.flatMap(_.fetchP99Millis), List(36.0, 36.0, 36.0))
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
            case Left(failure) => fail(s"throughput never refuses a read; got $failure")
            case Right(series) => assertEquals(series.buckets.size, each.bucketCount, each.wire)
          }
        )
    }
  }

  test("a bucket nothing landed in is absent, and a measured zero is not") {
    // The two facts a chart draws differently, arriving through the buffer rather than through the fold.
    buffer().flatMap { held =>
      held.record(sample(noon.minusSeconds(60), 0.0)) *> held.throughput(range, noon).map {
        case Left(failure) => fail(s"throughput never refuses a read; got $failure")
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
        case Left(failure) => fail(s"throughput never refuses a read; got $failure")
        case Right(series) =>
          assertEquals(series.buckets.size, range.bucketCount)
          assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(42.0))
      }
    }
  }

  test("a retention shorter than one scrape interval is refused rather than silently widened") {
    // `KuiConfigSource.checkMetricsRules` refuses `retention < scrapeInterval` at load, by name, so this
    // pair cannot come from a configuration file. It used to be widened here anyway, under a paragraph
    // claiming the loader accepted it — which meant a configuration the loader refuses would have behaved
    // as though it had been accepted, in the one service that exists to keep figures honest. The window's
    // own invariant is the second half of one rule now, not a second opinion about it.
    // `IO.defer`, because `SeriesWindow.empty` checks its own invariant while the effect is being built
    // rather than while it runs: the refusal reaches a composition root as a start-up failure naming the
    // two durations, which is the loudest place it could land.
    IO.defer(MetricsBuffer.create[IO](cluster, 1.hour, 1.minute, 5000, noon, CacheMetrics.noop[IO]))
      .attempt
      .map {
        case Left(refusal) =>
          assert(refusal.getMessage.contains("must be at least one step"), refusal.toString)
        case Right(_) => fail("the pair the loader refuses must not be silently widened here")
      }
  }

  test("a sample older than the retention window is dropped and does not shorten the axis") {
    MetricsBuffer
      .create[IO](cluster, 30.seconds, 10.minutes, 5000, noon.minusSeconds(86400), CacheMetrics.noop[IO])
      .flatMap { held =>
        held.record(sample(noon.minusSeconds(3600), 99.0)) *>
          held.record(sample(noon.minusSeconds(60), 7.0)) *>
          held.throughput(range, noon).map {
            case Left(failure) => fail(s"throughput never refuses a read; got $failure")
            case Right(series) =>
              assertEquals(series.buckets.size, range.bucketCount)
              assertEquals(series.buckets.flatMap(_.bytesInPerSecond), List(7.0))
          }
      }
  }

  // -----------------------------------------------------------------------------------------------
  // The point-in-time readings, and the two refusals
  // -----------------------------------------------------------------------------------------------

  test("the newest scrape is what the three current readings answer from") {
    buffer().flatMap { held =>
      held.record(full(noon.minusSeconds(600), 10.0, 3.0)) *>
        held.record(full(noon.minusSeconds(60), 90.0, 9.0)) *>
        (held.requestHandlers(noon), held.producers(1, noon), held.recordSize(noon)).tupled.map {
          case (Right(handlers), Right(producers), Right(recordSize)) =>
            assertEquals(handlers.value.requestHandlerIdleRatio, Some(0.8912))
            assertEquals(
              handlers.value.purgatory,
              List(PurgatoryQueue("Fetch", 481L), PurgatoryQueue("Produce", 0L))
            )
            // Ranked by rate and cut to the count asked for, in the adapter rather than in the browser.
            assertEquals(producers.value.topics.map(_.topic), List("audit.log"))
            // 90 bytes/s over 0.9 records/s is 100 bytes a record, from the newest scrape and not the first.
            assertEquals(recordSize.value.meanBytes, Some(100.0))
            // And each carries the instant of the scrape it came from rather than the moment it was asked
            // for. Without it the layer above cannot tell a fresh gauge from an hour-old one, which is how
            // last-known-good readings came to be drawn as current.
            assertEquals(handlers.at, noon.minusSeconds(60))
            assertEquals(producers.at, noon.minusSeconds(60))
            assertEquals(recordSize.at, noon.minusSeconds(60))
          case other => fail(s"expected three readings, got $other")
        }
    }
  }

  test("a family the exporter does not serve is a stated refusal and not a zero") {
    // The scrape succeeded and carried the byte rates only. Answering `RequestHandlerReading.Empty` would
    // draw three gauges reading zero — a saturated broker — and an empty top-producers list would read as
    // a cluster with no traffic. Both have to be the `unavailable` section with a sentence instead.
    buffer().flatMap { held =>
      held.record(sample(noon.minusSeconds(60), 42.0)) *>
        (held.requestHandlers(noon), held.producers(5, noon), held.latency(range, noon)).tupled.map {
          case (Left(handlers), Left(producers), Left(latency)) =>
            assertEquals(handlers.code, ErrorCode.UpstreamUnavailable)
            assert(handlers.message.contains("whitelist"), handlers.message)
            assert(producers.message.contains("per-topic bytes-in rate"), producers.message)
            assert(latency.message.contains("request-latency percentile"), latency.message)
          case other => fail(s"expected three refusals, got $other")
        }
    }
  }

  test("a window whose latest scrapes carry no percentile is a series with a gap, not a refused card") {
    // The rule `samples.forall(_.isEmpty)` keeps and `samples.exists(_.isEmpty)` destroys. The exporter
    // served a percentile ten minutes ago and stopped — a broker restart, a ruleset reloaded, a family that
    // went quiet — and the window now holds one reading with a latency and two without. Refusing would throw
    // away the reading that exists and would name a whitelist problem on a deployment that has none; the
    // honest answer is the axis, with the last two steps blank.
    buffer().flatMap { held =>
      held.record(full(noon.minusSeconds(600), 10.0, 9.0)) *>
        held.record(sample(noon.minusSeconds(300), 20.0)) *>
        held.record(sample(noon.minusSeconds(60), 30.0)) *>
        held.latency(range, noon).map {
          case Left(failure) => fail(s"a window with one measured step is not a refusal; got $failure")
          case Right(series) =>
            assertEquals(series.buckets.size, range.bucketCount)
            assertEquals(series.buckets.flatMap(_.produceP99Millis), List(9.0))
            assertEquals(series.buckets.count(!_.isAbsent), 1)
        }
    }
  }

  test("a window in which no scrape ever carried a percentile refuses, and names the whitelist") {
    // The other side of the same rule, so it cannot be satisfied by never refusing. Three scrapes, all of
    // them without a `RequestMetrics` family: an axis of gaps here is indistinguishable from a KUI that
    // started a minute ago, and only one of those two ever fills in.
    buffer().flatMap { held =>
      List(600L, 300L, 60L).traverse_(ago => held.record(sample(noon.minusSeconds(ago), 20.0))) *>
        held.latency(range, noon).map {
          case Left(failure) =>
            assert(failure.message.contains("request-latency percentile"), failure.message)
          case Right(series) => fail(s"a family no scrape carried must be named; got ${series.buckets.size}")
        }
    }
  }

  test("a window nothing has been scraped into refuses with a different sentence") {
    // Nothing scraped yet clears itself when the exporter answers; a family that is not whitelisted never
    // will. The card's next move is different, so the sentence has to be.
    buffer().flatMap { held =>
      held.requestHandlers(noon).map {
        case Left(failure) =>
          assert(failure.message.contains("no scrape has succeeded"), failure.message)
          assert(!failure.message.contains("whitelist"), failure.message)
        case Right(reading) => fail(s"expected a refusal, got $reading")
      }
    }
  }

  test("throughput still answers an axis when nothing has been scraped, unlike the other four") {
    // Deliberate asymmetry, and the reason is what a card can draw. A series has an axis to show: a day of
    // gaps says "KUI has nothing for this window" all by itself. A gauge has nothing at all to draw, so it
    // needs the sentence. `deployment/compose/smoke.sh` asserts this section is `ok`.
    buffer().flatMap(held => held.throughput(range, noon)).map {
      case Right(series) => assertEquals(series.buckets.size, range.bucketCount)
      case Left(failure) => fail(s"throughput never refuses a read; got $failure")
    }
  }

  test("a served family with no topic line answers an empty list, which is not a refusal") {
    // "The whitelist omits this family entirely" and "the family is here and ranks nothing" are different
    // facts, and only the first is worth an `unavailable` section. Seen on a live broker behind an exporter
    // whose ruleset had a broker-wide rule and no per-topic one.
    buffer().flatMap { held =>
      held.record(sample(noon.minusSeconds(60), 42.0).copy(topicBytesInPerSecond = Some(Nil))) *>
        held.producers(5, noon).map {
          case Right(producers) => assertEquals(producers.value.topics, Nil)
          case Left(failure) => fail(s"an empty family is an answer, not a refusal; got $failure")
        }
    }
  }

  test("a reading older than the retention window is not handed out as current") {
    // A gauge is a claim about now. An exporter that went away an hour ago must not leave an hour-old
    // idle ratio on a card with nothing saying how old it is.
    MetricsBuffer
      .create[IO](cluster, 30.seconds, 10.minutes, 5000, noon.minusSeconds(86400), CacheMetrics.noop[IO])
      .flatMap { held =>
        held.record(full(noon.minusSeconds(3600), 10.0, 3.0)) *> held.requestHandlers(noon).map {
          case Left(failure) => assert(failure.message.contains("no scrape has succeeded"), failure.message)
          case Right(reading) => fail(s"an evicted reading must not be current; got $reading")
        }
      }
  }

  test("a mean record size is absent rather than infinite when no record arrived") {
    // `x / 0` is `Infinity`, which serialises to `null` by a route nobody chose and reads as a gap that
    // was never measured. A broker receiving bytes and no records has no mean record size, and the two
    // rates travel beside the null so a card can say why.
    buffer().flatMap { held =>
      held.record(sample(noon.minusSeconds(60), 100.0).copy(recordsPerSecond = Some(0.0))) *>
        held.recordSize(noon).map {
          case Right(reading) =>
            assertEquals(reading.value.meanBytes, None)
            assertEquals(reading.value.bytesInPerSecond, Some(100.0))
            assertEquals(reading.value.recordsPerSecond, Some(0.0))
          case Left(failure) => fail(s"a served family is not a refusal; got $failure")
        }
    }
  }
}
