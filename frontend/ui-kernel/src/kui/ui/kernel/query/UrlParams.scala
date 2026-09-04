package kui.ui.kernel.query

import scala.scalajs.js
import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Reading and writing the query string, in one place, so that a screen's state is a URL.
  *
  * ## Why a list screen's state lives in the address bar
  *
  * A list screen's whole state — the search text, the search mode, the sort, the page, the page size, the
  * "show internal" toggle — is in the query string. Three things follow, and each of them is something a user
  * expects and does not get from a state variable:
  *
  *   - "send me the link to what you are looking at" reproduces the same screen for the recipient;
  *   - the browser's Back button undoes a page change or a filter, instead of leaving the application;
  *   - a bookmark to page 3 of a filtered list is still page 3 of that filtered list tomorrow.
  *
  * ## Why every write is a read-modify-write
  *
  * Each control owns one parameter and knows nothing about the others. If a control wrote the whole query
  * string it would erase whatever a control it has never heard of had put there — which is exactly how a
  * page-size selector silently clears a search box. [[set]] therefore merges into the parameters that are
  * already present, and `unrelatedParametersAreLeftAlone` is the assertion that keeps it that way.
  */
object UrlParams {

  /** The parsed query string, kept in step with the address bar.
    *
    * `lazy` so that merely importing this object touches neither `window.location` nor `window.history`: the
    * kernel is linked into `main.js` and is initialised in environments (a linker test, a server-side render)
    * that have no such objects.
    */
  private lazy val state: Var[Map[String, String]] = {
    val initial = Var(read())
    // Back and Forward change the URL without a navigation, and `popstate` is the only notification of
    // it. Without this listener the address bar and the screen disagree after one press of Back, which
    // is worse than not supporting Back at all.
    dom.window.addEventListener("popstate", (_: dom.Event) => initial.set(read()))
    initial
  }

  def get(name: String): Option[String] = state.now().get(name)

  /** Applies a set of changes to the query string. `None` removes a parameter.
    *
    * A removal is spelled `Some(name -> None)` rather than by writing an empty string, because an empty value
    * is a real value — `q=` means "the user cleared the search box", which is not the same request as one
    * with no `q` at all.
    *
    * The new URL is *pushed* rather than replaced, so that each filter change is a step the Back button can
    * undo. Typing is debounced before it reaches here, so a burst of keystrokes is one history entry.
    */
  def set(updates: Map[String, Option[String]]): Unit = {
    val merged = updates.foldLeft(read()) {
      case (parameters, (name, Some(value))) => parameters.updated(name, value)
      case (parameters, (name, None)) => parameters.removed(name)
    }
    if merged != read() then {
      push(merged)
      state.set(merged)
    }
  }

  def signal(name: String): Signal[Option[String]] = state.signal.map(_.get(name)).distinct

  /** Re-reads the address bar. For a test that changed the URL by some route other than [[set]]. */
  private[kernel] def resync(): Unit = state.set(read())

  private def read(): Map[String, String] =
    Try(dom.window.location.search).toOption.fold(Map.empty[String, String])(parse)

  /** Parses `?a=1&b=2`. A parameter with no `=` is present with an empty value, which is what a browser's own
    * `URLSearchParams` does, and an unparseable escape leaves the raw text rather than failing.
    */
  private[query] def parse(search: String): Map[String, String] =
    search
      .stripPrefix("?")
      .split('&')
      .toList
      .filter(_.nonEmpty)
      .map { pair =>
        val (name, rest) = pair.span(_ != '=')
        decode(name) -> decode(rest.stripPrefix("="))
      }
      .toMap

  private[query] def render(parameters: Map[String, String]): String =
    if parameters.isEmpty then ""
    else
      // Sorted, so that the same state always produces the same URL and two links to the same screen
      // compare equal as text.
      parameters.toList
        .sortBy(_._1)
        .map((name, value) => s"${encode(name)}=${encode(value)}")
        .mkString("?", "&", "")

  private def push(parameters: Map[String, String]): Unit =
    Try {
      val target = dom.window.location.pathname + render(parameters) + dom.window.location.hash
      dom.window.history.pushState(dom.window.history.state, "", target)
    }.getOrElse(())

  /** `+` is a legal encoding of a space in a query string, and a topic name may contain none of the
    * characters that would make this ambiguous — but a search query can contain anything the user typed.
    */
  private def decode(raw: String): String =
    Try(js.URIUtils.decodeURIComponent(raw.replace("+", "%20"))).getOrElse(raw)

  private def encode(raw: String): String = Try(js.URIUtils.encodeURIComponent(raw)).getOrElse(raw)
}
