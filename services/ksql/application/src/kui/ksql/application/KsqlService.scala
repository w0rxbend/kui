package kui.ksql.application

import kui.kernel.ServiceId

/** This service's identity, declared once.
  *
  * It has to equal the id the gateway is configured with — `kui.gateway.services.ksql` — or every call from
  * the gateway is refused with a 401 naming no cause the caller can see. It is here rather than in the `api`
  * module because the audit line a statement writes carries it too, and the application layer may not see
  * `api`.
  */
object KsqlService {

  val Id: ServiceId = ServiceId.unsafe("ksql")

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.ksql"
}
