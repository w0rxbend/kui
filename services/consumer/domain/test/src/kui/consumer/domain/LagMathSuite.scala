package kui.consumer.domain

import java.time.Instant

import kui.consumer.domain.fixtures.GroupFixtures
import kui.kernel.group.LagAnomaly
import kui.kernel.Offset
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Lag, and the four ways it can fail to be a number.
  *
  * The property at the end is the one that matters most: whatever the inputs, a total is the sum of the
  * defined lags and nothing else. No zero is ever substituted for an undefined one, which is the fabrication
  * the reference product's `orElse(0)` performs and the reason a capacity decision can be made from a wrong
  * figure.
  */
final class LagMathSuite extends ScalaCheckSuite {

  private def offset(n: Long): Option[Offset] = Some(Offset.unsafe(n))

  test("an ordinary partition lags by the distance to the end of the log") {
    val lag = LagMath.lagOf(offset(90L), offset(0L), offset(100L))
    assertEquals(lag.value, Some(10L))
    assertEquals(lag.anomalies, Set.empty[LagAnomaly])
  }

  test("a group that has never committed has no lag, and says why") {
    val lag = LagMath.lagOf(None, offset(0L), offset(100L))
    assertEquals(lag.value, None)
    assertEquals(lag.anomalies, Set(LagAnomaly.NoCommit))
  }

  test("an unreadable end offset has no lag, and is not a lag of zero") {
    val lag = LagMath.lagOf(offset(90L), offset(0L), None)
    assertEquals(lag.value, None)
    assertEquals(lag.anomalies, Set(LagAnomaly.NoLeader))
  }

  test("a commit past the end of the log is an anomaly, never a negative number") {
    val lag = LagMath.lagOf(offset(150L), offset(0L), offset(100L))
    assertEquals(lag.value, None)
    assertEquals(lag.anomalies, Set(LagAnomaly.CommittedBeyondEnd))
  }

  test("a commit older than the log start still has a lag, and is flagged") {
    // The consumer really is 100 records behind; it will resume from offset 50, not from 0.
    val lag = LagMath.lagOf(offset(0L), offset(50L), offset(100L))
    assertEquals(lag.value, Some(100L))
    assertEquals(lag.anomalies, Set(LagAnomaly.CommittedBeforeStart))
  }

  test("a total of nothing is None, not zero") {
    assertEquals(LagMath.total(Nil).value, None)
    assertEquals(LagMath.total(List(PartitionLag.noCommit, PartitionLag.noLeader)).value, None)
  }

  test("a total says how many partitions it left out") {
    val total = LagMath.total(List(PartitionLag(Some(5L), Set.empty), PartitionLag.noCommit))
    assertEquals(total.value, Some(5L))
    assertEquals(total.counted, 1)
    assertEquals(total.excluded, 1)
  }

  property("a total is the sum of the defined lags and nothing else") {
    val lags = Gen.listOf(
      Gen.oneOf(
        Gen.choose(0L, 1000000L).map(value => PartitionLag(Some(value), Set.empty)),
        Gen.const(PartitionLag.noCommit),
        Gen.const(PartitionLag.noLeader)
      )
    )

    forAll(lags) { partitions =>
      val total = LagMath.total(partitions)
      val defined = partitions.flatMap(_.value)

      assertEquals(total.value, Option.when(defined.nonEmpty)(defined.sum))
      assertEquals(total.counted + total.excluded, partitions.size)
      // The fabrication that must never happen: an undefined lag counted as zero.
      assert(total.value.isEmpty || defined.nonEmpty)
    }
  }

  // ------------------------------------------------------------------ pace

  private val at: Instant = GroupFixtures.At

  private def sample(seconds: Int, total: Option[Long], partitions: Set[Int]): LagMath.PaceSample =
    LagMath.PaceSample(at.plusSeconds(seconds.toLong), total, partitions.map(GroupFixtures.partition))

  test("pace is committed-offset movement per second between two passes") {
    val previous = sample(0, Some(100L), Set(0, 1))
    val current = sample(30, Some(400L), Set(0, 1))

    assertEquals(LagMath.pace(Some(previous), current), Some(10.0))
  }

  test("one observation has no pace") {
    assertEquals(LagMath.pace(None, sample(0, Some(100L), Set(0))), None)
  }

  test("a changed partition set has no pace, because that arithmetic is two different quantities") {
    val previous = sample(0, Some(100L), Set(0, 1))
    val current = sample(30, Some(400L), Set(0, 1, 2))

    assertEquals(LagMath.pace(Some(previous), current), None)
  }

  test("no elapsed time and an unknown total both have no pace") {
    assertEquals(LagMath.pace(Some(sample(0, Some(1L), Set(0))), sample(0, Some(2L), Set(0))), None)
    assertEquals(LagMath.pace(Some(sample(0, None, Set(0))), sample(30, Some(2L), Set(0))), None)
  }

  test("commits moving backwards report a negative pace rather than a zero") {
    val previous = sample(0, Some(400L), Set(0))
    val current = sample(10, Some(300L), Set(0))

    assertEquals(LagMath.pace(Some(previous), current), Some(-10.0))
  }
}
