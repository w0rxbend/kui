package kui.gateway.api.routing

import sttp.tapir.AnyEndpoint

import kui.cluster.contract.ClusterEndpoints
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.kernel.ServiceId
import kui.topic.contract.TopicEndpoints

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
    Map(
      ServiceId.unsafe("cluster") -> ClusterEndpoints.all,
      ServiceId.unsafe("topic") -> TopicEndpoints.all,
      // Both lists, because the consumer service publishes its reads and its mutations from two
      // objects: the reads are ordinary contract endpoints and the four mutation endpoints carry the
      // marker and the CSRF header ADR-047 requires. The gateway proxies them all the same way — it
      // rewrites the prefix and forwards the inputs, and the marker is read by policy, not by routing.
      ServiceId.unsafe("consumer") -> (ConsumerEndpoints.all ++ ConsumerMutationEndpoints.all)
    )

  def of(service: ServiceId): List[AnyEndpoint] = byService.getOrElse(service, Nil)

  /** Endpoints the gateway serves **itself**, as an aggregation, and must therefore not derive a proxy route
    * for.
    *
    * Keyed by the endpoint `name` the contract fixes rather than by its path, because the name is stable and
    * the path is a string the derivation is already deriving. There is exactly one entry in M1: the cluster
    * list, which the gateway answers with per-row status and its own last-known fallback. Two routes for one
    * path is a collision nobody sees in a route list, so the exclusion is data rather than an accident of
    * ordering.
    */
  val aggregated: Set[String] = Set("cluster.list")

  /** The endpoints of one service that the gateway proxies verbatim. */
  def proxied(service: ServiceId): List[AnyEndpoint] =
    of(service).filterNot(_.info.name.exists(aggregated.contains))
}
