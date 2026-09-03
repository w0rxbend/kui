package kui.security

import kui.kernel.{RoleName, UserName}

/** How KUI came to believe who the caller is. */
enum PrincipalKind {

  /** Nobody signed in. Either authentication is switched off, or the request has not been through it yet.
    */
  case Anonymous

  /** A browser session cookie, established by an interactive sign-in. */
  case Session

  /** A bearer token presented by a script or another system. */
  case Bearer

  /** KUI itself: a scheduler, a readiness prober, an internal maintenance job. */
  case System

  /** The lowercase name used in the signed principal's claims and in metric labels. Written out so that
    * renaming a case cannot silently change a wire value.
    */
  def wire: String = this match {
    case Anonymous => "anonymous"
    case Session => "session"
    case Bearer => "bearer"
    case System => "system"
  }
}

object PrincipalKind {

  def fromWire(raw: String): Option[PrincipalKind] = values.find(_.wire == raw)

  given CanEqual[PrincipalKind, PrincipalKind] = CanEqual.derived
}

/** Who is making a request, reduced to what an authorization decision needs: a name for the audit log, the
  * roles they hold, and how they were identified.
  *
  * It holds no session id, no token and no password. A service receives this after verifying the signed
  * header, and everything it is allowed to decide follows from these three fields.
  */
final case class Principal(name: UserName, roles: Set[RoleName], kind: PrincipalKind)

object Principal {

  /** The caller nobody identified. It holds no roles, so an RBAC decision about it can only be a denial
    * unless a deployment has deliberately configured a default role.
    */
  val Anonymous: Principal =
    Principal(UserName.unsafe("anonymous"), Set.empty, PrincipalKind.Anonymous)

  given CanEqual[Principal, Principal] = CanEqual.derived
}

/** A one-way reference to a session, for correlating audit entries.
  *
  * It is a hash of the session id and never the id itself: an audit log is read by more people than a session
  * store is, and a session id in a log line is a credential in a log line.
  */
final case class SessionRef(value: String)

object SessionRef {
  given CanEqual[SessionRef, SessionRef] = CanEqual.derived
}

/** The identity of one HTTP call: its method, its path and the hash of its body.
  *
  * This is what binds a signed principal to a single request (ADR-020). Without it, a token intercepted from
  * one call could be replayed on another — the same principal, the same 60-second window, a different and
  * more destructive operation. With it, a token that does not match the request it arrived on is refused.
  *
  * Building one from a body needs a SHA-256 implementation, which is a JVM concern: see `RequestDigests.of`
  * in this module's JVM source set. The browser never signs anything, so nothing in the shared half needs to
  * hash.
  */
final case class RequestDigest(method: String, path: String, bodySha256: String)

object RequestDigest {

  /** The SHA-256 of zero bytes. It is a constant of the algorithm, so it can be written down rather than
    * computed, which is what keeps hashing out of the cross-compiled source set.
    */
  val EmptyBodySha256: String =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  /** The digest for an endpoint whose body is not read as a whole: a streaming response, or a GET.
    *
    * ADR-020 binds streaming endpoints to the request line only, because the gateway must start forwarding a
    * stream before it has seen the end of it, and a digest it cannot compute is a digest it cannot sign.
    */
  def ofRequestLine(method: String, path: String): RequestDigest =
    RequestDigest(method.toUpperCase, path, EmptyBodySha256)

  given CanEqual[RequestDigest, RequestDigest] = CanEqual.derived
}
