package kui.ui.consumers

import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.ui.kernel.feature.{FeatureId, Page}

/** The static half of the registration, which is the half that misbehaves before anything is downloaded.
  *
  * A broken route pattern is a 404 for a page that exists. A broken `history.state` codec is a Back button
  * that lands on "not found". Neither is visible from the feature's own screens, because by the time a screen
  * is on show both have already worked.
  */
final class ConsumersRoutesSuite extends ScalaCheckSuite {

  private val prefix = "/ui"

  /** Waypoint resolves a relative URL against an origin. Nothing in these assertions depends on which. */
  private val Origin = "https://kui.example"

  private val clusterIds: Gen[String] = Gen.oneOf("prod-eu", "local", "quickstart", "a")

  /** Group ids Kafka genuinely permits, including the two shapes every naive path builder breaks on. */
  private val groupIds: Gen[String] =
    Gen.oneOf("orders-indexer", "billing.replay", "a_b-c", "team/one", "50%-sampler")

  private given Arbitrary[ConsumersPageId] = Arbitrary(
    Gen.oneOf(
      clusterIds.map(ConsumersPageId.List(_)),
      for {
        cluster <- clusterIds
        group <- groupIds
      } yield ConsumersPageId.Detail(cluster, group)
    )
  )

  private def urlFor(page: ConsumersPageId): Option[String] =
    ConsumersRoutes.routes(prefix).flatMap(_.relativeUrlForPage(page)).headOption

  private def pageAt(url: String): Option[Page] =
    ConsumersRoutes.routes(prefix).flatMap(route => route.pageForRelativeUrl(Origin, url)).headOption

  property("everyPageRoundTripsThroughItsUrl") {
    forAll { (page: ConsumersPageId) =>
      urlFor(page).flatMap(pageAt).contains(page)
    }
  }

  property("everyPageRoundTripsThroughItsHistoryStateCodec") {
    forAll { (page: ConsumersPageId) =>
      val restored =
        for {
          encoded <- ConsumersRoutes.encodePage(page)
          tag <- encoded.hcursor.get[String]("page").toOption
          decoded <- ConsumersRoutes.decodePage(tag, encoded.hcursor)
        } yield decoded
      restored.contains(page)
    }
  }

  test("theDetailUrlSitsUnderTheListUrl") {
    val list = urlFor(ConsumersPageId.List("local")).getOrElse(fail("the list page must have a URL"))
    val detail =
      urlFor(ConsumersPageId.Detail("local", "orders-indexer")).getOrElse(fail("a group must have a URL"))
    assert(detail.startsWith(list), s"$detail should sit under $list")
  }

  test("aGroupIdWithASlashDoesNotBecomeTwoSegments") {
    // Kafka permits `/` and `%` in a group id. Left unencoded, the first turns one page into a URL that
    // matches nothing and the second turns the whole path into a decode failure.
    val page = ConsumersPageId.Detail("local", "team/one")
    val url = urlFor(page).getOrElse(fail("a group id containing a slash must still produce a URL"))
    assert(!url.contains("team/one"), url)
    assertEquals(pageAt(url), Some(page))
  }

  test("anUnknownSubPathIsNotClaimedByTheListRoute") {
    // Without `endOfSegments` the list pattern matches a prefix, so a mistyped sub-path would silently
    // render the list instead of the shell's own not-found page.
    assertEquals(pageAt("/ui/clusters/local/consumer-groups/orders/extra"), None)
  }

  test("theFeatureIdIsConsumersAndTheServiceIsConsumer") {
    // The two are not the same word, which is exactly why one is not guessed from the other: the capability
    // registry reports health per service and the shell dims a feature.
    assertEquals(ConsumersRoutes.id, FeatureId.Consumers)
    assertEquals(FeatureId.Consumers.value, "consumers")
    assertEquals(FeatureId.Consumers.serviceId, "consumer")
    assertEquals(FeatureId.forService("consumer"), Some(FeatureId.Consumers))
  }

  test("theNavEntryNeedsAClusterAndSortsAfterTopics") {
    assert(ConsumersRoutes.nav.requiresCluster, "a consumer group belongs to a cluster")
    // And because it does, the sidebar has to be told which cluster: `landing` holds a placeholder id, and
    // a placeholder that reaches a URL becomes an empty segment, which collapses to a path matching no
    // route at all.
    assertEquals(
      ConsumersRoutes.landingFor(kui.kernel.ClusterId.unsafe("prod-eu")),
      ConsumersPageId.List("prod-eu")
    )
    assertEquals(ConsumersRoutes.nav.featureId, FeatureId.Consumers)
    assert(ConsumersRoutes.nav.order > 200, "Brokers, Topics, then Consumers")
  }

  test("anotherFeaturesPageIsRefused") {
    // Every registered `FeatureRoutes` is offered every page. A codec that answered for somebody else's tag
    // would decode a history entry into the wrong feature's screen.
    assertEquals(ConsumersRoutes.decodePage("topics.list", Json.obj().hcursor), None)
    assertEquals(ConsumersRoutes.encodePage(new Page {}), None)
  }

  test("routesAreRegisteredExactlyOnce") {
    // A duplicate pattern silently shadows and Waypoint will not say so.
    val patterns = ConsumersRoutes.routes(prefix).map(_.toString)
    assertEquals(patterns.distinct.size, patterns.size)
    assertEquals(patterns.size, 2)
  }
}
