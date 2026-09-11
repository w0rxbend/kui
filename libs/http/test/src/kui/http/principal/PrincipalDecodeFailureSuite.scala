package kui.http.principal

import munit.FunSuite

import sttp.tapir.{header, query, EndpointInput}

import kui.contracts.KuiEndpoint

/** Which decode failure `PrincipalInterceptor` converts into a 401, asserted in the module that owns it.
  *
  * The rule is gated — `services/cluster/api`'s `PrincipalVerificationSuite` and
  * `services/consumer/api`'s `ConsumerRoutesSuite` both go red when it breaks — but it was gated **nowhere
  * in `libs/http`**, which is where the interceptor lives and where a reader changing it would look.
  * Inverting `isPrincipalHeader`'s comparison to `!=` left every one of the 258 cases over 24 suites that
  * `./mill -k libs.http.test + libs.securityCore.jvm.test` held before this file existed green, and
  * reddened only once three services' route suites were run — `PrincipalVerificationSuite`,
  * `ClusterWriteRoutesSuite`, `ConsumerRoutesSuite` and `ConsumerBodyDigestSuite`.
  *
  * That distance is the defect this file closes. `libs/http` is the module a second, third and eleventh
  * service inherit this behaviour from; a rule whose only gate is downstream is a rule the next service can
  * be built against while it is already broken.
  *
  * What the rule is: a request that carries **no** `X-Kui-Principal` fails while Tapir is still decoding
  * inputs, so it never reaches the endpoint's security logic. Left to the shared decode-failure handler it
  * would answer `400 KUI-VALIDATION` naming the header — which both leaks which half of a forged request to
  * fix and answers a different status from every other authentication failure. Everything that is not that
  * header must still fall through untouched, or a genuinely malformed query parameter starts answering 401.
  */
final class PrincipalDecodeFailureSuite extends FunSuite {

  test("thePrincipalHeaderIsTheInputTheInterceptorClaims") {
    assert(PrincipalInterceptor.isPrincipalHeader(header[String](KuiEndpoint.PrincipalHeader)))
  }

  test("everyOtherHeaderIsLeftToTheSharedHandler") {
    // The other direction, and the one that matters more: a `false` for everything would still pass the
    // case above if it were written as an inequality, and an interceptor that claimed every header would
    // turn a malformed `If-Match` or `X-Csrf-Token` into an authentication failure.
    assert(!PrincipalInterceptor.isPrincipalHeader(header[String]("X-Csrf-Token")))
    assert(!PrincipalInterceptor.isPrincipalHeader(header[String]("If-Match")))
    // The input compared is the *endpoint's own declaration*, so the comparison is against the constant
    // KUI declares it with. A differently-spelled declaration is a different input and not this one.
    assert(!PrincipalInterceptor.isPrincipalHeader(header[String](KuiEndpoint.PrincipalHeader.toLowerCase)))
  }

  test("anInputThatIsNotAHeaderAtAllIsNeverClaimed") {
    val notAHeader: EndpointInput[?] = query[String]("pageSize")

    assert(!PrincipalInterceptor.isPrincipalHeader(notAHeader))
  }
}
