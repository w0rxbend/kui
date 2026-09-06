package kui.cache

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.SyncIO
import org.scalacheck.{Gen, Prop}

import kui.testkit.KuiSuite
import kui.testkit.fakes.FakeClock

/** The three refusals `SeriesWindow` exists to make, asserted against a clock the test moves.
  *
  * Nothing here reads the wall clock, and that is the subject as much as the method: retention is decided
  * against an `Instant` the caller hands in, so a day of retention is testable in a millisecond.
  */
final class SeriesWindowSuite extends KuiSuite {

  private val step: FiniteDuration = 1.minute
  private val maxAge: FiniteDuration = 1.hour
  private val maxSamples: Int = 60

  /** A clock the test owns. `SyncIO` because this suite has no effects of its own to run — it only needs a
    * source of instants that does not move unless the test moves it.
    */
  private def clockFrom(start: Instant = FakeClock.Epoch): FakeClock[SyncIO] =
    FakeClock[SyncIO](start).unsafeRunSync()

  private def windowFrom(
      startedAt: Instant,
      age: FiniteDuration = maxAge,
      cap: Int = maxSamples
  ): SeriesWindow[Int] = SeriesWindow.empty[Int](step, age, cap, startedAt)

  test("evictionByAgeIsDecidedAgainstTheSuppliedInstant") {
    val clock = clockFrom()
    val start = clock.now.unsafeRunSync()

    // Ten samples a minute apart, with a five-minute retention: the oldest five must be gone by the end,
    // and the eviction happened without a millisecond of real time passing.
    val recorded = (0 until 10).foldLeft(windowFrom(start, age = 5.minutes)) { (window, minute) =>
      clock.set(start.plusSeconds(minute.toLong * 60L)).unsafeRunSync()
      window.record(clock.now.unsafeRunSync(), minute)
    }

    assertEquals(recorded.size, 6)
    assertEquals(recorded.oldest.map(_.value), Some(4))
    assertEquals(recorded.latest.map(_.value), Some(9))

    // And a window that simply stopped being fed empties: an exporter that went away an hour ago must not
    // leave an hour-old sample readable as though it were current.
    clock.advance(1.hour).unsafeRunSync()
    assert(recorded.evict(clock.now.unsafeRunSync()).isEmpty)
  }

  test("aNeverSampledBucketIsAbsentAndASampledZeroIsNot") {
    val start = FakeClock.Epoch
    // The window has been collecting for an hour before the first sample, so coverage cannot be what
    // refuses this read; the gap in the middle is the only thing under test.
    val window = windowFrom(start.minusSeconds(3600L))
      .record(start, 0)
      .record(start.plusSeconds(120L), 5)

    val buckets = window.bucketsOver(3.minutes, start.plusSeconds(120L))

    assertEquals(buckets.map(_.map(_.value)), Some(Vector(Some(0), None, Some(5))))
    // The distinction the whole type exists for, read one bucket at a time.
    assertEquals(window.valueAt(start), Some(0))
    assertEquals(window.valueAt(start.plusSeconds(60L)), None)
    // A fold sees the two readings and never invents the third.
    assertEquals(window.valuesOver(3.minutes, start.plusSeconds(120L)), Some(Vector(0, 5)))
  }

  test("aWindowShorterThanTheRequestedPeriodAnswersNone") {
    val start = FakeClock.Epoch
    val window = (0 until 5).foldLeft(windowFrom(start)) { (acc, minute) =>
      acc.record(start.plusSeconds(minute.toLong * 60L), minute)
    }
    val now = start.plusSeconds(300L)

    // Five minutes of samples cannot answer "over the last hour" — the point being that a percentage
    // computed over five minutes and labelled 24h is worse than no answer, because nobody can see it.
    assertEquals(window.bucketsOver(1.hour, now), None)
    assertEquals(window.valuesOver(1.hour, now), None)
    assertEquals(window.coverage(now), 5.minutes)
    assertEquals(window.spans(5.minutes, now), true)
    assert(window.bucketsOver(5.minutes, now).isDefined)

    // A ring that cannot physically hold the period refuses it however long it has been running: sixty
    // one-minute buckets are an hour, whatever the retention says.
    val long = windowFrom(start.minusSeconds(86400L), age = 24.hours, cap = 60)
    assertEquals(long.coverage(now), 1.hour)
    assertEquals(long.bucketsOver(24.hours, now), None)
  }

