package kui.contracts

/** The header names the browser and the gateway have to agree on, letter for letter.
  *
  * ## Why a shared constant and not two string literals
  *
  * A header name is a contract between two programs that are compiled separately, deployed separately and
  * tested separately. When each side spells the name out for itself, nothing fails at compile time if one of
  * them is changed: the browser goes on sending a header the server no longer reads, every mutation is
  * rejected as a forged request, and the only evidence is a `403` in production. That is exactly what
  * happened here — the browser sent `X-Kui-Csrf` while the gateway read `X-Csrf-Token` — so the name now
  * lives in `contracts-core`, which both halves already compile against, and neither side can rename it
  * without renaming it for the other.
  */
object HttpHeaders {

  /** Where a mutation carries the session's CSRF token (ADR-019).
    *
    * Deliberately **not** in the `X-Kui-*` family. The gateway strips every inbound `X-Kui-*` header at the
    * edge, because no browser ever legitimately sets one (ADR-040) — they are how the gateway talks to
    * itself. The CSRF header is the opposite: a browser setting it correctly *is* the whole mechanism, so
    * naming it `X-Kui-Csrf` would have the edge policy delete it from every genuine request before the CSRF
    * check ever saw it.
    */
  val Csrf: String = "X-Csrf-Token"
}
