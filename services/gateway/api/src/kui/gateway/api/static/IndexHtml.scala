package kui.gateway.api.static

/** What the shell needs to know before its own code runs, and cannot know on its own: where KUI is mounted,
  * where its API is, and which build served the page.
  *
  * A browser cannot read `server.basePath` from a configuration file — it has none — so the gateway hands the
  * answer over as data embedded in the page it serves. `ApiClient` (`frontend/ui-kernel`) reads this block
  * instead of hard-coding `/api/v1`, which is what lets the same linked frontend run unmodified whether KUI
  * is mounted at the root or behind a reverse proxy at `/kui`.
  *
  * @param basePath
  *   the path prefix every route is served under, normalised (`""` or `"/kui"`, never `"/"` or a trailing
  *   slash)
  * @param apiBase
  *   where the shell sends API calls, e.g. `/api/v1` or `/kui/api/v1`
  * @param buildVersion
  *   the version shown in the shell's footer (GW-010)
  */
final case class BootstrapConfig(basePath: String, apiBase: String, buildVersion: String)

object BootstrapConfig {
  given CanEqual[BootstrapConfig, BootstrapConfig] = CanEqual.derived
}

/** Injecting the bootstrap data into `index.html`, without a templating engine.
  *
  * A full templating library would be a strange dependency for one substitution done once per process start.
  * What is here is deliberately small: two markers in the committed HTML, two replacements, and a function
  * pure enough to table-test without a server or a browser.
  */
object IndexHtml {

  /** The element `ApiClient` reads its configuration from. The id is the contract between this function and
    * the frontend; changing it here without changing `ApiClient` breaks every deployment silently, which is
    * why `StaticRoutesSuite` asserts on this exact string rather than on "a script tag is present somewhere".
    */
  val BootstrapElementId: String = "kui-bootstrap"

  /** The placeholder the committed template carries in place of the real JSON, and the one in place of the
    * real `<base>` tag. Plain string markers rather than a `{{mustache}}` syntax, because two literal
    * substitutions do not need a parser, and a parser is one more thing that can fail on a page with no
    * server behind it to report the failure.
    */
  val BootstrapPlaceholder: String = "<!--KUI_BOOTSTRAP-->"
  val BaseHrefPlaceholder: String = "<!--KUI_BASE_HREF-->"

  /** Produces the page the browser actually receives.
    *
    * Pure: no file is read and no server is started, so the substitution itself — and in particular that the
    * JSON it writes is valid and matches `config` exactly — is tested as a table rather than through an
    * integration suite.
    *
    * @param template
    *   the committed `index.html`, with both placeholders present
    * @param config
    *   what to embed
    */
  def render(template: String, config: BootstrapConfig): String = {
    val bootstrapJson =
      s"""{"basePath":${quote(config.basePath)},"apiBase":${quote(config.apiBase)},"buildVersion":${quote(
          config.buildVersion
        )}}"""
    val bootstrapScript =
      s"""<script id="$BootstrapElementId" type="application/json">$bootstrapJson</script>"""
    val baseHref = s"""<base href="${htmlAttribute(config.basePath)}/ui/">"""

    template
      .replace(BootstrapPlaceholder, bootstrapScript)
      .replace(BaseHrefPlaceholder, baseHref)
  }

  /** A JSON string literal, safe to sit inside an HTML `<script>` element.
    *
    * The three values this ever holds — a base path, an API prefix, a version — come from configuration and
    * the build, never from a browser. Escaping them anyway is one line, and it turns "cannot happen" into
    * "cannot happen, checked": a JSON string escapes a quote and a backslash, but an HTML parser terminates a
    * `<script>` element on the literal text `</script`, wherever it appears — even inside a string a script's
    * own language would consider unterminated. Escaping every `/` as `\/` (valid and a no-op in JSON,
    * meaningless to HTML) is what stops a configured value that happened to contain `</script>` from ending
    * the tag early.
    */
  private def quote(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("/", "\\/") + "\""

  /** A value safe to sit inside a double-quoted HTML attribute.
    *
    * `basePath` reaches this function already validated by `kui.http.BasePath.normalize`, so in practice it
    * is `""` or something like `"/kui"` and never contains a quote. The escape is written anyway: a value
    * this function did not choose is one wrong assumption away from being interpreted as markup, and the cost
    * of being wrong here is an attribute that terminates early and lets whatever followed run as HTML.
    */
  private def htmlAttribute(raw: String): String =
    raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