  test("bucketsOverAlwaysReturnsTheFullAxisEndingAtNow") {
    val start = FakeClock.Epoch
    val window = windowFrom(start.minusSeconds(3600L)).record(start, 7)
    val now = start.plusSeconds(240L)

    val buckets = window.bucketsOver(5.minutes, now).getOrElse(Vector.empty)

    // Five evenly spaced entries whatever was sampled, so a caller can index the vector as an axis.
    assertEquals(buckets.size, 5)
    assertEquals(buckets.last.start, start.plusSeconds(240L))
    assertEquals(buckets.head.value, Some(7))
    assertEquals(buckets.tail.forall(_.value.isEmpty), true)
    // Four minutes without a sample: the tail of that answer is gaps and the read is a stale one.
    assertEquals(window.isStaleAt(now), true)
    assertEquals(window.isStaleAt(start.plusSeconds(60L)), false)
  }

  test("aSecondReadingInOneBucketReplacesTheFirst") {
    val start = FakeClock.Epoch
    val window = windowFrom(start.minusSeconds(3600L))
      .record(start.plusSeconds(1L), 1)
      .record(start.plusSeconds(59L), 2)

    // One bucket, the newest reading of it, filed under the bucket's start rather than the scrape's drift.
    assertEquals(window.size, 1)
    assertEquals(window.latest, Some(SeriesSample(start, 2)))
  }

  test("aSampleOlderThanTheHorizonIsRefusedRatherThanFiledAtTheFront") {
    val start = FakeClock.Epoch
    val window = windowFrom(start.minusSeconds(3600L), age = 5.minutes).record(start, 1)

    // A clock corrected backwards by an hour. Keeping this would push a point off the left edge of every
    // chart drawn from the window; refusing it is visible in the metric instead.
    assertEquals(window.accepts(start.minusSeconds(3600L)), false)
    assertEquals(window.record(start.minusSeconds(3600L), 9), window)

    // A late sample that is merely out of order, and still inside the horizon, is kept in place.
    val late = window.record(start.minusSeconds(120L), 9)
    assertEquals(late.size, 2)
    assertEquals(late.oldest.map(_.value), Some(9))
    assertEquals(late.latest.map(_.value), Some(1))
  }

  property("theRingNeverExceedsItsMaximumSampleCount") {
    val shapes = for {
      stepSeconds <- Gen.chooseNum(1, 600)
      cap <- Gen.chooseNum(1, 40)
      buckets <- Gen.chooseNum(1, 200)
      offsets <- Gen.listOfN(120, Gen.chooseNum(0L, stepSeconds.toLong * buckets.toLong))
    } yield (stepSeconds, cap, buckets, offsets)

    Prop.forAll(shapes) { case (stepSeconds, cap, buckets, offsets) =>
      val start = FakeClock.Epoch
      val window = offsets.foldLeft(
        SeriesWindow.empty[Long](
          stepSeconds.seconds,
          (stepSeconds.toLong * buckets.toLong).seconds,
          cap,
          start
        )
      )((acc, offset) => acc.record(start.plusSeconds(offset), offset))

      val ordered = window.samples.map(_.at.toEpochMilli)

      // Three invariants at once, because they are the same invariant: the ring is bounded, sorted and
      // aligned however the samples arrive.
      window.size <= cap &&
      ordered == ordered.sorted &&
      window.samples.forall(_.at.toEpochMilli % (stepSeconds.toLong * 1000L) == 0L)
    }
  }
}
