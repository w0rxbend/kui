package kui.ui.topics.detail

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.contracts.capability.ReasonCode
import kui.contracts.topic.{PartitionDto, ReplicaDto, TopicConfigEntryDto, TopicDetailDto, TopicRowDto}
import kui.contracts.{ErrorEnvelope, Section}
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.error.ErrorCode
import kui.kernel.{BrokerId, ClusterId, PartitionId, TopicName}
import kui.topic.contract.dto.{TopicConfigResponse, TopicConfigViewDto}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.DataTable
import kui.ui.topics.{Messages, TopicTab, TopicsQueries}

/** The topic detail page, the partition table and the settings tab, in a document.
  *
  * The three suites the task spec names are one file, because they share a fixture: every one of them needs
  * the same fake API and the same mount, and three copies of that would be three places for the fake to drift
  * from what the page actually calls.
  */
final class TopicDetailPageSuite extends FunSuite {

  private val cluster = ClusterId.unsafe("prod-eu")
  private val topic = TopicName.unsafe("orders")
  private val fetchedAt = Instant.parse("2026-09-03T10:11:12Z")

  private def replica(broker: Int, leader: Boolean = false, inSync: Boolean = true): ReplicaDto =
    ReplicaDto(BrokerId.unsafe(broker), leader, inSync)

  private def partition(
      id: Int,
      leader: Option[Int] = Some(1),
      replicas: List[ReplicaDto] = List(ReplicaDto(BrokerId.unsafe(1), true, true)),
      messages: Option[Long] = Some(100L)
  ): PartitionDto =
    PartitionDto(
      PartitionId.unsafe(id),
      leader.map(BrokerId.unsafe),
      replicas,
      Some(0L),
      messages.map(_ + 0L),
      messages,
      Some(1024L)
    )

  private def detail(
      partitions: List[PartitionDto] = List(partition(0)),
      messages: Option[Long] = Some(100L)
  ): TopicDetailDto =
    TopicDetailDto(
      row = TopicRowDto(topic, false, partitions.size, Some(3), 0, 0, messages, Some(2048L)),
      partitions = partitions,
      cleanupPolicy = Some("delete"),
      segmentCount = Some(4)
    )

  private def overviewOf(section: Section[TopicDetailDto]): TopicOverviewDto =
    TopicOverviewDto(
      topic = section,
      consumerGroups = Section.NotConfigured,
      connectors = Section.NotConfigured,
      acls = Section.NotConfigured,
      schemas = Section.NotConfigured,
      generatedAt = fetchedAt
    )

  /** Counts what each endpoint was asked for, so "the Settings tab issues no request until it is opened" is
    * an assertion about a number rather than about a rendering.
    */
  final private class FakeApi extends ApiClient {
    private val overview = new EventBus[Either[ApiError, TopicOverviewDto]]
    private val config = new EventBus[Either[ApiError, TopicConfigResponse]]
    val calls: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)

    def call[I, O](
        endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
        input: I
    ): EventStream[Either[ApiError, O]] = {
      val name = endpoint.info.name.getOrElse("?")
      calls.update(name, calls(name) + 1)
      name match {
        case "gateway.topic.overview" => overview.events.map(_.map(_.asInstanceOf[O]))
        case "topics.config" => config.events.map(_.map(_.asInstanceOf[O]))
        case other => fail(s"the topic detail page called $other, which it has no business calling")
      }
    }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def send(section: Section[TopicDetailDto]): Unit =
      overview.writer.onNext(Right(overviewOf(section)))

    /** Named `refuse` and not `fail`: a method called `fail` on a fixture shadows MUnit's own inside
      * this class, and the first symptom is a type error a hundred lines away.
      */
    def refuse(error: ApiError): Unit = overview.writer.onNext(Left(error))

