package kui.ui.clusters.brokers

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.cluster.contract.dto.BrokersResponse
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.contracts.capability.ReasonCode
import kui.contracts.cluster.BrokerDto
import kui.contracts.{ErrorEnvelope, Section}
import kui.kernel.{BrokerId, ClusterId}
import kui.ui.clusters.ClustersQueries
import kui.ui.clusters.dashboard.ClusterFixtures
import kui.ui.clusters.dashboard.ClusterFixtures.*
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.DataTable
import kui.ui.kernel.css.KernelCss

class BrokersPageSuite extends FunSuite {

  private val cluster = clusterId("local")

  private def broker(
      id: Int,
      rack: Option[String] = Some("rack-a"),
      isController: Boolean = false,
      replicas: Option[Int] = Some(30),
      disk: Option[Long] = Some(1024L * 1024)
  ): BrokerDto =
    BrokerDto(
      id = ClusterFixtures.brokerId(id),
      host = s"broker-$id.example",
      port = 9090 + id,
      rack = rack,
      isController = isController,
      partitionCount = replicas,
      leaderCount = Some(10),
      inSyncReplicaCount = Some(30),
      replicaSkewPercent = None,
      leaderSkewPercent = None,
      diskUsageBytes = disk,
      segmentCount = Some(4)
    )

  /** Answers the two calls this page makes — the brokers list, and the cached cluster list the summary strip
    * reads — telling them apart by the endpoint's name.
    */
  final private class FakeApi extends ApiClient {
    private val brokersBus = new EventBus[Either[ApiError, BrokersResponse]]
    private val clustersBus = new EventBus[Either[ApiError, ClusterOverviewDto]]

    def call[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I): EventStream[Either[ApiError, O]] =
      endpoint.info.name match {
        case Some("clusters.brokers") => brokersBus.events.map(_.map(_.asInstanceOf[O]))
        case Some("clusters.list") => clustersBus.events.map(_.map(_.asInstanceOf[O]))
        case other => fail(s"the brokers page called $other, which it has no business calling")
      }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def brokers(section: Section[List[BrokerDto]]): Unit =
      brokersBus.writer.onNext(Right(BrokersResponse(section)))

    def clusters(response: ClusterOverviewDto): Unit = clustersBus.writer.onNext(Right(response))
  }

  final private class Fixture {
    // The page owns timers now (the refresh flow's), so it needs an owner. Killed with the mount.
    given owner: com.raquo.airstream.ownership.ManualOwner = new com.raquo.airstream.ownership.ManualOwner

    val api = new FakeApi
    val opened: mutable.ListBuffer[(ClusterId, BrokerId)] = mutable.ListBuffer.empty

    val page: HtmlElement = BrokersPage(
      cluster = cluster,
      queries = new ClustersQueries(api),
      openBroker = (c, b) => opened.append(c -> b): Unit,
      brokerHref = (c, b) => s"/ui/clusters/${c.value}/brokers/${b.value}",
      backHref = "/ui/clusters",
      zone = Val("UTC"),
      now = () => Instant.parse("2026-09-03T12:08:00Z")
    )
  }

