package kui.ui.clusters.brokers

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import io.circe.Json
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.cluster.contract.dto.{BrokerConfigsResponse, BrokersResponse, LogDirsResponse}
import kui.contracts.capability.ReasonCode
import kui.contracts.cluster.{BrokerConfigEntryDto, BrokerDto, LogDirDto}
import kui.contracts.{ErrorEnvelope, Section}
import kui.kernel.BrokerId
import kui.ui.clusters.dashboard.ClusterFixtures
import kui.ui.clusters.{ClustersPageId, ClustersQueries, ClustersRoutes}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.css.KernelCss

class BrokerDetailPageSuite extends FunSuite {

  private val cluster = ClusterFixtures.clusterId("local")
  private val broker: BrokerId = ClusterFixtures.brokerId(1)

  /** The plaintext a server that stopped redacting would send. It must never reach the DOM. */
  private val secretToken = "hunter2-should-never-be-rendered"

  final private class FakeApi extends ApiClient {
    private val logDirs = new EventBus[Either[ApiError, LogDirsResponse]]
    private val configs = new EventBus[Either[ApiError, BrokerConfigsResponse]]
    private val brokers = new EventBus[Either[ApiError, BrokersResponse]]

    val calls: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    def call[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I): EventStream[Either[ApiError, O]] = {
      val name = endpoint.info.name.getOrElse("?")
      calls.append(name): Unit
      name match {
        case "clusters.logDirs" => logDirs.events.map(_.map(_.asInstanceOf[O]))
        case "clusters.broker.configs" => configs.events.map(_.map(_.asInstanceOf[O]))
        case "clusters.brokers" => brokers.events.map(_.map(_.asInstanceOf[O]))
        case other => fail(s"the broker page called $other, which it has no business calling")
      }
    }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def answerLogDirs(section: Section[List[LogDirDto]]): Unit =
      logDirs.writer.onNext(Right(LogDirsResponse(section)))

    def answerConfigs(section: Section[List[BrokerConfigEntryDto]]): Unit =
      configs.writer.onNext(Right(BrokerConfigsResponse(section)))

    def answerBrokers(section: Section[List[BrokerDto]]): Unit =
      brokers.writer.onNext(Right(BrokersResponse(section)))

    def callsTo(name: String): Int = calls.count(_ == name)
  }

  final private class Fixture(initial: BrokerTab = BrokerTab.LogDirs) {
    val api = new FakeApi
    val tab: Var[BrokerTab] = Var(initial)
    val selected: mutable.ListBuffer[BrokerTab] = mutable.ListBuffer.empty

