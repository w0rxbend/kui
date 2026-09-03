package kui.contracts

import sttp.tapir.*

/** The prefix every route a browser calls is served under.
  *
  * ## Why it is here and not in a service's contract
  *
  * A service serves its endpoints under `/internal/v1` and knows nothing about the public prefix: `/api/v1`
  * belongs to the gateway, which derives its public routes by rewriting the first path segment of each
  * service's published contract (`ARCHITECTURE.md` §5). So no service may declare it.
  *
  * The *browser* is the other party to the same convention, and it does have to know: a typed client is built
  * from an endpoint value, and that value has to describe the address the browser actually calls. Putting the
  * prefix in `contracts-core` — which both the gateway and the browser already compile against — is what lets
  * the frontend name it without any service's contract naming it, and without the frontend inventing a second
  * copy that can drift from the gateway's.
  */
object PublicApi {

  /** The major version of the public API. A path segment rather than a header: a version a caller can see in
    * a URL is a version a caller can pin, bookmark and curl.
    */
  val Version: String = "v1"

  /** The prefix as text, for the places that print it rather than compose with it. */
  val Prefix: String = s"/api/$Version"

  /** The prefix as Tapir path inputs, which is what an endpoint definition composes with.
    *
    * The deployment's own `server.basePath` is deliberately not part of it. That prefix is applied once, over
    * the whole endpoint list, when the server starts, so nothing in a contract has to know where the
    * deployment mounted KUI.
    */
  val prefix: EndpointInput[Unit] = "api" / Version
}
