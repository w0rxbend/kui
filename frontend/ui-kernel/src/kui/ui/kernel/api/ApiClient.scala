package kui.ui.kernel.api

import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.scalajs.concurrent.JSExecutionContext
import scala.scalajs.js
import scala.util.control.NonFatal

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.client4.fetch.{FetchBackend, FetchOptions}
import sttp.client4.{Backend, Request, Response}
import sttp.model.{Method, Uri}
import sttp.tapir.client.sttp4.SttpClientInterpreter
import sttp.tapir.{DecodeResult, Endpoint, PublicEndpoint}

import kui.contracts.{ErrorEnvelope, HttpHeaders}
import kui.ui.kernel.state.Auth

/** The one way the browser talks to the gateway.
  *
  * A feature never builds a request, never names a header and never constructs a backend. It hands an
  * endpoint — the same `Endpoint` value the gateway implements, compiled from the same source — and its
  * input, and receives the endpoint's output type or an [[ApiError]]. A field renamed in the contract is
  * therefore a compile error in both halves at once, which is the entire reason the contracts are
  * cross-compiled.
  *
  * Three things happen on every call that no feature should have to remember:
  *
  *   - the session cookie travels, because the backend is built with `credentials: include`;
  *   - a mutation carries the CSRF header from the current session (ADR-019);
  *   - the call carries an `X-Kui-Request-Id` the browser made up, so that a user who reports "it failed at
  *     about ten past three" can be found in the gateway's logs. The gateway still mints the authoritative
  *     correlation id (GW-001); this is a second, client-side thread to pull on.
  *
  * There is no retry policy here, deliberately. A browser that retries silently turns a five-minute outage
  * into a five-minute spinner and hides the one thing the user needed to know. Retrying is an explicit action
  * a user takes (ADR-032's "Retry now").
  */
trait ApiClient {

  /** Runs an endpoint that needs no security input.
    *
    * The result is an `EventStream` rather than a `Future` so that callers stay inside Airstream, where a
    * subscription's lifetime is tied to the element that made it: a request whose page has been navigated
    * away from stops updating anything, without the caller writing cancellation code.
    *
    * The stream emits exactly one value and then completes, and it never fails — see [[ApiError]].
    */
  def call[I, O](
      endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
      input: I
  ): EventStream[Either[ApiError, O]]

  /** Runs an endpoint with a security input, such as one that carries a bearer token in a header.
    *
    * KUI's own endpoints authenticate with a cookie the browser attaches by itself, so `security` is `()` for
    * all of them today; the parameter exists so that M6's token-based flows have somewhere to put a token
    * without changing this interface.
    */
  def callSecure[A, I, O](
      endpoint: Endpoint[A, I, ErrorEnvelope, O, Any],
      security: A,
      input: I
  ): EventStream[Either[ApiError, O]]
}

/** An `ApiClient` on top of an sttp backend.
  *
  * Separate from the trait, and given the backend rather than making one, because that is what lets the
  * suites run the real header, decoding and interception logic against `BackendStub` with no browser and no
  * server (`research/scala/frontend-research.md` §7).
  *
  * @param baseUri
  *   the absolute URL the endpoints' paths are appended to.
  * @param auth
  *   supplies the CSRF token and is told when a response says the session has expired.
  */
final class SttpApiClient(baseUri: Uri, auth: Auth, backend: Backend[Future])(using
    executionContext: ExecutionContext
) extends ApiClient {

  private val interpreter: SttpClientInterpreter = SttpClientInterpreter()

  def call[I, O](
      endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
      input: I
  ): EventStream[Either[ApiError, O]] =
    run(interpreter.toRequest(endpoint, Some(baseUri))(input))

  def callSecure[A, I, O](
      endpoint: Endpoint[A, I, ErrorEnvelope, O, Any],
      security: A,
      input: I
  ): EventStream[Either[ApiError, O]] =
    run(interpreter.toSecureRequest(endpoint, Some(baseUri))(security)(input))

  /** Sends one request, once, when somebody is listening.
    *
    * Two properties are worth spelling out, because both are easy to lose and neither is visible from a call
    * site. The request is *lazy*: building the stream sends nothing, so a component that is destroyed before
    * it subscribes — a page the user navigated away from during start-up — makes no call at all. And it is
    * *memoised*: the `lazy val` means a second subscriber joins the first request rather than issuing a
    * second one, which is what makes it safe for two parts of a page to share one `EventStream`.
    */
  private def run[O](
      request: => Request[DecodeResult[Either[ErrorEnvelope, O]]]
  ): EventStream[Either[ApiError, O]] = {
    lazy val sent: Future[Either[ApiError, O]] =
      decorate(request)
        .send(backend)
        .map(interpret)
        .recover { case NonFatal(failure) => Left(ApiClient.transportFailure(failure)) }

    EventStream.fromValue(()).flatMapSwitch(_ => EventStream.fromFuture(sent))
  }

  /** Adds the two headers every KUI request carries.
    *
    * The CSRF token is added to everything except `GET`, which is the boundary ADR-019 draws: a `GET` cannot
    * change anything, so requiring a token on one would only break the case the mechanism exists to allow — a
    * user pasting a deep link. A missing token is not an error here; the gateway rejects the request and says
    * so, which is a far more debuggable failure than a request the browser refused to send.
    */
  private def decorate[T](request: Request[T]): Request[T] = {
    val identified = request.header(ApiClient.RequestIdHeader, ApiClient.nextRequestId())
    if request.method == Method.GET then identified
    else
      auth.csrfToken
        .now()
        .fold(identified)(token => identified.header(ApiClient.CsrfHeader, token))
  }

  /** Turns one response into the caller's answer, and reports a `401` on the way past.
    *
    * The status is inspected before the body, because a `401` has to be noticed whether or not the endpoint
    * declared that status: an endpoint that does not mention `401` yields a decoding failure, and a session
    * that silently stopped working is exactly the failure a user cannot diagnose.
    */
  private def interpret[O](
      response: Response[DecodeResult[Either[ErrorEnvelope, O]]]
  ): Either[ApiError, O] = {
    if response.code.code == ApiClient.UnauthorizedStatus then auth.markExpired()

    response.body match {
      case DecodeResult.Value(Right(value)) => Right(value)
      case DecodeResult.Value(Left(envelope)) => Left(ApiError.of(envelope))
      case failure: DecodeResult.Failure =>
        Left(ApiError.Decoding(s"HTTP ${response.code.code}: $failure"))
    }
  }
}

