package kui.cluster.application

import kui.kernel.ServiceId

/** Facts about this service that its own use cases need to state about themselves.
  *
  * There is exactly one of them today: the name the service is known by, which appears as the `service.name`
  * field on every log line (`ARCHITECTURE.md` §13) and as the `aud` claim the gateway signs a principal for
  * (ADR-020). It lives in `application` rather than in `domain` because it is not a business rule — it is who
  * this deployment is — and it lives in a named object rather than in a string literal at each use site
  * because the two places that spell it must never disagree.
  */
object ClusterService {

  /** The service's identifier. `unsafe` is correct here: the value is a literal in this file, not something a
    * caller typed, so there is no untrusted input to validate.
    */
  val Id: ServiceId = ServiceId.unsafe("cluster")
}
