package kui.connect.application

import kui.kernel.ServiceId

/** This service's identity, declared once.
  *
  * It has to equal the id the gateway is configured with — `kui.gateway.services.connect` — or every call
  * from the gateway is refused with a 401 naming no cause the caller can see. It is here rather than in the
  * `api` module because the audit line an operation writes carries it too, and the application layer may not
  * see `api`.
  */
object ConnectService {

  val Id: ServiceId = ServiceId.unsafe("connect")

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.connect"
}
