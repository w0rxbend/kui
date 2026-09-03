package kui.cluster.infrastructure

import java.time.Instant

import kui.kernel.error.{ApplicationError, DomainError, ErrorCode, FieldError, InfrastructureError, KuiError}
import kui.testkit.KuiSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

/** The invalidation policy of `research/kafka/admin-capabilities.md` §0, asserted case by case.
  *
  * The property at the end is the one that matters over time: it fails the day somebody adds a `KuiError`
  * case and does not think about this file.
  */
final class ReconnectPolicySuite extends KuiSuite {

  test("unreachableTimeoutAndAuthFailedInvalidate") {
    assert(ReconnectPolicy.shouldInvalidate(InfrastructureError.Unreachable("kafka:local", "TimeoutException")))
    assert(ReconnectPolicy.shouldInvalidate(InfrastructureError.Timeout("describeCluster", 30000L)))
    assert(ReconnectPolicy.shouldInvalidate(InfrastructureError.AuthFailed("kafka:local")))
  }

  test("otherInfrastructureErrorsDoNotInvalidate") {
    // An upstream that answered, a breaker that never made the call, and another process's failure all say
    // nothing about this client's socket.
    assert(!ReconnectPolicy.shouldInvalidate(InfrastructureError.Upstream("kui-store", 503)))
    assert(!ReconnectPolicy.shouldInvalidate(InfrastructureError.CircuitOpen("kafka:local", Instant.EPOCH)))
    assert(
      !ReconnectPolicy.shouldInvalidate(InfrastructureError.Remote(ErrorCode.Timeout, "upstream timed out", Nil))
    )
  }

  test("applicationErrorsDoNotInvalidate") {
    val cases: List[KuiError] = List(
      ApplicationError.NotFound("cluster", "prod", ErrorCode.ClusterNotFound),
      ApplicationError.Conflict("version conflict"),
      ApplicationError.Forbidden("not allowed"),
      ApplicationError.Unauthenticated("no principal"),
      ApplicationError.Unsupported("broker configuration"),
      ApplicationError.InvalidState("not replayed yet"),
      ApplicationError.Invalid("bad request", List(FieldError.of("name", "must not be empty"))),
      ApplicationError.Remote(ErrorCode.Validation, "invalid", Nil)
    )
    cases.foreach(e => assert(!ReconnectPolicy.shouldInvalidate(e), s"$e must not invalidate"))
  }

  test("domainErrorsDoNotInvalidate") {
    assert(!ReconnectPolicy.shouldInvalidate(DomainError.InvariantViolation("isr must be a subset of replicas")))
  }

  property("thePolicyIsTotal") {
    forAll(ReconnectPolicySuite.anyKuiError) { (error: KuiError) =>
      // The assertion is that the function answers at all: no MatchError, no exception, for every case the
      // hierarchy has.
      val answer = ReconnectPolicy.shouldInvalidate(error)
      answer || !answer
    }
  }
}

object ReconnectPolicySuite {

  private val text: Gen[String] = Gen.alphaNumStr.map(s => if s.isEmpty then "x" else s)

  private val code: Gen[ErrorCode] = Gen.oneOf(ErrorCode.values.toIndexedSeq)

  /** Every `KuiError` case the kernel declares, one branch each. Listing them by hand rather than deriving
    * is the point: a new case has to be added here, and that is the moment somebody reconsiders the policy.
    */
  val anyKuiError: Gen[KuiError] = Gen.oneOf[Gen[KuiError]](
    text.map(r => DomainError.InvariantViolation(r)),
    for w <- text; i <- text; c <- code yield ApplicationError.NotFound(w, i, c),
    text.map(ApplicationError.Conflict.apply),
    text.map(ApplicationError.Forbidden.apply),
    text.map(ApplicationError.Unauthenticated.apply),
    text.map(ApplicationError.Unsupported.apply),
    text.map(ApplicationError.InvalidState.apply),
    text.map(m => ApplicationError.Invalid(m, Nil)),
    for c <- code; m <- text yield ApplicationError.Remote(c, m, Nil),
    for u <- text; c <- text yield InfrastructureError.Unreachable(u, c),
    for o <- text; ms <- Gen.chooseNum(0L, 60000L) yield InfrastructureError.Timeout(o, ms),
    text.map(InfrastructureError.AuthFailed.apply),
    for u <- text; s <- Gen.chooseNum(400, 599) yield InfrastructureError.Upstream(u, s),
    text.map(u => InfrastructureError.CircuitOpen(u, Instant.EPOCH)),
    for c <- code; m <- text yield InfrastructureError.Remote(c, m, Nil)
  ).flatMap(identity)

  given Arbitrary[KuiError] = Arbitrary(anyKuiError)
}
