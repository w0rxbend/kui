package kui.cluster.domain

import java.time.Instant

import kui.kernel.ValidationError
import kui.kernel.error.DomainError

/** A message that was echoed back, and the moment the service saw it.
  *
  * `Ping` is deliberately the smallest value object that still has a rule of its own. In M0 the cluster
  * context has no real model yet — `ClusterProfile`, `Broker` and the rest arrive in M1 — so this exists to
  * prove the shape of the layering: a value that cannot be built out of a raw `String`, whose rule lives in
  * `domain`, whose failure is a `DomainError` value rather than an exception, and which the layers above map
  * to the wire without the domain ever hearing about JSON.
  *
  * The constructor is private so that `Ping.from` is the only way in. In Scala 3 that also makes the
  * generated `apply` and `copy` private, which closes the two doors a `private` constructor alone leaves
  * open: `Ping("", now)` and `valid.copy(message = "")` would otherwise both build a value that breaks the
  * invariant.
  */
final case class Ping private (message: String, at: Instant)

object Ping {

  /** The longest message the endpoint accepts. 128 characters is arbitrary in the sense that no Kafka rule
    * dictates it, and deliberate in the sense that the value is echoed into a log line and a span name: an
    * unbounded string there is how a caller fills a log volume.
    */
  val MaxMessageLength: Int = 128

  private val Field: String = "message"

  private val Expected: String = s"1 to $MaxMessageLength characters"

  /** Builds a `Ping`, or says why it refused.
    *
    * `Either` rather than an exception, because the caller is a use case that has to turn the refusal into an
    * HTTP 400 with a field name in it, and a stack trace carries none of that (ADR-034).
    */
  def from(message: String, at: Instant): Either[DomainError, Ping] =
    if message.nonEmpty && message.length <= MaxMessageLength then Right(Ping(message, at))
    else Left(DomainError.fromValidation(ValidationError.Format(Field, Expected, message)))

  given CanEqual[Ping, Ping] = CanEqual.derived
}
