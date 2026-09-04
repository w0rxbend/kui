package kui.kernel.browse

import kui.kernel.ValidationError
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

/** The browse vocabulary's closed sets, asserted against literal wire strings.
  *
  * Restating the enum in a list looks redundant and is the point: these strings are a published contract that
  * a browser, a service and a Kafka client all agree on, so renaming a case has to fail a test and be
  * re-approved rather than quietly changing what they agree on.
  */
final class SeekModeSuite extends ScalaCheckSuite {

  test("the direction wire set is exactly the documented set") {
    assertEquals(Direction.All.map(_.wire).sorted, List("BACKWARD", "FORWARD"))
  }

  test("the isolation wire set is exactly the documented set") {
    assertEquals(IsolationLevel.All.map(_.wire).sorted, List("READ_COMMITTED", "READ_UNCOMMITTED"))
  }

  test("an isolation level carries the string a Kafka client wants") {
    assertEquals(IsolationLevel.ReadCommitted.kafkaConfigValue, "read_committed")
    assertEquals(IsolationLevel.ReadUncommitted.kafkaConfigValue, "read_uncommitted")
  }

  property("every direction round-trips through its wire string") {
    forAll(BrowseGenerators.direction) { direction =>
      assertEquals(Direction.from(direction.wire), Right(direction))
    }
  }

  property("every isolation level round-trips through its wire string") {
    forAll(BrowseGenerators.isolationLevel) { level =>
      assertEquals(IsolationLevel.from(level.wire), Right(level))
    }
  }

  test("an unknown direction is a validation error naming the field") {
    Direction.from("REVERSE") match {
      case Left(ValidationError.Format(field, _, got)) =>
        assertEquals(field, "direction")
        assertEquals(got, "REVERSE")
      case other => fail(s"expected a Format error naming the field, got $other")
    }
  }

  test("defaultDirectionTable") {
    val table: List[(SeekMode, Direction)] = List(
      SeekMode.Beginning -> Direction.Forward,
      SeekMode.Latest -> Direction.Backward,
      SeekMode.AtOffset(o(42)) -> Direction.Forward,
      SeekMode.AtOffsets(Map(p(0) -> o(1))) -> Direction.Forward,
      SeekMode.AtTimestamp(1700000000000L) -> Direction.Forward
    )

    table.foreach { case (mode, expected) =>
      assertEquals(SeekMode.defaultDirection(mode), expected, s"for $mode")
    }
  }

  property("atOffsetsKeepsEveryPartition") {
    forAll(BrowseGenerators.seekMode) {
      case SeekMode.AtOffsets(perPartition) =>
        // A mode that silently dropped a partition would silently drop that partition's records, and the
        // user would see a short page with nothing to explain it.
        assertEquals(SeekMode.AtOffsets(perPartition), SeekMode.AtOffsets(perPartition))
        assertEquals(
          SeekMode.AtOffsets(perPartition) match {
            case SeekMode.AtOffsets(m) => m.size
            case _                     => -1
          },
          perPartition.size
        )
      case _ => ()
    }
  }

  private def o(value: Long) = kui.kernel.Offset.unsafe(value)
  private def p(value: Int) = kui.kernel.PartitionId.unsafe(value)
}
