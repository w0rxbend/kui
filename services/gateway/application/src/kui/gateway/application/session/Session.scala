package kui.gateway.application.session

import java.time.Instant

import kui.kernel.Secret
import kui.security.Principal

/** A session's identifier.
  *
  * `opaque type` rather than a `case class` field: a session id is 32 random bytes, base64url-encoded
  * (ADR-019), and the one rule that matters about it is that it must never appear in a log line. Making it
  * opaque means every place that would otherwise interpolate a bare `String` into a log message has to go
  * through `SessionId.value` to do it, which is a single grep away from finding the mistake in review — the
  * same reasoning `Secret` uses, applied to a value that is not exactly a secret but is exactly as dangerous
  * to leak (whoever holds it can act as the session).
  */
opaque type SessionId = String

object SessionId {

  /** Wraps an id this module minted itself. Never call it on a value read from a cookie without also checking
    * it against the store — an unchecked cookie value becomes a session id here, and this constructor does no
    * validation of its own.
    */
  def unsafe(raw: String): SessionId = raw

  extension (id: SessionId) def value: String = id

  given CanEqual[SessionId, SessionId] = CanEqual.derived
}

/** A one-way reference to a session id, safe to log.
  *
  * `ARCHITECTURE.md` §14 and this task's own observability note both require it: a rejected request is logged
  * with `session.ref`, never the id, so that a log file is not itself a way to hijack the session it
  * describes. It reuses `kui.security.SessionRef`, the same type `PrincipalClaims` carries for audit
  * correlation, so a gateway log line and a service's audit trail can be joined on one value.
  */
object SessionRef {

  /** SHA-256 of the id, hex-encoded. One-way: nothing can recover the id from the hash, but the same id
    * always hashes to the same reference, which is what makes two log lines about the same session
    * recognisably about the same session.
    */
  def of(id: SessionId): kui.security.SessionRef = {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.value.getBytes("UTF-8"))
    kui.security.SessionRef(digest.map(byte => f"${byte & 0xff}%02x").mkString)
  }
}

/** What the gateway keeps about one browser session (ADR-019).
  *
  * @param id
  *   the opaque cookie value. Rotated on privilege change (session fixation defence) and never reused.
  * @param principal
  *   who this session belongs to. `Principal.Anonymous` for every session in M0 (`auth.type = disabled`), but
  *   the field exists now so M6 changes no shape, only what fills it.
  * @param csrfSecret
  *   the value `GET /api/v1/auth/me` hands the browser, and that every mutating cookie-authenticated request
  *   must echo back in `X-Kui-Csrf`. A `Secret` so it cannot be logged by accident the way the id could be.
  * @param createdAt
  *   when this session (or the one it was rotated from) started
  * @param lastSeenAt
  *   the last request that used it, which is what the idle timeout is measured from
  * @param absoluteExpiry
  *   the hard cutoff, `createdAt` plus twelve hours, that no amount of activity extends
  */
final case class Session(
    id: SessionId,
    principal: Principal,
    csrfSecret: Secret[String],
    createdAt: Instant,
    lastSeenAt: Instant,
    absoluteExpiry: Instant
) {

  /** Whether `now` is past either timeout. Pure, so the two timeout rules are one function a suite can call
    * directly rather than something only observable through a store's behaviour.
    */
  def isExpired(now: Instant, idleTimeout: java.time.Duration): Boolean =
    now.isAfter(absoluteExpiry) || now.isAfter(lastSeenAt.plus(idleTimeout))
}

object Session {
  given CanEqual[Session, Session] = CanEqual.derived
}
