package kui.http.principal

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Counter
import sttp.model.StatusCode
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DecodeFailureInterceptor}
import sttp.tapir.server.interceptor.{DecodeFailureContext, Interceptor}
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.{header, statusCode, EndpointIO, EndpointInput, EndpointOutput}

import kui.contracts.ErrorEnvelope.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.observability.{Correlation, MetricNames}
import kui.security.PrincipalError

/** Answering a request that carried no principal header with the same 401 as one that carried a forged one.
  *
  * ==Why this exists==
  *
  * `KuiEndpoint.internal` declares the principal as a *security input* whose codec refuses a blank value. A
  * request with no `X-Kui-Principal` header therefore never reaches the endpoint's security logic at all: it
  * fails while Tapir is still decoding inputs, and the shared decode-failure handler in `libs/http` would
  * answer `400 KUI-VALIDATION` naming the header — which is both the wrong status and a different response
  * from the one every other authentication failure produces.
  *
  * That difference matters for exactly the reason [[PrincipalVerification]] gives: two distinguishable
  * refusals let a caller learn which half of a forged request to fix. So a decode failure on that one input
  * is turned into the identical `401 KUI-UNAUTHENTICATED`, and everything else is passed straight through to
  * the shared handler untouched.
  *
  * ==Where it goes in the chain==
  *
  * Before `ErrorInterceptor.interceptors`. Tapir applies the first interceptor in the list outermost, and an
  * outer decode-failure interceptor is offered the failure first and delegates inward when it returns `None`
  * — which is what makes "handle this one input, ignore the rest" possible without reimplementing the shared
  * handler.
  *
  * ==Why it lives here==
  *
  * It was written in `services/cluster/api` in M0, with its own comment saying it belonged in `libs/http` and
  * that hoisting it was "a mechanical move once a second service exists". M2 is that second service. Rule A11
  * makes the alternative explicit rather than merely untidy: one service may not see another's `api` module,
  * so leaving these three hundred lines where they were would have meant the topic service, the message
  * service and the consumer service each copying them — four implementations of one authentication check,
  * differing in ways invisible from inside any one of them.
  */
object PrincipalInterceptor {

  /** The interceptor, ready to be placed ahead of the shared error handling. */
  def interceptor[F[_]: Sync](
      logger: StructuredLogger[F],
      rejections: Counter[F, Long]
  ): Interceptor[F] =
    new DecodeFailureInterceptor[F](handler[F](logger, rejections))

  /** Whether this failure is about the principal header rather than about the request's own fields. */
  def isPrincipalHeader(input: EndpointInput[?]): Boolean =
    input match {
      case headerInput: EndpointIO.Header[?] => headerInput.name == KuiEndpoint.PrincipalHeader
      case _ => false
    }

  private def handler[F[_]: Sync](
      logger: StructuredLogger[F],
      rejections: Counter[F, Long]
  ): DecodeFailureHandler[F] =
    new DecodeFailureHandler[F] {
      def apply(ctx: DecodeFailureContext)(using
          monad: sttp.monad.MonadError[F]
      ): F[Option[ValuedEndpointOutput[?]]] =
        if !isPrincipalHeader(ctx.failingInput) then monad.unit(None)
        else respond[F](logger, rejections, ctx).map(Some(_))
    }

  /** The 401, with the same body [[PrincipalVerification]] produces and the same counter incremented.
    *
    * `PrincipalError.Missing` is the reason recorded, because that is precisely what happened, and it is the
    * label an operator needs to tell "the gateway is not sending the header at all" apart from "the gateway
    * is sending one this service cannot verify".
    */
  private def respond[F[_]: Sync](
      logger: StructuredLogger[F],
      rejections: Counter[F, Long],
      ctx: DecodeFailureContext
  ): F[ValuedEndpointOutput[?]] = {
    val reason = PrincipalError.Missing.metricLabel

    for {
      correlationId <- kui.http.ErrorInterceptor.correlationIdOf[F](ctx.request)
      now <- Clock[F].realTimeInstant
      _ <- rejections.inc(Attribute(MetricNames.Attr.Reason, reason))
      _ <- logger.warn(
        Map(
          kui.observability.ContextKeys.CorrelationId -> correlationId.value,
          MetricNames.Attr.Reason -> reason
        )
      )(s"refused a signed principal: $reason")
    } yield ValuedEndpointOutput(
      output,
      (
        StatusCode(ErrorEnvelope.statusOf(PrincipalVerification.Unauthenticated)),
        ErrorEnvelope.of(PrincipalVerification.Unauthenticated, correlationId, now),
        correlationId.value
      )
    )
  }

  /** The same three parts every KUI error response has: the status, the envelope, and the correlation id in a
    * header for a client that cannot read the body.
    */
  private val output: EndpointOutput[(StatusCode, ErrorEnvelope, String)] =
    statusCode.and(jsonBody[ErrorEnvelope]).and(header[String](Correlation.HeaderName))
}
