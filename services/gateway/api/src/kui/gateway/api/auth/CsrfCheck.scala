package kui.gateway.api.auth

import kui.contracts.HttpHeaders
import kui.security.PrincipalKind

/** Whether one request is allowed to mutate state, as a pure function of the facts ADR-019 names (the
  * decision itself, GW-009's whole security contract).
  *
  * Kept as a function from plain values to a verdict — no `ServerRequest`, no session store, no effect —
  * because a security rule that can only be exercised by starting a server is a rule three quarters of the
  * combinations of which never get tested. Every branch below is one row of `CsrfCheckSuite`'s table, and
  * that table **is** the specification: a change to this function without a matching change to the table is a
  * change nobody can see happened.
  */
object CsrfCheck {

  enum Verdict {
    case Allowed
    case Denied(reason: String)
  }

  /** `GET` and `HEAD` never mutate, so CSRF does not apply to them — a mutation-only check on a safe method
    * would let a `<img src="...">` on a hostile page trigger it, which is exactly what CSRF protection exists
    * to stop, not something to add back for reads.
    */
  private val SafeMethods: Set[String] = Set("GET", "HEAD", "OPTIONS")

  /** The decision.
    *
    * @param method
    *   the HTTP method, upper-cased
    * @param authKind
    *   how the caller was authenticated. Bearer callers are exempt outright (ADR-019): a script presenting a
    *   token chose to send it, which is not something a hostile page can forge into happening, unlike a
    *   cookie the browser attaches on its own.
    * @param headerToken
    *   the value of `X-Kui-Csrf`, if the request carried one
    * @param sessionSecret
    *   the current session's CSRF secret, if there is a session at all
    * @param secFetchSite
    *   the browser-supplied `Sec-Fetch-Site` value: `"same-origin"`, `"same-site"`, `"cross-site"`, `"none"`,
    *   or absent for a browser too old to send it
    */
  def verdict(
      method: String,
      authKind: PrincipalKind,
      headerToken: Option[String],
      sessionSecret: Option[String],
      secFetchSite: Option[String]
  ): Verdict = {
    val upperMethod = method.toUpperCase

    if SafeMethods.contains(upperMethod) then Verdict.Allowed
    else if authKind == PrincipalKind.Bearer then Verdict.Allowed
    else if secFetchSite.contains("cross-site") then
      Verdict.Denied("Sec-Fetch-Site is cross-site on a cookie-authenticated mutation")
    else
      (headerToken, sessionSecret) match {
        case (None, _) => Verdict.Denied(s"${HttpHeaders.Csrf} is missing")
        case (Some(_), None) => Verdict.Denied("no session is active")
        case (Some(token), Some(secret)) =>
          if constantTimeEquals(token, secret) then Verdict.Allowed
          else Verdict.Denied(s"${HttpHeaders.Csrf} does not match the session's token")
      }
  }

  given CanEqual[Verdict, Verdict] = CanEqual.derived

  /** A comparison that takes the same time whether the first character differs or the last does.
    *
    * The ordinary `==` on two strings returns as soon as it finds a mismatch, which turns "is this token
    * right" into a side channel: an attacker who can measure response time can recover the token one
    * character at a time by trying every next character and keeping whichever took longest. `Secret`
    * (`libs/kernel`) does the same thing for the same reason; this is the same algorithm inlined because the
    * two values being compared here are plain, already-extracted strings rather than `Secret` values.
    */
  private def constantTimeEquals(a: String, b: String): Boolean = {
    val left = a.getBytes("UTF-8")
    val right = b.getBytes("UTF-8")
    val length = math.max(left.length, right.length)

    var difference = left.length ^ right.length
    var i = 0
    while i < length do {
      val leftByte = if i < left.length then left(i).toInt else 0
      val rightByte = if i < right.length then right(i).toInt else 0
      difference |= leftByte ^ rightByte
      i += 1
    }
    difference == 0
  }
}
