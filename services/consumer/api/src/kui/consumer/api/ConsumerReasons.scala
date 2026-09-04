package kui.consumer.api

import kui.contracts.capability.ReasonCode
import kui.kernel.error.*

/** Why a document is stale, from the error that made it so.
  *
  * Classified by failure *case* rather than by error code, because "could not connect" and "the breaker is
  * open" share a code and mean different things on a screen: one is waited out, the other is investigated.
  *
  * It is the topic service's `TopicSections.reasonOf` table, in this service's own module. Rule A11 forbids
  * one service seeing another's `api`, so the alternative to repeating eight lines was hoisting a
  * classification of `KuiError` into `libs/contracts-core` — which would put a wire vocabulary's opinion
  * about an error hierarchy into the module that owns neither.
  */
object ConsumerReasons {

  def of(error: KuiError): ReasonCode = error match {
    // Nothing is broken when a caller is not permitted: per ADR-039 §6 this must not take the service
    // out of its healthy state, which an infrastructure classification here would cause.
    case ApplicationError.Forbidden(_) => ReasonCode.Forbidden
    case ApplicationError.Unsupported(_) => ReasonCode.NotConfigured
    case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
    case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
    case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
    case InfrastructureError.Unreachable(_, _) | InfrastructureError.Upstream(_, _) =>
      ReasonCode.UpstreamUnavailable
    case _ => ReasonCode.Unknown
  }
}
