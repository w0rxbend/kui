package kui.http

import java.time.Instant

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.interceptor.decodefailure.{
  DecodeFailureHandler,
  DecodeFailureInterceptor,
  DefaultDecodeFailureHandler
}
import sttp.tapir.server.interceptor.exception.{ExceptionHandler, ExceptionInterceptor}
import sttp.tapir.server.interceptor.reject.{RejectHandler, RejectInterceptor}
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.{header, statusCode, DecodeResult, EndpointIO, EndpointInput, EndpointOutput}

import kui.contracts.ErrorEnvelope.given
import kui.contracts.{ErrorDetail, ErrorEnvelope, KernelDecodeFailure}
import kui.kernel.CorrelationId
import kui.kernel.error.{ErrorCode, KuiError}
import kui.observability.{ContextKeys, Correlation}

/** The one place a failure becomes an HTTP response.
  *
  * Every KUI process answers a failure with the same body, in the same shape, whatever went wrong: a route
  * that does not exist, a path segment that will not parse, a body that does not decode, an error the
  * endpoint's own logic returned, or an exception nobody expected. `ARCHITECTURE.md` §15 calls this "the
  * single mapping point", and the value of that is not tidiness — it is that the browser and every API client
  * can be written against one error shape and one set of codes, and that adding an endpoint cannot
  * accidentally introduce a sixth way of failing.
  *
  * Two rules are absolute here (ADR-034):
  *
  *   - **A stack trace never leaves the process.** An uncaught exception becomes `KUI-INTERNAL` with the
  *     fixed message `Internal error`; the trace goes to the log, tied to the response by the correlation id.
  *     A stack trace in a response body tells an attacker the framework versions, the package layout and
  *     often the file paths.
  *   - **An upstream's response body is never echoed.** It may contain another system's internal detail, or
  *     its credentials.
  */
object ErrorInterceptor {

  /** The fixed message an uncaught exception produces. It says nothing, deliberately: the correlation id is
    * what leads to the log entry that says everything.
    */
  val InternalMessage: String = "Internal error"

  /** The interceptors that together cover every failure path, in the order a server applies them.
    *
    * Three are needed rather than one because Tapir routes the three kinds of failure to three different
    * extension points, and there is no single `Interceptor` that sees all of them: an exception is caught
    * around the endpoint's logic, a decode failure happens before the logic runs, and "no endpoint matched"
    * happens before any endpoint is chosen at all.
    */
  def interceptors[F[_]: Sync](logger: StructuredLogger[F]): List[Interceptor[F]] =
    List(
      new RejectInterceptor[F](rejectHandler[F](logger)),
      new ExceptionInterceptor[F](exceptionHandler[F](logger)),
      new DecodeFailureInterceptor[F](decodeFailureHandler[F](logger))
    )

  /** The status and the body for a `KuiError`, with no server involved.
    *
    * This is what a service's `api` layer calls to turn the `Either[KuiError, A]` its application layer
    * returned into a response, and it is pure so that the mapping can be tested as a table rather than by
    * starting a server.
    */
  def render(error: KuiError, correlationId: CorrelationId, at: Instant): (Int, ErrorEnvelope) =
    (ErrorEnvelope.statusOf(error), ErrorEnvelope.of(error, correlationId, at))

  /** The envelope for a failure that has no `KuiError` to come from.
    *
    * `KUI-ROUTE-NOT-FOUND` and `KUI-INTERNAL` are both like this: neither is something a domain or an
    * application layer can return, so neither has a case in the `KuiError` hierarchy. The status still comes
    * from `ErrorCode.httpStatus`, which is the same mapping `ErrorEnvelope.statusOf` uses, so there is still
    * exactly one code-to-status table.
    */
  def envelope(
      code: ErrorCode,
      message: String,
      details: List[ErrorDetail],
      correlationId: CorrelationId,
      at: Instant
  ): ErrorEnvelope =
    ErrorEnvelope(code.wire, message, details, correlationId.value, at, code.retryable)

  // -----------------------------------------------------------------------------------------------
  // The three handlers
  // -----------------------------------------------------------------------------------------------

