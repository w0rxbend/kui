package kui.ui.topics.list

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.contracts.capability.ReasonCode
import kui.contracts.paging.{PageDto, PageInfo}
import kui.contracts.topic.TopicRowDto
import kui.contracts.{ErrorEnvelope, Section}
import kui.kernel.{ClusterId, SortOrder, TopicName}
import kui.security.rbac.{Action, ClusterPermission, ClusterScope, RbacPolicy, Resource, ResourcePattern}
import kui.topic.contract.TopicSortField
import kui.topic.contract.dto.TopicsResponse
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.DataTable
import kui.ui.kernel.prefs.{Favourites, PreferenceStore}
import kui.ui.kernel.query.UrlParams
import kui.ui.kernel.state.Permissions
import kui.ui.topics.TopicsQueries

/** The topic list, in a document.
  *
  * The assertions that matter here are the ones about what the screen *says* when it does not have what it
  * would like to have: an em dash rather than a zero, an explanation rather than an empty table, and an empty
  * state rather than an error when the answer is "you may not look".
  */
final class TopicListPageSuite extends FunSuite {

  private val cluster = ClusterId.unsafe("prod-eu")
  private val fetchedAt = Instant.parse("2026-09-03T10:11:12Z")

  private def dto(
      name: String,
      messages: Option[Long] = Some(100L),
      offlinePartitions: Int = 0,
      internal: Boolean = false,
      sizeBytes: Option[Long] = Some(1024L)
  ): TopicRowDto =
    TopicRowDto(TopicName.unsafe(name), internal, 3, Some(3), 0, offlinePartitions, messages, sizeBytes)

  private def page(items: List[TopicRowDto], total: Long): PageDto[TopicRowDto] =
    PageDto(items, PageInfo(1, 25, Some(total), None))

  /** Answers the one call this screen makes, and fails loudly on any other: a page that started fetching
    * something it has no business fetching should not be able to do so quietly.
    */
  final private class FakeApi extends ApiClient {
    private val topics = new EventBus[Either[ApiError, TopicsResponse]]

    def call[I, O](
        endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
        input: I
    ): EventStream[Either[ApiError, O]] =
      endpoint.info.name match {
        case Some("topics.list") => topics.events.map(_.map(_.asInstanceOf[O]))
        case Some("topics.refresh") => EventStream.empty
        case other => fail(s"the topic list called $other, which it has no business calling")
      }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def send(section: Section[PageDto[TopicRowDto]], incomplete: Int = 0): Unit =
      topics.writer.onNext(Right(TopicsResponse(section, incomplete)))
  }

  /** An in-memory preference store, so one test's favourites cannot leak into the next. */
  final private class MemoryStore extends PreferenceStore {
    private val values = mutable.Map.empty[String, String]
    def read(key: String): Option[String] = values.get(key)
    def write(key: String, value: String): Unit = values.update(key, value): Unit
  }

  final private class Fixture(
      store: PreferenceStore = new MemoryStore,
      permissions: Permissions = everything
  ) {
    val api = new FakeApi
    val opened: mutable.ListBuffer[(ClusterId, String)] = mutable.ListBuffer.empty

    /** 600 pixels of table, set by hand because jsdom lays nothing out. */
    val viewport: Var[Int] = Var(600)

    val favourites = new Favourites("kui.test.favourites", store)

    val element: HtmlElement = TopicListPage(
      cluster = cluster,
      queries = new TopicsQueries(api),
      favourites = favourites,
      navigate = (c, topic) => opened.append(c -> topic): Unit,
      hrefFor = (c, topic) => s"/ui/clusters/${c.value}/topics/$topic",
      zone = Val("UTC"),
      now = () => Instant.parse("2026-09-03T10:20:00Z"),
      store = store,
      tableViewportHeight = viewport,
      permissions = permissions
    )
  }

