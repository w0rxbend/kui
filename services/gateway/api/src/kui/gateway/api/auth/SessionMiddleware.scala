package kui.gateway.api.auth

import java.time.Instant

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.model.headers.{Cookie, CookieWithMeta}
import sttp.model.{Header, StatusCode}
import sttp.monad.MonadError
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.{
  DecodeFailureContext,
  DecodeSuccessContext,
  EndpointHandler,
  EndpointInterceptor,
  Interceptor,
  RequestInterceptor,
  RequestResult,
  Responder,
  SecurityFailureContext
}
import sttp.tapir.server.interpreter.BodyListener
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.{statusCode, AttributeKey, EndpointOutput}

import kui.contracts.{ErrorEnvelope, HttpHeaders}
import kui.contracts.ErrorEnvelope.given
import kui.gateway.application.session.{Session, SessionId, SessionRef, SessionStore}
import kui.kernel.error.ErrorCode
import kui.observability.Correlation
import kui.security.Principal

/** The session and CSRF boundary every request passes through (ADR-019).
  *
  * Three interceptors, in the order [[interceptors]] returns them, mirroring the split `EdgeHeaders` already
  * uses and for the identical reason: a request has to be attached to a session *before* Tapir decodes any
  * endpoint's inputs, or an endpoint that reads the request via `sttp.tapir.extractFromRequest` — which
  * `AuthRoutes` does — captures the pre-attachment version and never sees it. That rules out doing this as a
  * single [[EndpointInterceptor]], which only runs *after* decoding:
  *
  *   1. **Attach a session** ([[RequestInterceptor.transformServerRequest]]). The cookie the request carries
  *      is looked up; a missing, unrecognised or expired one gets a fresh anonymous session instead of a
  *      failure — anonymous mode issues a session to every browser precisely so the CSRF machinery is
  *      exercised in CI for six milestones before login exists. The session is attached to the request as an
  *      attribute ([[SessionMiddleware.Attribute]]), which both later steps and `AuthRoutes` read.
  *   1. **Enforce CSRF on a mutation** (an [[EndpointInterceptor]], which alone has a [[Responder]] in hand
  *      to answer with something other than the endpoint's own logic). [[CsrfCheck.verdict]] is applied once
  *      an endpoint has matched; a `Denied` verdict answers `403 KUI-FORBIDDEN` immediately, without that
  *      endpoint's logic ever running, and is logged at `WARN` — a CSRF rejection is a signal worth alerting
  *      on, not a client error to note and move past.
  *   1. **Stamp `Set-Cookie`** ([[RequestInterceptor.transformResultEffect]]), reading the same attribute
  *      back off the request once a response exists, on every endpoint — so a new or rotated session's cookie
  *      reaches the browser without every route having to declare it as an output.
  *
  * Installed as a block, after `EdgeHeaders` and before `KuiInterceptors`, in `GatewayWiring`'s chain:
  * `Cookie` is not an `X-Kui-*` header so the edge strip does not touch it, and this decision belongs beside
  * authentication, ahead of tracing and metrics recording an endpoint that never ran.
  */
object SessionMiddleware {

  /** The cookie name ADR-019 fixes. */
  val CookieName: String = "kui_session"

  /** The header a non-`GET` cookie-authenticated request must echo the session's CSRF secret in.
    *
    * The name itself lives in `kui.contracts.HttpHeaders`, which the browser's `ApiClient` compiles against
    * too, so that the two halves cannot drift apart the way they once did. `HttpHeaders.Csrf` explains why
    * the name is deliberately outside the `X-Kui-*` family.
    */
  val CsrfHeaderName: String = HttpHeaders.Csrf

  /** Where the resolved session lives for the rest of the request pipeline to read. */
  val Attribute: AttributeKey[Session] = new AttributeKey[Session]("kui.gateway.session")

