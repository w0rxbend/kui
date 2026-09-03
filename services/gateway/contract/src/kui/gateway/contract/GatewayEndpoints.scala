package kui.gateway.contract

import sttp.tapir.*

import kui.contracts.{ErrorEnvelope, KuiEndpoint}

/** Everything the gateway itself serves under `/api/v1`, described once.
  *
  * Two kinds of route reach a browser, and only one of them is declared here:
  *
  *   - the gateway's **own** endpoints — build info, the session and CSRF endpoints, the capability registry
  *     — which are what this module holds;
  *   - the **proxied** endpoints, which are derived from each service's published contract (GW-006) and are
  *     therefore not declared anywhere in the gateway at all. Writing a path list for them by hand is exactly
  *     the drift `ARCHITECTURE.md` §5 forbids.
  *
  * The module is cross-compiled for the same reason every other contract module is (ADR-003): the browser
  * decodes exactly what the gateway encodes, from the same source, so a field that is renamed here stops
  * compiling in the frontend rather than turning into a runtime `undefined`.
  */
object GatewayEndpoints {

  /** The major version of the public API. It is a path segment, not a header: a version a caller can see in a
    * URL is a version a caller can pin, bookmark and curl.
    */
  val ApiVersion: String = "v1"

  /** The prefix every public route is served under, as text, for the places that need to print it — the
    * bootstrap block the shell reads (GW-008) and the OpenAPI `servers` entry (GW-007).
    *
    * The deployment's own `server.basePath` is *not* part of this. That prefix is applied once, over the
    * whole endpoint list, by `kui.http.BasePath` when the server starts, so nothing in a contract has to know
    * where the deployment mounted KUI.
    */
  val ApiPrefix: String = s"/api/$ApiVersion"

  /** The prefix as Tapir path inputs, which is what an endpoint definition composes with. */
  val apiPrefix: EndpointInput[Unit] = "api" / ApiVersion

  /** The base every gateway endpoint starts from: the `/api/v1` prefix and the shared error envelope.
    *
    * It builds on `KuiEndpoint.base` rather than restating it, so the gateway cannot invent a second failure
    * shape (ADR-034). It deliberately carries no principal header: `X-Kui-Principal` is what the gateway
    * *emits* on its way to a service, never something it accepts from a browser (ADR-040).
    */
  val base: PublicEndpoint[Unit, ErrorEnvelope, Unit, Any] = KuiEndpoint.base.in(apiPrefix)

  /** Every endpoint the gateway serves in its own right, for the OpenAPI document (GW-007).
    *
    * Empty here and filled in by the endpoint objects beside it, so that adding an endpoint to the product
    * and adding it to the published documentation are the same edit.
    */
  /** A `def`, not a `val`, and that matters. Each of these objects builds its endpoints from
    * `GatewayEndpoints.base`, so a `val` here would make this object's initialiser call theirs while `base`
    * was still null -- a class-initialisation cycle that fails at runtime with a `NullPointerException`
    * nowhere near the cause. Evaluating the list on demand breaks the cycle.
    */
  def all: List[AnyEndpoint] = InfoEndpoints.all ++ AuthEndpoints.all ++ CapabilityEndpoints.all
}
