package kui.cluster.api

import io.scalaland.chimney.dsl.*

import kui.cluster.application.ClusterService
import kui.cluster.contract.dto.PingResponse
import kui.cluster.domain.Ping

/** Turning the domain's `Ping` into the DTO the browser decodes.
  *
  * The two types have two fields in common and one that only the wire has. `service` is not a property of a
  * ping — the domain has no opinion about which process answered — but it is something a client reading a
  * response through a gateway very much wants to know, so it is supplied here from the one constant that
  * spells this service's name.
  *
  * That single extra field is why this file exists at all rather than the endpoint returning the domain type.
  * A domain type that is also a DTO gains a field the moment the wire needs one, and from then on the domain
  * is shaped by JSON (ADR-041 A2, PLAN §18).
  */
object PingMapping {

  def toWire(ping: Ping): PingResponse =
    ping
      .into[PingResponse]
      .withFieldConst(_.service, ClusterService.Id.value)
      .transform
}