  private def mounted[A](fixture: Fixture)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, fixture.page)
    try check(fixture.page.ref)
    finally {
      root.unmount(): Unit
      fixture.owner.killSubscriptions()
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def headers(root: dom.Element): List[String] = {
    val found = root.querySelectorAll("th")
    (0 until found.length).map(found(_).textContent.trim).toList
  }

  private val threeBrokers =
    Section.Ok(List(broker(1), broker(2, isController = true), broker(3)), scrapedAt)

  test("everyBrokerIsARowAndEveryRowLinksToItsBroker") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      assertEquals(root.querySelectorAll("[data-testid$='-link']").length, 3)

      val link = byTestId(root, "broker-row-2-link")
      assertEquals(Option(link.getAttribute("href")), Some("/ui/clusters/local/brokers/2"))
      link.dispatchEvent(
        // Cancelable, or `preventDefault` is ignored and jsdom tries to follow the link for real.
        new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })
      ): Unit
      assertEquals(fixture.opened.toList.map((c, b) => c.value -> b.value), List("local" -> 2))
    }
  }

  test("exactlyOneRowCarriesTheControllerTag") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      val tags = root.querySelectorAll(s".${KernelCss.Tag}")
      val controllerTags = (0 until tags.length).map(tags(_).textContent).count(_.contains("controller"))
      assertEquals(controllerTags, 1)
    }
  }

  test("noThroughputColumnsExist") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      val names = headers(root).map(_.toLowerCase)
      assert(!names.exists(_.contains("bytes in")), names.mkString(", "))
      assert(!names.exists(_.contains("bytes out")), names.mkString(", "))
      assert(names.exists(_.contains("rack")), names.mkString(", "))
    }
  }

  test("theSkewFigureCarriesItsExplanation") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      // A bare "12.4 %" in a column called "Leader skew" is a number nobody can act on until they are told
      // what it is a percentage of.
      val tooltip = Option(root.querySelector(s".${KernelCss.Tooltip}"))
        .getOrElse(fail("no explanation is attached to the skew figures"))
      assert(tooltip.textContent.contains("above the average"), tooltip.textContent)
    }
  }

  test("thresholdColoursAppearOnlyAboveTheThreshold") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      // 31 against a mean of 30.33 is about 2 %: real, and well below the warning threshold.
      fixture.api.brokers(
        Section.Ok(List(broker(1, replicas = Some(31)), broker(2), broker(3)), scrapedAt)
      )
      val figure = byTestId(root, "broker-row-1-replicaSkew")
      assert(!figure.classList.contains(KernelCss.ThresholdOver), figure.getAttribute("class"))
      assert(!figure.classList.contains(KernelCss.ThresholdCritical), figure.getAttribute("class"))
    }
  }

  test("aStaleSectionKeepsTheRowsUnderTheOverlay") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(Section.Stale(List(broker(1), broker(2)), scrapedAt, ReasonCode.UpstreamTimeout))
      assertEquals(root.querySelectorAll("[data-testid$='-link']").length, 2)
      val region = byTestId(root, "brokers-table-region")
      assert(region.classList.contains(KernelCss.StaleActive), region.getAttribute("class"))
    }
  }

  test("anUnavailableSectionShowsTheReasonAndNoFabricatedRows") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", None))
      val notice = byTestId(root, "brokers-unavailable")
      assert(notice.textContent.contains("connection refused"), notice.textContent)
      // Not an empty table: a broker table with no rows in it is a claim that the cluster has no brokers.
      assertEquals(Option(root.querySelector("[data-testid='brokers-table']")), None)
    }
  }

  test("anEmptyBrokerListRendersAnEmptyStateNotABlankTable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(Section.Ok(Nil, scrapedAt))
      assert(Option(root.querySelector(s".${KernelCss.EmptyState}")).isDefined, root.outerHTML)
    }
  }

  test("breadcrumbsNameTheClusterAndLinkBackToTheDashboard") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      val back = Option(root.querySelector("nav a")).getOrElse(fail("no breadcrumb link"))
      assertEquals(Option(back.getAttribute("href")), Some("/ui/clusters"))
      assert(root.textContent.contains("local"), "the heading does not name the cluster")
    }
  }

  test("theScrapedTimeIsAlwaysOnScreen") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      // Before anything arrives, it says so rather than inventing a time.
      assertEquals(byTestId(root, "brokers-scraped-at").textContent, "Never refreshed")
      fixture.api.brokers(threeBrokers)
      assert(byTestId(root, "brokers-scraped-at").textContent.contains("2026-09-03 12:00:00 UTC+00:00"))
    }
  }

  test("aBrokerWhoseDisksCouldNotBeReadShowsTheMissingMarkerNotZeroBytes") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      // The partly-authorised cluster: every broker is still a row, and the page shows no error at all.
      fixture.api.brokers(Section.Ok(List(broker(1, disk = None)), scrapedAt))
      val cells = root.querySelectorAll("td")
      val texts = (0 until cells.length).map(cells(_).textContent.trim).toList
      assert(texts.contains(DataTable.missing), texts.mkString(" | "))
      assert(!texts.exists(_.contains("0 B")), texts.mkString(" | "))
      assertEquals(Option(root.querySelector("[data-testid='brokers-unavailable']")), None)
    }
  }

  test("theSummaryStripReadsTheClusterVersionFromTheSharedCacheWithoutAskingTheCluster") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.brokers(threeBrokers)
      assertEquals(byTestId(root, "broker-summary-version").textContent.trim, s"${DataTable.missing}version")

      fixture.api.clusters(response(row("local")))
      assertEquals(byTestId(root, "broker-summary-version").textContent.trim, "4.0.0version")
      assertEquals(byTestId(root, "broker-summary-controller").textContent.trim, "2controller")
    }
  }
}
