package kui.ui.topics

import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import com.raquo.waypoint.Route

import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRegistry, FeatureRoutes, FeatureSlots, GuestTab, NavEntry, Page}

/** The static half of the registration, which is the half that misbehaves before anything is downloaded.
  *
  * A broken route pattern is a 404 for a page that exists. A broken `history.state` codec is a Back button
  * that lands on "not found". Neither is visible from the feature's own screens, because by the time a screen
  * is on show both have already worked.
  */
final class TopicsRoutesSuite extends ScalaCheckSuite {

  private val prefix = "/ui"

  /** Waypoint resolves a relative URL against an origin. Nothing in these assertions depends on which. */
  private val Origin = "https://kui.example"

  private val clusterIds: Gen[String] = Gen.oneOf("prod-eu", "local", "quickstart", "a")
  private val topicNames: Gen[String] = Gen.oneOf("orders", "payments.dlq", "a_b-c", "__consumer_offsets")
  private val tabs: Gen[TopicTab] = Gen.oneOf(TopicTab.own)

  private given Arbitrary[TopicsPageId] = Arbitrary(
    Gen.oneOf(
      clusterIds.map(TopicsPageId.List(_)),
      for {
        cluster <- clusterIds
        topic <- topicNames
        tab <- tabs
      } yield TopicsPageId.Detail(cluster, topic, tab)
    )
  )

  private def urlFor(page: TopicsPageId): Option[String] =
    TopicsRoutes.routes(prefix).flatMap(_.relativeUrlForPage(page)).headOption

  private def pageAt(url: String): Option[Page] =
    TopicsRoutes.routes(prefix).flatMap(route => route.pageForRelativeUrl(Origin, url)).headOption

  property("everyPageRoundTripsThroughItsUrl") {
    forAll { (page: TopicsPageId) =>
      urlFor(page).flatMap(pageAt).contains(page)
    }
  }

  property("everyPageRoundTripsThroughItsHistoryStateCodec") {
    forAll { (page: TopicsPageId) =>
      TopicsRoutes
        .encodePage(page)
        .flatMap { json =>
          val cursor = json.hcursor
          cursor.get[String]("page").toOption.flatMap(tag => TopicsRoutes.decodePage(tag, cursor))
        }
        .contains(page)
    }
  }

  test("theRoutePatternsEndOfSegments") {
    // Without `endOfSegments` a pattern claims its own sub-paths, and a mistyped URL never 404s: it silently
    // resolves to the page above it, so the user sees the topic list when they asked for a topic.
    assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/settings/nonsense"), None)
    assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/settings/deeper/still"), None)
  }

  test("theTablessPatternRefusesAPageThatNamesATab") {
    // If it did not, `Detail(_, _, Settings)` would encode to the tabless URL and a link to a topic's
    // configuration would open on its overview — with nothing anywhere reporting a problem.
    val settings = TopicsPageId.Detail("prod-eu", "orders", TopicTab.Settings)
    assertEquals(urlFor(settings), Some(s"$prefix/clusters/prod-eu/topics/orders/settings"))
    val overview = TopicsPageId.Detail("prod-eu", "orders", TopicTab.Overview)
    assertEquals(urlFor(overview), Some(s"$prefix/clusters/prod-eu/topics/orders"))
  }

  test("aFifthSegmentThatIsNotATabIsNotClaimed") {
    // This route used to decode *any* fifth segment to the Overview tab, on the grounds that a bookmark can
    // outlive a tab. The cost was invisible until another feature wanted a URL of the same shape:
    // `/clusters/c/topics/t/messages` is the message browser's address, this route is registered first, and
    // it swallowed the URL and drew the topic's overview. The message browser was unreachable in the running
    // product with every suite in the repository green. A segment that is not one of this page's own tabs
    // belongs to whoever does recognise it, and to the shell's not-found page when nobody does.
    assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/messages"), None)
    assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/statistics"), None)
    // The tab that does exist still decodes, from the same pattern.
    assertEquals(
      pageAt(s"$prefix/clusters/prod-eu/topics/orders/settings"),
      Some(TopicsPageId.Detail("prod-eu", "orders", TopicTab.Settings))
    )
  }

  test("aTabAnotherFeatureContributedIsAddressable") {
    // The defect: the topic page's tab strip is not all its own. The consumers feature contributes a
    // "Consumers" tab through `FeatureSlots.TopicTabs`, and the tab was rendered, opened and drawn correctly
    // - while the address bar went on saying the page was on Overview. Refreshing lost the tab, a copied link
    // sent the recipient to the wrong screen, and typing `…/orders/consumers` produced "That page does not
    // exist" beside a `…/orders/settings` that worked.
    //
    // The registry is installed here because that is exactly what the router reads: which segments are
    // routable is a function of which features this build shipped, not of a list written in this file.
    FeatureRegistry.install(Map.empty, List(TopicsRoutes, GuestFeatureRoutes))
    try {
      val consumers = TopicsPageId.Detail("prod-eu", "orders", TopicTab("consumers"))

      assertEquals(urlFor(consumers), Some(s"$prefix/clusters/prod-eu/topics/orders/consumers"))
      assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/consumers"), Some(consumers))
      // And the segment that is another feature's *page* rather than a tab on this one is still refused, so
      // the message browser keeps its URL.
      assertEquals(pageAt(s"$prefix/clusters/prod-eu/topics/orders/messages"), None)
    } finally FeatureRegistry.install(Map.empty, Nil)
  }

