package kui.cluster.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.dto.PingResponse
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.security.SignedPrincipal

/** Everything `kui-cluster-service` serves, described once.
  *
  * This is the single source ADR-003 asks for: the same values produce the server's routes, the gateway's
  * client, the browser's client and the OpenAPI document. Nothing is served that is not in `all`, and no path
  * is written out by hand anywhere else — a hand-written path is a path that drifts from the handler it was
  * supposed to name.
  *
  * The paths start at `/internal/v1` and not at `/api/v1`. `/api/v1` is the public prefix and it belongs to
  * the gateway (`ARCHITECTURE.md` §5); a service is only ever called by the gateway, over the internal
  * prefix, with a signed principal header.
  */
object ClusterEndpoints {

  /** The last path segment of [[ping]], and the name of its query parameter.
    *
    * Named constants rather than literals typed twice, because the gateway serves this endpoint at a
    * *rewritten* path (`/api/v1/ping`) and the browser's client is built from a separate endpoint value
    * describing that address. Two literals in two files is exactly the drift ADR-003 exists to prevent, and
    * it is the kind that compiles cleanly and 404s in production.
    */
  val PingPath: String = "ping"

  val PingMessageParam: String = "message"

  /** Echoes a message back with the time the service saw it.
    *
    * It exists to prove the whole chain end to end — contract to server route, contract to gateway client,
    * contract to browser client, contract to OpenAPI — with something that has no upstream to fail. M1 adds
    * the endpoints that do the real work; this one stays, because a chain nobody checks is a chain that
    * breaks quietly.
    */
  val ping: Endpoint[SignedPrincipal, String, ErrorEnvelope, PingResponse, Any] =
    KuiEndpoint.internal.get
      .in("internal" / "v1" / PingPath)
      .in(query[String](PingMessageParam).description("Echoed back, 1..128 characters"))
      .out(jsonBody[PingResponse])
      .name("cluster.ping")
      .summary("Echo endpoint used to prove the contract -> client -> gateway -> browser chain")
      .tag("cluster")

  /** Every endpoint this service serves. The gateway and the OpenAPI generator read this.
    *
    * The health and capability endpoints are deliberately absent: they are identical in all eleven services
    * and come from `libs/http` (HTTP-002) rather than being redeclared once per service, which is how eleven
    * copies of the same path end up disagreeing.
    */
  val all: List[AnyEndpoint] = List(ping)
}
