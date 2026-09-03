# KERN-006 — `libs/security-core`: principal and `PrincipalCodec`

- **ID:** KERN-006
- **Title:** `libs/security-core`: principal and `PrincipalCodec`
- **Milestone / Feature:** M0 / KU-005, KU-006 (security skeleton)
- **Owner role:** Security Engineer
- **Context / service:** `libs/security-core`
- **Size:** M
- **Dependencies / blocked by:** KERN-002

## Goal (user value)

A service that someone reaches directly, bypassing the gateway, refuses the request — from
the first milestone, before there is anything worth stealing, so no later feature ships on an
unauthenticated internal API.

## Scope

1. The cross-compiled core: `Principal`, `PrincipalKind`, `PrincipalClaims`, `RequestDigest`,
   `SignedPrincipal`, `PrincipalError`, and the `PrincipalCodec[F]` trait.
2. Two implementations:
   - `PrincipalCodec.inProcess[F]` — no signature, used by all-in-one (ADR-005).
   - `JwsPrincipalCodec[F]` (JVM source set only) — compact JWS, HS256, `kid` selection,
     nimbus as the only library dependency.
3. `RequestDigest.of(method, path, body)` including the streaming variant that hashes the
   request line only (ADR-020: "streaming endpoints bind `req` to the request line only").

## Non-goals

**No RBAC.** `Resource`, `Action`, `Permission`, `Role`, `RbacPolicy` and `Rbac.decide` are
M6 and must not be written here. No session store (GW-009 owns the M0 skeleton). No masking
rules (ADR-023, M3/M6). No OIDC, LDAP or bcrypt.

## Design references

ADR-020 (algorithm, claims, verification steps, rotation, in-process variant),
ADR-005 (all-in-one principal passing), `ARCHITECTURE.md` §4.6 and §5 (header table), §14.

## Files to create

```
libs/security-core/src/kui/security/Principal.scala
libs/security-core/src/kui/security/PrincipalClaims.scala
libs/security-core/src/kui/security/PrincipalCodec.scala
libs/security-core/src-jvm/kui/security/JwsPrincipalCodec.scala
libs/security-core/test/src/kui/security/PrincipalCodecSuite.scala
libs/security-core/test-jvm/src/kui/security/JwsPrincipalCodecSuite.scala
build.mill                                   (declare the cross module)
```

## Public Scala signatures to implement

Copied from `ARCHITECTURE.md` §4.6, reduced to the M0 subset:

```scala
package kui.security

enum PrincipalKind { case Anonymous, Session, Bearer, System }

final case class Principal(name: UserName, roles: Set[RoleName], kind: PrincipalKind)
object Principal:
  val Anonymous: Principal      // name "anonymous", no roles, kind Anonymous

final case class RequestDigest(method: String, path: String, bodySha256: String)
object RequestDigest:
  def of(method: String, path: String, body: Array[Byte]): RequestDigest
  def ofRequestLine(method: String, path: String): RequestDigest    // streaming endpoints

final case class SessionRef(value: String)          // a hash of the session id, never the id

final case class PrincipalClaims(
    subject: UserName,
    roles: Set[RoleName],
    kind: PrincipalKind,
    sessionRef: Option[SessionRef],
    issuedAt: Instant,
    expiresAt: Instant,
    audience: ServiceId,
    requestDigest: RequestDigest
)

opaque type SignedPrincipal = String
object SignedPrincipal:
  def from(raw: String): Either[PrincipalError, SignedPrincipal]
  extension (s: SignedPrincipal) def value: String

enum PrincipalError:
  case Missing
  case Malformed(reason: String)
  case BadSignature
  case UnknownKeyId(kid: String)
  case Expired(at: Instant)
  case WrongAudience(expected: ServiceId, got: ServiceId)
  case RequestMismatch

trait PrincipalCodec[F[_]]:
  def sign(claims: PrincipalClaims): F[SignedPrincipal]
  def verify(token: SignedPrincipal, expected: ServiceId, request: RequestDigest, now: Instant)
      : F[Either[PrincipalError, Principal]]

object PrincipalCodec:
  /** All-in-one: claims are carried as a value, the token is their JSON, no signature.
    * Logs a warning once at construction so nobody ships it by accident. */
  def inProcess[F[_]: Applicative]: PrincipalCodec[F]
```

```scala
// JVM only
final case class SigningKey(kid: String, key: Secret[Array[Byte]], notBefore: Instant)

object JwsPrincipalCodec:
  /** `keys` must be non-empty; signing uses the newest key whose notBefore <= now,
    * verification accepts any listed kid. Clock skew tolerance is 5 seconds (ADR-020). */
  def make[F[_]: Sync](keys: NonEmptyList[SigningKey], issuer: String): F[PrincipalCodec[F]]
```

Verification order, which the tests assert one by one: parse → `kid` lookup → signature →
`aud` → `exp` (with 5 s skew) → `req` digest. Each failure yields its own `PrincipalError`;
none of them leaks which key or which claim value was expected into the HTTP response, which
is always `401 KUI-UNAUTHENTICATED`.

## Library coordinates

```
com.nimbusds:nimbus-jose-jwt:10.9.1        (JVM source set only — must not appear in src/)
io.circe::circe-core::0.14.16              (shared, for the claims JSON)
org.typelevel::cats-core::2.13.0           (shared)
```

`libs.securityCore.js` must link without nimbus. `./mill checkArchitecture` rule A6 enforces it.

## Acceptance criteria

```
$ ./mill libs.securityCore.jvm.test
$ ./mill libs.securityCore.js.test          # the shared suites link and pass on Scala.js
$ ./mill checkArchitecture
```

## Tests required

- `PrincipalCodecSuite` (unit + property, cross-compiled): `inProcess` round-trips claims;
  `RequestDigest.of` is stable for the same input and differs for a one-byte body change.
- `JwsPrincipalCodecSuite` (unit + property, JVM):
  - `signThenVerifyReturnsThePrincipal`.
  - `rejectsTamperedPayload`, `rejectsTamperedSignature` — property: flipping any byte of the
    token fails verification.
  - `rejectsWrongAudience`, `rejectsExpiredToken`, `acceptsWithinFiveSecondsOfSkew`,
    `rejectsUnknownKeyId`, `rejectsMismatchedRequestDigest`.
  - `rotationAcceptsBothKeysAndSignsWithTheNewest`.
  - `secretKeyIsNeverPrintedByAnyErrorOrToString` — the redaction guard for this module.

## Observability

`kui.principal.rejected {reason}` counter (`ARCHITECTURE.md` §13) is emitted by the *caller*
of `verify` (HTTP-001's interceptor), not by the codec — the codec stays effect-light. This
task documents the label values: one per `PrincipalError` case, lowercase.

## Degraded behavior

A service that cannot construct a codec (no keys configured, distributed mode) must fail at
startup, not fall back to accepting unsigned requests. Assert this in SVC-004.

## Docs to update

`ARCHITECTURE.md` §4.6: replace the sketch with a link to the implementing file and note that
`Rbac` is still M6.
