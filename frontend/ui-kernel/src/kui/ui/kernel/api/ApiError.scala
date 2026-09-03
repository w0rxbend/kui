package kui.ui.kernel.api

import kui.contracts.{ErrorDetail, ErrorEnvelope}
import kui.kernel.error.ErrorCode

/** Everything that can go wrong with one API call, as data.
  *
  * Every `ApiClient` method answers with `Either[ApiError, O]` and never with a failed `EventStream`. That is
  * not a stylistic choice: an Airstream error travels up to the unhandled-error handler and takes the
  * subscription with it, so a page that was rendering a list stops rendering anything at all (ADR-011 §3.6).
  * A failure has to be an ordinary value that a `Signal` can hold, so that the page can draw the failure.
  *
  * There are four cases because a caller genuinely treats them differently. `Envelope` is the server having
  * an opinion — it says what went wrong and whether trying again would help. `Unreachable` and `Timeout` are
  * the server having said nothing, which the shell escalates to the full-screen state (UI-011) when it is its
  * own calls that failed. `Decoding` is the two sides disagreeing about the contract, which is a bug rather
  * than an outage and must be loud rather than silently retried.
  */
enum ApiError {

  /** The gateway answered with the error shape every KUI endpoint uses (ADR-034).
    *
    * `code` is a `String` and not `ErrorCode` on purpose: a browser built against an older KUI must render a
    * response from a newer one that has invented a code, rather than fail to parse it.
    */
  case Envelope(
      code: String,
      message: String,
      details: List[ErrorDetail],
      correlationId: String,
      retryable: Boolean
  )

  /** Nothing answered: the network is down, DNS failed, the browser is offline, the gateway is not running.
    */
  case Unreachable(cause: String)

  /** Something answered too late, or not at all within the deadline. */
  case Timeout

  /** Something answered, and it was not what the contract describes.
    *
    * The usual sources are a reverse proxy substituting an HTML error page for a JSON one, and a gateway and
    * a browser built from different revisions of the contracts.
    */
  case Decoding(cause: String)

  /** Whether this failure means "we do not know who you are" — the signal to re-establish the session.
    *
    * Answered from the code rather than from an HTTP status, because a status is gone by the time a caller
    * holds an `ApiError`, and because the code is the thing ADR-034 makes stable.
    */
  def isAuth: Boolean = this match {
    case Envelope(code, _, _, _, _) => code == ErrorCode.Unauthenticated.wire
    case Unreachable(_) | Timeout | Decoding(_) => false
  }

  /** Whether the caller is known and simply not allowed. Rendered as the 403 page, never as a retry prompt.
    */
  def isForbidden: Boolean = this match {
    case Envelope(code, _, _, _, _) => code == ErrorCode.Forbidden.wire
    case Unreachable(_) | Timeout | Decoding(_) => false
  }

  /** Whether this failure is one the shell counts towards "the gateway is not there" (UI-011).
    *
    * A `Decoding` failure is deliberately excluded. Something answered, so the gateway is reachable; showing
    * "cannot reach gateway" would send an operator to look at the network when the problem is in the code.
    */
  def isTransport: Boolean = this match {
    case Unreachable(_) | Timeout => true
    case Envelope(_, _, _, _, _) | Decoding(_) => false
  }

  /** Whether asking again, unchanged, could work.
    *
    * The server's own answer is used where there is one, because only the server knows; a transport failure
    * is retryable by definition, and a contract mismatch never is.
    */
  def isRetryable: Boolean = this match {
    case Envelope(_, _, _, _, retryable) => retryable
    case Unreachable(_) | Timeout => true
    case Decoding(_) => false
  }

  /** What to put on screen.
    *
    * The server's `message` is used verbatim when there is one — it is written for the user and may name the
    * topic or the cluster that this particular request was about, which no client-side string can. The other
    * three cases have no server text, so the kernel supplies it.
    */
  def userMessage: String = this match {
    case Envelope(_, message, _, _, _) => message
    case Unreachable(_) => ApiError.UnreachableMessage
    case Timeout => ApiError.TimeoutMessage
    case Decoding(_) => ApiError.DecodingMessage
  }

  /** The identifier a user quotes in a support request, when the failure has one. */
  def correlation: Option[String] = this match {
    case Envelope(_, _, _, correlationId, _) => Some(correlationId)
    case Unreachable(_) | Timeout | Decoding(_) => None
  }
}

object ApiError {

  val UnreachableMessage = "KUI cannot reach the server."

  val TimeoutMessage = "The server took too long to answer."

  val DecodingMessage = "The server sent something KUI could not read."

  /** Lifts a decoded envelope. The timestamp is dropped: it is the moment the *server* failed, and every
    * place the browser shows a time it wants the moment the *user* saw the failure, which the browser knows
    * and the envelope does not.
    */
  def of(envelope: ErrorEnvelope): ApiError =
    Envelope(
      code = envelope.code,
      message = envelope.message,
      details = envelope.details,
      correlationId = envelope.correlationId,
      retryable = envelope.retryable
    )

  given CanEqual[ApiError, ApiError] = CanEqual.derived
}
