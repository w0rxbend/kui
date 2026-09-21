package kui.topic.domain

import java.time.{Duration, Instant}

import kui.kernel.{BrokerId, PartitionId, TopicName}
import kui.testkit.KuiSuite

/** The rate, and the five states in which there is no rate to report.
  *
  * Every refusal here is a case where a number could be produced and would be wrong, so each one is asserted
  * against the *plausible* wrong answer rather than merely against `None`: zero for the first scrape, a spike
  * for a partition increase, a spike for a forced refresh, a negative for a recreated topic.
  */
final class ProduceRateSuite extends KuiSuite {

  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")

  private def sample(offset: Long, seconds: Long, partitions: Int = 3): ProduceRate.Sample =
    ProduceRate.Sample(at.plusSeconds(seconds), Some(offset), partitions)

  test("oneObservationIsNotARate") {
    // `None` and not `Some(0.0)`. Zero would say "this topic is idle" about a topic KUI has measured once,
    // which is the first thing an operator sees after a restart and the last thing they should believe.
    assertEquals(ProduceRate.of(None, sample(1_000L, 0L)), None)
  }

  test("twoObservationsAreTheDifferenceOverTheInterval") {
    // 400 records over 10 seconds is 40 a second — the difference, not the 1 400 the second sample holds.
    assertEquals(ProduceRate.of(Some(sample(1_000L, 0L)), sample(1_400L, 10L)), Some(40.0d))
  }

  test("aTopicThatWroteNothingIsZeroAndNotAbsent") {
    // The one place a zero is the right answer: both totals are known and equal, so the topic really did
    // take no writes in the interval. Confusing this with the refusals above is the whole distinction.
    assertEquals(ProduceRate.of(Some(sample(1_000L, 0L)), sample(1_000L, 30L)), Some(0.0d))
  }

  test("anUnknownTotalOnEitherSideRefuses") {
    val unknown = ProduceRate.Sample(at.plusSeconds(10L), None, 3)

    assertEquals(ProduceRate.of(Some(sample(1_000L, 0L)), unknown), None)
    assertEquals(ProduceRate.of(Some(unknown), sample(1_400L, 20L)), None)
  }

  test("aPartitionIncreaseRefusesRatherThanReportingItsOwnOffsets") {
    // A new partition contributes its end offset to the total, so the difference counts records that were
    // never produced in this interval. It spikes exactly while an operator is watching the increase.
    val before = sample(1_000L, 0L, partitions = 3)

    assertEquals(ProduceRate.of(Some(before), sample(1_400L, 10L, partitions = 6)), None)
  }

  test("anIntervalShorterThanTheMinimumRefuses") {
    // The refresh button can force a scrape moments after a scheduled one. Five records over 200ms is 25 a
    // second by arithmetic and a spike by eye, and nothing on the screen distinguishes it from a real one.
    val forced = ProduceRate.Sample(at.plusMillis(200L), Some(1_005L), 3)

    assertEquals(ProduceRate.of(Some(sample(1_000L, 0L)), forced), None)
    assertEquals(ProduceRate.MinimumInterval.toMillis, 1000L)
  }

  test("exactlyTheMinimumIntervalIsAccepted") {
    val edge = ProduceRate.Sample(at.plusMillis(1000L), Some(1_010L), 3)

    assertEquals(ProduceRate.of(Some(sample(1_000L, 0L)), edge), Some(10.0d))
  }

  test("offsets that went backwards are a recreated topic, not a negative rate") {
    // End offsets only move forwards under production, so a fall means the topic was deleted and recreated
    // under the same name. The two samples measure two different topics and their difference is not a rate.
    // This is where the rule parts company with the consumer service's `pace`, which reports a fall because
    // a commit moving backwards is a reset an operator wants to see, not two topics being subtracted.
    assertEquals(ProduceRate.of(Some(sample(9_000L, 0L)), sample(12L, 30L)), None)
  }

  test("theSampleOnARowIsItsEndOffsetTotalAndPartitionCount") {
    // The bridge between the rule and the row it is computed for: a leaderless partition means no end-offset
    // total, so a topic with one refuses before any of the rules above are reached.
    val led = partition(0, leader = Some(1), latest = 500L)
    val leaderless = partition(1, leader = None, latest = 0L)

    val healthy = TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, List(led))
    val broken = TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, List(led, leaderless))

    assertEquals(healthy.sample(at), ProduceRate.Sample(at, Some(500L), 1))
    assertEquals(broken.sample(at).endOffsetTotal, None)
    val later = broken.sample(at.plus(Duration.ofMinutes(1)))

    assertEquals(ProduceRate.of(Some(healthy.sample(at)), later), None)
  }

  private def partition(id: Int, leader: Option[Int], latest: Long): PartitionView =
    PartitionView
      .from(
        partition = PartitionId.unsafe(id),
        leader = leader.map(BrokerId.unsafe),
        replicas = List(BrokerId.unsafe(1)),
        inSync = List(BrokerId.unsafe(1)),
        earliestOffset = leader.map(_ => 0L),
        latestOffset = leader.map(_ => latest),
        sizeBytes = Some(1024L)
      )
      .fold(error => fail(s"the fixture partition should be valid: ${error.message}"), identity)
}
