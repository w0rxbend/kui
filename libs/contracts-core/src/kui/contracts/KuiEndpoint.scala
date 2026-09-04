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

  /** What a mutating endpoint says about itself.
    *
    * @param operation
    *   the stable name the audit record and the application layer's `MutationKind` use for the same
    *   operation. One string, so that "what did KUI do" and "which endpoint did it" are answerable with an
    *   equality rather than with a guess
    * @param destructive
    *   whether applying it can lose information. A reset that only moves offsets forward past records the
    *   group had already read is a mutation but is not destructive; a delete is both. The UI asks for a typed
    *   confirmation for the second kind and not for the first
    */
  final case class MutationMarker(operation: String, destructive: Boolean)

  object MutationMarker {
    given CanEqual[MutationMarker, MutationMarker] = CanEqual.derived
  }

  /** The attribute every mutating endpoint carries.
    *
    * An attribute rather than a naming convention because M5's read-only exit criterion is an *enumeration*:
    * walk every endpoint the product serves and assert each one is classified. A test can read
    * `endpoint.attribute(KuiEndpoint.MutationKey)`; it cannot read a convention. A documented rule that
    * nothing can read is the "documented rule nothing enforces" the M0 review found four times, and this is
    * the one place in M4 where making it machine-readable costs a single line (ADR-047).
    *
    * The consequence is worth stating plainly: an endpoint that changes something and forgets this marker is
    * invisible to the read-only policy M5 builds on top of it. That is why the helper below attaches the
    * marker and the CSRF header together — the two things a mutation must not be declared without.
    */
  val MutationKey: AttributeKey[MutationMarker] = AttributeKey[MutationMarker]

  /** True when this endpoint declares itself as changing something. */
  def isMutation(endpoint: AnyEndpoint): Boolean = endpoint.attribute(MutationKey).isDefined

  /** The base every mutating `/internal/v1` endpoint starts from: the internal base, the marker, and the CSRF
    * header.
    *
    * The CSRF header is required from the day the endpoint exists, even though authentication is disabled in
    * M4 and there is no session to forge. A header added later has to be added to every client that already
    * shipped, and the clients that were not updated start failing with a `403` that looks like a permissions
    * problem. Requiring it now costs one line in the browser's client and nothing at all in a `curl`, because
    * M4's CSRF check accepts any value while there is no session to bind it to.
    */
  def mutation(
      operation: String,
      destructive: Boolean
  ): Endpoint[SignedPrincipal, String, ErrorEnvelope, Unit, Any] =
    internal
      .in(
        header[String](HttpHeaders.Csrf)
          .description("The session's CSRF token (ADR-019). Required on every mutation")
      )
      .attribute(MutationKey, MutationMarker(operation, destructive))

  /** The sentence a mutating endpoint's own description starts with, so that an operator reading the
    * generated API reference can see which calls change something without inferring it from the HTTP verb.
    *
    * It is a function the endpoint calls rather than a `.description` set by [[mutation]], because Tapir's
    * `description` *replaces* — an endpoint that then wrote its own description would silently drop the
    * marker's sentence, and the OpenAPI document would look exactly as it does now with the warning gone. The
    * machine-readable half is [[MutationKey]] and is not affected either way; this is the half a human reads.
    */
  def mutationNote(operation: String, destructive: Boolean): String =
    if destructive then s"Mutation ($operation). This call changes the cluster and cannot be undone. "
    else s"Part of the $operation flow. This call changes nothing. "
}
