package kui.topic.api

import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.topic.domain.TopicError

/** `TopicError` on the wire.
  *
  * | domain                       | code                       | status |
  * |:-----------------------------|:---------------------------|:-------|
  * | `NotFound(topic)`            | `KUI-TOPIC-NOT-FOUND`      | 404    |
  * | `ClusterNotFound(id)`        | `KUI-CLUSTER-NOT-FOUND`    | 404    |
  * | `Forbidden(detail)`          | `KUI-FORBIDDEN`            | 403    |
  * | `Unreachable(detail, true)`  | `KUI-TIMEOUT`              | 408    |
  * | `Unreachable(detail, false)` | `KUI-UPSTREAM-UNAVAILABLE` | 503    |
  * | `AlreadyExists(topic)`       | `KUI-INVALID-STATE`        | 409    |
  * | `Rejected(detail)`           | `KUI-VALIDATION`           | 400    |
  *
  * Both 404s exist because the remedies differ: "check the topic name" and "check which cluster you are on".
  * A single code would make the browser's message a guess.
  *
  * The two `Unreachable` rows are the milestone's other distinction. `retryable` is the domain's word for
  * "trying again shortly is worth it" — a timeout, a leader election — as against a configuration that will
  * fail identically for ever. A screen offers a retry button for the first and an explanation for the second,
  * and it can only tell them apart if the code does.
  *
  * ==Why this returns a `KuiError` and not an `ErrorEnvelope`==
  *
  * `ErrorEnvelope.statusOf` is the single error-code-to-status table in KUI (ADR-034), and it is consulted in
  * exactly one place per service — `TopicApi.failure`, where the correlation id and the clock are already in
  * hand. Building an envelope here would put a second copy of that decision in a file whose subject is the
  * domain, and a second copy is a copy that can disagree.
  */
object TopicErrors {

  /** The upstream a failure is reported against, for the message an operator reads. */
  val Upstream: String = "kafka"

  def toKui(error: TopicError): KuiError = error match {
    case TopicError.NotFound(topic) =>
      ApplicationError.NotFound("topic", topic.value, ErrorCode.TopicNotFound)

    case TopicError.ClusterNotFound(cluster) =>
      ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)

    case TopicError.Forbidden(detail) =>
      // An authorization failure is an `ApplicationError` and never an infrastructure one. Nothing is
      // broken, and per ADR-039 §6 it must not dim a capability or take this service out of its healthy
      // state — which is exactly what an `InfrastructureError` here would cause the gateway to do.
      ApplicationError.Forbidden(error.message)

    case TopicError.Unreachable(detail, retryable) =>
      if retryable then InfrastructureError.Timeout(s"$Upstream: $detail", RetryableAfterMs)
      else InfrastructureError.Unreachable(Upstream, detail)

    case TopicError.AlreadyExists(_) =>
      // `Conflict`, which is `KUI-INVALID-STATE` and a 409. A 400 would say the request was malformed,
      // which it was not: the same request would have succeeded a minute earlier.
      ApplicationError.Conflict(error.message)

    case TopicError.Rejected(_) =>
      // A refusal by the cluster is an `ApplicationError` and never an infrastructure one, for the reason
      // `Forbidden` above gives: nothing is broken, and per ADR-039 §6 it must not dim a capability. The
      // code is `KUI-VALIDATION` because the remedy is always to change what was asked for.
      ApplicationError.Invalid(error.message, Nil)
  }

  /** The duration reported on a retryable failure.
    *
    * `InfrastructureError.Timeout` carries how long the operation was given, and the domain's `Unreachable`
    * does not know: it was told "this is worth retrying", not "this took eleven seconds". Zero is the honest
    * answer to a question this path cannot answer, and the message the caller reads carries the real detail.
    * The alternative — inventing a plausible number — would put a fabricated duration into an operator's
    * incident timeline.
    */
  val RetryableAfterMs: Long = 0L
}
