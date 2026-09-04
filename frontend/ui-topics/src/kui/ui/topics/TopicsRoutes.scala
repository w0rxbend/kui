package kui.ui.topics

import scala.annotation.nowarn

import com.raquo.waypoint.*
import io.circe.{HCursor, Json}

import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{
  FeatureId,
  FeatureRegistry,
  FeatureRoutes,
  FeatureSlots,
  GuestTabs,
  NavEntry,
  Page
}

/** Which tab of a topic's detail page is open.
  *
  * The tab is part of the page and therefore part of the URL. A configuration listing is what an operator
  * pastes into a ticket, and a link that always opened on the overview would make the recipient hunt for what
  * they were sent. It also means the Settings tab's data is a separate query that is not fetched at all until
  * somebody opens the tab.
  *
  * ## Why this is an open string and not an enum of two cases
  *
  * It was an enum, and that made a third of the strip unaddressable. The topic page's tabs are not all its
  * own: another feature contributes one through `FeatureSlots.TopicTabs`, and the consumers feature
  * contributes "Consumers". A closed enum of the page's own tabs cannot name that one, so clicking it left
  * the address bar saying Overview, refreshing lost the tab, a copied link sent the recipient to the wrong
  * screen, and typing the obvious `…/orders.v1/consumers` produced "That page does not exist" while
  * `…/orders.v1/settings` worked.
  *
  * The id is the tab's id in the strip as well as its URL segment, so the two vocabularies cannot drift: the
  * page's own tabs are `overview` and `settings`, and a guest's tab is its feature's id.
  */
final case class TopicTab(id: String) {

  /** The URL segment, or `None` for the default tab — so a topic's canonical URL is its short form and a link
    * to a topic needs to know nothing about tabs.
    */
  def segment: Option[String] = Option.unless(id == TopicTab.OverviewId)(id)
}

object TopicTab {

  val OverviewId: String = "overview"
  val SettingsId: String = "settings"

  val Overview: TopicTab = TopicTab(OverviewId)
  val Settings: TopicTab = TopicTab(SettingsId)

  val Default: TopicTab = Overview

  /** The tabs the topic page renders itself. Guests' tabs are not here and are not knowable from here: they
    * come from the feature registry at navigation time.
    */
  val own: List[TopicTab] = List(Overview, Settings)

  /** Reads a tab back from a URL segment.
    *
    * Anything is accepted, including a segment naming a feature this build does not have: a bookmark can
    * outlive a tab, and `Tabs` falls back to its first tab when the selected id matches none of them, so an
    * unknown id lands on the overview of the right topic. Deciding which segments are *routable* is a
    * different question and is answered in `TopicsRoutes.routes`, where refusing an unknown one is what
    * leaves `/topics/t/messages` for the message browser.
    */
  def fromSegment(raw: Option[String]): TopicTab =
    raw.filter(_.nonEmpty).map(TopicTab(_)).getOrElse(Default)

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

  /** The cluster's topic list. */
  override def landingFor(cluster: kui.kernel.ClusterId): Page = TopicsPageId.List(cluster.value)

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
      // The decode is a partial function over *known* tab segments only, and that is load-bearing rather
      // than tidy. `/clusters/c/topics/t/messages` has the same shape as a tab URL, and this route is
      // registered before the message browser's. Decoding any fifth segment — the lenient
      // `TopicTab.fromSegment` behaviour — meant this route claimed that URL and drew the topic's Overview
      // tab, so the message browser was unreachable in the running product with every suite green.
      // Refusing an unknown segment hands the URL to the next feature that recognises it, and to the
      // shell's not-found page when none does. `TopicTab.fromSegment` stays lenient where leniency is
      // actually wanted: `decodePage`, which reads a stored history entry that may predate a tab.
      Route.applyPF[TopicsPageId, (String, String, String)](
        matchEncode = detailEncode(_.tab.segment.isDefined)(page =>
          (page.clusterId, page.topic, page.tab.segment.getOrElse(""))
        ),
        decode = {
          case (clusterId, topic, tab) if routableTabs.contains(tab) =>
            TopicsPageId.Detail(clusterId, topic, TopicTab.fromSegment(Some(tab)))
        },
        pattern = root / ClustersSegment / segment[String] / TopicsSegment / segment[String] /
          segment[String] / endOfSegments,
        basePath = uiPrefix
      )
    )

  /** Every tab segment this page will answer to: its own, plus one per feature that has registered a tab
    * against `FeatureSlots.TopicTabs`.
    *
    * A `def`, deliberately. `routes` is built while the shell is wiring itself up, and the feature registry
    * is filled in that same pass; reading it eagerly here would depend on which of the two ran first. This is
    * read when a URL is matched, which is always afterwards.
    */
  private def routableTabs: scala.collection.immutable.List[String] =
    TopicTab.own.flatMap(_.segment) ++
      GuestTabs.idsOf(FeatureRegistry.staticRoutes, id, FeatureSlots.TopicTabs)

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
        // A tab this build cannot route is dropped rather than carried, so a state written before a tab
        // existed - or by a deployment that had a feature this one does not - still decodes, and Back across
        // an upgrade lands on the overview of the right topic rather than on "not found". Carrying it would
        // be worse than dropping it: `encodePage` would then write a URL back out that nothing decodes.
        tab = cursor
          .get[Option[String]]("tab")
          .toOption
          .flatten
          .filter(routableTabs.contains)
          .map(TopicTab(_))
          .getOrElse(TopicTab.Default)
      } yield TopicsPageId.Detail(clusterId, topic, tab)
    else None
}
