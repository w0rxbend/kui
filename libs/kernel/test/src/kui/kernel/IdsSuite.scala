package kui.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.testkit.Generators

/** What the identifiers of KERN-001 promise, checked rather than described.
  *
  * The two kinds of test here answer two different questions. The properties answer "does this hold
  * for every value", which is the only honest way to talk about a validation rule. The tables answer
  * "does this exact input produce this exact error", which is what stops a future refactor quietly
  * widening a rule: a table row is a decision someone has to delete on purpose.
  */
final class IdsSuite extends ScalaCheckSuite {

  property("a value that survives from() unwraps to exactly the string it was built from") {
    forAll(Generators.validSlug) { raw =>
      assertEquals(ClusterId.from(raw).map(_.value), Right(raw))
    }
  }

  property("a topic name that survives from() unwraps to exactly the string it was built from") {
    forAll(Generators.validTopicName) { raw =>
      assertEquals(TopicName.from(raw).map(_.value), Right(raw))
    }
  }

  property("a correlation id round-trips through from() and value") {
    forAll(Generators.validCorrelationId) { raw =>
      assertEquals(CorrelationId.from(raw).map(_.value), Right(raw))
    }
  }

  property("a partition id round-trips through from() and value") {
    forAll(Gen.chooseNum(0, Int.MaxValue)) { raw =>
      assertEquals(PartitionId.from(raw).map(_.value), Right(raw))
    }
  }

  property("an offset round-trips through from() and value") {
    forAll(Gen.chooseNum(0L, Long.MaxValue)) { raw =>
      assertEquals(Offset.from(raw).map(_.value), Right(raw))
    }
  }

  /** Every shape ADR-031's slug rule exists to refuse. Each row names the reason, so a failure says
    * which rule stopped holding rather than only which string broke.
    */
  private val rejectedClusterIds: List[(String, String)] = List(
    ""                 -> "empty",
    "-leading"         -> "starts with a dash",
    "trailing-"        -> "ends with a dash",
    "Prod"             -> "contains an uppercase letter",
    "prod eu"          -> "contains a space",
    "prod.eu"          -> "contains a dot",
    "prod_eu"          -> "contains an underscore",
    "prod/eu"          -> "contains a path separator",
    "prod:eu"          -> "contains a colon",
    "prödü"            -> "contains a non-ASCII letter",
    "a" * 65           -> "is longer than 64 characters",
    "-"                -> "is a lone dash"
  )

  rejectedClusterIds.foreach { row =>
    test(s"a cluster id that ${row._2} is rejected as a format error") {
      val expected = ValidationError.Format(
        "clusterId",
        "a lowercase slug of 1 to 64 letters, digits and dashes, starting and ending with a letter or a digit",
        row._1
      )
      assertEquals(ClusterId.from(row._1), Left(expected))
    }
  }

  test("a topic name may use every character Kafka allows") {
    val legal = List("orders", "orders.v2", "orders_v2", "orders-v2", "A1._-", "x" * 249)
    legal.foreach { name =>
      assertEquals(TopicName.from(name).map(_.value), Right(name), clue = name)
    }
  }

  test("'.' and '..' are refused as topic names even though every character in them is legal") {
    assert(TopicName.from(".").isLeft)
    assert(TopicName.from("..").isLeft)
    assertEquals(TopicName.from("...").map(_.value), Right("..."))
  }

  test("a topic name longer than Kafka's 249-character limit is refused") {
    assert(TopicName.from("x" * 250).isLeft)
  }

  property("every hand-written invalid topic name is refused") {
    forAll(Generators.invalidTopicName) { raw =>
      assert(TopicName.from(raw).isLeft, s"'$raw' should not be a legal topic name")
    }
  }

  property("every hand-written invalid cluster id is refused") {
    forAll(Generators.invalidClusterId) { raw =>
      assert(ClusterId.from(raw).isLeft, s"'$raw' should not be a legal cluster id")
    }
  }

  test("a String is not a TopicName and a TopicName is not a String") {
    assert(
      compileErrors("""val topic: TopicName = "orders"""").nonEmpty,
      "a bare String must not typecheck as a TopicName"
    )
    assert(
      compileErrors("""val raw: String = TopicName.unsafe("orders")""").nonEmpty,
      "a TopicName must not typecheck as a String"
    )
  }

  test("two identifiers over the same primitive are not interchangeable") {
    assert(
      compileErrors("""val group: GroupId = TopicName.unsafe("orders")""").nonEmpty,
      "a TopicName must not typecheck as a GroupId"
    )
  }

  test("identifiers sort by their underlying value") {
    val ids = List(ClusterId.unsafe("prod-us"), ClusterId.unsafe("dev"), ClusterId.unsafe("prod-eu"))
    assertEquals(ids.sorted.map(_.value), List("dev", "prod-eu", "prod-us"))
  }
}
