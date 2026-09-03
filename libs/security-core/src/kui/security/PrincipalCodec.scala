package kui.security

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.Applicative
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*

import kui.kernel.ServiceId

/** Minting and checking the identity that travels between the gateway and a service.
  *
  * A service must not believe a plain `X-User` header: anyone who can reach the service's port could write
  * one. So the gateway signs a small claim set, and the service verifies it — every request, regardless of
  * network position (ADR-020). Defence in depth is the point: a network policy that is misconfigured becomes
  * a slow day, not a privilege escalation.
  *
  * `F[_]` rather than a plain return value because the JVM implementation touches a key, and a key is a
  * resource: the effect type is what lets a composition root decide when and where that happens.
  */
trait PrincipalCodec[F[_]] {

  /** Mints a token for exactly the call described by `claims.requestDigest`. */
  def sign(claims: PrincipalClaims): F[SignedPrincipal]

  /** Checks a token against the service that received it, the call it arrived on, and the clock.
    *
    * Returns the principal on success and a reason on failure. The reason is for the server's log and
    * metrics; the HTTP response is always the same 401 (`KUI-UNAUTHENTICATED`).
    */
  def verify(
      token: SignedPrincipal,
      expected: ServiceId,
      request: RequestDigest,
      now: Instant
  ): F[Either[PrincipalError, Principal]]
}

object PrincipalCodec {

  /** How far a service's clock may be behind the gateway's before a fresh token looks expired (ADR-020).
    * Sixty-second tokens leave no room for anything larger.
    */
  val ClockSkew: FiniteDuration = 5.seconds

  /** The all-in-one codec: the claims travel as their own JSON, with no signature at all.
    *
    * This is safe in exactly one deployment shape — the single process of ADR-005, where the "gateway" and
    * the "service" are two objects in the same JVM and the token never leaves it. In any other shape it is a
    * forgeable header, which is why constructing it writes a warning to standard error: a deployment that
    * ends up here by accident says so out loud on every start.
    *
    * It still checks audience, expiry and request binding. That is not paranoia about a process lying to
    * itself; it is so that the all-in-one build exercises the same rules as the distributed one, and a
    * mistake in those rules is found by the cheap tests rather than the expensive ones.
    */
  def inProcess[F[_]: Applicative]: PrincipalCodec[F] = {
    Console.err.println(
      "WARNING: KUI is using the in-process principal codec. Tokens are not signed, so this is " +
        "only safe in the single-process all-in-one deployment. Configure kui.gateway.principalKeys " +
        "before running the gateway and the services as separate processes."
    )
    new PrincipalCodec[F] {

      def sign(claims: PrincipalClaims): F[SignedPrincipal] =
        SignedPrincipal.unsafe(claims.asJson.noSpaces).pure[F]

      def verify(
          token: SignedPrincipal,
          expected: ServiceId,
          request: RequestDigest,
          now: Instant
      ): F[Either[PrincipalError, Principal]] =
        decode[PrincipalClaims](token.value)
          .leftMap(failure => PrincipalError.Malformed(failure.getMessage))
          .flatMap(claims => checkClaims(claims, expected, request, now))
          .pure[F]
    }
  }

  /** The checks that follow a successful parse, shared by every implementation so that the signed and the
    * unsigned codec cannot disagree about what a valid token is.
    *
    * The order matters and is asserted by the tests: audience, then expiry, then the request binding. Each
    * answers a different question — "is this token even for me", "is it still good", "is it for this call" —
    * and reporting the first failure rather than the most interesting one keeps the answer independent of the
    * caller.
    */
  def checkClaims(
      claims: PrincipalClaims,
      expected: ServiceId,
      request: RequestDigest,
      now: Instant
  ): Either[PrincipalError, Principal] =
    if claims.audience.value != expected.value then
      Left(PrincipalError.WrongAudience(expected, claims.audience))
    else if now.isAfter(claims.expiresAt.plusSeconds(ClockSkew.toSeconds)) then
      Left(PrincipalError.Expired(claims.expiresAt))
    else if claims.requestDigest != request then Left(PrincipalError.RequestMismatch)
    else Right(claims.principal)
}
