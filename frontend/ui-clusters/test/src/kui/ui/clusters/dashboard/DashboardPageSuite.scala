package kui.ui.clusters.dashboard

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.gateway.contract.dto.ClusterOverviewDto
import kui.contracts.{ErrorEnvelope, Section}
import kui.kernel.ClusterId
import kui.ui.clusters.dashboard.ClusterFixtures.*
import kui.ui.clusters.ClustersQueries
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.DataTable
import kui.ui.kernel.css.KernelCss

/** The assertions that exist only in the DOM.
  *
  * The milestone's headline criterion is one of them, and it is a criterion precisely because it cannot be
  * satisfied by reading the code: the row of a cluster nobody can reach has to stay a real, focusable,
  * clickable link, or the user is stranded on the one screen that cannot explain what is wrong.
  */
class DashboardPageSuite extends FunSuite {

  /** An `ApiClient` that answers whichever call the page makes, when the test says so. */
  final private class FakeApi extends ApiClient {
    private val bus = new EventBus[Either[ApiError, ClusterOverviewDto]]
    val calls: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    def call[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I): EventStream[Either[ApiError, O]] = {
      calls.append(endpoint.info.name.getOrElse("?")): Unit
      // The page only ever calls the cluster list; anything else is a bug this cast would expose as one.
      bus.events.map(_.map(_.asInstanceOf[O]))
    }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def respond(response: ClusterOverviewDto): Unit = bus.writer.onNext(Right(response))
    def fail(failure: ApiError): Unit = bus.writer.onNext(Left(failure))
  }

  final private class Fixture {
    val api = new FakeApi
    val navigated: mutable.ListBuffer[ClusterId] = mutable.ListBuffer.empty