  /** A role that may do everything, which is what the gateway hands a deployment with no authorization
    * configured. Without it every fixture would start with an empty grant list, which is the "not signed
    * in yet" state and not the default one.
    */
  private def everything: Permissions = {
    val store = new Permissions
    store.adopt(
      List(
        ClusterPermission(
          ClusterScope.Every,
          RbacPolicy.permission(Resource.Topic, ResourcePattern.compile(".*").toOption, Resource.Topic.allActions)
        )
      )
    )
    store
  }

  /** A reader: may look at topics and read their records, and nothing else. */
  private def reader: Permissions = {
    val store = new Permissions
    store.adopt(
      List(
        ClusterPermission(
          ClusterScope.Every,
          RbacPolicy.permission(
            Resource.Topic,
            ResourcePattern.compile(".*").toOption,
            Set(Action.TopicView, Action.TopicMessagesRead)
          )
        )
      )
    )
    store
  }

  private def mounted[A](fixture: Fixture)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, fixture.element)
    try check(fixture.element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def optionalTestId(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def rowElements(root: dom.Element): Int =
    root.querySelectorAll(s".${kui.ui.kernel.css.KernelCss.VirtualTableRow}").length

  private val threeTopics =
    Section.Ok(page(List(dto("orders"), dto("payments"), dto("audit")), 3L), fetchedAt)

  test("anAbsentCountRendersAnEmDashAndNotZero") {
    // The worst bug this screen can have. `0` reads as "this topic is empty", which ends an investigation
    // that should have started.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(List(dto("orders", messages = None, offlinePartitions = 3)), 1L), fetchedAt))
      val cell = byTestId(root, "topic-row-orders-messages")
      assert(cell.textContent.contains(DataTable.missing), cell.textContent)
      assert(!cell.textContent.contains("0"), cell.textContent)
      // And the chip that says why, with the full sentence on its title.
      val chip = byTestId(root, "topic-row-orders-offline")
      assertEquals(Option(chip.getAttribute("title")).map(_.contains("3")), Some(true))
    }
  }

  test("aCountOfZeroIsRenderedAsZeroAndNotAsAnEmDash") {
    // The other half of the same rule, and the one an over-eager em dash would break: a genuinely empty
    // topic must say it is empty.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(List(dto("orders", messages = Some(0L))), 1L), fetchedAt))
      val cell = byTestId(root, "topic-row-orders-messages")
      assert(cell.textContent.contains("0"), cell.textContent)
      assertEquals(optionalTestId(root, "topic-row-orders-offline"), None)
    }
  }

  test("tenThousandRowsPutFewerThanThirtyRowElementsInTheDocument") {
    // The virtualization, asserted where it is used and not only in the kernel. A component that windows
    // correctly and a page that hands it every row anyway is two correct halves and a slow screen.
    val fixture = new Fixture
    mounted(fixture) { root =>
      val many = List.tabulate(10_000)(index => dto(f"topic-$index%05d"))
      fixture.api.send(Section.Ok(page(many, 10_000L), fetchedAt))
      assert(rowElements(root) > 0, "the window is empty; the viewport height did not reach the table")
      assert(rowElements(root) < 30, s"expected a window, found ${rowElements(root)} rows")
    }
  }

  test("theCountComesFromTotalItemsAndNotFromTheRowsOnScreen") {
    // The reference product counts before applying the internal-topic filter, so its total overstates. Here
    // the server counts after every filter and the screen simply prints what it was told — which is also why
    // a page of 25 out of 10 000 says "10000 topics" rather than "25".
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(List(dto("orders")), 10_000L), fetchedAt))
      assertEquals(byTestId(root, "topics-count").textContent, "10000 topics")
    }
  }

  test("theCountIsSingularForOneTopic") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(List(dto("orders")), 1L), fetchedAt))
      assertEquals(byTestId(root, "topics-count").textContent, "1 topic")
    }
  }

  test("aStaleResponseKeepsTheRowsUnderTheOverlayAndDisablesRefresh") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(
        Section.Stale(page(List(dto("orders")), 1L), fetchedAt, ReasonCode.UpstreamTimeout)
      )
      assertEquals(rowElements(root), 1)
      assert(
        byTestId(root, "topics-refresh").asInstanceOf[dom.html.Button].disabled,
        "refresh promises the data will be current in a moment; while the upstream is failing that is false"
      )
      val region = byTestId(root, "topics-table-region")
      assertEquals(Option(region.getAttribute("aria-busy")), Some("true"))
    }
  }

  test("anUnavailableResponseWithNothingHeldRendersTheErrorRegionWithARetry") {
    // Never an empty table: a table with no rows in it is a claim that the cluster has no topics.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Unavailable(ReasonCode.UpstreamUnavailable, "no snapshot yet", None))
      val error = byTestId(root, "topics-error")
      assert(error.textContent.contains("no snapshot yet"), error.textContent)
      // The reason verbatim, so an operator has the string they can search for (ADR-032).
      assert(error.textContent.contains("UPSTREAM_UNAVAILABLE"), error.textContent)
      assertEquals(optionalTestId(root, "topics-retry").isDefined, true)
      assertEquals(optionalTestId(root, "topics-table-region"), None)
    }
  }

  test("aForbiddenResponseRendersItsOwnEmptyStateAndNotAnError") {
    // Forbidden is not an error (ADR-032): the request worked, and the answer is that this user may not see
    // it. A "Try again" would invite them to press a button that will do exactly the same thing.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Forbidden)
      assert(optionalTestId(root, "topics-forbidden").isDefined, root.outerHTML)
      assertEquals(optionalTestId(root, "topics-error"), None)
      assertEquals(optionalTestId(root, "topics-retry"), None)
    }
  }

  test("theEmptyResponseRendersAnEmptyStateNotABlankTable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(Nil, 0L), fetchedAt))
      assert(
        root.querySelector(s".${kui.ui.kernel.css.KernelCss.EmptyState}") != null,
        s"expected an empty state in ${root.outerHTML}"
      )
      // The header stays, so the columns still say what the table would have held.
      assert(root.querySelectorAll("thead th").length >= 6, "the column headers went away with the rows")
    }
  }

  test("aRowIsARealLinkWithAnHref") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(threeTopics)
      val link = byTestId(root, "topic-row-orders")
      assertEquals(Option(link.getAttribute("href")), Some("/ui/clusters/prod-eu/topics/orders"))
      link.dispatchEvent(
        // Cancelable, or `preventDefault` is ignored and jsdom tries to follow the link for real.
        new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })
      ): Unit
      assertEquals(fixture.opened.toList, List(cluster -> "orders"))
    }
  }

  test("anInternalTopicCarriesItsTag") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(page(List(dto("__consumer_offsets", internal = true)), 1L), fetchedAt))
      assert(optionalTestId(root, "topic-row-__consumer_offsets-internal").isDefined, root.outerHTML)
    }
  }

  test("favouritesArePinnedToTheTopOfThePage") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.favourites.toggle(cluster.value, "audit")
      fixture.api.send(threeTopics)
      val names = root
        .querySelectorAll(s".${kui.ui.topics.TopicsCss.NameLink}")
        .toList
        .map(_.textContent)
      assertEquals(names.headOption, Some("audit"))
      // And the rest keep the server's order.
      assertEquals(names.drop(1), List("orders", "payments"))
    }
  }

  test("theStarTogglesTheFavouriteAndSaysWhatItWillDo") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(threeTopics)
      val star = byTestId(root, "topic-row-orders-star")
      assertEquals(Option(star.getAttribute("aria-pressed")), Some("false"))
      assert(Option(star.getAttribute("aria-label")).exists(_.contains("Add")), star.outerHTML)
      star.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      assert(fixture.favourites.isFavourite(cluster.value, "orders"))
    }
  }

  test("theInternalToggleIsRememberedAcrossAMount") {
    // An operator who hides internal topics wants them hidden tomorrow. The URL still wins when it says
    // anything, which is what keeps a shared link showing its recipient what the sender saw.
    val store = new MemoryStore
    store.write(s"kui.topics.showInternal.${cluster.value}", "true")
    val fixture = new Fixture(store)
    mounted(fixture) { root =>
      val toggle = byTestId(root, "topics-internal-toggle").asInstanceOf[dom.html.Input]
      assert(toggle.checked, "the stored preference did not seed the switch")
    }
  }

  test("sortingWritesTheUrlAndResetsThePage") {
    // The sort is a *server* parameter, so it has to reach the query string: the server sorts ten thousand
    // rows this screen has never seen, of which it holds twenty-five. And it resets the page, because sorting
    // while on page nine asks for page nine of a differently ordered list, which is not where the user was.
    val fixture = new Fixture
    mounted(fixture) { root =>
      UrlParams.set(Map("page" -> Some("3")))
      fixture.api.send(threeTopics)
      val nameHeader = root
        .querySelectorAll(s".${kui.ui.kernel.css.KernelCss.TableSortButton}")
        .toList
        .map(_.asInstanceOf[dom.Element])
        .find(_.textContent.contains("Topic name"))
        .getOrElse(fail(s"no sortable Topic name header in ${root.outerHTML}"))

      nameHeader.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit

      val search = dom.window.location.search
      assert(search.contains("sort=name%3Aasc") || search.contains("sort=name:asc"), search)
      assert(!search.contains("page=3"), s"sorting did not reset the page: $search")
    }
  }

  test("theStarColumnCannotPutAnUnknownFieldInTheUrl") {
    // The star column has no sort field. If a click on it wrote its id, the next request would carry
    // `sort=favourite`, and the server refuses an unknown sort field with a 400 rather than ignoring it — so
    // the screen the milestone is judged on would go to an error page.
    assertEquals(TopicListPage.renderSort(kui.kernel.Sort(TopicColumns.FavouriteId, SortOrder.Asc)), None)
    assertEquals(
      TopicListPage.renderSort(kui.kernel.Sort("size", SortOrder.Desc)),
      Some("size:desc")
    )
  }

  test("aBookmarkedSortForAColumnThatNoLongerExistsIsNoSortRatherThanAnError") {
    assertEquals(TopicListPage.parseSort("statistics:asc"), None)
    assertEquals(TopicListPage.parseSort("name:sideways"), None)
    assertEquals(TopicListPage.parseSort("name"), None)
    assertEquals(
      TopicListPage.parseSort("name:asc"),
      Some(kui.kernel.Sort(TopicSortField.Name, SortOrder.Asc))
    )
  }

  test("noCreateButtonExists") {
    // Asserted by absence. The assertion that a feature has *not* been built early is the only thing that
    // keeps M5's scope out of M2 (risk R-11), and a disabled Create button would be a promise with a date on
    // it (DEVPLAN §10 D8).
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(threeTopics)
      val text = root.textContent.toLowerCase
      List("create", "delete", "purge", "clone", "download").foreach { forbidden =>
        assert(!text.contains(forbidden), s"the topic list offers '$forbidden', which is M5's")
      }
    }
  }

  test("thePageDoesNotFetchAnythingItHasNoBusinessFetching") {
    // The fake fails the test on any other endpoint, so merely mounting and rendering is the assertion.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(threeTopics)
      assertEquals(rowElements(root), 3)
    }
  }

  test("aReaderIsNotOfferedNewTopic") {
    // The defect this pins: `viewer`, holding TOPIC: [VIEW, MESSAGES_READ], was shown "New topic", which
    // opened a form, took a name and ended in KUI-FORBIDDEN from the gateway. Observed in a browser
    // against the quickstart with authentication turned on.
    mounted(new Fixture(permissions = reader)) { root =>
      val button = root.querySelector("[data-testid='topic-create-open']")
      assert(button != null, "the button should still be on the screen, carrying its reason")
      assertEquals(button.getAttribute("disabled"), "")
    }
  }

  test("someoneWhoMayCreateStillGetsTheButton") {
    mounted(new Fixture()) { root =>
      val button = root.querySelector("[data-testid='topic-create-open']")
      assert(button != null, "no create button")
      assertEquals(button.getAttribute("disabled"), null)
    }
  }
}
