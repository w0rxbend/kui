package kui.ui.consumers

import scala.annotation.nowarn

import com.raquo.waypoint.*
import io.circe.{HCursor, Json}

import kui.consumer.contract.ConsumerEndpoints
import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, FeatureSlots, GuestTab, NavEntry, Page}

/** The pages this feature owns.
  *
  * A page is *data*: what the URL is built from and parsed into, and nothing about how anything is drawn
  * (ADR-012 amendment 2). That is what lets the shell hold a route without holding the code that renders it.
  */
sealed trait ConsumersPageId extends Page

object ConsumersPageId {

  /** One cluster's consumer groups.
    *
    * The cluster id is a `String` rather than a `ClusterId` because a page has to survive a round trip
    * through `history.state` and a URL can hold anything a user types. It is validated where it is used, so a
    * value that will not parse renders the page's own empty state instead of failing to decode the whole
    * history entry — which would strand the Back button.
    */
  final case class List(clusterId: String) extends ConsumersPageId

  /** One group, whole. */
  final case class Detail(clusterId: String, groupId: String) extends ConsumersPageId

  given CanEqual[ConsumersPageId, ConsumersPageId] = CanEqual.derived
}

/** The static half of this feature's registration: everything the shell must know **before** the feature's
  * JavaScript module has been downloaded.
  *
  * All of it is data — a label, a sort order, path shapes, a JSON tag — and naming it from the shell costs a
  * few bytes in `main.js` and pulls no feature code with it. `ConsumersFeature` and every screen are
  * reachable only through the dynamic import, which is what `checkBundleShape` asserts on the linked output.
  *
  * The path segments come from `ConsumerEndpoints`, not from literals typed here. The screen's URL and the
  * service's URL are different things and are allowed to differ, but `consumer-groups` is one word in both,
  * and a rename on the server that left this file untouched would be a link that no longer matched what the
  * product calls the thing.
  */
object ConsumersRoutes extends FeatureRoutes {

  private val ListTag = "consumers.list"
  private val DetailTag = "consumers.detail"

  private val ClustersSegment = ConsumerEndpoints.ClustersSegment
  private val GroupsSegment = ConsumerEndpoints.GroupsSegment

  val id: FeatureId = FeatureId.Consumers

  /** The landing page needs a cluster, and the shell substitutes the chosen one before it draws the link. The
    * placeholder is never navigated to: `nav.requiresCluster` is true, so the entry is not offered until a
    * cluster has been chosen.
    */
  val landing: Page = ConsumersPageId.List("")

  /** The cluster's consumer groups. */
  override def landingFor(cluster: kui.kernel.ClusterId): Page = ConsumersPageId.List(cluster.value)

  val nav: NavEntry =
    NavEntry(
      featureId = id,
      label = Messages.Title,
      // A thunk, because a DOM node can only be in one place at a time and the navigation may render more
      // than one copy of an entry (a sidebar and a mobile menu).
      icon = () => Icon.dot,
      // After Topics at 200, which is the order the reference product's sidebar uses and the one operators
      // already have in their fingers: Brokers, Topics, Consumers.
      order = 300,
      // A consumer group belongs to a cluster; the entry means nothing until one has been chosen.
      requiresCluster = true
    )

  /** The Consumers tab on the topic page.
    *
    * Declared here, in the static half, rather than only as a `PanelContribution` on `ConsumersFeature`. The
    * heading is three characters of data and the topic page has to be able to draw it before this feature has
    * been downloaded — otherwise the tab exists only for users who have already been to the Consumers screen,
    * and a user who has not been there has no way to discover that the tab is a thing at all.
    *
    * The panel behind it is still in the dynamic half, and opening the tab is what fetches this module.
    */
  override val guestTabs: scala.collection.immutable.List[GuestTab] =
    scala.collection.immutable.List(
      GuestTab(host = FeatureId.Topics, slot = FeatureSlots.TopicTabs, label = Messages.TopicTabLabel)
    )

  def routes(uiPrefix: String): scala.collection.immutable.List[Route[? <: Page, ?]] =
    scala.collection.immutable.List(
      // `endOfSegments` throughout. Without it a pattern matches a prefix, so the list URL would also claim
      // every sub-path under it and a mistyped one would never produce a 404.
      Route[ConsumersPageId.List, String](
        encode = _.clusterId,
        decode = ConsumersPageId.List(_),
        pattern = root / ClustersSegment / segment[String] / GroupsSegment / endOfSegments,
        basePath = uiPrefix
      ),
      // `applyPF` rather than `apply`, because the list page above is also a `ConsumersPageId` and Waypoint
      // asks each route whether it can encode a given page. A total function here would answer "yes" for a
      // list page and then fail destructuring it.
      Route.applyPF[ConsumersPageId, (String, String)](
        matchEncode = detailEncode,
        decode = { case (clusterId, groupId) => ConsumersPageId.Detail(clusterId, groupId) },
        pattern = root / ClustersSegment / segment[String] / GroupsSegment / segment[String] / endOfSegments,
        basePath = uiPrefix
      )
    )

  /** The `matchEncode` for the detail pattern.
    *
    * Waypoint types `matchEncode` as `PartialFunction[Any, Args]`, and Scala 3 refuses to destructure an
    * `Any` — a value that is not `Matchable` may be an opaque type whose runtime shape is not its static one.
    * Narrowing to `Matchable` once, here, is what lets the case be written as an ordinary one.
    */
  @nowarn("msg=unmatchable type Any")
  private def detailEncode: PartialFunction[Any, (String, String)] = {
    val claim: PartialFunction[Matchable, (String, String)] = { case page: ConsumersPageId.Detail =>
      (page.clusterId, page.groupId)
    }
    { case value: Matchable if claim.isDefinedAt(value) => claim(value) }
  }

  def encodePage(page: Page): Option[Json] =
    page match {
      case ConsumersPageId.List(clusterId) =>
        Some(Json.obj("page" -> Json.fromString(ListTag), "clusterId" -> Json.fromString(clusterId)))
      case ConsumersPageId.Detail(clusterId, groupId) =>
        Some(
          Json.obj(
            "page" -> Json.fromString(DetailTag),
            "clusterId" -> Json.fromString(clusterId),
            "groupId" -> Json.fromString(groupId)
          )
        )
      case _ => None
    }

  def decodePage(tag: String, cursor: HCursor): Option[Page] =
    if tag == ListTag then cursor.get[String]("clusterId").toOption.map(ConsumersPageId.List(_))
    else if tag == DetailTag then
      // Both identifying fields, or nothing: a stored state that names a cluster but no group cannot be
      // turned into a group page, and guessing a group would show somebody else's data.
      for {
        clusterId <- cursor.get[String]("clusterId").toOption
        groupId <- cursor.get[String]("groupId").toOption
      } yield ConsumersPageId.Detail(clusterId, groupId)
    else None
}