    val page: HtmlElement = BrokerDetailPage(
      cluster = cluster,
      broker = broker,
      tab = tab.signal,
      selectTab = wanted => {
        selected.append(wanted): Unit
        tab.set(wanted)
      },
      queries = new ClustersQueries(api),
      clustersHref = "/ui/clusters",
      brokersHref = "/ui/clusters/local/brokers",
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
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def tabButton(root: dom.Element, label: String): dom.Element = {
    val buttons = root.querySelectorAll(s".${KernelCss.TabsTab}")
    (0 until buttons.length)
      .map(buttons(_))
      .find(_.textContent.contains(label))
      .getOrElse(fail(s"no tab called '$label'"))
  }

  private def click(element: dom.Element): Unit =
    element.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })): Unit

  private val oneDir =
    Section.Ok(List(LogDirDto(broker, "/var/lib/kafka/data", None, Some(1000L), Some(400L), 3, 12)), ClusterFixtures.scrapedAt)

  private val configs = Section.Ok(
    List(
      BrokerConfigEntryDto("log.retention.ms", Some("604800000"), "DYNAMIC_BROKER_CONFIG", false, false, None, Nil),
      // What the server sends for a sensitive setting: no value at all.
      BrokerConfigEntryDto("ssl.key.password", None, "STATIC_BROKER_CONFIG", true, true, None, Nil)
    ),
    ClusterFixtures.scrapedAt
  )

  test("theDefaultTabIsLogDirsAndItsUrlHasNoTabSegment") {
    assertEquals(BrokerTab.LogDirs.segment, None)
    assertEquals(BrokerTab.fromSegment(None), BrokerTab.LogDirs)
    // A hand-edited or truncated URL lands on the page rather than on "not found".
    assertEquals(BrokerTab.fromSegment(Some("nonsense")), BrokerTab.LogDirs)
    assertEquals(BrokerTab.fromSegment(Some("configs")), BrokerTab.Configs)
  }

  test("theConfigsCallIsNotMadeUntilTheConfigsTabIsOpened") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(oneDir)
      // Somebody who came to look at disk usage should neither wait for a describeConfigs call nor cause
      // one. This is invisible in the DOM, which is why it is asserted on the request count.
      assertEquals(fixture.api.callsTo("clusters.broker.configs"), 0)
      assertEquals(fixture.api.callsTo("clusters.logDirs"), 1)

      click(tabButton(root, BrokerTab.Configs.label))
      assertEquals(fixture.api.callsTo("clusters.broker.configs"), 1)
    }
  }

  test("openingConfigsNavigatesAndRendersTheConfigTable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(oneDir)
      click(tabButton(root, BrokerTab.Configs.label))

      // It asks to navigate rather than setting its own state, so the URL and the visible tab cannot become
      // two independent truths.
      assertEquals(fixture.selected.toList, List(BrokerTab.Configs))

      fixture.api.answerConfigs(configs)
      byTestId(root, "broker-config-row-log.retention.ms"): Unit
    }
  }

  test("aSensitiveValueIsNeverInTheDom") {
    val fixture = new Fixture(initial = BrokerTab.Configs)
    mounted(fixture) { root =>
      fixture.api.answerConfigs(
        Section.Ok(
          // A server that had stopped redacting would send this. It never should, and if it ever does this
          // test is what catches it.
          List(BrokerConfigEntryDto("ssl.key.password", Some(secretToken), "STATIC_BROKER_CONFIG", true, true, None, Nil)),
          ClusterFixtures.scrapedAt
        )
      )
      assert(!root.outerHTML.contains(secretToken), "a sensitive value reached the DOM")
      byTestId(root, "broker-config-row-ssl.key.password"): Unit
    }
  }

  test("noEditControlExistsInTheConfigsTable") {
    val fixture = new Fixture(initial = BrokerTab.Configs)
    mounted(fixture) { root =>
      fixture.api.answerConfigs(configs)
      val table = byTestId(root, "broker-configs-table")
      val buttons = table.querySelectorAll("button")
      // Not even a disabled one. A greyed-out Edit button is a promise the product has not made.
      val labels = (0 until buttons.length).map(buttons(_).textContent.toLowerCase).toList
      assert(!labels.exists(_.contains("edit")), labels.mkString(", "))
      assert(!labels.exists(_.contains("save")), labels.mkString(", "))
    }
  }

  test("noMetricsTabExists") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      // Absent, not disabled: a permanently greyed tab would be noise on every visit for several milestones.
      val tabs = root.querySelectorAll(s".${KernelCss.TabsTab}")
      val labels = (0 until tabs.length).map(tabs(_).textContent).toList
      assertEquals(labels.length, 2)
      assert(!labels.exists(_.toLowerCase.contains("metric")), labels.mkString(", "))
    }
  }

  test("aBrokerWithOneFailedLogDirStillShowsTheOthers") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(
        Section.Ok(
          List(
            LogDirDto(broker, "/data/one", None, Some(1000L), Some(400L), 3, 12),
            LogDirDto(broker, "/data/broken", Some("KafkaStorageException"), None, None, 0, 0),
            LogDirDto(broker, "/data/three", None, Some(1000L), Some(900L), 1, 2)
          ),
          ClusterFixtures.scrapedAt
        )
      )
      // Three directories, one of them an error — not one page-level failure, which would hide exactly the
      // fact the operator opened the page to find.
      byTestId(root, "broker-logdir-0"): Unit
      byTestId(root, "broker-logdir-2"): Unit
      assert(byTestId(root, "broker-logdir-1").textContent.contains("KafkaStorageException"))
      assert(root.textContent.contains("/data/broken"), "the failed directory lost its path")
    }
  }

  test("anUnavailableConfigsSectionLeavesTheLogDirsTabWorking") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(oneDir)
      click(tabButton(root, BrokerTab.Configs.label))
      fixture.api.answerConfigs(Section.Unavailable(ReasonCode.UpstreamAuth, "not authorized", None))
      assert(byTestId(root, "broker-configs-unavailable").textContent.contains("not authorized"))

      fixture.tab.set(BrokerTab.LogDirs)
      // The two tabs read two endpoints; one failing must not blank the other.
      assert(root.textContent.contains("/var/lib/kafka/data"))
    }
  }

  test("aStaleSectionPutsThatTabsBodyUnderTheOverlayAndNotTheWholePage") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(
        Section.Stale(
          List(LogDirDto(broker, "/var/lib/kafka/data", None, Some(1000L), Some(400L), 3, 12)),
          ClusterFixtures.scrapedAt,
          ReasonCode.UpstreamTimeout
        )
      )
      val region = byTestId(root, "broker-logdirs-region")
      assert(region.classList.contains(KernelCss.StaleActive), region.getAttribute("class"))
      // The heading and the identity strip are outside the overlay: only the tab's own body is old.
      assert(!byTestId(root, "broker-identity-host").closest(s".${KernelCss.StaleActive}").isInstanceOf[dom.Element])
    }
  }

  test("eachTabShowsItsOwnScrapedTime") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      // The two are fetched at different moments, so one timestamp covering both would be wrong for
      // whichever was fetched second.
      assertEquals(byTestId(root, "broker-logdirs-region-scraped-at").textContent, "Never refreshed")
      fixture.api.answerLogDirs(oneDir)
      assert(byTestId(root, "broker-logdirs-region-scraped-at").textContent.contains("2026-09-03 12:00:00"))
    }
  }

  test("theIdentityStripReadsTheBrokerFromTheListTheUserCameFrom") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.answerLogDirs(oneDir)
      // Dashes until it arrives, rather than the page waiting behind a spinner for a strip.
      assertEquals(byTestId(root, "broker-identity-host").textContent.trim, "—Host")
      fixture.api.answerBrokers(
        Section.Ok(
          List(
            BrokerDto(broker, "broker-1.example", 9092, Some("rack-a"), true, Some(30), Some(10), Some(30), None, None, Some(2048L), Some(4))
          ),
          ClusterFixtures.scrapedAt
        )
      )
      assertEquals(byTestId(root, "broker-identity-host").textContent.trim, "broker-1.exampleHost")
      assertEquals(byTestId(root, "broker-identity-rack").textContent.trim, "rack-aRack")
      assert(byTestId(root, "broker-identity-role").textContent.contains("controller"))
    }
  }

  test("aStoredStateWithNoTabDecodesToLogDirs") {
    // The upgrade-compatibility case: a `history.state` written before tabs existed must still decode, so
    // Back across a deployment upgrade lands on the default tab rather than on "not found".
    val stored = Json.obj(
      "page" -> Json.fromString("clusters.broker"),
      "clusterId" -> Json.fromString("local"),
      "brokerId" -> Json.fromInt(1)
    )
    val decoded = ClustersRoutes.decodePage("clusters.broker", stored.hcursor)
    assertEquals(decoded, Some(ClustersPageId.BrokerDetail("local", 1, None)))
    assertEquals(BrokerTab.fromSegment(None), BrokerTab.LogDirs)
  }

  test("theTabIsInTheUrlSoAConfigsLinkOpensOnConfigs") {
    val routes = ClustersRoutes.routes("/ui")
    val url = routes
      .flatMap(_.relativeUrlForPage(ClustersPageId.BrokerDetail("local", 1, Some("configs"))))
      .headOption
    assertEquals(url, Some("/ui/clusters/local/brokers/1/configs"))
  }
}
