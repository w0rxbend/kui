package kui.ui.topics

import scala.annotation.nowarn

import com.raquo.waypoint.*
import io.circe.{HCursor, Json}

import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, NavEntry, Page}

/** Which tab of a topic's detail page is open.
  *
  * The tab is part of the page and therefore part of the URL. A configuration listing is what an operator
  * pastes into a ticket, and a link that always opened on the overview would make the recipient hunt for what
  * they were sent. It also means the Settings tab's data is a separate query that is not fetched at all until
  * somebody opens the tab.
  *
  * Only two, and no third that says "coming in M5". A tab that promises a milestone is a promise with a date
  * on it (DEVPLAN §10 D13).
  */
enum TopicTab(val segment: Option[String]) {

  /** The default, and the one with no segment of its own, so a topic's canonical URL is its short form and a
    * link to a topic needs to know nothing about tabs.
    */
  case Overview extends TopicTab(None)
  case Settings extends TopicTab(Some("settings"))
}

object TopicTab {

  val Default: TopicTab = Overview

  /** Reads a tab back from a URL segment.
    *
    * Anything unrecognised is the default rather than a failure: a bookmark can outlive a tab, and landing on
    * the overview of the right topic is a far better answer than "not found".
    */
  def fromSegment(raw: Option[String]): TopicTab =
    raw.flatMap(value => values.find(_.segment.contains(value))).getOrElse(Default)

  given CanEqual[TopicTab, TopicTab] = CanEqual.derived
}

/** The pages this feature owns.
  *
  * A page is *data*: what the URL is built from and parsed into, and nothing about how anything is drawn
  * (ADR-012 amendment 2). That is what lets the shell hold a route without holding the code that renders it.
  */
sealed trait TopicsPageId extends Page

object TopicsPageId {

  /** One cluster's topic list.
    *
    * The cluster id is a `String` rather than a `ClusterId` because a page has to survive a round trip
    * through `history.state` and a URL can hold anything a user types. It is validated where it is used, so a
    * value that will not parse renders the page's own not-found state instead of failing to decode the whole
    * history entry — which would strand the Back button.
    */
  final case class List(clusterId: String) extends TopicsPageId

  /** One topic, on one of its tabs. */
  final case class Detail(clusterId: String, topic: String, tab: TopicTab = TopicTab.Default)
      extends TopicsPageId

  given CanEqual[TopicsPageId, TopicsPageId] = CanEqual.derived
}

/** The static half of this feature's registration: everything the shell must know **before** the feature's
  * JavaScript module has been downloaded.
  *
  * All of it is data — a label, a sort order, path shapes, a JSON tag — and naming it from the shell costs a
  * few bytes in `main.js` and pulls no feature code with it. `TopicsFeature` and every screen are reachable
  * only through the dynamic import, which is what `checkBundleShape` asserts on the linked output.
  */
object TopicsRoutes extends FeatureRoutes {

  private val ListTag = "topics.list"
  private val DetailTag = "topics.detail"

  private val ClustersSegment = "clusters"
  private val TopicsSegment = "topics"

  val id: FeatureId = FeatureId.Topics

  /** The landing page needs a cluster, and the shell substitutes the chosen one before it draws the link. The
    * placeholder is never navigated to: `nav.requiresCluster` is true, so the entry is not offered until a
    * cluster has been chosen.
    */
  val landing: Page = TopicsPageId.List("")

  val nav: NavEntry =
    NavEntry(
      featureId = id,
      label = Messages.Title,
      // A thunk, because a DOM node can only be in one place at a time and the navigation may render more
      // than one copy of an entry (a sidebar and a mobile menu).
      icon = () => Icon.dot,
      // After the cluster list, which is what a cluster is chosen from, and well before the shell's own
      // development and preferences entries at 9000 and above.
      order = 200,
      // Topics belong to a cluster; the entry means nothing until one has been chosen.
      requiresCluster = true
    )

