package kui.gateway.api.routing

import sttp.tapir.AnyEndpoint

import kui.cluster.contract.ClusterEndpoints
import kui.kernel.ServiceId

/** Which published contract belongs to which service id.
  *
  * The one place in the gateway that names another service. Every path, input, output and codec still comes
  * from that service's own `contract` module (ADR-041 rule A4 makes it the only edge allowed); what is
  * written here is only the association between the id an operator configures and the endpoint list to derive
  * routes from.
  *
  * It grows by one line per service across M1 to M8. A service the gateway has no contract for is not an
  * error: it is configured, polled, and reported in the capability snapshot, it simply has no proxied routes
  * yet. That is what lets a service be deployed before the gateway build that routes it.
  */
object ServiceContracts {

  val byService: Map[ServiceId, List[AnyEndpoint]] =
    Map(ServiceId.unsafe("cluster") -> ClusterEndpoints.all)

  def of(service: ServiceId): List[AnyEndpoint] = byService.getOrElse(service, Nil)
}
