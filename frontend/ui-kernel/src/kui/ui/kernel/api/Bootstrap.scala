package kui.ui.kernel.api

import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import org.scalajs.dom

/** The handful of facts the server tells the browser before any request is made.
  *
  * The gateway injects them as a `<script id="kui-bootstrap" type="application/json">` block into
  * `index.html` (task GW-008). They cannot be compiled in, because they depend on where KUI is *deployed*
  * rather than on how it was built: an operator who mounts KUI at `https://tools.example.com/kafka/` needs
  * every asset URL and every API URL to gain that prefix, and neither the build nor a hard-coded constant can
  * know it.
  *
  * @param basePath
  *   the prefix the whole application is served under, without a trailing slash — `""` at the root of a
  *   domain, `"/kafka"` behind a path-mounting reverse proxy. The router builds URLs from it.
  * @param apiBase
  *   where the API lives, including `basePath` — `"/api/v1"` or `"/kafka/api/v1"`.
  * @param buildVersion
  *   which build the *server* is, which is not necessarily which build the browser has: a user with an old
  *   tab open after a deploy has two different answers, and the header shows both when they disagree.
  */
final case class Bootstrap(basePath: String, apiBase: String, buildVersion: String)

object Bootstrap {

  /** The id of the `<script>` element the gateway writes. Contract with GW-008. */
  val ElementId = "kui-bootstrap"

  /** What the frontend assumes when the block is missing.
    *
    * Missing is a real case rather than a defensive one: `./mill frontend.uiShell.fastLinkJS` output opened
    * from a bare `index.html`, and every unit test, have no gateway to inject anything. Defaulting to the
    * root deployment keeps those working instead of making an absent script a start-up crash.
    */
  val Fallback: Bootstrap = Bootstrap(basePath = "", apiBase = "/api/v1", buildVersion = "dev")

  /** Reads the block out of the current document.
    *
    * Never throws and never returns a partly-filled value: anything unreadable — no element, empty text,
    * malformed JSON, a field of the wrong type — yields [[Fallback]]. A frontend that refuses to start
    * because one string was mistyped is worse than one that starts pointed at the conventional path, which is
    * what an operator would have configured anyway.
    */
  def read(): Bootstrap = readFrom(dom.document)

  /** The same, against a document a test supplies. */
  def readFrom(document: dom.Document): Bootstrap =
    Option(document.getElementById(ElementId))
      .flatMap(element => Option(element.textContent))
      .flatMap(text => decode[Bootstrap](text).toOption)
      .getOrElse(Fallback)

  /** Turns [[apiBase]] into something sttp can parse.
    *
    * `apiBase` is a path (`/api/v1`) because that is what the gateway knows: it serves the frontend and the
    * API from one origin (ADR-012 amendment 1) and has no reason to name its own host, which may be behind a
    * proxy under a different name. sttp's `Uri`, on the other hand, needs an absolute URL. The browser's own
    * origin is the missing half, and it is authoritative — it is where the page came from.
    *
    * An `apiBase` that is already absolute is passed through, so a deployment that does split the two origins
    * is not locked out.
    */
  def absoluteApiBase(bootstrap: Bootstrap, origin: String): String =
    if bootstrap.apiBase.startsWith("http://") || bootstrap.apiBase.startsWith("https://") then
      bootstrap.apiBase
    else s"${origin.stripSuffix("/")}/${bootstrap.apiBase.stripPrefix("/")}"

  /** Written out rather than derived, for the same reason every other KUI codec is (ADR-007): every field is
    * optional and falls back to [[Fallback]]'s value, so a gateway that learns to send a fourth field, or
    * forgets to send the third, does not stop the browser from starting.
    */
  given Decoder[Bootstrap] = (cursor: HCursor) =>
    for {
      basePath <- cursor.getOrElse[String]("basePath")(Fallback.basePath)
      apiBase <- cursor.getOrElse[String]("apiBase")(Fallback.apiBase)
      buildVersion <- cursor.getOrElse[String]("buildVersion")(Fallback.buildVersion)
    } yield Bootstrap(basePath.stripSuffix("/"), apiBase.stripSuffix("/"), buildVersion)

  given CanEqual[Bootstrap, Bootstrap] = CanEqual.derived
}
