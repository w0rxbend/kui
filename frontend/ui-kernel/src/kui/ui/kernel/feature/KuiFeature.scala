package kui.ui.kernel.feature

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route

/** The base type of every page in the application.
  *
  * Each feature defines its own sealed `Page` subtype (`TopicListPage`, `TopicDetailPage`, …) and the shell
  * concatenates every feature's routes into one router over this type. A page is *data*: it carries the
  * cluster, the entity name, the selected tab and the filter parameters, and it is what the URL is built from
  * and parsed into. Rendering happens somewhere else entirely, which is what lets the shell hold a route
  * without holding the code that draws it (ADR-012 amendment 2).
  */
trait Page

/** Why a feature is not usable, in a form the kernel owns.
  *
  * Deliberately *not* the capability DTO from the wire. The kernel is the bottom of the frontend and must not
  * depend on the shape of a service's response; the shell translates the capability registry's answer into
  * this when it renders the fallback (task UI-008). If the DTO gains a field tomorrow, nothing in here has to
  * change.
  *
  * @param code
  *   a short machine-readable reason, e.g. `"upstream_unavailable"`, `"chunk_load_failed"`.
  * @param message
  *   what to show the user. A sentence, not a stack trace.
  * @param since
  *   when the problem started, as an ISO-8601 instant, when that is known. ADR-032 requires it on screen:
  *   "down for two minutes" and "down since Tuesday" call for very different reactions.
  */
final case class UnavailableReason(code: String, message: String, since: Option[String] = None)

/** What a feature contributes to the shell's navigation.
  *
  * @param icon
  *   a thunk, because a DOM node can only be in one place at a time and the nav may render more than one copy
  *   (a sidebar and a mobile menu).
  * @param order
  *   where it sits in the sidebar. Explicit rather than "the order they were registered in", because
  *   registration order is a fact about the code and sidebar order is a product decision.
  * @param requiresCluster
  *   whether the entry means anything before a cluster is chosen. Topics do not; the cluster list does.
  */
final case class NavEntry(
    featureId: FeatureId,
    label: String,
    icon: () => SvgElement,
    order: Int,
    requiresCluster: Boolean
)

/** Everything a host feature's page can tell a panel it did not write.
  *
  * Deliberately narrow. A panel from another feature gets the identifiers it needs to fetch its own data, and
  * nothing else: passing the host's state object would make the two features share a type and defeat the
  * whole arrangement.
  */
final case class PanelContext(cluster: Option[String], params: Map[String, String])

/** A panel one feature contributes to another feature's page.
  *
  * The topic page's "Consumers" tab is the worked example: the consumers feature owns that panel, and the
  * topics feature renders it without ever naming the consumers feature (ADR-012). A panel is rendered only if
  * its feature is *already loaded*, so a host page never triggers a download.
  *
  * @param host
  *   whose page this appears on.
  * @param slot
  *   which position on that page, e.g. `"topic.tabs"`. The host defines the slot names it offers.
  */
final case class PanelContribution(host: FeatureId, slot: String, render: PanelContext => HtmlElement)

/** The interface every microfrontend implements.
  *
  * A feature is a self-contained slice of the product: its own routes, its own pages, its own state and its
  * own requests. The shell knows only this interface, and the concrete class is named in exactly one place
  * (`FeatureRegistry`), inside a dynamic import, so that the linker can put the whole feature in a JavaScript
  * module the browser downloads only when it is needed.
  *
  * See `docs/frontend/features.md` for the four steps that add one.
  */
trait KuiFeature {

  def id: FeatureId

  /** Where it appears in the sidebar. */
  def nav: NavEntry

  /** The URLs this feature owns.
    *
    * Route *patterns* are static data — path shapes, not code — and the shell links against them normally, so
    * that a bookmarked deep link resolves before this feature's module has been downloaded. Without that, the
    * first URL the router sees would be one it cannot match, and the user would get a 404 for a page that
    * exists (ADR-012 amendment 2).
    */
  def routes: List[Route[? <: Page, ?]]

  /** Draws one of this feature's pages. Only ever called with a page this feature's routes produced. */
  def render(page: Page): HtmlElement

  /** What the shell shows instead of the page when this feature cannot be used.
    *
    * ADR-032 requires four things on that panel, and the reason all four are here rather than in a generic
    * kernel component is that only the feature knows the fourth: the reason, when it started, a working
    * retry, and *what still works* — "you can still browse messages; only the schema names are missing" is a
    * sentence the schema feature can write and the shell cannot.
    *
    * @param retry
    *   asks the gateway to probe the service again. Never a page reload.
    */
  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement

  /** Panels this feature contributes to other features' pages. Empty for most features. */
  def panels: List[PanelContribution] = Nil
}
