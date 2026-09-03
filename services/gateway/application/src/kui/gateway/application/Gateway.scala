package kui.gateway.application

import kui.kernel.ServiceId

/** Who the gateway is, in the three forms the rest of the system refers to it by.
  *
  * They live in the application layer rather than beside the routes because they are not a fact about HTTP.
  * The capability registry keys on the id, the log fields carry the id, the telemetry resource carries the
  * service name, and none of that stops being true if the gateway were served over something other than
  * Netty. `ARCHITECTURE.md` §16 fixes which form goes where; this object is that table in code, so a rename
  * is one edit rather than a search for eleven string literals.
  *
  * ==What else belongs in this layer==
  *
  * Decisions the gateway makes on its own behalf, stated without reference to HTTP: how the health reports of
  * several services fold into one capability state (ADR-039, GW-003), what a session is and when it has
  * expired (ADR-019, GW-009), which upstreams a screen aggregation needs. Each is a plain function, or a port
  * plus an implementation, testable without a server.
  *
  * ==What never belongs in it==
  *
  * A business rule about Kafka. The gateway owns no domain (ADR-004 §3): there is no
  * `services/gateway/domain` module and there will not be one. "This topic name is invalid", "this consumer
  * group is empty", "this ACL grants too much" are all statements about the world the *services* model, and a
  * copy of one of them here is a copy that will disagree with the original. `./mill checkArchitecture` cannot
  * catch that — a rule about topics needs no forbidden dependency in order to be written — so it is a review
  * responsibility, and this comment is where the responsibility is written down.
  *
  * ==Why this layer may name contract types when a service's may not==
  *
  * ADR-041 rule A3 keeps a domain-owning service's `application` layer off the wire, so that its use cases
  * own the types they return and cannot be reshaped by a JSON concern. Amendment 1 exempts the gateway,
  * because the gateway's subject matter *is* other services' published contracts: a `CapabilityState` is not
  * this layer's internal type that leaked outwards, it is the thing the layer exists to compute. Rules A4 (no
  * reaching into another service's internals) and A8 (no Kafka client) still bind, and they are the two that
  * actually protect the service split.
  */
object Gateway {

  /** The identifier of record: a `ServiceId`, a capability key, a metric label, an OpenAPI tag. */
  val Id: ServiceId = ServiceId.unsafe("gateway")

  /** The `service.name` resource attribute on every span and metric this process emits (ADR-009), and the
    * name a trace search uses to tell the gateway's spans from a service's.
    */
  val ServiceName: String = "kui-gateway"
}
