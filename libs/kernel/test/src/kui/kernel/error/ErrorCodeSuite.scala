package kui.kernel.error

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The properties that make an error code usable as a contract.
  *
  * None of these are about one code in particular. They are about the whole table staying
  * well-formed as cases are added, which is when a duplicate or a typo actually happens.
  */
final class ErrorCodeSuite extends ScalaCheckSuite {

  private val allCodes: List[ErrorCode] = ErrorCode.values.toList

  test("no two codes share a wire string") {
    val wires      = allCodes.map(_.wire)
    val duplicates = wires.groupBy(identity).filter(_._2.sizeIs > 1).keys.toList.sorted
    assertEquals(duplicates, Nil, clue = "a duplicate wire string makes two failures indistinguishable")
  }

  test("every wire string follows the KUI-<AREA>-<NAME> convention") {
    val pattern = "^KUI-[A-Z]+(-[A-Z]+)*$".r
    allCodes.foreach { code =>
      assert(pattern.matches(code.wire), s"${code.wire} does not match KUI-<AREA>-<NAME>")
    }
  }

  property("fromWire is the inverse of wire") {
    forAll(Gen.oneOf(allCodes)) { code =>
      assertEquals(ErrorCode.fromWire(code.wire), Some(code))
    }
  }

  test("an unrecognised code decodes to None rather than failing") {
    assertEquals(ErrorCode.fromWire("KUI-SOMETHING-NEWER"), None)
    assertEquals(ErrorCode.fromWire(""), None)
    assertEquals(ErrorCode.fromWire("kui-validation"), None)
  }

  test("every code carries an HTTP status that is actually an error status") {
    allCodes.foreach { code =>
      assert(
        code.httpStatus >= 400 && code.httpStatus <= 599,
        s"${code.wire} has status ${code.httpStatus}"
      )
    }
  }

  test("every code carries an operator-facing description, ending in a full stop") {
    allCodes.foreach { code =>
      assert(code.description.nonEmpty, s"${code.wire} has no description")
      assert(code.description.endsWith("."), s"${code.wire}'s description is not a sentence")
    }
  }

  test("the area is the second segment of the wire string") {
    assertEquals(ErrorCode.TopicNotFound.area, "TOPIC")
    assertEquals(ErrorCode.UpstreamUnavailable.area, "UPSTREAM")
    assertEquals(ErrorCode.Validation.area, "VALIDATION")
  }

  /** The wire strings of ADR-034, written out. This table is the contract: if a rename ever makes it
    * past review, this is the test that says so, and the fix is to revert the rename rather than to
    * update the table.
    */
  test("the wire strings and statuses are the ones ADR-034 assigns") {
    val expected: List[(ErrorCode, String, Int, Boolean)] = List(
      (ErrorCode.ClusterNotFound, "KUI-CLUSTER-NOT-FOUND", 404, false),
      (ErrorCode.TopicNotFound, "KUI-TOPIC-NOT-FOUND", 404, false),
      (ErrorCode.SchemaNotFound, "KUI-SCHEMA-NOT-FOUND", 404, false),
      (ErrorCode.Validation, "KUI-VALIDATION", 400, false),
      (ErrorCode.ReadOnly, "KUI-READ-ONLY", 405, false),
      (ErrorCode.ConnectRebalancing, "KUI-CONNECT-REBALANCING", 409, true),
      (ErrorCode.GroupNotFound, "KUI-GROUP-NOT-FOUND", 404, false),
      (ErrorCode.GroupNotEmpty, "KUI-GROUP-NOT-EMPTY", 409, false),
      (ErrorCode.InvalidState, "KUI-INVALID-STATE", 409, false),
      (ErrorCode.Timeout, "KUI-TIMEOUT", 408, true),
      (ErrorCode.FilterCompile, "KUI-FILTER-COMPILE", 400, false),
      (ErrorCode.ConnectorOffsets, "KUI-CONNECTOR-OFFSETS", 400, false),
      (ErrorCode.UpstreamKsql, "KUI-UPSTREAM-KSQL", 502, true),
      (ErrorCode.UpstreamAuth, "KUI-UPSTREAM-AUTH", 502, false),
      (ErrorCode.UpstreamUnavailable, "KUI-UPSTREAM-UNAVAILABLE", 503, true),
      (ErrorCode.Unsupported, "KUI-UNSUPPORTED", 501, false),
      (ErrorCode.Forbidden, "KUI-FORBIDDEN", 403, false),
      (ErrorCode.Unauthenticated, "KUI-UNAUTHENTICATED", 401, false),
      (ErrorCode.CursorExpired, "KUI-CURSOR-EXPIRED", 400, false),
      (ErrorCode.CursorInvalid, "KUI-CURSOR-INVALID", 400, false),
      (ErrorCode.CursorTooLarge, "KUI-CURSOR-TOO-LARGE", 400, false),
      (ErrorCode.ConfigVersionConflict, "KUI-CONFIG-VERSION-CONFLICT", 409, false),
      (ErrorCode.StoreUnavailable, "KUI-STORE-UNAVAILABLE", 503, true),
      (ErrorCode.StoreReplayTimeout, "KUI-STORE-REPLAY-TIMEOUT", 503, true),
      (ErrorCode.StoreTopicIncompatible, "KUI-STORE-TOPIC-INCOMPATIBLE", 500, false),
      (ErrorCode.StoreEnvelope, "KUI-STORE-ENVELOPE", 500, false),
      (ErrorCode.StoreCrypto, "KUI-STORE-CRYPTO", 500, false),
      (ErrorCode.StoreNotConfigured, "KUI-STORE-NOT-CONFIGURED", 501, false),
      (ErrorCode.RouteNotFound, "KUI-ROUTE-NOT-FOUND", 404, false),
      (ErrorCode.Internal, "KUI-INTERNAL", 500, false)
    )

    expected.foreach { row =>
      assertEquals(row._1.wire, row._2)
      assertEquals(row._1.httpStatus, row._3, clue = row._2)
      assertEquals(row._1.retryable, row._4, clue = row._2)
    }
    assertEquals(
      allCodes.size,
      expected.size,
      clue = "a new ErrorCode case needs a row here and a regenerated docs/api/error-codes.md"
    )
  }
}