  def routes(uiPrefix: String): scala.collection.immutable.List[Route[? <: Page, ?]] =
    scala.collection.immutable.List(
      // `endOfSegments` throughout. Without it a pattern matches a prefix, so `/ui/clusters/x/topics` would
      // also claim `/ui/clusters/x/topics/anything` and a mistyped sub-path would never produce a 404.
      Route[TopicsPageId.List, String](
        encode = _.clusterId,
        decode = TopicsPageId.List(_),
        pattern = root / ClustersSegment / segment[String] / TopicsSegment / endOfSegments,
        basePath = uiPrefix
      ),
      // Two patterns rather than an optional trailing segment, because Waypoint matches a pattern by shape
      // and an optional segment is two shapes. `applyPF`, and the partial function is the point: the tabless
      // pattern must *refuse* a page that names a tab, or it would encode the Settings page to the tabless
      // URL and a link to a topic's configuration would open on its overview.
      Route.applyPF[TopicsPageId, (String, String)](
        matchEncode = detailEncode(_.tab.segment.isEmpty)(page => (page.clusterId, page.topic)),
        decode = { case (clusterId, topic) => TopicsPageId.Detail(clusterId, topic, TopicTab.Default) },
        pattern = root / ClustersSegment / segment[String] / TopicsSegment / segment[String] / endOfSegments,
        basePath = uiPrefix
      ),
      Route.applyPF[TopicsPageId, (String, String, String)](
        matchEncode = detailEncode(_.tab.segment.isDefined)(page =>
          (page.clusterId, page.topic, page.tab.segment.getOrElse(""))
        ),
        decode = { case (clusterId, topic, tab) =>
          TopicsPageId.Detail(clusterId, topic, TopicTab.fromSegment(Some(tab)))
        },
        pattern = root / ClustersSegment / segment[String] / TopicsSegment / segment[String] /
          segment[String] / endOfSegments,
        basePath = uiPrefix
      )
    )

  /** A `matchEncode` for one of the two detail patterns.
    *
    * Waypoint types `matchEncode` as `PartialFunction[Any, Args]`, and Scala 3 refuses to destructure an
    * `Any` — a value that is not `Matchable` may be an opaque type whose runtime shape is not its static one.
    * Narrowing to `Matchable` once, here, is what lets both patterns be written as ordinary cases.
    */
  @nowarn("msg=unmatchable type Any")
  private def detailEncode[A](
      wanted: TopicsPageId.Detail => Boolean
  )(encode: TopicsPageId.Detail => A): PartialFunction[Any, A] = {
    // Guarded rather than nested-and-hoped: a partial function has to answer `isDefinedAt` honestly, and an
    // inner match that threw would make Waypoint's "can this route encode this page" question crash instead
    // of answering "no".
    val claim: PartialFunction[Matchable, A] = {
      case page: TopicsPageId.Detail if wanted(page) => encode(page)
    }
    { case value: Matchable if claim.isDefinedAt(value) => claim(value) }
  }

  def encodePage(page: Page): Option[Json] =
    page match {
      case TopicsPageId.List(clusterId) =>
        Some(Json.obj("page" -> Json.fromString(ListTag), "clusterId" -> Json.fromString(clusterId)))
      case TopicsPageId.Detail(clusterId, topic, tab) =>
        Some(
          Json.obj(
            "page" -> Json.fromString(DetailTag),
            "clusterId" -> Json.fromString(clusterId),
            "topic" -> Json.fromString(topic),
            "tab" -> tab.segment.fold(Json.Null)(Json.fromString)
          )
        )
      case _ => None
    }

  def decodePage(tag: String, cursor: HCursor): Option[Page] =
    if tag == ListTag then cursor.get[String]("clusterId").toOption.map(TopicsPageId.List(_))
    else if tag == DetailTag then
      // Both identifying fields, or nothing: a stored state that names a cluster but no topic cannot be
      // turned into a topic page, and guessing a topic would show somebody else's data.
      for {
        clusterId <- cursor.get[String]("clusterId").toOption
        topic <- cursor.get[String]("topic").toOption
        // The tab is read leniently, so a state written before a tab existed still decodes and Back across a
        // deployment upgrade lands on the overview rather than on "not found".
        tab = TopicTab.fromSegment(cursor.get[Option[String]]("tab").toOption.flatten)
      } yield TopicsPageId.Detail(clusterId, topic, tab)
    else None
}
