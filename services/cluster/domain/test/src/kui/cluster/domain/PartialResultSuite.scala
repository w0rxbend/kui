package kui.cluster.domain

import org.scalacheck.Prop.forAll

import kui.kernel.error.InfrastructureError
import kui.testkit.KuiSuite

/** The one invariant a partial answer must hold: every key that went in comes out.
  *
  * It is a property and not an example because the failure mode is a key the caller forgot to account for,
  * and an example test only ever checks the keys its author remembered.
  */
final class PartialResultSuite extends KuiSuite {

  private val failure = InfrastructureError.Timeout("describeLogDirs", 30_000L)

  test("everyRequestedKeyAppearsExactlyOnce") {
    forAll { (requested: Set[Int], values: Map[Int, String]) =>
      val result = PartialResult.from(requested, values, Map.empty[Int, SkipReason])

      assertEquals(result.values.keySet ++ result.skipped.keySet, requested)
      assert(result.values.keySet.intersect(result.skipped.keySet).isEmpty)
    }
  }

  test("aKeyInNeitherMapBecomesASkip") {
    val result = PartialResult.from(Set(1, 2), Map(1 -> "a"), Map.empty[Int, SkipReason])

    assertEquals(result.values, Map(1 -> "a"))
    assert(result.skipped.contains(2), "a key nobody accounted for must be a skip, never a silent drop")
  }

  test("isCompleteIsTrueOnlyWhenNothingWasSkipped") {
    assert(PartialResult.complete(Map(1 -> "a")).isComplete)
    assert(!PartialResult(Map(1 -> "a"), Map(2 -> SkipReason.Unauthorized)).isComplete)
  }

  test("mapPreservesSkips") {
    val mapped = PartialResult(Map(1 -> "a"), Map(2 -> SkipReason.NotFound)).map(_.toUpperCase)

    assertEquals(mapped.values, Map(1 -> "A"))
    assertEquals(mapped.skipped, Map(2 -> SkipReason.NotFound))
  }

  test("mergePrefersAValueOverASkipForTheSameKey") {
    // The retry case: the second attempt answered for the key the first one skipped.
    val first = PartialResult(Map.empty[Int, String], Map(1 -> SkipReason.Unauthorized))
    val second = PartialResult.complete(Map(1 -> "a"))

    assertEquals(first.merge(second).values, Map(1 -> "a"))
    assert(first.merge(second).isComplete)
  }

  test("mergeIsAssociativeOverDisjointKeys") {
    forAll { (a: Map[Int, String], b: Map[Int, String], c: Map[Int, String]) =>
      val (left, middle, right) = disjoint(a, b, c)

      assertEquals(
        left.merge(middle).merge(right).values,
        left.merge(middle.merge(right)).values
      )
    }
  }

  test("skipReasonFailedCarriesAKuiErrorAndNoStackTrace") {
    val reason = SkipReason.Failed(failure)

    assertEquals(reason.describe, failure.message, "the display text is the error's own sentence")
    assertEquals(failure.code, kui.kernel.error.ErrorCode.Timeout)
    // The type makes a `Throwable` unrepresentable here. The assertion documents that it is
    // deliberate: a skip reason is rendered to a user, and a class name is not display text.
    assert(!reason.toString.contains("Exception"))
  }

  private def disjoint(
      a: Map[Int, String],
      b: Map[Int, String],
      c: Map[Int, String]
  ): (PartialResult[Int, String], PartialResult[Int, String], PartialResult[Int, String]) = {
    val first = a
    val second = b -- first.keySet
    val third = c -- first.keySet -- second.keySet

    (PartialResult.complete(first), PartialResult.complete(second), PartialResult.complete(third))
  }
}
