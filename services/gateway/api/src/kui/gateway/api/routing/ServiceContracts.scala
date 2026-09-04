package kui.gateway.api.routing

import sttp.tapir.AnyEndpoint

import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints}
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.kernel.ServiceId
import kui.message.contract.{FilterEndpoints, MessageMutationEndpoints}
import kui.schema.contract.{SchemaEndpoints, SchemaMutationEndpoints}
import kui.topic.contract.{TopicAdminEndpoints, TopicEndpoints}

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
      // Both lists. `ClusterWriteEndpoints` used to be deliberately absent, so that the one write M1
      // shipped had no public route while it had no screen. It has a screen now — the cluster
      // administration page — and an endpoint the browser cannot reach would make that screen a set of
      // buttons that answer 404. What keeps an unauthorised caller out is `ApplicationConfig.Edit`, which
      // is a rule the product can state, rather than the absence of a route, which is only a rule nobody
      // has got round to breaking.
      ServiceId.unsafe("cluster") -> (ClusterEndpoints.all ++ ClusterWriteEndpoints.all),
      // Both lists, because the topic service publishes its reads and its administration from two
      // objects: `TopicEndpoints` states that nothing in it changes a cluster, and `TopicAdminEndpoints`
      // carries create, configure, grow and delete with the marker, the plan phases and the CSRF header
      // ADR-045 and ADR-047 require. Leaving the second list out would be a set of endpoints no browser
      // could reach, which is a failure this project has already shipped once as a sidebar of dead links.
      ServiceId.unsafe("topic") -> (TopicEndpoints.all ++ TopicAdminEndpoints.all),
      // Both lists, because the consumer service publishes its reads and its mutations from two
      // objects: the reads are ordinary contract endpoints and the four mutation endpoints carry the
      // marker and the CSRF header ADR-047 requires. The gateway proxies them all the same way — it
      // rewrites the prefix and forwards the inputs, and the marker is read by policy, not by routing.
      ServiceId.unsafe("consumer") -> (ConsumerEndpoints.all ++ ConsumerMutationEndpoints.all),
      // Only the mutations. The message service's other endpoint is the browse *stream*, which is not a
      // request/response call and cannot be proxied by this derivation at all — `MessageStreamRoutes`
      // carries it, over `StreamProxy`, because a stream needs its own cancellation and heartbeat
      // handling rather than a request forwarded and a response awaited.
      // The mutations and the two filter endpoints. `FilterEndpoints` changes nothing on a cluster — one
      // compiles an expression, the other runs it against a record the caller sent — but both are still
      // ordinary request/response calls the browser has to be able to reach, and a filter editor with no
      // route to register against is a filter engine nothing can use, which is what MS-007 was.
      ServiceId.unsafe("message") -> (MessageMutationEndpoints.all ++ FilterEndpoints.all),
      // Both lists, for the same reason as the topic and consumer services: the schema service
      // publishes its five reads from one object and its three bodied endpoints from another. The
      // second list holds the two compatibility writes *and* the compatibility check, which is not a
      // mutation at all — it is grouped by request shape, not by effect — so leaving that list out
      // would silently drop the one endpoint a registration flow needs most.
      ServiceId.unsafe("schema") -> (SchemaEndpoints.all ++ SchemaMutationEndpoints.all)
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
