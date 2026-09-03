package kui.cluster.api

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}

import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{CorrelationId, ServiceId}
import kui.observability.{ContextKeys, MetricNames}
import kui.security.{
  Principal,
  PrincipalCodec,
  PrincipalError,
  RequestDigest,
  RequestDigests,
  SignedPrincipal
}

/** What this service knows about the call a principal arrived on.
  *
  * Verifying a signed principal needs two facts that are properties of the HTTP request rather than of the
  * token: which call it covers, and which correlation id the answer should carry. Both are read off the
  * request once, by an extractor in [[ClusterApi]], and handed here as a value — so everything in this file
  * is a plain function of its arguments and can be tested without a server.
  *
  * @param digest
  *   the method and path the token must have been minted for (ADR-020). A token for `GET /internal/v1/clusters`
  *   is refused on `DELETE /internal/v1/topics/orders`, which is what stops an intercepted header from being
  *   replayed against a more destructive call.
  * @param correlationId
  *   the id the caller supplied, when it supplied a usable one. `None` means one has to be generated, which
  *   is the caller's job because generating it is an effect.
  */
final case class RequestContext(digest: RequestDigest, correlationId: Option[CorrelationId])

object RequestContext {
  given CanEqual[RequestContext, RequestContext] = CanEqual.derived
}

/** Checking the gateway's signature before any use case runs.
  *
  * ==Why every failure looks the same==
  *
  * There are seven ways for a token to be refused (`PrincipalError`), and the caller is told none of them.
  * The response is always `401` with `KUI-UNAUTHENTICATED` and the message `Unauthenticated`, byte for byte,
  * whichever check failed. That is not tidiness: an endpoint that answered "wrong audience" for one forgery
  * and "bad signature" for another is an oracle, and an attacker with an oracle can fix a forged token one
  * field at a time until it is accepted.
  *
  * The distinction is not thrown away — it is written to this service's own log and counted on
  * `kui.principal.rejected{reason}`, which is where an operator looking at a wave of 401s finds out whether a
  * key rotation went wrong (`unknown_key_id`) or a clock drifted (`expired`). Neither of those reaches the
  * caller.
  */
object PrincipalVerification {

  /** The one refusal. It is a `val` rather than a function so that nothing can accidentally build a
    * differently-worded one and make two 401s distinguishable.
    */
  val Unauthenticated: KuiError = ApplicationError.Unauthenticated("Unauthenticated")

  /** The counter behind `kui.principal.rejected{reason}` (`ARCHITECTURE.md` §13).
    *
    * It is taken as a parameter rather than created here because a counter is a resource: creating one per
    * request would allocate a new instrument on every rejection, and a suite needs to be handed one whose
    * recordings it can read back.
    */
  def rejectionCounter[F[_]](meter: Meter[F]): F[Counter[F, Long]] =
    meter
      .counter[Long](MetricNames.PrincipalRejected)
      .withDescription("Signed principals this service refused, by reason")
      .create

  /** Verifies a token, or refuses it.
    *
    * The clock is read here rather than passed in because expiry is checked against *this service's* now, and
    * a caller that supplied the instant could hold a token open indefinitely by supplying an old one.
    */
  def verify[F[_]: {Monad, Clock}](
      codec: PrincipalCodec[F],
      service: ServiceId,
      logger: StructuredLogger[F],
      rejections: Counter[F, Long]
  )(token: SignedPrincipal, ctx: RequestContext): F[Either[KuiError, Principal]] =
    for {
      now <- Clock[F].realTimeInstant
      outcome <- codec.verify(token, service, ctx.digest, now)
      result <- outcome match {
        case Right(principal) => accept[F](logger, service, ctx, principal)
        case Left(failure) => reject[F](logger, rejections, service, ctx, failure)
      }
    } yield result