  test("aStoredTabThisBuildCannotRouteFallsBackToOverview") {
    // A `history.state` entry written by a deployment that had a feature this one does not. Carrying the tab
    // through would be worse than dropping it: `encodePage` would write a URL back out that no route decodes,
    // so Back would land on "not found" instead of on the topic.
    val stored = Json.obj(
      "page" -> Json.fromString("topics.detail"),
      "clusterId" -> Json.fromString("prod-eu"),
      "topic" -> Json.fromString("orders"),
      "tab" -> Json.fromString("consumers")
    )

    FeatureRegistry.install(Map.empty, List(TopicsRoutes))
    try
      assertEquals(
        TopicsRoutes.decodePage("topics.detail", stored.hcursor),
        Some(TopicsPageId.Detail("prod-eu", "orders", TopicTab.Overview))
      )
    finally FeatureRegistry.install(Map.empty, Nil)
  }

  test("theNavEntryLandsOnTheChosenClustersTopicList") {
    // `landing` carries a placeholder cluster id, and a placeholder that reaches the sidebar becomes an
    // empty path segment, which collapses: `/ui/clusters//topics` is `/ui/clusters/topics` and matches no
    // route. `landingFor` is what the shell asks instead once a cluster has been chosen.
    assertEquals(
      TopicsRoutes.landingFor(kui.kernel.ClusterId.unsafe("prod-eu")),
      TopicsPageId.List("prod-eu")
    )
  }

  test("anUnknownTabInAStoredStateFallsBackToOverview") {
    val stored = Json.obj(
      "page" -> Json.fromString("topics.detail"),
      "clusterId" -> Json.fromString("prod-eu"),
      "topic" -> Json.fromString("orders"),
      "tab" -> Json.fromString("statistics")
    )
    assertEquals(
      TopicsRoutes.decodePage("topics.detail", stored.hcursor),
      Some(TopicsPageId.Detail("prod-eu", "orders", TopicTab.Overview))
    )
  }

  test("aStoredStateMissingTheTopicIsRefusedRatherThanGuessed") {
    // Guessing a topic would show the user somebody else's data under the name they asked for.
    val stored = Json.obj("page" -> Json.fromString("topics.detail"), "clusterId" -> Json.fromString("p"))
    assertEquals(TopicsRoutes.decodePage("topics.detail", stored.hcursor), None)
  }

  test("anotherFeaturesStoredStateIsNotClaimed") {
    // The shell tries each contributor in turn, so a codec that answered for a tag it does not own would
    // steal another feature's Back button.
    assertEquals(TopicsRoutes.decodePage("clusters.overview", Json.obj().hcursor), None)
    assertEquals(TopicsRoutes.encodePage(new Page {}), None)
  }

  test("theNavEntryNamesTheTopicFeatureId") {
    assertEquals(TopicsRoutes.nav.featureId, FeatureId.Topics)
    assertEquals(TopicsRoutes.id, FeatureId.Topics)
    // The service behind the feature is `topic`, singular, and the shell dims the entry from that name.
    assertEquals(FeatureId.Topics.serviceId, "topic")
    // Topics belong to a cluster, so the entry means nothing until one has been chosen.
    assert(TopicsRoutes.nav.requiresCluster)
  }

  test("theDeploymentPrefixIsHonoured") {
    // A deployment mounted under `/kafka` must not produce links to `/ui`; the prefix is a parameter for
    // exactly this reason.
    val mounted = TopicsRoutes
      .routes("/kafka/ui")
      .flatMap(_.relativeUrlForPage(TopicsPageId.List("prod-eu")))
      .headOption
    assertEquals(mounted, Some("/kafka/ui/clusters/prod-eu/topics"))
  }
}

/** A stand-in for the consumers feature's static registration: enough of one to contribute a tab.
  *
  * A top-level object rather than a value inside the suite, because this is what the shell installs — a
  * feature's static half, declared once per feature and reachable before anything is downloaded.
  */
object GuestFeatureRoutes extends FeatureRoutes {

  val id: FeatureId = FeatureId.Consumers

  val landing: Page = TopicsPageId.List("")

  val nav: NavEntry =
    NavEntry(featureId = id, label = "Consumers", icon = () => Icon.dot, order = 400, requiresCluster = true)

  def routes(uiPrefix: String): scala.collection.immutable.List[Route[? <: Page, ?]] =
    scala.collection.immutable.List.empty

  override def guestTabs: scala.collection.immutable.List[GuestTab] =
    scala.collection.immutable.List(
      GuestTab(host = FeatureId.Topics, slot = FeatureSlots.TopicTabs, label = "Consumers")
    )

  def encodePage(page: Page): Option[Json] = None

  def decodePage(tag: String, cursor: io.circe.HCursor): Option[Page] = None
}
