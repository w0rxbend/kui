package kui.kernel.group

import kui.kernel.ValidationError
import kui.kernel.error.ErrorCode
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The properties that make the consumer-group vocabulary usable as a contract.
  *
  * Each closed set is asserted against a literal list of wire strings. That looks like restating the enum,
  * and it is exactly the point: the wire strings are a published contract, so renaming a case has to fail a
  * test and be re-approved rather than quietly changing what a browser and a broker mapping agree on.
  */
final class GroupVocabularySuite extends ScalaCheckSuite {

  property("every group state round-trips through its wire string") {
    forAll(Gen.oneOf(GroupState.All)) { state =>
      assertEquals(GroupState.from(state.wire), Right(state))
    }
  }

  test("the group-state wire set is exactly the documented set") {
    assertEquals(
      GroupState.All.map(_.wire).sorted,
      List(
        "COMPLETING_REBALANCE",
        "DEAD",
        "EMPTY",
        "PREPARING_REBALANCE",
        "STABLE",
        "UNKNOWN"
      )
    )
  }

  test("an unknown group-state wire is a validation error naming the field") {
    GroupState.from("REBALANCING") match {
      case Left(ValidationError.Format(field, _, got)) =>
        assertEquals(field, "state")
        assertEquals(got, "REBALANCING")
      case other => fail(s"expected a Format error naming the field, got $other")
    }
  }

  test("every group state carries a non-empty operator-facing description") {
    GroupState.All.foreach { state =>
      assert(state.description.nonEmpty, s"${state.wire} has no tooltip")
      assert(state.description.endsWith("."), s"${state.wire}'s tooltip is not a sentence")
    }
  }

  test("permitsOffsetChange is exactly Empty and Dead") {
    assertEquals(
      GroupState.All.filter(GroupState.permitsOffsetChange).toSet,
      Set(GroupState.Empty, GroupState.Dead)
    )
  }

  property("every reset target round-trips through its wire string") {
    forAll(Gen.oneOf(ResetTarget.All)) { target =>
      assertEquals(ResetTarget.from(target.wire), Right(target))
    }
  }

  test("the reset-target wire set is exactly the documented set") {
    assertEquals(
      ResetTarget.All.map(_.wire).sorted,
      List("DURATION", "EARLIEST", "LATEST", "OFFSET", "SHIFT_BY", "TIMESTAMP")
    )
  }

  test("an unknown reset-target wire is a validation error") {
    assert(ResetTarget.from("TO_EARLIEST").isLeft)
  }

  property("every lag anomaly round-trips through its wire string") {
    forAll(Gen.oneOf(LagAnomaly.All)) { anomaly =>
      assertEquals(LagAnomaly.from(anomaly.wire), Right(anomaly))
    }
  }

  test("the lag-anomaly wire set is exactly the documented set") {
    assertEquals(
      LagAnomaly.All.map(_.wire).sorted,
      List("COMMITTED_BEFORE_START", "COMMITTED_BEYOND_END", "NO_COMMIT", "NO_LEADER")
    )
  }

  test("an unknown lag-anomaly wire is a validation error") {
    assert(LagAnomaly.from("NEGATIVE_LAG").isLeft)
  }

  property("every group protocol round-trips through its wire string") {
    forAll(Gen.oneOf(GroupProtocol.All)) { protocol =>
      assertEquals(GroupProtocol.from(protocol.wire), Right(protocol))
    }
  }

  test("the two new error codes carry the documented statuses") {
    assertEquals(ErrorCode.GroupNotFound.httpStatus, 404)
    assertEquals(ErrorCode.GroupNotEmpty.httpStatus, 409)
    assert(!ErrorCode.GroupNotFound.retryable)
    assert(!ErrorCode.GroupNotEmpty.retryable)
    assertEquals(ErrorCode.fromWire("KUI-GROUP-NOT-FOUND"), Some(ErrorCode.GroupNotFound))
    assertEquals(ErrorCode.fromWire("KUI-GROUP-NOT-EMPTY"), Some(ErrorCode.GroupNotEmpty))
  }

  test("no two error codes share a wire string") {
    val wires = ErrorCode.values.toList.map(_.wire)
    assertEquals(wires.distinct.length, wires.length)
  }
}