  private def rejectHandler[F[_]: Sync](logger: StructuredLogger[F]): RejectHandler[F] =
    new RejectHandler[F] {
      def apply(ctx: sttp.tapir.server.interceptor.reject.RejectContext)(using
          monad: sttp.monad.MonadError[F]
      ): F[Option[ValuedEndpointOutput[?]]] = {
        val method = ctx.request.method.method
        val path = ctx.request.uri.path.mkString("/", "/", "")
        val message = s"No route for $method $path"

        respond[F](
          logger,
          ctx.request,
          ErrorCode.RouteNotFound,
          message,
          Nil,
          route = s"$method $path",
          cause = None
        )
      }
    }

  private def exceptionHandler[F[_]: Sync](logger: StructuredLogger[F]): ExceptionHandler[F] =
    new ExceptionHandler[F] {
      def apply(ctx: sttp.tapir.server.interceptor.exception.ExceptionContext)(using
          monad: sttp.monad.MonadError[F]
      ): F[Option[ValuedEndpointOutput[?]]] =
        respond[F](
          logger,
          ctx.request,
          ErrorCode.Internal,
          InternalMessage,
          Nil,
          route = routeOf(ctx.endpoint),
          // The only place the throwable is passed on. It reaches the log and stops there.
          cause = Some(ctx.e)
        )
    }

  private def decodeFailureHandler[F[_]: Sync](logger: StructuredLogger[F]): DecodeFailureHandler[F] =
    new DecodeFailureHandler[F] {
      def apply(ctx: sttp.tapir.server.interceptor.DecodeFailureContext)(using
          monad: sttp.monad.MonadError[F]
      ): F[Option[ValuedEndpointOutput[?]]] =
        if !shouldRespond(ctx) then monad.unit(None)
        else {
          val detail = detailOf(ctx)
          respond[F](
            logger,
            ctx.request,
            ErrorCode.Validation,
            detail.restrictions.headOption.getOrElse("the request is not valid"),
            List(detail),
            route = routeOf(ctx.endpoint),
            cause = None
          )
        }
    }

  /** Whether a decode failure should answer, or let the router try the next endpoint.
    *
    * This is Tapir's own rule and it matters for routing rather than for error reporting. Two endpoints can
    * share a path shape — `/topics/{name}` and `/topics/summary` — and the router distinguishes them by
    * *trying* the first and moving on when its path does not decode. If a path mismatch answered 400, the
    * second endpoint would become unreachable.
    *
    * A path segment that is present and *malformed* is different: nothing else could have matched it, so
    * answering `KUI-VALIDATION` is both correct and far more useful than a bare 404.
    */
  private def shouldRespond(ctx: sttp.tapir.server.interceptor.DecodeFailureContext): Boolean =
    ctx.failingInput match {
      case _: EndpointInput.PathCapture[?] | _: EndpointInput.PathsCapture[?] =>
        ctx.failure match {
          case _: DecodeResult.Error | _: DecodeResult.InvalidValue => true
          case _ => false
        }
      case _ => true
    }

  // -----------------------------------------------------------------------------------------------
  // Building the response
  // -----------------------------------------------------------------------------------------------

  /** The output shape of every error response: the status, the envelope as JSON, and the correlation id as a
    * header.
    *
    * The header exists so that a client which cannot read the body — a browser looking at an opaque response,
    * a proxy writing an access log — can still record the id that leads to the server-side log entry.
    */
  private val errorOutput: EndpointOutput[(StatusCode, ErrorEnvelope, String)] =
    statusCode.and(jsonBody[ErrorEnvelope]).and(header[String](Correlation.HeaderName))

  private def respond[F[_]: Sync](
      logger: StructuredLogger[F],
      request: sttp.tapir.model.ServerRequest,
      code: ErrorCode,
      message: String,
      details: List[ErrorDetail],
      route: String,
      cause: Option[Throwable]
  ): F[Option[ValuedEndpointOutput[?]]] =
    for {
      correlationId <- correlationIdOf[F](request)
      now <- Clock[F].realTimeInstant
      body = envelope(code, message, details, correlationId, now)
      _ <- log(logger, code, correlationId, route, cause)
    } yield Some(
      ValuedEndpointOutput(errorOutput, (StatusCode(code.httpStatus), body, correlationId.value))
    )

