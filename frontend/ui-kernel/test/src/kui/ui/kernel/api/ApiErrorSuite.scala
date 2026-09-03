package kui.ui.kernel.api

import java.time.Instant

import munit.FunSuite

import kui.contracts.{ErrorDetail, ErrorEnvelope}
import kui.kernel.error.ErrorCode

/** The four predicates every caller branches on, for every case.
  *
  * They are exhaustive on purpose. Each one decides something a user sees — whether a sign-in prompt appears,
  * whether the full-screen "cannot reach gateway" state takes over, whether a "Try again" button is offered —
  * and each is one `match` away from silently answering `false` for a case somebody adds later.
  */
class ApiErrorSuite extends FunSuite {

  private def envelopeWith(code: String, retryable: Boolean = false): ApiError =
    ApiError.of(
      ErrorEnvelope(
        code = code,
        message = s"Something about $code.",
        details = List(ErrorDetail(Some("name"), List("must not be empty"))),
        correlationId = "corr-9",
        timestamp = Instant.parse("2026-09-03T10:11:12Z"),
        retryable = retryable
      )
    )

  test("isAuthIsTrueOnlyForTheUnauthenticatedCode") {
    assert(envelopeWith(ErrorCode.Unauthenticated.wire).isAuth)
    assert(!envelopeWith(ErrorCode.Forbidden.wire).isAuth)
    assert(!envelopeWith(ErrorCode.Internal.wire).isAuth)
    assert(!ApiError.Unreachable("offline").isAuth)
    assert(!ApiError.Timeout.isAuth)
    assert(!ApiError.Decoding("bad json").isAuth)
  }

  test("isForbiddenIsTrueOnlyForTheForbiddenCode") {
    assert(envelopeWith(ErrorCode.Forbidden.wire).isForbidden)
    assert(!envelopeWith(ErrorCode.Unauthenticated.wire).isForbidden)
    assert(!envelopeWith(ErrorCode.Internal.wire).isForbidden)
    assert(!ApiError.Unreachable("offline").isForbidden)
    assert(!ApiError.Timeout.isForbidden)
    assert(!ApiError.Decoding("bad json").isForbidden)
  }

  test("isTransportSeparatesSilenceFromAnAnswerKuiCouldNotRead") {
    assert(ApiError.Unreachable("offline").isTransport)
    assert(ApiError.Timeout.isTransport)
    // Something answered, so the gateway is reachable and the shell must not claim otherwise.
    assert(!ApiError.Decoding("<html>").isTransport)
    assert(!envelopeWith(ErrorCode.Internal.wire).isTransport)
  }

  test("isRetryableFollowsTheServerAndNeverInventsAnAnswer") {
    assert(envelopeWith(ErrorCode.ConnectRebalancing.wire, retryable = true).isRetryable)
    assert(!envelopeWith(ErrorCode.TopicNotFound.wire, retryable = false).isRetryable)
    assert(ApiError.Unreachable("offline").isRetryable)
    assert(ApiError.Timeout.isRetryable)
    // Asking again for a response the contract cannot parse produces the same unparseable response.
    assert(!ApiError.Decoding("<html>").isRetryable)
  }

  test("anUnknownCodeStillRenders") {
    // KERN-005's forward-compatibility rule: a browser built today must show a failure a gateway
    // built tomorrow invented, rather than treat it as a parse error or a blank screen.
    val future = envelopeWith("KUI-SOMETHING-FROM-2027")
    assertEquals(future.userMessage, "Something about KUI-SOMETHING-FROM-2027.")
    assertEquals(future.correlation, Some("corr-9"))
    assert(!future.isAuth)
    assert(!future.isForbidden)
  }

  test("everyNonEnvelopeCaseHasItsOwnUserFacingSentenceAndNoCorrelationId") {
    assertEquals(ApiError.Unreachable("net::ERR").userMessage, ApiError.UnreachableMessage)
    assertEquals(ApiError.Timeout.userMessage, ApiError.TimeoutMessage)
    assertEquals(ApiError.Decoding("<html>").userMessage, ApiError.DecodingMessage)
    // Nothing that failed before the server answered has a correlation id, and inventing one would
    // send a user hunting through logs for an identifier that was never written.
    assertEquals(ApiError.Unreachable("net::ERR").correlation, None)
    assertEquals(ApiError.Timeout.correlation, None)
    assertEquals(ApiError.Decoding("<html>").correlation, None)
  }

  test("theEnvelopesDetailsSurviveSoAFormCanHighlightTheFieldThatWasRejected") {
    envelopeWith(ErrorCode.Validation.wire) match {
      case ApiError.Envelope(_, _, details, _, _) =>
        assertEquals(details, List(ErrorDetail(Some("name"), List("must not be empty"))))
      case other => fail(s"expected an envelope, got $other")
    }
  }

  test("theRawTransportCauseIsKeptForTheConsoleAndNeverShownToTheUser") {
    val error = ApiError.Unreachable("net::ERR_CONNECTION_REFUSED")
    assertEquals(error.userMessage, ApiError.UnreachableMessage)
    assertEquals(error, ApiError.Unreachable("net::ERR_CONNECTION_REFUSED"))
  }
}
