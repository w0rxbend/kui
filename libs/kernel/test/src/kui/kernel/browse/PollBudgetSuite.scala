package kui.kernel.browse

import scala.concurrent.duration.{Duration, DurationInt}

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** A budget's whole job is to reach zero and stay there.
  *
  * The properties here are all one property said three ways: subtraction saturates. A budget that goes
  * negative silently becomes unbounded, because the next `isExhausted` compares a negative number with zero
  * and answers "keep going" — and the failure looks like a stream that never ends rather than like arithmetic.
  */
final class PollBudgetSuite extends ScalaCheckSuite {

  property("consumeSaturatesAtZero") {
    val extremes = Gen.oneOf(0, 1, 1000, Int.MaxValue)
    forAll(BrowseGenerators.pollBudget, extremes, extremes) { (budget, records, bytes) =>
      val left = budget.consume(records, bytes.toLong, 10.days)
      assert(left.recordsLeft >= 0, s"records went to ${left.recordsLeft}")
      assert(left.bytesLeft >= 0L, s"bytes went to ${left.bytesLeft}")
      assert(left.timeLeft >= Duration.Zero, s"time went to ${left.timeLeft}")
    }
  }

  property("consumingNothingChangesNothing") {
    forAll(BrowseGenerators.pollBudget) { budget =>
      assertEquals(budget.consume(0, 0L), budget)
    }
  }

  property("consumeNeverGrowsABudget") {
    forAll(BrowseGenerators.pollBudget, Gen.chooseNum(0, 5000), Gen.chooseNum(0L, 5000L)) {
      (budget, records, bytes) =>
        val left = budget.consume(records, bytes)
        assert(left.recordsLeft <= budget.recordsLeft)
        assert(left.bytesLeft <= budget.bytesLeft)
    }
  }

  test("consumeSaturatesAtZeroForTheConservativeBudget") {
    assertEquals(PollBudget.Conservative.consume(Int.MaxValue, Long.MaxValue).recordsLeft, 0)
    assertEquals(PollBudget.Conservative.consume(Int.MaxValue, Long.MaxValue).bytesLeft, 0L)
  }

  test("isExhaustedWhenAnyDimensionIsSpent") {
    val budget = PollBudget.unsafe(10, 1000L, 5.seconds)

    assert(!budget.isExhausted)
    assertEquals(budget.exhaustedDimension, None)

    assert(budget.consume(10, 0L).isExhausted)
    assertEquals(budget.consume(10, 0L).exhaustedDimension, Some("records"))

    assert(budget.consume(0, 1000L).isExhausted)
    assertEquals(budget.consume(0, 1000L).exhaustedDimension, Some("bytes"))

    assert(budget.consume(0, 0L, 5.seconds).isExhausted)
    assertEquals(budget.consume(0, 0L, 5.seconds).exhaustedDimension, Some("deadline"))
  }

  test("aBudgetThatIsSpentBeforeItStartsIsRefused") {
    assert(PollBudget.of(0, 1000L, 5.seconds).isLeft)
    assert(PollBudget.of(10, 0L, 5.seconds).isLeft)
    assert(PollBudget.of(10, 1000L, Duration.Zero).isLeft)
    assert(PollBudget.of(10, 1000L, 5.seconds, Some(0L)).isLeft)
    assert(PollBudget.of(10, 1000L, 5.seconds, Some(1024L)).isRight)
  }

  test("aRefusedBudgetNamesTheFieldThatIsWrong") {
    assertEquals(PollBudget.of(0, 1L, 1.second).left.map(_.fieldName), Left("maxRecords"))
    assertEquals(PollBudget.of(1, 0L, 1.second).left.map(_.fieldName), Left("maxBytes"))
    assertEquals(PollBudget.of(1, 1L, Duration.Zero).left.map(_.fieldName), Left("deadline"))
  }
}