  /** The three interceptors, in the order `GatewayWiring` installs them. */
  def interceptors[F[_]: Sync](
      store: SessionStore[F],
      logger: StructuredLogger[F],
      basePath: String,
      secureCookies: Boolean
  ): List[Interceptor[F]] =
    List(
      attachInterceptor[F](store),
      CsrfInterceptor[F](logger),
      stampCookieInterceptor[F](basePath, secureCookies)
    )

  /** Ensures a request carries a valid session, creating one when it does not. */
  def ensureSession[F[_]: Sync](store: SessionStore[F], request: ServerRequest, now: Instant): F[Session] =
    cookieValue(request) match {
      case None => store.create(Principal.Anonymous, now)
      case Some(raw) =>
        store.get(SessionId.unsafe(raw), now).flatMap {
          case Some(session) => session.pure[F]
          case None => store.create(Principal.Anonymous, now)
        }
    }

  /** The `kui_session` cookie's value from the request's `Cookie` header, if present and well-formed.
    *
    * Parsed with `sttp.model.headers.Cookie.parse` rather than read as a raw substring: a `Cookie` header can
    * carry several cookies, `;`-separated, and a substring search would find the wrong one if a proxy or
    * another application on the same domain set a cookie whose name happened to contain `kui_session`.
    */
  def cookieValue(request: ServerRequest): Option[String] =
    request.header("Cookie").flatMap { raw =>
      Cookie.parse(raw).toOption.flatMap(_.find(_.name == CookieName)).map(_.value)
    }