    def settings(view: TopicConfigViewDto): Unit =
      config.writer.onNext(Right(TopicConfigResponse(Section.Ok(view, fetchedAt))))
  }

  final private class Fixture {
    val api = new FakeApi
    val tab: Var[TopicTab] = Var(TopicTab.Overview)
    val chosen: mutable.ListBuffer[TopicTab] = mutable.ListBuffer.empty

    /** 600 pixels of partition table, set by hand because jsdom lays nothing out. */
    val viewport: Var[Int] = Var(600)

    val element: HtmlElement = TopicDetailPage(
      cluster = cluster,
      topic = topic,
      tab = tab.signal,
      queries = new TopicsQueries(api),
      onTab = wanted => chosen.append(wanted): Unit,
      zone = Val("UTC"),
      backHref = "/ui/clusters/prod-eu/topics",
      now = () => Instant.parse("2026-09-03T10:20:00Z"),
      partitionViewportHeight = viewport
    )
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

  private def partitionRows(root: dom.Element): Int =
    root.querySelectorAll(s".${kui.ui.kernel.css.KernelCss.VirtualTableRow}").length

  // --- The page -------------------------------------------------------------------------------

  test("theIndicatorStripAndThePartitionTableAreDrawnFromOneRequest") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(detail(List(partition(0), partition(1))), fetchedAt))
      assert(optionalTestId(root, "topic-indicators").isDefined, root.outerHTML)
      assertEquals(partitionRows(root), 2)
      assertEquals(fixture.api.calls("gateway.topic.overview"), 1)
    }
  }

  test("theSettingsQueryIsNotIssuedUntilTheTabIsOpened") {
    // `Tabs` builds a panel from a thunk when its tab is selected. That laziness is why the tab is in the
    // URL rather than in a local `Var`, and it is the reason a visit to a topic costs one request and not
    // two.
    val fixture = new Fixture
    mounted(fixture) { _ =>
      fixture.api.send(Section.Ok(detail(), fetchedAt))
      assertEquals(fixture.api.calls("topics.config"), 0)

      fixture.tab.set(TopicTab.Settings)
      fixture.api.settings(TopicConfigViewDto.Entries(Nil))
      assertEquals(fixture.api.calls("topics.config"), 1)
    }
  }

  test("switchingATabReportsItSoTheUrlCanFollow") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(detail(), fetchedAt))
      val settings = root
        .querySelectorAll(s".${kui.ui.kernel.css.KernelCss.TabsTab}")
        .toList
        .map(_.asInstanceOf[dom.Element])
        .find(_.textContent.contains(Messages.TabSettings))
        .getOrElse(fail(s"no Settings tab in ${root.outerHTML}"))
      settings.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      assertEquals(fixture.chosen.toList, List(TopicTab.Settings))
    }
  }

  test("absentSectionsRenderNothingButLeaveTheirSlot") {
    // KU-013's slot, asserted rather than asserted-to-exist-later: M4's registration is visible in a test
    // the moment it lands. Four permanent "unavailable" panels would train an operator to ignore the colour
    // that matters, so the containers are empty rather than filled with a placeholder.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(detail(), fetchedAt))
      List("consumer-groups", "connectors", "acls", "schemas").foreach { section =>
        val slot = byTestId(root, s"topic-panel-$section")
        assertEquals(slot.childElementCount, 0, s"the $section slot is not empty")
        assertEquals(slot.textContent, "", s"the $section slot says something")
      }
    }
  }

  test("theSlotTestIdIsTheGatewaysSectionNameHyphenated") {
    assertEquals(TopicDetailPage.slotTestId("consumerGroups"), "topic-panel-consumer-groups")
    assertEquals(TopicDetailPage.slotTestId("acls"), "topic-panel-acls")
    // Every section the gateway names has a slot, so a section added there cannot be silently unrendered.
    val expected = TopicOverviewDto.sections.filterNot(_ == TopicOverviewDto.TopicSection)
    assertEquals(expected.size, 4)
  }

  test("noMessagesOrConsumersTabIsRenderedByThisPage") {
    // M3 and M4 register those. A tab rendered here — disabled or not — would be a promise with a date on
    // it, and would also be the thing M3 has to delete in the milestone it is trying to add a tab in.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(detail(), fetchedAt))
      val labels = root
        .querySelectorAll(s".${kui.ui.kernel.css.KernelCss.TabsTab}")
        .toList
        .map(_.textContent.trim)
      assertEquals(labels, List(Messages.TabOverview, Messages.TabSettings))
    }
  }

  test("anUnavailableTopicSectionRendersTheErrorAndKeepsTheHeading") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Unavailable(ReasonCode.UpstreamUnavailable, "no snapshot yet", None))
      // The user still knows which topic they were looking at.
      assertEquals(byTestId(root, "topic-heading").textContent, "orders")
      val error = byTestId(root, "topic-error")
      assert(error.textContent.contains("no snapshot yet"), error.textContent)
      assertEquals(optionalTestId(root, "topic-indicators"), None)
    }
  }

  test("aStaleTopicSectionIsGreyedAndEverythingStaysReadable") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Stale(detail(), fetchedAt, ReasonCode.UpstreamTimeout))
      val region = byTestId(root, "topic-detail-region")
      assertEquals(Option(region.getAttribute("aria-busy")), Some("true"))
      assert(optionalTestId(root, "topic-indicators").isDefined, "stale data is greyed, not removed")
    }
  }

  test("a404RendersNoSuchTopicAndNotAnEmptyPage") {
    // And not a retry: trying again will not create the topic. It is also read from the error *code* and
    // not from a status, because by the time a caller holds an `ApiError` the status is gone.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.refuse(ApiError.Envelope(ErrorCode.TopicNotFound.wire, "no such topic", Nil, "c-1", false))
      val missing = byTestId(root, "topic-not-found")
      assert(missing.textContent.contains("orders"), missing.textContent)
      assertEquals(optionalTestId(root, "topic-error"), None)
      assertEquals(byTestId(root, "topic-heading").textContent, "orders")
    }
  }

  test("aForbiddenTopicSectionIsAnEmptyStateAndNotAnError") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Forbidden)
      assert(optionalTestId(root, "topic-forbidden").isDefined, root.outerHTML)
      assertEquals(optionalTestId(root, "topic-error"), None)
    }
  }

  // --- The partition table --------------------------------------------------------------------

  test("anOfflinePartitionShowsOfflineAndNotLeaderMinusOne") {
    // Kafka reports a leaderless partition as node -1. Rendering that is worse than useless: it looks like
    // a broker id.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(
        Section.Ok(
          detail(List(partition(0), partition(1, leader = None, messages = None)), messages = None),
          fetchedAt
        )
      )
      val chip = byTestId(root, "partition-row-1-offline")
      assertEquals(chip.textContent, Messages.Offline)
      assert(!root.textContent.contains("-1"), "a leaderless partition rendered Kafka's node id")
      // And no count for that row, and none on the strip: four consistent statements of one fact.
      assertEquals(byTestId(root, "partition-row-1-messages").textContent, DataTable.missing)
      assertEquals(byTestId(root, "topic-indicator-messages").textContent, DataTable.missing)
    }
  }

  test("theLeaderReplicaIsChippedAsLeaderAndOutOfSyncOnesAreMarked") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(
        Section.Ok(
          detail(
            List(partition(0, replicas = List(replica(1, leader = true), replica(2, inSync = false))))
          ),
          fetchedAt
        )
      )
      assertEquals(byTestId(root, "partition-row-0-replica-1").textContent, "1 leader")
      // The word as well as the colour: spotting the odd one out is what this column is for.
      assertEquals(byTestId(root, "partition-row-0-replica-2").textContent, "2 out of sync")
    }
  }

  test("partitionsAreOrderedById") {
    // A partition table is read by looking for a number. Whatever order the broker answered in would make
    // that a search rather than a lookup.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(
        Section.Ok(detail(List(partition(2), partition(0), partition(1))), fetchedAt)
      )
      val ids = List(0, 1, 2).map(id => optionalTestId(root, s"partition-row-$id").isDefined)
      assertEquals(ids, List(true, true, true))
      // Only the id cells: the same prefix also names the replica chips and the message cells.
      val rendered = List(0, 1, 2)
        .map(id => root.querySelectorAll(s"[data-testid='partition-row-$id']").toList.map(_.textContent))
      assertEquals(rendered, List(List("0"), List("1"), List("2")))
    }
  }

  test("aTopicWithTwoThousandPartitionsRendersAWindow") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(Section.Ok(detail(List.tabulate(2_000)(partition(_))), fetchedAt))
      assert(partitionRows(root) > 0, "the window is empty; the viewport height did not reach the table")
      assert(partitionRows(root) < 30, s"expected a window, found ${partitionRows(root)} rows")
    }
  }

  // --- The settings tab -----------------------------------------------------------------------

  private def entry(
      name: String,
      value: Option[String],
      defaultValue: Option[String],
      sensitive: Boolean = false
  ): TopicConfigEntryDto =
    TopicConfigEntryDto(name, value, defaultValue, "dynamic_topic_config", sensitive, false, None)

  private def openSettings(fixture: Fixture, view: TopicConfigViewDto): Unit = {
    fixture.api.send(Section.Ok(detail(), fetchedAt))
    fixture.tab.set(TopicTab.Settings)
    fixture.api.settings(view)
  }

  test("aSensitiveValueIsMaskedAndItsLengthIsNotRevealed") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      openSettings(
        fixture,
        TopicConfigViewDto.Entries(List(entry("ssl.key.password", None, None, sensitive = true)))
      )
      val cell = byTestId(root, "topic-setting-ssl.key.password-value")
      assert(cell.textContent.contains(ConfigValue.masked), cell.textContent)
      // Fixed width, whatever the value was. The server did not even send it.
      assertEquals(ConfigValue.masked.length, 6)
      assert(cell.textContent.contains("sensitive"), "a screen reader hears six bullets and nothing else")
    }
  }

  test("anOverriddenKeyIsEmphasisedAndADefaultOneIsNot") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      openSettings(
        fixture,
        TopicConfigViewDto.Entries(
          List(
            entry("cleanup.policy", Some("delete"), Some("delete")),
            entry("retention.ms", Some("604800000"), Some("-1"))
          )
        )
      )
      val overridden = byTestId(root, "topic-setting-retention.ms")
      val default = byTestId(root, "topic-setting-cleanup.policy")
      assert(overridden.classList.contains(kui.ui.topics.TopicsCss.SettingOverridden), overridden.outerHTML)
      assert(!default.classList.contains(kui.ui.topics.TopicsCss.SettingOverridden), default.outerHTML)
    }
  }

  test("aDurationHintSitsBesideTheRawValueAndNotInsteadOfIt") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      openSettings(
        fixture,
        TopicConfigViewDto.Entries(List(entry("retention.ms", Some("604800000"), Some("-1"))))
      )
      val cell = byTestId(root, "topic-setting-retention.ms-value")
      assert(cell.textContent.contains("604800000"), cell.textContent)
      assert(cell.textContent.contains("7 days"), cell.textContent)
    }
  }

  test("theDefaultColumnIsBlankWhenTheValueEqualsTheDefault") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      openSettings(
        fixture,
        TopicConfigViewDto.Entries(List(entry("cleanup.policy", Some("delete"), Some("delete"))))
      )
      val cells = root.querySelectorAll("tbody td").toList.map(_.textContent.trim)
      // Three columns; the third is the default and is blank because the value is the default. Repeating
      // it in both columns doubles the reading and says nothing.
      assertEquals(cells.lastOption, Some(""))
    }
  }

  test("notPermittedRendersItsSentenceAndNotAnEmptyTable") {
    // An empty table and "you may not look" are different answers, and the remedy for the second is an ACL
    // change the operator will never think of if the screen tells them the first.
    val fixture = new Fixture
    mounted(fixture) { root =>
      openSettings(fixture, TopicConfigViewDto.NotPermitted("TopicAuthorizationException"))
      val refused = byTestId(root, "topic-settings-not-permitted")
      assert(refused.textContent.contains("TopicAuthorizationException"), refused.textContent)
      assertEquals(optionalTestId(root, "topic-settings-table"), None)
    }
  }
}
