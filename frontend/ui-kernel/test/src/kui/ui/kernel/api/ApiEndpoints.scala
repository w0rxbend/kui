package kui.ui.kernel.api

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given

/** Two endpoints that exist only so the suites have something to run.
  *
  * Deliberately not one of the gateway's real endpoints: those live in a module the kernel cannot see, and
  * borrowing one would make the `ApiClient` suites fail whenever someone changed an unrelated contract. What
  * is under test is the client's own behaviour — headers, base URL, decoding, `401` — and any endpoint pair
  * exercises all of it.
  */
object ApiEndpoints {

  val ping: PublicEndpoint[Unit, ErrorEnvelope, String, Any] =
    endpoint.get.in("ping").out(stringBody).errorOut(jsonBody[ErrorEnvelope])

  val poke: PublicEndpoint[String, ErrorEnvelope, String, Any] =
    endpoint.post.in("poke").in(stringBody).out(stringBody).errorOut(jsonBody[ErrorEnvelope])
}
