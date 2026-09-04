package kui.ui.clusters

import scala.annotation.nowarn

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

  /** The administration screen: add, edit and remove the clusters this KUI knows about.
    *
    * A page of its own rather than a mode of the dashboard, and it has a URL, because it is the page an
    * operator is sent a link to — "add the staging cluster, here is where" — and because a form the browser's
    * Back button cannot leave is a form people close by reloading.
    */
  case object Manage extends ClustersPageId

  /** One cluster's brokers.
    *
    * The id is a `String` and not a `ClusterId` because a page is data that has to survive a round trip
    * through `history.state`, and a URL can hold anything a user types. The id is validated where it is used
    * — a value that will not parse as a slug renders the page's own not-found state rather than failing to
    * decode the whole history entry, which would strand the Back button.
    */
  final case class Brokers(clusterId: String) extends ClustersPageId

  /** One broker of one cluster, on one of its tabs.
    *
    * The tab is part of the page, and therefore part of the URL: a configuration listing is the thing an
    * operator pastes into a ticket, and a link that always opened on log directories would make the recipient
    * hunt for what they were sent.
    */
  final case class BrokerDetail(clusterId: String, brokerId: Int, tab: Option[String] = None)
      extends ClustersPageId

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

  private val ManageTag = "clusters.manage"

  private val ManageSegment = "manage"

  private val BrokersTag = "clusters.brokers"

  private val BrokersSegment = "brokers"

  private val BrokerTag = "clusters.broker"

  private val ClustersSegment = "clusters"

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
      Route.static(ClustersPageId.Overview, root / ClustersSegment / endOfSegments, uiPrefix),
      // Before the `{clusterId}/brokers` pattern, and it does not collide with it: that one has three
      // segments and this has two. `manage` is not a legal cluster id anyway — a slug is matched by the
      // codec, not by this pattern — but ordering it first costs nothing and removes the question.
      Route.static(
        ClustersPageId.Manage,
        root / ClustersSegment / ManageSegment / endOfSegments,
        uiPrefix
      ),
      Route[ClustersPageId.Brokers, String](
        encode = _.clusterId,
        decode = ClustersPageId.Brokers(_),
        pattern = root / ClustersSegment / segment[String] / BrokersSegment / endOfSegments,
        basePath = uiPrefix
      ),
      // The default tab has no segment of its own, so a broker's canonical URL is its short form and a
      // link to a broker needs to know nothing about tabs. Two patterns rather than an optional segment,
      // because Waypoint matches a pattern by shape and an optional trailing segment is two shapes.
      // `applyPF`, and the partial function is the point: this pattern must *refuse* a page that names a
      // tab, or it would happily encode `BrokerDetail(c, b, Some("configs"))` to the tabless URL and a
      // link to a broker's configuration would open on its log directories.
      Route.applyPF[ClustersPageId, (String, Int)](
        matchEncode = brokerDetailEncode(_.tab.isEmpty)(page => (page.clusterId, page.brokerId)),
        decode = { case (clusterId, brokerId) => ClustersPageId.BrokerDetail(clusterId, brokerId, None) },
        pattern = root / ClustersSegment / segment[String] / BrokersSegment / segment[Int] / endOfSegments,
        basePath = uiPrefix
      ),
      Route.applyPF[ClustersPageId, (String, Int, String)](
        matchEncode = brokerDetailEncode(_.tab.isDefined)(page =>
          (page.clusterId, page.brokerId, page.tab.getOrElse(""))
        ),
        decode = { case (clusterId, brokerId, tab) =>
          ClustersPageId.BrokerDetail(clusterId, brokerId, Some(tab))
        },
        pattern = root / ClustersSegment / segment[String] / BrokersSegment / segment[Int] / segment[String] /
          endOfSegments,
        basePath = uiPrefix
      )
    )

  /** A `matchEncode` for one of the two broker-detail patterns.
    *
    * Waypoint types `matchEncode` as `PartialFunction[Any, Args]`, and Scala 3 refuses to destructure an
    * `Any` — a value that is not `Matchable` may be an opaque type whose runtime shape is not its static one.
    * Narrowing to `Matchable` once, here, is what lets both patterns be written as ordinary cases.
    *
    * @param wanted
    *   which of the two shapes this pattern claims. Written as a predicate rather than as a pattern guard so
    *   that neither route can silently claim the other's URLs — the tabless pattern refusing a page that
    *   names a tab is the whole reason these are partial.
    */
  @nowarn("msg=unmatchable type Any")
  private def brokerDetailEncode[A](
      wanted: ClustersPageId.BrokerDetail => Boolean
  )(encode: ClustersPageId.BrokerDetail => A): PartialFunction[Any, A] = {
    // Guarded rather than nested-and-hoped: a partial function has to answer `isDefinedAt` honestly, and an
    // inner match that throws would make Waypoint's "can this route encode this page" question crash
    // instead of answering "no".
    val claim: PartialFunction[Matchable, A] = {
      case page: ClustersPageId.BrokerDetail if wanted(page) => encode(page)
    }
    { case value: Matchable if claim.isDefinedAt(value) => claim(value) }
  }

  def encodePage(page: Page): Option[Json] =
    page match {
      case ClustersPageId.Overview => Some(Json.obj("page" -> Json.fromString(OverviewTag)))
      case ClustersPageId.Manage => Some(Json.obj("page" -> Json.fromString(ManageTag)))
      case ClustersPageId.Brokers(clusterId) =>
        Some(Json.obj("page" -> Json.fromString(BrokersTag), "clusterId" -> Json.fromString(clusterId)))
      case ClustersPageId.BrokerDetail(clusterId, brokerId, tab) =>
        Some(
          Json.obj(
            "page" -> Json.fromString(BrokerTag),
            "clusterId" -> Json.fromString(clusterId),
            "brokerId" -> Json.fromInt(brokerId),
            "tab" -> tab.fold(Json.Null)(Json.fromString)
          )
        )
      case _ => None
    }

  def decodePage(tag: String, cursor: HCursor): Option[Page] =
    if tag == OverviewTag then Some(ClustersPageId.Overview)
    else if tag == ManageTag then Some(ClustersPageId.Manage)
    else if tag == BrokersTag then cursor.get[String]("clusterId").toOption.map(ClustersPageId.Brokers(_))
    else if tag == BrokerTag then
      // Both fields, or nothing: a stored state that names a cluster but no broker cannot be turned into a
      // broker page, and guessing a broker id would land the user on somebody else's machine.
      for {
        clusterId <- cursor.get[String]("clusterId").toOption
        brokerId <- cursor.get[Int]("brokerId").toOption
        // The tab is read leniently and defaults to absent, so a state written before tabs existed still
        // decodes — a Back button pressed across a deployment upgrade lands on the default tab rather than
        // on "not found".
        tab = cursor.get[Option[String]]("tab").toOption.flatten
      } yield ClustersPageId.BrokerDetail(clusterId, brokerId, tab)
    else None
}
