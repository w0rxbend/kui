package kui.consumer.domain

import java.time.Instant

import scala.concurrent.duration.*

import kui.consumer.domain.fixtures.GroupFixtures
import kui.kernel.{GroupId, Offset, TopicPartition}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Where a running application will start reading next, decided by a pure function.
  *
  * Two properties carry most of the weight: no proposed offset is ever outside the range the partition holds,
  * and every clamp is reported. A silent clamp is what Kafbat does, and it means an operator who typed
  * 900 000 into a partition holding 400 records is shown a confirmation that agrees with them and a cluster
  * that does something else.
  */
final class ResetPlannerSuite extends ScalaCheckSuite {

  private val group: GroupId = GroupId.unsafe("orders-consumer")
  private val now: Instant = GroupFixtures.At

  private def partition(n: Int): TopicPartition = GroupFixtures.partition(n)

  private val scope: ResetScope = ResetScope(GroupFixtures.Orders, Set(partition(0), partition(1)))

  private def offsets(pairs: (Int, Long)*): Map[TopicPartition, Offset] =
    pairs.map((n, offset) => partition(n) -> Offset.unsafe(offset)).toMap

  private val window: OffsetWindow = OffsetWindow(
    begin = offsets(0 -> 10L, 1 -> 0L),
    end = offsets(0 -> 100L, 1 -> 50L),
    committed = offsets(0 -> 40L, 1 -> 25L),
    atTimestamp = Map(partition(0) -> Some(Offset.unsafe(60L)), partition(1) -> None),
    leaderless = Set.empty
  )

  private def planned(result: Either[ResetRefusal, ResetPlan]): Map[Int, Long] =
    result.fold(
      refusal => fail(s"expected a plan, got a refusal: ${refusal.message}"),
      _.partitions.map(one => one.partition.partition.value -> one.proposed.value).toMap
    )

  test("earliest plans the beginning of each partition") {
    assertEquals(planned(ResetPlanner.plan(group, scope, ResetSpec.ToEarliest, window, now)), Map(0 -> 10L, 1 -> 0L))
  }

  test("latest plans the end of each partition") {
    assertEquals(planned(ResetPlanner.plan(group, scope, ResetSpec.ToLatest, window, now)), Map(0 -> 100L, 1 -> 50L))
  }

  test("a timestamp plans the offset the cluster resolved for it") {
    val plan = ResetPlanner.plan(group, scope, ResetSpec.ToTimestamp(now), window, now)
    assertEquals(planned(plan).apply(0), 60L)
  }

  test("no record at or after the timestamp plans the end of the partition, and says so") {
    val plan = ResetPlanner.plan(group, scope, ResetSpec.ToTimestamp(now), window, now)

    assertEquals(planned(plan).apply(1), 50L)
    assert(
      plan.exists(_.warnings.exists {
        case ResetWarning.TimestampBeyondEnd(p, applied) => p == partition(1) && applied.value == 50L
        case _ => false
      }),
      "KIP-122's rule was applied without telling the operator they will skip the partition"
    )
  }

  test("a duration plans exactly what the equivalent timestamp plans") {
    val at = now.minusMillis(2.hours.toMillis)
    val byDuration = ResetPlanner.plan(group, scope, ResetSpec.ByDuration(2.hours), window, now)
    val byTimestamp = ResetPlanner.plan(group, scope, ResetSpec.ToTimestamp(at), window, now)

    assertEquals(planned(byDuration), planned(byTimestamp))
    assertEquals(ResetPlanner.timestampOf(ResetSpec.ByDuration(2.hours), now), Some(at))
  }

  test("a shift moves from the committed offset") {
    assertEquals(planned(ResetPlanner.plan(group, scope, ResetSpec.ShiftBy(5L), window, now)), Map(0 -> 45L, 1 -> 30L))
    assertEquals(planned(ResetPlanner.plan(group, scope, ResetSpec.ShiftBy(-5L), window, now)), Map(0 -> 35L, 1 -> 20L))
  }

  test("a shift with no committed offset counts from the beginning, and says so") {
    val uncommitted = window.copy(committed = Map.empty)
    val plan = ResetPlanner.plan(group, scope, ResetSpec.ShiftBy(5L), uncommitted, now)

    assertEquals(planned(plan), Map(0 -> 15L, 1 -> 5L))
    assertEquals(plan.map(_.warnings.count(_.isInstanceOf[ResetWarning.ShiftedFromBeginning])), Right(2))
  }

  test("a partition with no leader refuses the whole reset rather than resetting the rest") {
    val offline = window.copy(leaderless = Set(partition(1)))

    ResetPlanner.plan(group, scope, ResetSpec.ToEarliest, offline, now) match {
      case Left(ResetRefusal.Leaderless(partitions)) => assertEquals(partitions, Set(partition(1)))
      case other => fail(s"a leaderless partition did not refuse the plan: $other")
    }
  }

