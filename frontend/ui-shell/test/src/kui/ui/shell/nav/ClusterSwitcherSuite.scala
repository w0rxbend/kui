package kui.ui.shell.nav

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.capability.{CapabilityKey, CapabilityState, DegradedReason, ReasonCode}
import kui.kernel.{ClusterId, ServiceId}
import kui.ui.kernel.state.{ClusterColor, ClusterColors, FeatureState}

class ClusterSwitcherSuite extends FunSuite {

  private def cluster(raw: String): ClusterId =
    ClusterId.from(raw).getOrElse(fail(s"'$raw' should be a legal cluster id"))

  private val clusterService = ServiceId.unsafe("kui-cluster-service")
  private val topicService = ServiceId.unsafe("kui-topic-service")

  private def entryFor(id: String, service: ServiceId = clusterService): CapabilityKey =
    CapabilityKey(service, Some(cluster(id)))

  private val degraded =
    CapabilityState.Degraded(DegradedReason(ReasonCode.UpstreamTimeout, "slow to answer", None, None))

  private val unavailable =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Instant.EPOCH)

  override def beforeEach(context: BeforeEach): Unit = {
    ClusterColors.reset()
    dom.window.localStorage.clear()
  }

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def click(element: dom.Element): Unit =
    element.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true; cancelable = true })): Unit

  private def keyDown(element: dom.Element, pressed: String): Unit =
    element.dispatchEvent(new dom.KeyboardEvent("keydown", new dom.KeyboardEventInit { key = pressed; bubbles = true })): Unit

  final private class Fixture(
      states: Map[CapabilityKey, CapabilityState],
      current: Option[ClusterId] = None,
      names: Map[CapabilityKey, String] = Map.empty
  ) {
    val chosen: Var[Option[ClusterId]] = Var(current)
    val opened: mutable.ListBuffer[ClusterId] = mutable.ListBuffer.empty

    val element: HtmlElement =
      ClusterSwitcher(Val(ClusterEntry.of(states, names)), chosen, id => opened.append(id): Unit)
  }

  test("everyConfiguredClusterIsListedAndNotConfiguredOnesAreNot") {
    // Hidden and not dimmed: this deployment has no such cluster, and showing it invites a click that can
    // never work. The same rule the sidebar applies to a feature entry.
    val fixture = new Fixture(
      Map(
        entryFor("dev") -> CapabilityState.Available,
        entryFor("prod") -> unavailable,
        entryFor("ghost") -> CapabilityState.NotConfigured
      )
    )
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      assertEquals(root.querySelectorAll("[role='option']").length, 2)
      byTestId(root, "cluster-switcher-option-dev"): Unit
      byTestId(root, "cluster-switcher-option-prod"): Unit
      assertEquals(Option(root.querySelector("[data-testid='cluster-switcher-option-ghost']")), None)
    }
  }

  test("aClusterIsAsHealthyAsItsWorstService") {
    // A cluster whose topic service is fine and whose cluster service is unreachable is not a healthy
    // cluster, and a dot reporting the best of its services would be reassuring and wrong.
    val entries = ClusterEntry.of(
      Map(entryFor("prod", topicService) -> CapabilityState.Available, entryFor("prod") -> unavailable)
    )
    assertEquals(entries.length, 1)
    assert(entries.head.state.isInstanceOf[FeatureState.Unavailable], entries.head.state.toString)
  }

  test("theCurrentClusterIsMarkedSelected") {
    val fixture = new Fixture(
      Map(entryFor("dev") -> CapabilityState.Available, entryFor("prod") -> CapabilityState.Available),
      current = Some(cluster("prod"))
    )
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      assertEquals(Option(byTestId(root, "cluster-switcher-option-prod").getAttribute("aria-selected")), Some("true"))
      assertEquals(Option(byTestId(root, "cluster-switcher-option-dev").getAttribute("aria-selected")), Some("false"))
    }
  }

  test("choosingAClusterSetsCurrentClusterAndNavigatesOnce") {
    val fixture = new Fixture(Map(entryFor("dev") -> CapabilityState.Available, entryFor("prod") -> CapabilityState.Available))
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      click(byTestId(root, "cluster-switcher-option-prod"))
      assertEquals(fixture.chosen.now().map(_.value), Some("prod"))
      assertEquals(fixture.opened.toList.map(_.value), List("prod"))
      // And the list closes behind it.
      assertEquals(Option(byTestId(root, "cluster-switcher-trigger").getAttribute("aria-expanded")), Some("false"))
    }
  }

  test("anUnavailableClusterIsStillChoosable") {
    // The switcher's version of the dashboard's criterion. The cluster's own page is where the reason, the
    // "since" and a retry live; a switcher that refused to open a broken cluster would remove the one route
    // to the explanation.
    val fixture = new Fixture(Map(entryFor("prod") -> unavailable))
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      val option = byTestId(root, "cluster-switcher-option-prod")
      assertEquals(Option(option.getAttribute("aria-disabled")), None)
      assertEquals(Option(option.getAttribute("tabindex")), Some("0"))
      click(option)
      assertEquals(fixture.opened.toList.map(_.value), List("prod"))
    }
  }

  test("theDotsTooltipIsTheStateWordAndTheReasonVerbatim") {
    val fixture = new Fixture(Map(entryFor("prod") -> unavailable))
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      val dot = byTestId(root, "cluster-switcher-dot-prod")
      assertEquals(Option(dot.getAttribute("title")), Some("Unavailable: connection refused"))
      // Available to a screen reader too, so the state is not carried by colour alone.
      assertEquals(Option(dot.getAttribute("aria-label")), Some("Unavailable: connection refused"))
    }
  }

  test("aNotYetPolledClusterShowsDegradedNotUnavailable") {
    // ADR-032 amendment 2, asserted where an operator would otherwise see a wall of red for one polling
    // interval after every gateway restart.
    assertEquals(ClusterSwitcher.describe(FeatureState.derive(None, permitted = true)).startsWith("Degraded"), true)
  }

  test("aDegradedClusterShowsItsReasonRatherThanTheWordAlone") {
    val fixture = new Fixture(Map(entryFor("stage") -> degraded))
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      assertEquals(
        Option(byTestId(root, "cluster-switcher-dot-stage").getAttribute("title")),
        Some("Degraded: slow to answer")
      )
    }
  }

  test("aClusterWithNoDisplayNameFallsBackToItsId") {
    // A service that reported no name at all. The switcher degrades to the slug rather than showing a
    // blank row.
    val entries = ClusterEntry.of(Map(entryFor("prod-eu-1") -> CapabilityState.Available))
    assertEquals(entries.map(_.displayName), List("prod-eu-1"))
  }

  test("theSwitcherRendersTheOperatorsNameAndNotTheSlug") {
    // The whole reason the switcher exists. Real cluster ids differ by one character — `prod-eu-1` and
    // `prod-eu-2` — and reading the wrong one means acting on the wrong cluster; the name an operator
    // chose is the string that tells them apart. It used to render the slug, because the capability
    // stream carried no name and `ClusterEntry.of` had nothing else to use.
    val key = entryFor("prod-eu-1")
    val fixture = new Fixture(
      Map(key -> CapabilityState.Available),
      names = Map(key -> "Production EU (primary)")
    )

    assertEquals(ClusterEntry.of(Map(key -> CapabilityState.Available), Map(key -> "Production EU (primary)"))
      .map(_.displayName), List("Production EU (primary)"))

    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      val option = byTestId(root, "cluster-switcher-option-prod-eu-1")
      assert(option.textContent.contains("Production EU (primary)"), option.textContent)
      // The trigger shows the selected cluster, and it has to read the same way.
      assert(
        byTestId(root, "cluster-switcher-trigger").textContent.contains("Production EU (primary)"),
        byTestId(root, "cluster-switcher-trigger").textContent
      )
      // The id is still the machine-readable handle, on the attribute rather than in the label.
      assertEquals(option.getAttribute("data-testid"), "cluster-switcher-option-prod-eu-1")
    }
  }

  test("aClusterNamedByOneOfItsServicesIsNamedForAllOfThem") {
    // One cluster has one entry per service scoped to it, and only the services that know a name send
    // one. A single name is enough to label the row.
    val entries = ClusterEntry.of(
      Map(
        entryFor("prod", topicService) -> CapabilityState.Available,
        entryFor("prod") -> CapabilityState.Available
      ),
      Map(entryFor("prod") -> "Production")
    )

    assertEquals(entries.map(_.displayName), List("Production"))
  }

  test("theColourTagAndTheStatusDotAreDistinctElements") {
    // One element doing both jobs would make a red "production" marker indistinguishable from a failing
    // cluster.
    val fixture = new Fixture(Map(entryFor("prod") -> CapabilityState.Available))
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      val tag = byTestId(root, "cluster-tag-prod")
      val dot = byTestId(root, "cluster-switcher-dot-prod")
      assert(!(tag eq dot))
      assert(tag.getAttribute("class").contains("cluster-tag"), tag.getAttribute("class"))
      assert(dot.getAttribute("class").contains("cluster-dot"), dot.getAttribute("class"))
    }
  }

  test("aColourChosenForOneClusterIsShownOnItsTagAndNoOthers") {
    val fixture = new Fixture(
      Map(entryFor("prod") -> CapabilityState.Available, entryFor("dev") -> CapabilityState.Available)
    )
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      ClusterColors.of("prod").set(ClusterColor.Danger)
      assert(byTestId(root, "cluster-tag-prod").getAttribute("class").contains("danger"))
      assert(!byTestId(root, "cluster-tag-dev").getAttribute("class").contains("danger"))
    }
  }

  test("keyboardOperationMatchesTheListboxContract") {
    val fixture = new Fixture(
      Map(entryFor("dev") -> CapabilityState.Available, entryFor("prod") -> CapabilityState.Available)
    )
    mounted(fixture.element) { root =>
      val trigger = byTestId(root, "cluster-switcher-trigger")
      keyDown(trigger, "ArrowDown")
      assertEquals(Option(trigger.getAttribute("aria-expanded")), Some("true"))

      val list = root.querySelector("[role='listbox']")
      assertEquals(Option(list.getAttribute("aria-label")).isDefined, true)

      keyDown(byTestId(root, "cluster-switcher-option-prod"), "Enter")
      assertEquals(fixture.opened.toList.map(_.value), List("prod"))

      keyDown(trigger, "ArrowDown")
      keyDown(root.querySelector("[role='listbox']"), "Escape")
      // A menu a keyboard user cannot get out of is worse than no menu.
      assertEquals(Option(trigger.getAttribute("aria-expanded")), Some("false"))
    }
  }

  test("noClustersAtAllIsASentenceRatherThanAnEmptyMenu") {
    val fixture = new Fixture(Map.empty)
    mounted(fixture.element) { root =>
      click(byTestId(root, "cluster-switcher-trigger"))
      assertEquals(root.querySelectorAll("[role='option']").length, 0)
      assert(root.textContent.contains("No clusters configured"), root.textContent)
    }
  }

  test("theOnlyClusterIsChosenWithoutBeingAskedFor") {
    // A single-cluster deployment is not asking the user to make a choice, and until one is made the
    // sidebar has no Topics and no Consumers entry in it — with nothing on screen to say that opening
    // the switcher is what brings them back.
    val fixture = new Fixture(Map(entryFor("only") -> CapabilityState.Available))
    mounted(fixture.element) { _ =>
      assertEquals(fixture.chosen.now().map(_.value), Some("only"))
      // Selected, not navigated to: moving somebody who deliberately opened another page is a
      // different and much ruder thing than filling in a blank.
      assertEquals(fixture.opened.toList, Nil)
    }
  }

  test("twoClustersAreLeftForThePersonToChooseBetween") {
    val fixture = new Fixture(
      Map(entryFor("prod") -> CapabilityState.Available, entryFor("staging") -> CapabilityState.Available)
    )
    mounted(fixture.element) { _ =>
      assertEquals(fixture.chosen.now(), None)
    }
  }

  test("anExistingChoiceIsNeverOverriddenByTheSoleClusterRule") {
    // The stored choice, or the one a pasted URL set, names a cluster the registry has not reported
    // yet. Replacing it with the one cluster that has reported would move the recipient of a link
    // away from what the sender saw.
    val fixture = new Fixture(
      Map(entryFor("only") -> CapabilityState.Available),
      current = Some(cluster("elsewhere"))
    )
    mounted(fixture.element) { _ =>
      assertEquals(fixture.chosen.now().map(_.value), Some("elsewhere"))
    }
  }
}
