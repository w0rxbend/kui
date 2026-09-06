package kui.topic.domain

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Records written to a topic per second, differenced from two consecutive scrapes.
  *
  * ==Two snapshots, not a window==
  *
  * This is the same shape as the consumer service's `LagMath.pace`, and for the same reason: a rate needs two
  * observations and nothing more. `SeriesWindow` in `libs/cache` is the retention primitive, and it is what a
  * *chart* of this figure will need — M7 — but a single `MSG/S` cell on a list row does not need a history to
  * be honest about, and keeping one would mean this service held a second cache with a staleness contract
  * nobody asked for.
  *
  * ==Why the sample is the end offsets and not the message count==
  *
  * `TopicSummary.messageCount` is the latest offset minus the earliest, so retention deleting a segment
  * lowers it while producers are writing as fast as they ever were. Differencing it would report a *negative*
  * produce rate on a busy topic that has just rolled a segment, which is not a slow topic and not an idle one
  * — it is a wrong number in the one column an operator scans to find the busy topics. The sum of the end
  * offsets only moves when a record is appended, so its difference is the thing the column claims to be.
  *
  * ==The five refusals==
  *
  * Each one is a case where a number could be produced and would be wrong:
  *
  *   - **One observation is not a rate.** The first scrape after a restart has nothing to subtract from, and
  *     `Some(0.0)` there would say "this topic is idle" about a topic nobody has looked at yet.
  *   - **Either total unknown.** A topic with a leaderless partition has no end-offset total at all
  *     ([[Aggregate.endOffsetTotal]] refuses over a partial set), and a rate over the partitions that did
  *     answer is an understatement that looks like a measurement.
  *   - **The partition count changed.** This is the rule worth arguing, and it is the consumer's rule about a
  *     changed partition set in the topic domain's words: adding a partition adds its own end offset to the
  *     total, so the difference counts records that were never produced in this interval. It renders as a
  *     spike exactly while an operator is watching a partition increase.
  *   - **The offsets went backwards.** End offsets only move forwards under production, so a fall means the
  *     topic was deleted and recreated under the same name: the two samples measure two different topics and
  *     their difference is not a rate of anything. This is where the rule parts company with `LagMath.pace`,
  *     which reports a fall, because a *commit* moving backwards is a reset an operator wants to see.
  *   - **The interval is shorter than [[MinimumInterval]].** The refresh button forces a scrape, which can
  *     land milliseconds after a scheduled one; dividing a handful of records by that gap renders as a spike
  *     too, and an operator cannot tell it from a real one.
  *
  * Two equal totals over a long enough interval are `Some(0.0)` and not a refusal: both were measured, and
  * the topic really did take no writes. That is the one zero on this figure that means what it says.
  */
object ProduceRate {

  /** The shortest interval a rate is computed over.
    *
    * One second, against a scrape interval whose configured minimum is `kui.topics.refreshInterval`'s five
    * seconds. It is not there to smooth the scheduled scrapes; it is there for the forced one, which any
    * operator can trigger at any moment from the refresh button and which is the only way two samples land
    * this close together.
    */
  val MinimumInterval: FiniteDuration = 1.second

  /** One observation of how far a topic's logs had been written, and when.
    *
    * The partition count travels with the sample rather than being looked up beside it, because the rule that
    * needs it — refuse across a partition increase — compares the two observations and nothing else.
    */
  final case class Sample(at: Instant, endOffsetTotal: Option[Long], partitionCount: Int)

  object Sample {
    given CanEqual[Sample, Sample] = CanEqual.derived
  }

  /** Records per second between two observations of the same topic, or `None`; see this object's comment. */
  def of(previous: Option[Sample], current: Sample): Option[Double] =
    for {
      earlier <- previous
      if earlier.partitionCount == current.partitionCount
      before <- earlier.endOffsetTotal
      after <- current.endOffsetTotal
      if after >= before
      seconds = (current.at.toEpochMilli - earlier.at.toEpochMilli).toDouble / 1000.0
      if seconds >= MinimumInterval.toMillis.toDouble / 1000.0
    } yield (after - before).toDouble / seconds
}
