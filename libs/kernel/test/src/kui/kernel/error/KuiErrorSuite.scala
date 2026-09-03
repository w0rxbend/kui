package kui.kernel.error

import java.time.Instant

import munit.FunSuite

import kui.kernel.ValidationError

/** Which code each concrete failure carries, and what its message is allowed to say.
  *
  * The table is the point. Every row is a decision from ADR-034, and the suite fails the moment a
  * case is pointed at a different code — which is exactly the change that silently breaks a
  * frontend that switches on the code and an operator's saved log query.
  */
final class KuiErrorSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")

  private val cases: List[(String, KuiError, ErrorCode)] = List(
    (
      "a missing topic",
      ApplicationError.NotFound("Topic", "orders", ErrorCode.TopicNotFound),
      ErrorCode.TopicNotFound
    ),
    (
      "a missing cluster",
      ApplicationError.NotFound("Cluster", "prod-eu", ErrorCode.ClusterNotFound),
      ErrorCode.ClusterNotFound
    ),
    ("a conflicting change", ApplicationError.Conflict("already exists"), ErrorCode.InvalidState),
    ("a denied operation", ApplicationError.Forbidden("no role permits this"), ErrorCode.Forbidden),
    (
      "an unidentified caller",
      ApplicationError.Unauthenticated("no principal"),
      ErrorCode.Unauthenticated
    ),
    ("an unsupported feature", ApplicationError.Unsupported("quotas"), ErrorCode.Unsupported),
    ("a wrong state", ApplicationError.InvalidState("connector is paused"), ErrorCode.InvalidState),
    (
      "a malformed request",
      ApplicationError.Invalid("Request is not valid", List(FieldError.of("partitions", "must be > 0"))),
      ErrorCode.Validation
    ),
    ("a broken business rule", DomainError.InvariantViolation("from must not exceed until"), ErrorCode.Validation),
    (
      "an unreachable upstream",
      InfrastructureError.Unreachable("schema-registry", "connection refused"),
      ErrorCode.UpstreamUnavailable
    ),
    ("a timed-out operation", InfrastructureError.Timeout("describeTopics", 5000L), ErrorCode.Timeout),
    ("rejected credentials", InfrastructureError.AuthFailed("schema-registry"), ErrorCode.UpstreamAuth),
    (
      "an upstream error status",
      InfrastructureError.Upstream("connect", 500),
      ErrorCode.UpstreamUnavailable
    ),
    (
      "an open circuit",
      InfrastructureError.CircuitOpen("ksql", at),
      ErrorCode.UpstreamUnavailable
    )
  )

  cases.foreach { row =>
    test(s"${row._1} carries ${row._3.wire}") {
      assertEquals(row._2.code, row._3)
    }
  }

  test("every error has a non-empty, single-sentence message") {
    cases.foreach { row =>
      assert(row._2.message.nonEmpty, s"${row._1} has no message")
      assert(!row._2.message.contains("\n"), s"${row._1} has a multi-line message")
    }
  }

  test("an upstream failure's message names the upstream and the status but nothing else") {
    assertEquals(
      InfrastructureError.Upstream("connect", 503).message,
      "connect answered with status 503"
    )
  }

  test("an unreachable upstream's message does not repeat the underlying cause") {
    val error = InfrastructureError.Unreachable(
      "schema-registry",
      "Connection refused to https://user:hunter2@registry.internal:8081"
    )
    assert(!error.message.contains("hunter2"), "the cause must stay out of the user-facing message")
    assertEquals(error.message, "schema-registry could not be reached")
  }

  test("only validation failures carry details") {
    assertEquals(InfrastructureError.Timeout("describeTopics", 1L).details, Nil)
    assertEquals(
      ApplicationError
        .Invalid("Request is not valid", List(FieldError.of("partitions", "must be > 0")))
        .details,
      List(FieldError(Some("partitions"), List("must be > 0")))
    )
  }

  test("a rejected value object becomes a domain error that keeps the field it was about") {
    val rejected = ValidationError.Range("partitionId", Some("0"), None, "-1")
    val error    = DomainError.fromValidation(rejected)
    assertEquals(error.code, ErrorCode.Validation)
    assertEquals(error.details.flatMap(_.field), List("partitionId"))
    assertEquals(error.message, "partitionId must be at least 0, got '-1'")
  }
}