  /** The `Set-Cookie` value for a session, exactly as ADR-019 specifies it.
    *
    * `secure` defaults to `true`; the one escape hatch is `devInsecureCookies` (`GatewayWiring`), meant for
    * `localhost` development over plain HTTP, and refused outright by CFG-001 when the deployment's
    * configured base URL is `https` — a flag meant for a laptop must not be reachable in a configuration that
    * could also describe production.
    */
  def setCookie(session: Session, basePath: String, secure: Boolean): CookieWithMeta =
    CookieWithMeta.unsafeApply(
      name = CookieName,
      value = session.id.value,
      path = Some(if basePath.isEmpty then "/" else s"$basePath/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  // -----------------------------------------------------------------------------------------------
  // 1. Attach a session, ahead of any endpoint's own input decoding.
  // -----------------------------------------------------------------------------------------------

  private def attachInterceptor[F[_]: Sync](store: SessionStore[F]): RequestInterceptor[F] =
    RequestInterceptor.transformServerRequest[F] { request =>
      for {
        now <- Clock[F].realTimeInstant
        session <- ensureSession[F](store, request, now)
      } yield request.attribute(Attribute, session)
    }

  // -----------------------------------------------------------------------------------------------
  // 2. CSRF: an EndpointInterceptor, because only this extension point hands over a Responder capable
  //    of answering with a whole different response before the endpoint's own logic runs.
  // -----------------------------------------------------------------------------------------------

  final private class CsrfInterceptor[F[_]: Sync](logger: StructuredLogger[F])
      extends EndpointInterceptor[F] {

    def apply[B](responder: Responder[F, B], delegate: EndpointHandler[F, B]): EndpointHandler[F, B] =
      new EndpointHandler[F, B] {

        def onDecodeSuccess[A, U, I](ctx: DecodeSuccessContext[F, A, U, I])(using
            monad: MonadError[F],
            bodyListener: BodyListener[F, B]
        ): F[sttp.tapir.server.model.ServerResponse[B]] =
          ctx.request.attribute(Attribute) match {
            case None =>
              // Unreachable once `attachInterceptor` is installed ahead of this one — every request has an
              // attribute by the time an endpoint has matched. If it is ever missing, failing open (letting
              // the endpoint run unchecked) would be the CSRF equivalent of a fail-open firewall; refusing
              // outright is the safe direction for a bug to fail in.
              CsrfInterceptor.internalError[F, B](responder, ctx.request)
            case Some(session) =>
              val verdict = CsrfCheck.verdict(
                method = ctx.request.method.method,
                authKind = session.principal.kind,
                headerToken = ctx.request.header(CsrfHeaderName),
                sessionSecret = Some(session.csrfSecret.value),
                secFetchSite = ctx.request.header("Sec-Fetch-Site")
              )
              verdict match {
                case CsrfCheck.Verdict.Allowed => delegate.onDecodeSuccess(ctx)
                case CsrfCheck.Verdict.Denied(reason) =>
                  CsrfInterceptor.forbidden[F, B](responder, logger, ctx.request, session, reason)
              }
          }

        def onSecurityFailure[A](ctx: SecurityFailureContext[F, A])(using
            monad: MonadError[F],
            bodyListener: BodyListener[F, B]
        ): F[sttp.tapir.server.model.ServerResponse[B]] =
          delegate.onSecurityFailure(ctx)

        def onDecodeFailure(ctx: DecodeFailureContext)(using
            monad: MonadError[F],
            bodyListener: BodyListener[F, B]
        ): F[Option[sttp.tapir.server.model.ServerResponse[B]]] =
          delegate.onDecodeFailure(ctx)
      }
  }

  private object CsrfInterceptor {

    private val forbiddenOutput: EndpointOutput[(StatusCode, ErrorEnvelope)] =
      statusCode.and(jsonBody[ErrorEnvelope])

    def forbidden[F[_]: Sync, B](
        responder: Responder[F, B],
        logger: StructuredLogger[F],
        request: ServerRequest,
        session: Session,
        reason: String
    ): F[sttp.tapir.server.model.ServerResponse[B]] =
      for {
        _ <- logger.warn(
          Map(
            "path" -> request.uri.path.mkString("/", "/", ""),
            "secFetchSite" -> request.header("Sec-Fetch-Site").getOrElse("absent"),
            "session.ref" -> SessionRef.of(session.id).value
          )
        )(s"CSRF rejected: $reason")
        response <- respond[F, B](responder, request, StatusCode.Forbidden, ErrorCode.Forbidden.wire, reason)
      } yield response

    def internalError[F[_]: Sync, B](
        responder: Responder[F, B],
        request: ServerRequest
    ): F[sttp.tapir.server.model.ServerResponse[B]] =
      respond[F, B](
        responder,
        request,
        StatusCode.InternalServerError,
        ErrorCode.Internal.wire,
        "no session was attached to the request"
      )

    private def respond[F[_]: Sync, B](
        responder: Responder[F, B],
        request: ServerRequest,
        status: StatusCode,
        code: String,
        message: String
    ): F[sttp.tapir.server.model.ServerResponse[B]] =
      for {
        correlationId <- request.header(Correlation.HeaderName).getOrElse("").pure[F]
        at <- Clock[F].realTimeInstant
        body = ErrorEnvelope(code, message, Nil, correlationId, at, retryable = false)
        response <- responder.apply(request, ValuedEndpointOutput(forbiddenOutput, (status, body)))
      } yield response
  }

  // -----------------------------------------------------------------------------------------------
  // 3. Stamp Set-Cookie on whatever response came back, for every endpoint at once.
  // -----------------------------------------------------------------------------------------------

  private def stampCookieInterceptor[F[_]: Sync](
      basePath: String,
      secureCookies: Boolean
  ): RequestInterceptor[F] =
    RequestInterceptor.transformResultEffect[F](
      new RequestInterceptor.RequestResultEffectTransform[F] {
        def apply[B](request: ServerRequest, result: F[RequestResult[B]]): F[RequestResult[B]] =
          result.map { requestResult =>
            request.attribute(Attribute) match {
              case None => requestResult
              case Some(session) =>
                requestResult match {
                  case RequestResult.Response(response, source) =>
                    RequestResult.Response(stampCookie(response, session, basePath, secureCookies), source)
                  case failure => failure
                }
            }
          }
      }
    )

  private def stampCookie[B](
      response: sttp.tapir.server.model.ServerResponse[B],
      session: Session,
      basePath: String,
      secure: Boolean
  ): sttp.tapir.server.model.ServerResponse[B] =
    response.copy(headers =
      response.headers :+ Header("Set-Cookie", setCookie(session, basePath, secure).toString)
    )
}