  /** The security stage of a Tapir server endpoint: a verified principal, or a rendered refusal.
    *
    * This is the shape every `/internal/v1` endpoint in every service uses, and it sits where it does for a
    * reason. Tapir runs an endpoint's *security* logic before it decodes the request body, so an
    * unauthenticated caller is refused without this service parsing a byte of what they sent — which is both
    * cheaper and a smaller attack surface than checking identity after decoding.
    *
    * The endpoint's own logic, downstream of this, never sees a token, never sees a `PrincipalError` and
    * cannot forget to check one: it is handed a `Principal` or it is not called at all.
    *
    * `render` is how the caller says what a failure looks like on the wire. Deciding that here would mean
    * this module knowing about status codes and correlation ids; it knows what a failure *is*, and
    * [[ClusterApi]] knows how one is served.
    */
  def secured[F[_]: {Monad, Clock}, E](
      codec: PrincipalCodec[F],
      service: ServiceId,
      logger: StructuredLogger[F],
      rejections: Counter[F, Long]
  )(render: (KuiError, RequestContext) => F[E])(
      token: SignedPrincipal,
      ctx: RequestContext
  ): F[Either[E, (Principal, RequestContext)]] =
    verify[F](codec, service, logger, rejections)(token, ctx).flatMap {
      case Right(principal) => (principal, ctx).asRight[E].pure[F]
      case Left(error) => render(error, ctx).map(_.asLeft[(Principal, RequestContext)])
    }

  /** The identity a log line may carry.
    *
    * A login name in a log file is personal data in a place that is read by more people, kept for longer and
    * exported more often than any other store in the system. A hash still answers the question an operator
    * actually has — "are these two entries the same person?" — without answering the one they do not need.
    * The first sixteen hex characters are eight bytes of the digest, which is far more than enough to tell
    * the users of one deployment apart and short enough to read.
    */
  def hashedUserId(principal: Principal): String =
    RequestDigests.sha256Hex(principal.name.value.getBytes("UTF-8")).take(16)

  // -----------------------------------------------------------------------------------------------

  /** One DEBUG line per verified request, carrying the four fields `ARCHITECTURE.md` §13 names.
    *
    * DEBUG and not INFO: a line per successful request doubles the log volume of a healthy service and tells
    * nobody anything the metrics do not. It exists for the day someone has to prove which identity a request
    * ran as, and on that day it is turned on.
    */
  private def accept[F[_]: Monad](
      logger: StructuredLogger[F],
      service: ServiceId,
      ctx: RequestContext,
      principal: Principal
  ): F[Either[KuiError, Principal]] =
    logger
      .debug(context(service, ctx) + (ContextKeys.UserId -> hashedUserId(principal)))(
        s"verified a ${principal.kind.wire} principal"
      )
      .as(principal.asRight[KuiError])

  /** The specific reason, logged and counted; the uniform refusal, returned.
    *
    * WARN rather than ERROR. A refused token is very often a client mistake or a token that expired in
    * flight, and a service that logs those at ERROR teaches its operators to ignore ERROR.
    */
  private def reject[F[_]: Monad](
      logger: StructuredLogger[F],
      rejections: Counter[F, Long],
      service: ServiceId,
      ctx: RequestContext,
      failure: PrincipalError
  ): F[Either[KuiError, Principal]] =
    rejections.inc(Attribute(MetricNames.Attr.Reason, failure.metricLabel)) *>
      logger
        .warn(context(service, ctx) + (MetricNames.Attr.Reason -> failure.metricLabel))(
          s"refused a signed principal: ${failure.metricLabel}"
        )
        .as(Unauthenticated.asLeft[Principal])

  private def context(service: ServiceId, ctx: RequestContext): Map[String, String] =
    Map(
      ContextKeys.ServiceName -> service.value,
      ContextKeys.Operation -> s"${ctx.digest.method} ${ctx.digest.path}"
    ) ++ ctx.correlationId.map(id => ContextKeys.CorrelationId -> id.value)
}
