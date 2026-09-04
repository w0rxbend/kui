package kui.ui.messages

import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.ui.kernel.feature.{FeatureId, Page}

/** The static half of the registration, which is the half that misbehaves before anything is downloaded.
  *
  * A broken route pattern is a 404 for a page that exists. A broken `history.state` codec is a Back button
  * that lands on "not found". Neither is visible from the feature's own screen, because by the time a screen
  * is on show both have already worked.
  */
final class MessagesRoutesSuite extends ScalaCheckSuite {

  private val prefix = "/ui"

  /** Waypoint resolves a relative URL against an origin. Nothing in these assertions depends on which. */
  private val Origin = "https://kui.example"

  private val clusterIds: Gen[String] = Gen.oneOf("prod-eu", "local", "quickstart", "a")
  private val topics: Gen[String] = Gen.oneOf("orders", "payments.dlq", "a_b-c", "__consumer_offsets")

  private given Arbitrary[MessagesPageId] = Arbitrary(
    for {
      cluster <- clusterIds
      topic <- topics
    } yield MessagesPageId.Browse(cluster, topic)
  )

  private def urlFor(page: MessagesPageId): Option[String] =
    MessagesRoutes.routes(prefix).flatMap(_.relativeUrlForPage(page)).headOption

  private def pageAt(url: String): Option[Page] =
    MessagesRoutes.routes(prefix).flatMap(route => route.pageForRelativeUrl(Origin, url)).headOption

  property("everyPageRoundTripsThroughItsUrl") {
    forAll { (page: MessagesPageId) => urlFor(page).flatMap(pageAt).contains(page) }
  }

  property("everyPageRoundTripsThroughItsHistoryStateCodec") {
    forAll { (page: MessagesPageId) =>
      val restored =
        for {
          encoded <- MessagesRoutes.encodePage(page)
          tag <- encoded.hcursor.get[String]("page").toOption
          decoded <- MessagesRoutes.decodePage(tag, encoded.hcursor)
        } yield decoded
      restored.contains(page)
    }
  }

  test("theBrowseUrlSitsUnderTheTopic") {
    // The screen belongs to a topic and its URL says so, which is what makes the Messages tab on a topic page
    // an ordinary link rather than a piece of navigation state.
    assertEquals(
      urlFor(MessagesPageId.Browse("local", "orders")),
      Some("/ui/clusters/local/topics/orders/messages")
    )
  }

  test("anUnknownSubPathIsNotClaimedByTheBrowseRoute") {
    // Without `endOfSegments` the pattern matches a prefix, so a mistyped sub-path would silently render the
    // browser instead of the shell's not-found page.
    assertEquals(pageAt("/ui/clusters/local/topics/orders/messages/extra"), None)
  }

  test("theFeatureIdIsMessagesAndTheServiceIsMessage") {
    // The two are not the same word, which is exactly why one is not guessed from the other: the capability
    // registry reports health per service and the shell dims a feature.
    assertEquals(MessagesRoutes.id, FeatureId.Messages)
    assertEquals(FeatureId.Messages.value, "messages")
    assertEquals(FeatureId.Messages.serviceId, "message")
    assertEquals(FeatureId.forService("message"), Some(FeatureId.Messages))
  }

  test("theNavEntryNeedsAClusterAndSortsBetweenTopicsAndConsumers") {
    assert(MessagesRoutes.nav.requiresCluster, "records belong to a topic on a cluster")
    assert(MessagesRoutes.nav.order > 200 && MessagesRoutes.nav.order < 300, MessagesRoutes.nav.order.toString)
  }

  test("anotherFeaturesPageIsRefused") {
    // Every registered `FeatureRoutes` is offered every page. A codec that answered for somebody else's tag
    // would decode a history entry into the wrong feature's screen.
    assertEquals(MessagesRoutes.decodePage("topics.list", Json.obj().hcursor), None)
    assertEquals(MessagesRoutes.encodePage(new Page {}), None)
  }

  test("aStateThatNamesNoTopicDoesNotDecode") {
    // Guessing a topic would read somebody else's records; refusing sends the user to the shell's own
    // not-found page, which is honest.
    val partial = Json.obj("clusterId" -> Json.fromString("local")).hcursor
    assertEquals(MessagesRoutes.decodePage("messages.browse", partial), None)
  }

  test("routesAreRegisteredExactlyOnce") {
    val patterns = MessagesRoutes.routes(prefix).map(_.toString)
    assertEquals(patterns.distinct.size, patterns.size)
    // Two: one topic's records, and the cluster-wide event track. The track page has no topic in its
    // address, so the two patterns cannot overlap and a URL can only ever match one of them.
    assertEquals(patterns.size, 2)
  }
}
