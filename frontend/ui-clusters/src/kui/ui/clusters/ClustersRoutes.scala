package kui.ui.clusters

import com.raquo.waypoint.*
import io.circe.{HCursor, Json}

import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, NavEntry, Page}

/** The pages this feature owns.
  *
  * A page is *data*: it carries what the URL is built from and parsed into, and nothing about how anything is
  * drawn. That is what lets the shell hold a route without holding the code that renders it (ADR-012
  * amendment 2).
  */
sealed trait ClustersPageId extends Page

object ClustersPageId {

  /** The list. M1 gives it a cluster id and a tab; today it is a singleton.
    *
    * A `case object` rather than an `enum` case, because Waypoint's `Route.static` needs the page's
    * *singleton type* — an enum case has the enum's type, and the route would not compile.
    */
  case object Overview extends ClustersPageId

  given CanEqual[ClustersPageId, ClustersPageId] = CanEqual.derived
}

/** The static half of this feature's registration: everything the shell has to know **before** the feature's
  * JavaScript module has been downloaded.
  *
  * Three things need that, and each one misbehaves visibly without it. The route pattern, or a bookmarked
  * link to `/ui/clusters` is a 404 on the first load. The nav entry and its destination, or the sidebar
  * cannot draw a link without fetching the feature — which would defeat the whole arrangement. The
  * `history.state` codec, or pressing Back onto this page lands on "not found".
  *
  * All of it is data: a label, a sort order, a path shape, a JSON tag. Naming it from the shell costs a few
  * bytes in `main.js` and pulls no feature code with it — `ClustersFeature`, `ClustersPage` and
  * `ClustersState` are all reachable only through the dynamic import, which is what `checkBundleShape`
  * asserts on the linked output.
  */
object ClustersRoutes extends FeatureRoutes {

  /** The JSON tag this feature's pages are stored under in `history.state`. Prefixed with the feature id, so
    * two features cannot claim the same tag.
    */
  private val OverviewTag = "clusters.overview"

  val id: FeatureId = FeatureId.Clusters

  val landing: Page = ClustersPageId.Overview

  val nav: NavEntry =
    NavEntry(
      featureId = id,
      label = Messages.Title,
      // A thunk, because a DOM node can only be in one place at a time and the navigation may render more
      // than one copy of an entry (a sidebar and a mobile menu).
      icon = () => Icon.dot,
      // Ahead of the shell's own development and preferences entries, which sit at 9000 and above.
      order = 100,
      // The cluster list is what a user picks a cluster *from*, so it is meaningful before one is chosen.
      requiresCluster = false
    )

  def routes(uiPrefix: String): List[Route[? <: Page, ?]] =
    List(
      // `endOfSegments` is deliberate: without it the pattern matches a prefix, so `/ui/clusters` would
      // also claim `/ui/clusters/anything` and a mistyped sub-path would never produce a 404.
      Route.static(ClustersPageId.Overview, root / "clusters" / endOfSegments, uiPrefix)
    )

  def encodePage(page: Page): Option[Json] =
    page match {
      case ClustersPageId.Overview => Some(Json.obj("page" -> Json.fromString(OverviewTag)))
      case _ => None
    }

  def decodePage(tag: String, cursor: HCursor): Option[Page] =
    if tag == OverviewTag then Some(ClustersPageId.Overview) else None
}
