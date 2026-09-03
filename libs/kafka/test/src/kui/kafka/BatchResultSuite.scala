package kui.kafka

import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}

import kui.kernel.error.ErrorCode
import kui.testkit.KuiSuite

/** The one invariant that makes `BatchResult` worth having: a key cannot vanish.
  *
  * The reference implementations return an empty map when a per-key call fails, which leaves a
  * caller unable to tell "this broker has no log directories" from "this broker would not say".
  * Those are different screens, and only one of them should start an investigation.
  */
final class BatchResultSuite extends KuiSuite {

  private val genReason: Gen[SkipReason] = Gen.oneOf(
    SkipReason.NotFound("no such thing"),
    SkipReason.NotAuthorized("no DESCRIBE_CONFIGS"),
    SkipReason.Unsupported("describeLogDirs"),
    SkipReason.NoLeader,
    SkipReason.Failed(ErrorCode.InvalidState, "offline")
  )

  /** A result whose two halves are disjoint by construction, which is the only kind an adapter is
    * allowed to build.
    */
  private val genResult: Gen[BatchResult[Int, String]] = for {
    keys <- Gen.listOfN(8, Gen.chooseNum(0, 40)).map(_.distinct)
    split <- Gen.chooseNum(0, keys.size)
    reason <- genReason
  } yield {
    val (kept, dropped) = keys.splitAt(split)
    BatchResult(kept.map(k => k -> s"value-$k").toMap, dropped.map(_ -> reason).toMap)
  }

  test("aKeyIsEitherAValueOrAReason") {
    val result = BatchResult(Map(1 -> "a"), Map(2 -> SkipReason.NotAuthorized("no DESCRIBE_CONFIGS")))

    assertEquals(result.requested, Set(1, 2))
    assertEquals(result.get(1), Right("a"))
    assertEquals(result.get(2).left.map(_.message), Left("not authorized: no DESCRIBE_CONFIGS"))
    assert(!result.isComplete)
  }

  test("aKeyThatWasNeverAskedAboutSaysSoRatherThanAnsweringNone") {
    assertEquals(BatchResult.empty[Int, String].get(7).isLeft, true)
  }

  property("requestedIsTheUnionOfValuesAndSkipped") {
    forAll(genResult) { result =>
      assertEquals(result.requested, result.values.keySet ++ result.skipped.keySet)
      Prop.passed
    }
  }

  property("valuesAndSkippedAreDisjointAfterEveryMerge") {
    forAll(genResult, genResult) { (left, right) =>
      val merged = left.combine(right)

      assertEquals(merged.values.keySet.intersect(merged.skipped.keySet), Set.empty[Int])
      Prop.passed
    }
  }

  property("aKeyThatSucceededAnywhereIsNotSkipped") {
    // The case this rules out: a chunk that failed and a retry that worked, merged, leaving the key
    // in both halves and the invariant broken.
    forAll(genResult, genReason) { (result, reason) =>
      val failedFirst = BatchResult.allSkipped[Int, String](result.values.keySet, reason)
      val merged = result.combine(failedFirst)

      assertEquals(merged.values, result.values)
      assertEquals(merged.skipped.keySet.intersect(result.values.keySet), Set.empty[Int])
      Prop.passed
    }
  }

  property("combineIsAssociative") {
    forAll(genResult, genResult, genResult) { (a, b, c) =>
      assertEquals(a.combine(b).combine(c).requested, a.combine(b.combine(c)).requested)
      assertEquals(a.combine(b).combine(c).values, a.combine(b.combine(c)).values)
      Prop.passed
    }
  }

  test("combineIsDeterministicallyOrdered") {
    // Chunked calls merge in whatever order they finish; the rendering must not depend on that.
    import BatchResult.orderedValues

    val left = BatchResult.complete(Map(3 -> "c", 1 -> "a"))
    val right = BatchResult.complete(Map(2 -> "b"))

    assertEquals(left.combine(right).orderedValues, List(1 -> "a", 2 -> "b", 3 -> "c"))
    assertEquals(right.combine(left).orderedValues, List(1 -> "a", 2 -> "b", 3 -> "c"))
  }

  test("combineCheckedReportsAnOverlap") {
    val left = BatchResult.complete(Map(1 -> "a", 2 -> "b"))
    val right = BatchResult.complete(Map(2 -> "b", 3 -> "c"))

    assert(left.combineChecked(right).isLeft)
    assert(left.combineChecked(right).left.exists(_.contains("2")))
    assert(left.combineChecked(BatchResult.complete(Map(9 -> "i"))).isRight)
  }

  property("mapPreservesSkipped") {
    forAll(genResult) { result =>
      val mapped = result.map(_.length)

      assertEquals(mapped.skipped, result.skipped)
      assertEquals(mapped.requested, result.requested)
      Prop.passed
    }
  }

  test("allSkippedIsTheEmptyClusterCase") {
    // A batch where every key failed is a valid result, not an error. It is the shape a cluster
    // that authenticates but authorizes nothing produces, and it renders as a full broker list
    // with a lock icon on each row rather than as an error banner.
    val result =
      BatchResult.allSkipped[Int, String](Set(1, 2, 3), SkipReason.NotAuthorized("no ACL"))

    assertEquals(result.requested, Set(1, 2, 3))
    assertEquals(result.values, Map.empty[Int, String])
    assert(!result.isComplete)
    assert(!result.isEmpty)
  }
}