    val page: HtmlElement = DashboardPage(
      queries = new ClustersQueries(api),
      navigate = id => navigated.append(id): Unit,
      hrefFor = id => s"/ui/clusters/${id.value}/brokers",
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

  private def rowCells(root: dom.Element, cluster: String): List[String] = {
    val row = byTestId(root, s"cluster-row-$cluster")
    val tr = Option(row.closest("tr")).getOrElse(fail(s"the $cluster row is not inside a table row"))
    val cells = tr.querySelectorAll("td")
    (0 until cells.length).map(cells(_).textContent.trim).toList
  }

  private def headers(root: dom.Element): List[String] = {
    val found = root.querySelectorAll("th")
    (0 until found.length).map(found(_).textContent.trim).toList
  }

  private val threeClusters = response(
    row("dev", name = "Development"),
    row("stage", name = "Staging", section = stale()),
    row("prod", name = "Production", section = unavailable("connection refused"))
  )

  test("anUnavailableRowIsFocusableAndClickable") {
    // The milestone's criterion. Asserted in the DOM, because "we kept the link" is not something that can
    // be established by reading a render function.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      val link = byTestId(root, "cluster-row-prod-link")

      assertEquals(link.tagName.toLowerCase, "a")
      assertEquals(Option(link.getAttribute("href")), Some("/ui/clusters/prod/brokers"))
      // No `tabindex="-1"`, no `aria-disabled`: nothing has taken it out of the tab order.
      assertEquals(Option(link.getAttribute("tabindex")), None)
      assertEquals(Option(link.getAttribute("aria-disabled")), None)

      // `cancelable`, because a click that is not cancelable ignores `preventDefault` and jsdom then tries
      // to follow the link for real. A browser's own clicks are always cancelable.
      link.dispatchEvent(
        new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })
      ): Unit
      assertEquals(fixture.navigated.toList.map(_.value), List("prod"))
    }
  }

  test("anUnavailableRowShowsTheReasonVerbatim") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      val chip = byTestId(root, "cluster-row-prod-status")
      // Unedited and untruncated. CSS may ellipsise it; the text node may not, because the operator needs
      // the string they can search for.
      assertEquals(chip.textContent.trim, "Unavailable: connection refused")
    }
  }

  test("dataCellsOfAnUnavailableRowAreTheMissingMarkerNotZero") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      // The worst bug this screen can have: a dead cluster reporting `0 brokers` reads as a fact.
      val cells = rowCells(root, "prod").drop(2)
      assert(cells.forall(_ == DataTable.missing), cells.mkString(" | "))
    }
  }

  test("topicsIsAlwaysTheMissingMarker") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      // Even for a healthy cluster: the number needs a topic sweep that belongs to the topic service.
      assertEquals(rowCells(root, "dev").last, DataTable.missing)
    }
  }

  test("noThroughputColumnsExist") {
    // Not an empty column: a header promising bytes per second over an empty cell reads as "this cluster
    // has no traffic", which is a claim rather than an absence.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      val names = headers(root)
      assert(!names.exists(_.toLowerCase.contains("production")), names.mkString(", "))
      assert(!names.exists(_.toLowerCase.contains("consumption")), names.mkString(", "))
      assert(names.contains("Topics"), names.mkString(", "))
    }
  }

  test("aFailingListCallKeepsThePreviousRowsUnderTheStaleOverlay") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      val before = byTestId(root, "cluster-row-dev")

      fixture.api.fail(ApiError.Unreachable("gateway down"))

      // The same element, not a rebuilt one: the rows the user was looking at are still there.
      assert(byTestId(root, "cluster-row-dev") eq before)
      val region = byTestId(root, "clusters-table-region")
      assert(region.classList.contains(KernelCss.StaleActive), region.getAttribute("class"))
      val badge = byTestId(root, "clusters-table-region-stale-badge")
      assert(badge.textContent.contains("Last updated"), badge.textContent)
    }
  }

  test("theSummaryStripCountsMatchTheRowsOnScreen") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      assertEquals(byTestId(root, "cluster-summary-online").textContent.trim, "1online")
      assertEquals(byTestId(root, "cluster-summary-unavailable").textContent.trim, "2not online")
    }
  }

  test("theUnavailableOnlyToggleFiltersAndRestores") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(threeClusters)
      val toggle = byTestId(root, "clusters-unavailable-only")

      toggle.asInstanceOf[dom.html.Input].checked = true
      toggle.dispatchEvent(new dom.Event("input", new dom.EventInit { bubbles = true })): Unit
      assertEquals(root.querySelectorAll("[data-testid^='cluster-row-'][data-testid$='-link']").length, 1)

      toggle.asInstanceOf[dom.html.Input].checked = false
      toggle.dispatchEvent(new dom.Event("input", new dom.EventInit { bubbles = true })): Unit
      assertEquals(root.querySelectorAll("[data-testid^='cluster-row-'][data-testid$='-link']").length, 3)
    }
  }

  test("sortingByDiskPutsMissingValuesLastInBothDirections") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(
        response(
          row("small", section = Section.Ok(summary(disk = Some(1024L)), scrapedAt)),
          row("dead", section = unavailable("refused")),
          row("large", section = Section.Ok(summary(disk = Some(1024L * 1024 * 1024)), scrapedAt))
        )
      )

      def order(): List[String] = {
        val links = root.querySelectorAll("[data-testid$='-link']")
        (0 until links.length)
          .map(links(_).getAttribute("data-testid").stripPrefix("cluster-row-").stripSuffix("-link"))
          .toList
      }

      val diskHeader = headerButton(root, "Disk")
      diskHeader.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      assertEquals(order(), List("small", "large", "dead"))

      diskHeader.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      // Reversed, and the row with no value is still last. Otherwise switching the order buries the rows
      // that have data under a wall of em dashes.
      assertEquals(order(), List("large", "small", "dead"))
    }
  }

  test("theEmptyResponseRendersAnEmptyStateNotABlankTable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.respond(response())
      assert(Option(root.querySelector(s".${KernelCss.EmptyState}")).isDefined, root.outerHTML)
    }
  }

  test("aFirstLoadFailureShowsTheReasonRatherThanAnEmptyTable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.fail(ApiError.Unreachable("gateway down"))
      val error = byTestId(root, "clusters-error")
      assertEquals(Option(error.getAttribute("role")), Some("alert"))
      // Nothing was ever fetched, so there is nothing to put a stale badge over.
      assertEquals(Option(root.querySelector("[data-testid='clusters-table-region-stale-badge']")), None)
    }
  }

  private def headerButton(root: dom.Element, header: String): dom.Element = {
    val buttons = root.querySelectorAll("th button")
    (0 until buttons.length)
      .map(buttons(_))
      .find(_.textContent.contains(header))
      .getOrElse(fail(s"no sortable header called '$header'"))
  }
}