  test("a scope with no partitions is a refusal, not a silent success") {
    assertEquals(
      ResetPlanner.plan(group, scope.copy(partitions = Set.empty), ResetSpec.ToLatest, window, now),
      Left(ResetRefusal.NoPartitionsInScope)
    )
  }

  test("a partition the cluster does not have is a refusal") {
    val shrunk = window.copy(begin = window.begin - partition(1), end = window.end - partition(1))

    ResetPlanner.plan(group, scope, ResetSpec.ToLatest, shrunk, now) match {
      case Left(ResetRefusal.UnknownPartition(partitions)) => assertEquals(partitions, Set(partition(1)))
      case other => fail(s"a vanished partition did not refuse the plan: $other")
    }
  }

  test("resetting to where it already is succeeds, and says nothing will change") {
    val single = ResetScope(GroupFixtures.Orders, Set(partition(0)))
    val plan = ResetPlanner.plan(group, single, ResetSpec.ToOffsets(offsets(0 -> 40L)), window, now)

    assert(plan.exists(_.isNoOp))
    assert(plan.exists(_.warnings.contains(ResetWarning.NoChange(partition(0)))))
  }

  test("a delta is None when the group has never committed") {
    val uncommitted = window.copy(committed = Map.empty)
    val plan = ResetPlanner.plan(group, scope, ResetSpec.ToLatest, uncommitted, now)

    assert(plan.exists(_.partitions.forall(_.delta.isEmpty)))
    assert(plan.exists(_.partitions.forall(_.current.isEmpty)))
  }

  test("the same inputs produce an equal plan, warnings included") {
    val first = ResetPlanner.plan(group, scope, ResetSpec.ToTimestamp(now), window, now)
    val second = ResetPlanner.plan(group, scope, ResetSpec.ToTimestamp(now), window, now)

    assertEquals(first, second)
    // The plan is signed into a token, so its partition order must not depend on set iteration.
    assertEquals(first.map(_.partitions.map(_.partition.partition.value)), Right(List(0, 1)))
  }

  // ------------------------------------------------------------------ properties

  private val anyOffset: Gen[Long] = Gen.choose(-1000L, 1000000L)

  property("an explicit offset is always clamped into the range the partition holds") {
    forAll(anyOffset) { requested =>
      val single = ResetScope(GroupFixtures.Orders, Set(partition(0)))
      val plan = ResetPlanner.plan(group, single, ResetSpec.ToOffsets(offsets(0 -> requested)), window, now)

      requested match {
        case negative if negative < 0L =>
          assertEquals(plan, Left(ResetRefusal.NegativeResult(partition(0))))
        case _ =>
          val proposed = planned(plan).apply(0)
          assert(proposed >= 10L && proposed <= 100L, s"$requested planned $proposed, outside [10, 100]")
      }
    }
  }

  property("every clamp is reported") {
    forAll(Gen.choose(0L, 1000000L)) { requested =>
      val single = ResetScope(GroupFixtures.Orders, Set(partition(0)))
      val plan = ResetPlanner.plan(group, single, ResetSpec.ToOffsets(offsets(0 -> requested)), window, now)
      val outOfRange = requested < 10L || requested > 100L

      val clamps = plan.fold(_ => 0, _.warnings.count(_.isInstanceOf[ResetWarning.Clamped]))
      assertEquals(clamps, if outOfRange then 1 else 0, clue = s"requested $requested")
    }
  }

  property("a shift of any size stays inside the partition, in both directions") {
    forAll(Gen.choose(-1000000L, 1000000L)) { records =>
      val proposed = planned(ResetPlanner.plan(group, scope, ResetSpec.ShiftBy(records), window, now))

      assert(proposed(0) >= 10L && proposed(0) <= 100L)
      assert(proposed(1) >= 0L && proposed(1) <= 50L)
    }
  }

  property("no mode ever proposes a negative offset") {
    val specs: Gen[ResetSpec] = Gen.oneOf(
      Gen.const(ResetSpec.ToEarliest),
      Gen.const(ResetSpec.ToLatest),
      Gen.const(ResetSpec.ToTimestamp(now)),
      Gen.choose(-1000000L, 1000000L).map(ResetSpec.ShiftBy.apply),
      Gen.choose(1L, 100000L).map(millis => ResetSpec.ByDuration(millis.millis))
    )

    forAll(specs) { spec =>
      ResetPlanner.plan(group, scope, spec, window, now) match {
        case Right(plan) => assert(plan.partitions.forall(_.proposed.value >= 0L))
        case Left(_) => ()
      }
    }
  }
}
