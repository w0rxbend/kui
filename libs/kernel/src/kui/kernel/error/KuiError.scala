package kui.kernel.error

import java.time.Instant

import kui.kernel.ValidationError

/** One field of a request and everything that is wrong with it.
  *
  * `field` is optional because a validation failure is sometimes about the request as a whole rather than
  * about one named field. `restrictions` is a list rather than one string because a single field can break
  * more than one rule at a time, and telling a user about one rule per round trip is how a form becomes
  * hostile.
  */
final case class FieldError(field: Option[String], restrictions: List[String])

object FieldError {

  def of(field: String, restriction: String): FieldError =
    FieldError(Some(field), List(restriction))

  /** The validation failures of `libs/kernel`'s smart constructors, in the shape the wire wants. */
  def fromValidation(error: ValidationError): FieldError =
    FieldError(Some(error.fieldName), List(error.message))

  given CanEqual[FieldError, FieldError] = CanEqual.derived
}

/** Every failure KUI returns, as a value.
  *
  * Business code returns `F[Either[KuiError, A]]` and never throws: an exception that crosses a layer
  * boundary carries a stack trace, a class name and often an upstream response body, none of which belong in
  * an HTTP response, and all of which end up there by accident the first time nobody catches it. Adapters
  * translate the exceptions their libraries throw into these cases at the boundary (ADR-034); genuinely fatal
  * errors still propagate.
  *
  * The three branches say where a failure came from, which is what decides who can fix it:
  *
  *   - [[DomainError]] — a business rule was broken. The request was understood and refused.
  *   - [[ApplicationError]] — the use case cannot proceed: the thing is missing, the caller is not allowed,
  *     the state is wrong.
  *   - [[InfrastructureError]] — something KUI depends on failed. The user did nothing wrong.
  *
  * **`message` is display text.** It must never contain a stack trace, an upstream response body, a JAAS
  * configuration string, a URL with credentials, or the contents of a `Secret`. That is why
  * `InfrastructureError.Upstream` carries a status code and no body: a field that does not exist cannot be
  * logged by mistake.
  */
sealed trait KuiError {

  /** The stable code an operator searches for and the browser switches on. */
  def code: ErrorCode

  /** One sentence, safe to show a user. */
  def message: String

  /** Per-field detail, populated for validation failures and empty for everything else. */
  def details: List[FieldError] = Nil
}

/** A business rule was broken. Services add their own cases in their `domain` modules; the kernel only
  * carries the one that every service needs, because every service validates input.
  */
sealed trait DomainError extends KuiError

object DomainError {

  /** A rule that the domain, not the transport, defines: an offset range that runs backwards, a replication
    * factor above the broker count, a reset spec that names a partition the group does not own.
    */
  final case class InvariantViolation(rule: String, fields: List[FieldError] = Nil) extends DomainError {
    val code: ErrorCode = ErrorCode.Validation
    val message: String = rule
    override val details: List[FieldError] = fields
  }

  /** Lifts a smart constructor's rejection into the error hierarchy, so that a value object's rule and a use
    * case's rule reach the wire through the same path.
    */
  def fromValidation(error: ValidationError): DomainError =
    InvariantViolation(error.message, List(FieldError.fromValidation(error)))
}

/** The use case cannot proceed. Nothing is broken; the request cannot be carried out as asked. */
sealed trait ApplicationError extends KuiError

object ApplicationError {

  /** Something that was addressed by id is not there. The code is a parameter because "not found" has one
    * code per kind of thing (`KUI-TOPIC-NOT-FOUND`, `KUI-CLUSTER-NOT-FOUND`, …) and the caller knows which
    * kind it was looking for.
    */
  final case class NotFound(what: String, id: String, code: ErrorCode) extends ApplicationError {
    val message: String = s"$what '$id' does not exist"
  }

  /** The request collides with the current state — a concurrent change, a duplicate name. */
  final case class Conflict(message: String) extends ApplicationError {
    val code: ErrorCode = ErrorCode.InvalidState
  }

  final case class Forbidden(message: String) extends ApplicationError {
    val code: ErrorCode = ErrorCode.Forbidden
  }

  final case class Unauthenticated(message: String) extends ApplicationError {
    val code: ErrorCode = ErrorCode.Unauthenticated
  }

  /** This deployment, cluster or upstream cannot do the thing at all — an older broker, a cluster with no
    * schema registry configured. Distinct from `Forbidden`, which is about permission.
    */
  final case class Unsupported(feature: String) extends ApplicationError {
    val code: ErrorCode = ErrorCode.Unsupported
    val message: String = s"$feature is not supported here"
  }

  final case class InvalidState(message: String) extends ApplicationError {
    val code: ErrorCode = ErrorCode.InvalidState
  }

  /** The request itself is malformed. `fields` becomes the `details` array of the envelope. */
  final case class Invalid(message: String, fields: List[FieldError]) extends ApplicationError {
    val code: ErrorCode = ErrorCode.Validation
    override val details: List[FieldError] = fields
  }
}

/** Something KUI depends on failed: a broker, a schema registry, a Connect cluster, ksqlDB. */
sealed trait InfrastructureError extends KuiError

object InfrastructureError {

  /** The upstream could not be reached at all. `cause` is kept for the log; `message` names only the
    * upstream, because a connection failure's text routinely contains hosts, ports and credentials.
    */
  final case class Unreachable(upstream: String, cause: String) extends InfrastructureError {
    val code: ErrorCode = ErrorCode.UpstreamUnavailable
    val message: String = s"$upstream could not be reached"
  }

  final case class Timeout(operation: String, afterMs: Long) extends InfrastructureError {
    val code: ErrorCode = ErrorCode.Timeout
    val message: String = s"$operation did not finish within ${afterMs}ms"
  }

  /** KUI's own credentials for the upstream were rejected. Not the user's problem, and never retryable: the
    * configuration has to change first.
    */
  final case class AuthFailed(upstream: String) extends InfrastructureError {
    val code: ErrorCode = ErrorCode.UpstreamAuth
    val message: String = s"KUI is not authenticated against $upstream"
  }

  /** The upstream answered, with a status KUI cannot use. It carries the status and deliberately not the
    * body: an upstream body is the most reliable way to leak a secret into a user-visible response (ADR-034).
    */
  final case class Upstream(upstream: String, status: Int) extends InfrastructureError {
    val code: ErrorCode = ErrorCode.UpstreamUnavailable
    val message: String = s"$upstream answered with status $status"
  }

  /** The circuit breaker for this upstream is open, so KUI is not even trying (ADR-037). */
  final case class CircuitOpen(upstream: String, since: Instant) extends InfrastructureError {
    val code: ErrorCode = ErrorCode.UpstreamUnavailable
    val message: String = s"calls to $upstream are suspended while it recovers"
  }
}