object ApiClient {

  /** The client-generated id, echoed in the gateway's access log (ADR-040). */
  val RequestIdHeader = "X-Kui-Request-Id"

  /** Where the CSRF token goes on a mutation (ADR-019).
    *
    * Taken from `kui.contracts.HttpHeaders` rather than written out here, because the gateway reads the name
    * from the same constant. When the two were spelled out independently they drifted — the browser sent
    * `X-Kui-Csrf`, which the gateway's edge policy strips before anything can read it, so every mutation was
    * rejected as a forgery. Sharing the constant makes that failure impossible to reintroduce.
    */
  val CsrfHeader: String = HttpHeaders.Csrf

  private[api] val UnauthorizedStatus = 401

  /** Builds the application's client: a real `fetch` against the deployment's own origin.
    *
    * `credentials: "include"` is what makes the session cookie travel. Without it `fetch` omits cookies on
    * anything it considers cross-origin, and a reverse proxy that rewrites the origin is enough to make a
    * same-origin deployment look cross-origin to the browser — a failure that appears only in production.
    */
  def make(bootstrap: Bootstrap, auth: Auth): ApiClient = {
    given ExecutionContext = JSExecutionContext.queue
    val base = Bootstrap.absoluteApiBase(bootstrap, dom.window.location.origin)
    new SttpApiClient(parseBase(base), auth, FetchBackend(BrowserFetchOptions))
  }

  /** The `fetch` options the application's backend is built with.
    *
    * A named value rather than an inline argument so that a suite can assert on it. `credentials` is not a
    * header and never appears on a request object, so a stubbed backend cannot see it; the only way to keep
    * an accidental deletion from reaching production is to pin the value itself.
    */
  private[api] val BrowserFetchOptions: FetchOptions =
    FetchOptions(credentials = Some(dom.RequestCredentials.include), mode = None)

  /** Parses a base URL, falling back to the current origin.
    *
    * `Uri.parse` can only fail here if the gateway injected something that is not a URL at all, and a
    * frontend that refuses to start over it would be harder to diagnose than one that tries the origin.
    */
  private def parseBase(base: String): Uri =
    Uri.parse(base).getOrElse(Uri.unsafeParse(dom.window.location.origin))

  /** Classifies an exception thrown by the transport rather than described by the server.
    *
    * Everything that is not explicitly a timeout is `Unreachable`, including exceptions the browser reports
    * with almost no detail — `fetch` deliberately says "Failed to fetch" and nothing more for a request it
    * refused, so that a page cannot use it to probe the network it is running on. The original text is kept
    * in `cause` for the console, and never shown to the user.
    */
  private[api] def transportFailure(failure: Throwable): ApiError =
    failure match {
      case _: TimeoutException => ApiError.Timeout
      case other => ApiError.Unreachable(Option(other.getMessage).getOrElse(other.toString))
    }

  /** A value unique within this page's lifetime.
    *
    * The random half distinguishes two tabs; the counter distinguishes two requests in one tab. Neither has
    * to be unguessable — it is a log-correlation aid, not a credential — and using `crypto.randomUUID` would
    * add a browser API for no gain.
    */
  private[api] def nextRequestId(): String = {
    counter += 1
    f"$pageId%s-$counter%04d"
  }

  private var counter: Int = 0

  private lazy val pageId: String =
    js.Math.random().toString.replace("0.", "").take(8)
}
