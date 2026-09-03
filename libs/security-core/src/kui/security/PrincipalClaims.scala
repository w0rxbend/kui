package kui.security

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}

import kui.kernel.{RoleName, ServiceId, UserName}

/** What the gateway asserts about one request, and what a service checks before trusting it.
  *
  * The set is ADR-020's: who the caller is (`subject`, `roles`, `kind`), which session it belongs to for
  * audit correlation (`sessionRef`), how long the assertion is good for (`issuedAt`, `expiresAt` — sixty
  * seconds apart), which service it was minted for (`audience`), and which exact call it covers
  * (`requestDigest`).
  *
  * The last two are what make a stolen header useless: a token for `topic` cannot be replayed against
  * `cluster`, and a token for `GET /topics` cannot be replayed against `DELETE /topics/orders`.
  */
final case class PrincipalClaims(
    subject: UserName,
    roles: Set[RoleName],
    kind: PrincipalKind,
    sessionRef: Option[SessionRef],
    issuedAt: Instant,
    expiresAt: Instant,
    audience: ServiceId,
    requestDigest: RequestDigest
) {

  /** The principal these claims assert, once they have been verified. */
  def principal: Principal = Principal(subject, roles, kind)
}

object PrincipalClaims {

  /** The claim names are the short ones of ADR-020 (`sub`, `aud`, `iat`, `exp`, …) rather than the field
    * names, because they travel in an HTTP header on every internal call and because a JWS with standard
    * claim names can be read by standard tooling.
    *
    * The codec is written out rather than derived (ADR-007): a claim set is a security contract, and a change
    * to it should be visible as a change to this file in a diff.
    */
  given Codec[PrincipalClaims] = Codec.from(
    (cursor: HCursor) =>
      for {
        subject <- cursor.get[String]("sub")
        roles <- cursor.get[Set[String]]("roles")
        kindRaw <- cursor.get[String]("kind")
        kind <- kindFromWire(kindRaw, cursor)
        sessionRef <- cursor.get[Option[String]]("sid")
        issuedAt <- cursor.get[Long]("iat")
        expiresAt <- cursor.get[Long]("exp")
        audience <- cursor.get[String]("aud")
        method <- cursor.downField("req").get[String]("m")
        path <- cursor.downField("req").get[String]("p")
        bodyHash <- cursor.downField("req").get[String]("b")
      } yield PrincipalClaims(
        subject = UserName.unsafe(subject),
        roles = roles.map(RoleName.unsafe),
        kind = kind,
        sessionRef = sessionRef.map(SessionRef.apply),
        issuedAt = Instant.ofEpochSecond(issuedAt),
        expiresAt = Instant.ofEpochSecond(expiresAt),
        audience = ServiceId.unsafe(audience),
        requestDigest = RequestDigest(method, path, bodyHash)
      ),
    (claims: PrincipalClaims) =>
      Json.obj(
        "sub" -> claims.subject.value.asJson,
        "roles" -> claims.roles.map(_.value).toList.sorted.asJson,
        "kind" -> claims.kind.wire.asJson,
        "sid" -> claims.sessionRef.map(_.value).asJson,
        "iat" -> claims.issuedAt.getEpochSecond.asJson,
        "exp" -> claims.expiresAt.getEpochSecond.asJson,
        "aud" -> claims.audience.value.asJson,
        "req" -> Json.obj(
          "m" -> claims.requestDigest.method.asJson,
          "p" -> claims.requestDigest.path.asJson,
          "b" -> claims.requestDigest.bodySha256.asJson
        )
      )
  )

  private def kindFromWire(raw: String, cursor: HCursor): Decoder.Result[PrincipalKind] =
    PrincipalKind
      .fromWire(raw)
      .toRight(io.circe.DecodingFailure(s"'$raw' is not a principal kind", cursor.history))

  given CanEqual[PrincipalClaims, PrincipalClaims] = CanEqual.derived

  /** Kept next to the codec so a reader can see both halves at once. */
  val encoder: Encoder[PrincipalClaims] = summon[Codec[PrincipalClaims]]
}

/** A signed principal header, as it travels: an opaque string.
  *
  * Opaque because nothing between the gateway that mints it and the service that verifies it has any business
  * reading it — and because the signing scheme is allowed to change (ADR-020 keeps EdDSA as a
  * configuration-only upgrade) without every caller changing with it.
  */
opaque type SignedPrincipal = String

object SignedPrincipal {

  def from(raw: String): Either[PrincipalError, SignedPrincipal] =
    if raw.trim.isEmpty then Left(PrincipalError.Missing) else Right(raw)

  /** Wraps a token this process has just produced. Never call it on an incoming header. */
  def unsafe(raw: String): SignedPrincipal = raw

  extension (token: SignedPrincipal) def value: String = token

  given CanEqual[SignedPrincipal, SignedPrincipal] = CanEqual.derived
}

/** Why a signed principal was not accepted.
  *
  * Every one of these becomes the same HTTP response — `401` with `KUI-UNAUTHENTICATED` — because telling a
  * caller *which* check failed tells an attacker which part of a forged token to fix. The distinction is kept
  * for the server's own logs and for the `kui.principal.rejected{reason}` counter, whose label values are
  * these case names in lowercase.
  */
enum PrincipalError {

  /** No header at all. */
  case Missing

  /** The header is not a token this codec can parse. */
  case Malformed(reason: String)

  /** The token parses, but the signature does not match its contents. */
  case BadSignature

  /** The token names a signing key this service has not been configured with — the usual cause is a key
    * rotation applied to the gateway before the services.
    */
  case UnknownKeyId(kid: String)

  /** The token is past its expiry, allowing for the configured clock skew. */
  case Expired(at: Instant)

  /** The token was minted for a different service. */
  case WrongAudience(expected: ServiceId, got: ServiceId)

  /** The token is valid but was minted for a different call than the one it arrived on. */
  case RequestMismatch

  /** The label value for the rejection counter (`ARCHITECTURE.md` §13).
    *
    * Not called `reason`: the `Malformed` case already has a constructor parameter of that name.
    */
  def metricLabel: String = this match {
    case Missing => "missing"
    case Malformed(_) => "malformed"
    case BadSignature => "bad_signature"
    case UnknownKeyId(_) => "unknown_key_id"
    case Expired(_) => "expired"
    case WrongAudience(_, _) => "wrong_audience"
    case RequestMismatch => "request_mismatch"
  }
}

object PrincipalError {
  given CanEqual[PrincipalError, PrincipalError] = CanEqual.derived
}