  /** The id in the request when the caller supplied a usable one, and a fresh one otherwise.
    *
    * Echoing the caller's id means a client that logged "I sent request X" can find X in KUI's logs. It is
    * validated first, because it goes straight back out in a header: an unchecked header value is how a
    * newline, or four kilobytes of someone else's choosing, gets into a log file.
    */
  def correlationIdOf[F[_]: Sync](request: sttp.tapir.model.ServerRequest): F[CorrelationId] =
    request.header(Correlation.HeaderName).flatMap(Correlation.accept) match {
      case Some(id) => Sync[F].pure(id)
      case None => Correlation.newRandom[F]
    }

  /** One entry per error, and none for a success — successful requests are the metrics' job (OBS-002), and
    * logging them as well would double the volume for no extra information.
    */
  private def log[F[_]](
      logger: StructuredLogger[F],
      code: ErrorCode,
      correlationId: CorrelationId,
      route: String,
      cause: Option[Throwable]
  ): F[Unit] = {
    val context = Map(
      "error.code" -> code.wire,
      ContextKeys.CorrelationId -> correlationId.value,
      ContextKeys.Operation -> route,
      "route" -> route
    )
    val message = s"${code.wire} on $route"

    cause match {
      // A server fault, and the only path on which a stack trace is recorded. It goes here and
      // nowhere else: the response carries the correlation id, which is how the two are joined.
      case Some(throwable) => logger.error(context, throwable)(message)
      case None if code.httpStatus >= 500 => logger.error(context)(message)
      case None => logger.warn(context)(message)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Naming the field that failed
  // -----------------------------------------------------------------------------------------------

  /** What was wrong, and with which field.
    *
    * Naming the field is the whole value of a 400 to whoever has to fix the request. The best case is a
    * kernel type's own rejection — `ClusterId.from` failing, say — which arrives wrapped in
    * `KernelDecodeFailure` and already knows both the field name and the rule it broke, so it is passed
    * through unchanged rather than being re-described here.
    */
  def detailOf(ctx: sttp.tapir.server.interceptor.DecodeFailureContext): ErrorDetail =
    ctx.failure match {
      case DecodeResult.Error(_, KernelDecodeFailure(error)) =>
        ErrorDetail(Some(error.fieldName), List(error.message))

      case DecodeResult.Error(original, cause) =>
        ErrorDetail(
          fieldOf(ctx.failingInput),
          List(Option(cause.getMessage).getOrElse(s"'$original' could not be decoded"))
        )

      case DecodeResult.Missing =>
        ErrorDetail(fieldOf(ctx.failingInput), List("is required"))

      case DecodeResult.InvalidValue(errors) =>
        ErrorDetail(
          fieldOf(ctx.failingInput),
          if errors.isEmpty then List("is not a valid value")
          else errors.map(DefaultDecodeFailureHandler.ValidationMessages.validationErrorMessage)
        )

      case DecodeResult.Mismatch(expected, actual) =>
        ErrorDetail(fieldOf(ctx.failingInput), List(s"expected '$expected', got '$actual'"))

      case DecodeResult.Multiple(values) =>
        ErrorDetail(fieldOf(ctx.failingInput), List(s"expected one value, got ${values.size}"))
    }

  /** The name a caller would recognise for the input that failed.
    *
    * A body has no name of its own, so it is called `body`: `"field": "body"` is what tells a caller the
    * problem is in what they sent rather than in the URL.
    */
  def fieldOf(input: EndpointInput[?]): Option[String] =
    input match {
      case EndpointInput.PathCapture(name, _, _) => name.orElse(Some("path"))
      case EndpointInput.PathsCapture(_, _) => Some("path")
      case query: EndpointInput.Query[?] => Some(query.name)
      case cookie: EndpointInput.Cookie[?] => Some(cookie.name)
      case headerInput: EndpointIO.Header[?] => Some(headerInput.name)
      case _: EndpointIO.Body[?, ?] => Some("body")
      case _: EndpointIO.StreamBodyWrapper[?, ?] => Some("body")
      case _ => None
    }

  private def routeOf(endpoint: sttp.tapir.AnyEndpoint): String =
    s"${endpoint.method.map(_.method).getOrElse("*")} ${endpoint.showPathTemplate()}"
}
