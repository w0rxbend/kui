package kui.ui.messages

import scala.annotation.nowarn

import com.raquo.waypoint.*
import io.circe.{HCursor, Json}

import kui.message.contract.BrowseAddress
import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, NavEntry, Page}

/** The pages this feature owns.
  *
  * A page is *data*: what the URL is built from and parsed into, and nothing about how anything is drawn
  * (ADR-012 amendment 2). That is what lets the shell hold a route without holding the code that renders it.
  *
  * Note what a page does **not** carry: where the browse starts, which partitions it reads, what it filters
  * on. Those live in the query string, read through `UrlParams`, for the reason ADR-032 gives — a browse is a
  * link somebody sends a colleague, and a link that reproduced only the topic would send the recipient
  * hunting. Keeping them out of the page type also means changing a filter does not push a history entry for
  * every keystroke.
  */
sealed trait MessagesPageId extends Page

object MessagesPageId {

  /** One topic's records.
    *
    * Both identifiers are `String` because a page has to survive a round trip through `history.state` and a
    * URL can hold anything a user types. They are validated where they are used, so a value that will not
    * parse renders the page's own empty container instead of failing to decode the whole history entry —
    * which would strand the Back button.
    */
  final case class Browse(clusterId: String, topic: String) extends MessagesPageId

  given CanEqual[MessagesPageId, MessagesPageId] = CanEqual.derived
}

/** The static half of this feature's registration: everything the shell must know **before** the feature's
  * JavaScript module has been downloaded.
  *
  * All of it is data — a label, a sort order, path shapes, a JSON tag — and naming it from the shell costs a
  * few bytes in `main.js` and pulls no feature code with it. `MessagesFeature` and every screen are reachable
  * only through the dynamic import, which is what `checkBundleShape` asserts on the linked output.
  */
object MessagesRoutes extends FeatureRoutes {

  private val BrowseTag = "messages.browse"

  private val ClustersSegment = BrowseAddress.ClustersSegment
  private val TopicsSegment = BrowseAddress.TopicsSegment
  private val MessagesSegment = BrowseAddress.MessagesSegment

  val id: FeatureId = FeatureId.Messages

  /** The landing page needs both a cluster and a topic, and neither is known from the sidebar.
    *
    * That is why `nav.requiresCluster` is true and why the entry is reached from a topic rather than from a
    * standing list: "messages" without a topic is not a screen anybody can draw.
    */
  val landing: Page = MessagesPageId.Browse("", "")

  val nav: NavEntry =
    NavEntry(
      featureId = id,
      label = Messages.Title,
      icon = () => Icon.dot,
      // Between Topics at 200 and Consumers at 300, which is where a reader looking for "the records" would
      // expect it: after the thing that holds them and before the thing that reads them.
      order = 250,
      requiresCluster = true,
      // Not in the sidebar. A browse names a cluster *and a topic*, and the sidebar knows only the cluster;
      // the entry used to be drawn anyway and pointed at `/ui/clusters//topics//messages`, which collapses
      // to a URL matching no route. The way in is the "Browse messages" link on a topic's page, which is the
      // only place in the product that knows which topic the user means.
      sidebar = false
    )

  def routes(uiPrefix: String): scala.collection.immutable.List[Route[? <: Page, ?]] =
    scala.collection.immutable.List(
      // `endOfSegments`, so that a mistyped sub-path produces the shell's not-found page rather than being
      // claimed by this pattern as a prefix match.
      Route.applyPF[MessagesPageId, (String, String)](
        matchEncode = browseEncode,
        decode = { case (clusterId, topic) => MessagesPageId.Browse(clusterId, topic) },
        pattern = root / ClustersSegment / segment[String] / TopicsSegment / segment[String] /
          MessagesSegment / endOfSegments,
        basePath = uiPrefix
      )
    )

  /** Waypoint types `matchEncode` as `PartialFunction[Any, Args]`, and Scala 3 refuses to destructure an
    * `Any` — a value that is not `Matchable` may be an opaque type whose runtime shape is not its static one.
    * Narrowing to `Matchable` once, here, is what lets the case be written as an ordinary one.
    */
  @nowarn("msg=unmatchable type Any")
  private def browseEncode: PartialFunction[Any, (String, String)] = {
    val claim: PartialFunction[Matchable, (String, String)] = { case page: MessagesPageId.Browse =>
      (page.clusterId, page.topic)
    }
    { case value: Matchable if claim.isDefinedAt(value) => claim(value) }
  }

  def encodePage(page: Page): Option[Json] =
    page match {
      case MessagesPageId.Browse(clusterId, topic) =>
        Some(
          Json.obj(
            "page" -> Json.fromString(BrowseTag),
            "clusterId" -> Json.fromString(clusterId),
            "topic" -> Json.fromString(topic)
          )
        )
      case _ => None
    }

  def decodePage(tag: String, cursor: HCursor): Option[Page] =
    if tag == BrowseTag then
      // Both identifying fields, or nothing: a stored state that names a cluster but no topic cannot be
      // turned into a browse, and guessing a topic would read somebody else's records.
      for {
        clusterId <- cursor.get[String]("clusterId").toOption
        topic <- cursor.get[String]("topic").toOption
      } yield MessagesPageId.Browse(clusterId, topic)
    else None
}
