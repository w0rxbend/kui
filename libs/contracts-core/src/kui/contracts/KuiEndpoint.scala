package kui.contracts

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope.given
import kui.security.SignedPrincipal

/** The Tapir endpoint every KUI contract module starts from.
  *
  * Two things are fixed here so that no endpoint anywhere can get them wrong. The first is the error body:
  * `errorOut` is declared once, so a service cannot invent its own failure shape and a client that learned to
  * handle one endpoint's errors handles all of them (ADR-034). The second is authentication: an endpoint
  * under `/internal/v1` is one service talking to another, and it carries the gateway's signed principal
  * header or it is not served at all (ADR-020).
  *
  * It lives in `libs/contracts-core` rather than in `libs/http` because a contract module is cross-compiled
  * to the browser (`ARCHITECTURE.md` §3) and `libs/http` is a Netty server that only exists on the JVM. A
  * base endpoint in `libs/http` could never be the base of a browser-compiled contract.
  */
object KuiEndpoint {

  /** The header the gateway signs a principal into, and the services verify (ADR-020). */
  val PrincipalHeader: String = "X-Kui-Principal"

  /** Reads the header as an opaque token. It is not verified here — verification needs a signing key, which
    * is a runtime concern of each service's `api` module — but an empty header is rejected by the parser,
    * because "present but blank" is not a case any later code should have to think about.
    */
  private given Codec[String, SignedPrincipal, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw =>
      SignedPrincipal.from(raw) match {
        case Right(token) => DecodeResult.Value(token)
        case Left(_) => DecodeResult.Missing
      }
    )(_.value)

  /** The base every public endpoint starts from: nothing in, the error envelope out. */
  val base: PublicEndpoint[Unit, ErrorEnvelope, Unit, Any] =
    endpoint.errorOut(jsonBody[ErrorEnvelope])

  /** The base every `/internal/v1` endpoint starts from: the same error envelope, plus the signed principal
    * as a security input.
    *
    * `securityIn` rather than a plain `in` is what makes the difference visible to everything downstream: the
    * server interpreter runs the security logic — verify the token, or fail with 401 — before it decodes the
    * request body, and the generated OpenAPI document describes the endpoint as requiring authentication.
    */
  val internal: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, Unit, Any] =
    base.securityIn(header[SignedPrincipal](PrincipalHeader))
}
