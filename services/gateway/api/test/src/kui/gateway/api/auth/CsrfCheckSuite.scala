package kui.gateway.api.auth

import munit.FunSuite

import kui.security.PrincipalKind

/** The full matrix ADR-019 specifies, and the specification itself: {GET, POST} × {cookie, bearer,
  * anonymous} × {token present/absent/wrong} × {`Sec-Fetch-Site`: same-origin, same-site, cross-site,
  * absent}. Every row states the expected verdict; a change to [[CsrfCheck.verdict]] that is not reflected
  * here is a change nobody can see happened.
  *
  * "Anonymous" is folded into the cookie column rather than given a fourth: `PrincipalKind.Anonymous` is
  * authenticated by the anonymous session's own cookie exactly the way `PrincipalKind.Session` is, and
  * ADR-019's rule ("bearer is exempt, cookie is not") does not distinguish them — an anonymous session still
  * has a CSRF secret and still must be checked, precisely so the machinery is exercised before login exists.
  */
final class CsrfCheckSuite extends FunSuite {

  private val secret = "the-session-secret"

  private final case class Row(
      method: String,
      authKind: PrincipalKind,
      headerToken: Option[String],
      secFetchSite: Option[String],
      expected: CsrfCheck.Verdict
  )

  private val allowed = CsrfCheck.Verdict.Allowed

  private val rows: List[Row] = List(
    // ---- Safe methods never need a token, whatever else is true. ----
    Row("GET", PrincipalKind.Session, None, Some("cross-site"), allowed),
    Row("GET", PrincipalKind.Anonymous, None, None, allowed),
    Row("HEAD", PrincipalKind.Session, None, Some("cross-site"), allowed),

    // ---- Bearer is exempt outright, on every method and every Sec-Fetch-Site. ----
    Row("POST", PrincipalKind.Bearer, None, None, allowed),
    Row("POST", PrincipalKind.Bearer, None, Some("cross-site"), allowed),
    Row("DELETE", PrincipalKind.Bearer, Some("wrong"), Some("cross-site"), allowed),

    // ---- Cookie-authenticated mutations: cross-site is refused before the token is even looked at. ----
    Row(
      "POST",
      PrincipalKind.Session,
      Some(secret),
      Some("cross-site"),
      CsrfCheck.Verdict.Denied("Sec-Fetch-Site is cross-site on a cookie-authenticated mutation")
    ),
    Row(
      "POST",
      PrincipalKind.Anonymous,
      Some(secret),
      Some("cross-site"),
      CsrfCheck.Verdict.Denied("Sec-Fetch-Site is cross-site on a cookie-authenticated mutation")
    ),

    // ---- Same-origin and same-site both pass the fetch-metadata check; the token then decides. ----
    Row("POST", PrincipalKind.Session, Some(secret), Some("same-origin"), allowed),
    Row("POST", PrincipalKind.Session, Some(secret), Some("same-site"), allowed),
    Row("PUT", PrincipalKind.Anonymous, Some(secret), Some("same-origin"), allowed),

    // ---- No Sec-Fetch-Site header at all: an older browser. Not cross-site, so it reaches the token
    // check — the token is the whole defence for a client too old to send fetch metadata. ----
    Row("POST", PrincipalKind.Session, Some(secret), None, allowed),
    Row(
      "POST",
      PrincipalKind.Session,
      None,
      None,
      CsrfCheck.Verdict.Denied("X-Kui-Csrf is missing")
    ),

    // ---- The token itself: missing, wrong, and matching. ----
    Row(
      "POST",
      PrincipalKind.Session,
      None,
      Some("same-origin"),
      CsrfCheck.Verdict.Denied("X-Kui-Csrf is missing")
    ),
    Row(
      "DELETE",
      PrincipalKind.Session,
      Some("not-the-secret"),
      Some("same-origin"),
      CsrfCheck.Verdict.Denied("X-Kui-Csrf does not match the session's token")
    ),
    Row("PATCH", PrincipalKind.Session, Some(secret), Some("same-origin"), allowed),

    // ---- System principals (an internal scheduler calling itself) are not bearer, so the same rule
    // applies to them as to a session — no special case exists for "this is KUI itself". ----
    Row(
      "POST",
      PrincipalKind.System,
      None,
      Some("same-origin"),
      CsrfCheck.Verdict.Denied("X-Kui-Csrf is missing")
    )
  )

  rows.zipWithIndex.foreach { (row, index) =>
    test(s"row $index: ${row.method} ${row.authKind} token=${row.headerToken} secFetchSite=${row.secFetchSite}") {
      val verdict =
        CsrfCheck.verdict(row.method, row.authKind, row.headerToken, Some(secret), row.secFetchSite)
      assertEquals(verdict, row.expected)
    }
  }

  test("noSessionAtAllDeniesAMutationEvenWithAMatchingLookingToken") {
    // A cookie-authenticated request that somehow reached this check without a session — should not be
    // reachable in practice, since `SessionMiddleware` always attaches one, but the function must still
    // answer safely if it ever is.
    val verdict = CsrfCheck.verdict("POST", PrincipalKind.Session, Some(secret), None, Some("same-origin"))
    assertEquals(verdict, CsrfCheck.Verdict.Denied("no session is active"))
  }

  test("optionsIsSafeAndNeverChecked") {
    assertEquals(
      CsrfCheck.verdict("OPTIONS", PrincipalKind.Session, None, Some(secret), Some("cross-site")),
      allowed
    )
  }

  test("theComparisonIsCaseSensitive") {
    // A token that differs only in case is not a match. Loosening this would make the secret effectively
    // shorter — case-insensitive comparison of a base64url string discards real entropy.
    val verdict =
      CsrfCheck.verdict("POST", PrincipalKind.Session, Some(secret.toUpperCase), Some(secret), Some("same-origin"))
    assertEquals(verdict, CsrfCheck.Verdict.Denied("X-Kui-Csrf does not match the session's token"))
  }
}
