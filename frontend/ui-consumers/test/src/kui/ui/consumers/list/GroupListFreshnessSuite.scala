package kui.ui.consumers.list

import com.raquo.laminar.api.L.*
import io.circe.parser.decode
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.consumer.contract.GoldenDocuments
import kui.consumer.contract.dto.GroupsResponse
import kui.contracts.{ErrorEnvelope, Section}
import kui.kernel.ClusterId
import kui.ui.consumers.ConsumersQueries
import kui.ui.kernel.api.{ApiClient, ApiError}

/** The seam between what the consumer service sends and what the group list shows.
  *
  * ==Why this suite exists==
  *
  * The defect it was written for was invisible from either side. The consumer service answered `200` with
  * the rows of its last successful scrape whether or not the cluster was still reachable, and the browser
  * drew them. Every test on both halves passed, because each half was asserted against its own idea of the
  * answer, and the screen went on showing lag figures from before the broker died with nothing saying so.
  *
  * Lag is the field that makes that unacceptable rather than untidy. It is the one number here that is
  * supposed to move on its own, and an operator reads it to decide whether their consumers are keeping up.
  * A dead broker makes it *freeze* rather than climb — so a frozen column looks exactly like a cluster that
  * has caught up, and the screen quietly told an operator the opposite of the truth.
  *
  * ==What joins the two halves==
  *
  * The golden documents in `services/consumer/contract`. They are encoded from the contract's own samples
  * and committed beside it; `ConsumerRoutesSuite` asserts that the service's live routes produce that shape
  * for a cluster that has stopped answering, and this suite feeds the very same text through the endpoint
  * the *browser* declares and into the real page. Nothing here writes a JSON literal of its own: a hand-typed
  * payload is a second opinion about the wire format, and a second opinion is precisely what the defect was.
  */
final class GroupListFreshnessSuite extends FunSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")

  /** The committed document, read back through the contract's own codec — the one `ConsumersApi.list`
    * declares its body with, so a decode that succeeds here is a decode that succeeds in the browser.
    */
  private def golden(document: String): GroupsResponse =
    decode[GroupsResponse](document)
      .getOrElse(fail(s"the committed golden document did not decode: $document"))

  /** Answers the list call, and refuses anything the screen has no business asking for. */
  final private class FakeApi extends ApiClient {
    private val groups = new EventBus[Either[ApiError, GroupsResponse]]

    def call[I, O](
        endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
        input: I
    ): EventStream[Either[ApiError, O]] =
      endpoint.info.name match {
        case Some("consumer.list") => groups.events.map(_.map(_.asInstanceOf[O]))
        case Some("consumer.lag") => EventStream.empty
        case other => fail(s"the group list called $other, which it has no business calling")
      }

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def send(response: GroupsResponse): Unit = groups.writer.onNext(Right(response))
  }

  final private class Fixture {
    val api = new FakeApi

    val element: HtmlElement = GroupListPage(
      cluster = cluster,
      queries = new ConsumersQueries(api),
      navigate = (_, _) => (),
      hrefFor = (c, group) => s"/ui/clusters/${c.value}/consumer-groups/$group",
      zone = Val("UTC")
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

  private def optionalTestId(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    optionalTestId(root, testId)
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  test("theDocumentAServiceSendsForADeadClusterMarksTheTableStale") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(golden(GoldenDocuments.groupsResponseStale))

      val region = byTestId(root, "groups-table-region")
      assertEquals(
        Option(region.getAttribute("aria-busy")),
        Some("true"),
        "the region holding frozen lag figures must announce that it is not authoritative"
      )
      val _ = byTestId(root, "groups-table-region-stale-badge")
    }
  }

  test("theSameDocumentFromALiveClusterMarksNothing") {
    // The control. Without it, a screen that dimmed itself permanently would satisfy the assertion above,
    // and a table that is always dimmed says no more than one that never is.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(golden(GoldenDocuments.groupsResponse))

      val region = byTestId(root, "groups-table-region")
      assertEquals(Option(region.getAttribute("aria-busy")), Some("false"))
      assertEquals(optionalTestId(root, "groups-table-region-stale-badge"), None)
    }
  }

  test("theRowsSurviveBeingMarkedStale") {
    // A stale table that emptied itself would be a different lie from the one being fixed: no rows at all
    // claims the cluster has no consumer groups.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(golden(GoldenDocuments.groupsResponseStale))
      assert(
        root.textContent.contains("orders-indexer"),
        s"the last known rows must stay on screen, got ${root.textContent}"
      )
    }
  }

  test("theBadgeSaysWhatHappenedInWordsAndKeepsTheCodeForSupport") {
    // The other defect this closes. `Stale: UPSTREAM_UNAVAILABLE` puts a wire code in front of somebody who
    // cannot act on it; the code is still what a support conversation needs, so it moves to the tooltip
    // rather than being thrown away.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(golden(GoldenDocuments.groupsResponseStale))

      val badge = byTestId(root, "groups-table-region-stale-badge")
      assert(
        badge.textContent.contains("the cluster is not answering"),
        s"expected a sentence a person can act on, got ${badge.textContent}"
      )
      assert(
        !badge.textContent.contains("UPSTREAM_UNAVAILABLE"),
        s"the wire code must not be the message, got ${badge.textContent}"
      )
      assertEquals(
        Option(badge.getAttribute("title")).map(_.contains("UPSTREAM_UNAVAILABLE")),
        Some(true),
        "the code must still be reachable for whoever has to quote it"
      )
    }
  }

  test("aSectionWithNoRowsIsAnExplanationAndNotAnEmptyTable") {
    // An empty table is a claim that the cluster has no consumer groups. A cluster KUI has never managed to
    // read has to say so instead.
    val fixture = new Fixture
    mounted(fixture) { root =>
      fixture.api.send(
        GroupsResponse(
          Section.Unavailable(
            kui.contracts.capability.ReasonCode.UpstreamUnavailable,
            "kafka could not be reached",
            None
          ),
          incompleteCoordinators = 0
        )
      )

      val _ = byTestId(root, "groups-error")
      assertEquals(optionalTestId(root, "groups-table-region"), None)
    }
  }
}
