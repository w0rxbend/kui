package kui.ui.clusters.component

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.duration.*

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.PublicEndpoint

import kui.cluster.contract.dto.RefreshAcceptedDto
import kui.contracts.ErrorEnvelope
import kui.contracts.capability.ReasonCode
import kui.ui.clusters.dashboard.ClusterFixtures
import kui.ui.clusters.{ClustersQueries, RefreshFlow}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.state.FeatureState

class RefreshButtonSuite extends FunSuite {

  private val cluster = ClusterFixtures.clusterId("local")
  private val baseline = Instant.parse("2026-09-03T12:00:00Z")

  final private class FakeApi extends ApiClient {
    private val bus = new EventBus[Either[ApiError, RefreshAcceptedDto]]

    def call[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I): EventStream[Either[ApiError, O]] =
      bus.events.map(_.map(_.asInstanceOf[O]))

    def callSecure[A, I, O](
        endpoint: sttp.tapir.Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = EventStream.empty

    def accept(): Unit = bus.writer.onNext(Right(RefreshAcceptedDto(cluster, baseline)))
    def refuse(error: ApiError): Unit = bus.writer.onNext(Left(error))
  }

  final private class Fixture(capability: FeatureState = FeatureState.Ready) {
    given owner: ManualOwner = new ManualOwner

    val api = new FakeApi
    val timers: mutable.Map[FiniteDuration, EventBus[Unit]] = mutable.Map.empty
    val scrapedAt: Var[Option[Instant]] = Var(Some(baseline))

    val flow: RefreshFlow = new RefreshFlow(
      cluster,
      new ClustersQueries(api),
      scrapedAt.signal,
      timer = after => timers.getOrElseUpdate(after, new EventBus[Unit]).events
    )

    val element: HtmlElement = RefreshButton(flow, Val(capability))

    def fire(after: FiniteDuration): Unit = timers.get(after).foreach(_.writer.onNext(()))
  }

  private def mounted[A](fixture: Fixture)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, fixture.element)
    try check(fixture.element.ref)
    finally {
      root.unmount(): Unit
      fixture.owner.killSubscriptions()
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def button(root: dom.Element): dom.Element = byTestId(root, "cluster-refresh-button")

  private def click(element: dom.Element): Unit =
    element.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })): Unit

  test("theButtonIsDisabledWhileRunningAndReEnablesOnCompletion") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      assert(!button(root).hasAttribute("disabled"))

      click(button(root))
      fixture.api.accept()
      assert(button(root).hasAttribute("disabled"), "the button stayed clickable while a refresh was running")

      fixture.scrapedAt.set(Some(baseline.plusSeconds(5)))
      fixture.fire(1.second)
      assert(!button(root).hasAttribute("disabled"))
    }
  }

  test("theBusyButtonKeepsItsLabelAndGainsASpinner") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(button(root))
      fixture.api.accept()
      // A label that changes to a verb is wider than one that does not, so pressing the button would move
      // everything next to it.
      assert(button(root).textContent.contains("Refresh"), button(root).textContent)
      assertEquals(Option(button(root).getAttribute("aria-busy")), Some("true"))
    }
  }

  test("theButtonIsDisabledWhenTheFeatureIsNotReadyWithOneMergedTooltip") {
    val fixture = new Fixture(capability = FeatureState.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", None))
    mounted(fixture) { root =>
      assert(button(root).hasAttribute("disabled"))
      // One explanation, not two competing ones: the wrapper merges the capability's reason with the
      // button's own.
      val gate = byTestId(root, "cluster-refresh-gate")
      assert(gate.textContent.nonEmpty)
    }
  }

  test("theTimedOutSentenceIsShownAndNothingIsCleared") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(button(root))
      fixture.api.accept()
      List(1.second, 3.seconds, 6.seconds, 10.seconds, 15.seconds).foreach(fixture.fire)

      val status = byTestId(root, "cluster-refresh-status")
      assert(status.textContent.contains("still be running"), status.textContent)
      // Announced, because the answer arrives seconds after the press and the user has probably looked away.
      assertEquals(Option(status.getAttribute("role")), Some("status"))
      // And the button comes back: the product does not decide on the user's behalf that it is broken.
      assert(!button(root).hasAttribute("disabled"))
    }
  }

  test("aRejectedRefreshShowsTheEnvelopeMessageVerbatim") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(button(root))
      fixture.api.refuse(ApiError.Envelope("KUI-FORBIDDEN", "not permitted on this cluster", Nil, "req-1", false))
      val status = byTestId(root, "cluster-refresh-status")
      assert(status.textContent.contains("not permitted on this cluster"), status.textContent)
      // A button that would fail identically on every press stops offering to.
      assert(button(root).hasAttribute("disabled"))
    }
  }

  test("theButtonSendsNothingUntilItIsPressed") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      assertEquals(byTestId(root, "cluster-refresh-status").textContent, "")
      assert(!button(root).hasAttribute("aria-busy") || button(root).getAttribute("aria-busy") == "false")
    }
  }
}
