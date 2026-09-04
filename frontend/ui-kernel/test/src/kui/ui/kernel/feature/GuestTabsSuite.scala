package kui.ui.kernel.feature

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import munit.FunSuite

import kui.ui.kernel.component.{Mounted, Tab, Tabs}

/** The host half of the `topic.tabs` slot: a host's own tabs, plus one per registered guest.
  *
  * This is the seam M3's Messages tab and M4's Consumers tab both land on, and neither of them may edit a
  * file in the topics feature. The cases below are written from the guest's side on purpose: they assert
  * what a registration produces, which is the only thing a guest milestone can check for itself.
  */
final class GuestTabsSuite extends FunSuite with Mounted {

  private val context = PanelContext(cluster = Some("production"), params = Map(FeatureSlots.TopicParam -> "orders"))

  private def guest(order: Int, label: Option[String]): KuiFeature = new KuiFeature {
    def id: FeatureId = FeatureId.Clusters
    def nav: NavEntry = NavEntry(FeatureId.Clusters, "Clusters", () => svg.svg(), order, requiresCluster = false)
    def routes: List[Route[? <: Page, ?]] = Nil
    def render(page: Page): HtmlElement = div()
    def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement = div(reason.message)
    override def panels: List[PanelContribution] = List(
      PanelContribution(
        FeatureId.Clusters,
        FeatureSlots.TopicTabs,
        ctx => div(dataAttr("testid") := "guest-body", ctx.params.getOrElse(FeatureSlots.TopicParam, "")),
        tabLabel = label
      )
    )
  }

  private def labelsOf(tabs: Signal[List[Tab]]): List[String] = {
    var seen = List.empty[String]
    val subscription = tabs.foreach(current => seen = current.map(_.label))(using unsafeWindowOwner)
    try seen
    finally subscription.kill()
  }

  test("aRegisteredGuestBecomesATab") {
    val tabs = GuestTabs.of(Val(List(guest(1, Some("Messages")))), FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    assertEquals(labelsOf(tabs), List("Messages"))
  }

  test("theHostsOwnTabsComeFirst") {
    val own = Val(List(Tab("overview", "Overview", () => div()), Tab("settings", "Settings", () => div())))
    val merged =
      GuestTabs.merged(own, Val(List(guest(1, Some("Messages")))), FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    assertEquals(labelsOf(merged), List("Overview", "Settings", "Messages"))
  }

  test("guestsAreOrderedByTheirFeaturesNavOrderNotByLoadOrder") {
    // Otherwise the tab strip lays itself out differently on a slow connection than on a fast one,
    // because the order two modules finished downloading in is not a product decision.
    val features = Val(List(guest(1, Some("Messages")), guest(2, Some("Consumers"))))
    val tabs = GuestTabs.of(features, FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    assertEquals(labelsOf(tabs), List("Messages", "Consumers"))
  }

  test("aContributionWithNoTabLabelIsNotATab") {
    val tabs = GuestTabs.of(Val(List(guest(1, None))), FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    assertEquals(labelsOf(tabs), Nil)
  }

  test("aGuestRegisteredForAnotherSlotOrAnotherHostIsNotATab") {
    val elsewhere: KuiFeature = new KuiFeature {
      def id: FeatureId = FeatureId.Clusters
      def nav: NavEntry = NavEntry(FeatureId.Clusters, "Clusters", () => svg.svg(), 1, requiresCluster = false)
      def routes: List[Route[? <: Page, ?]] = Nil
      def render(page: Page): HtmlElement = div()
      def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement = div()
      override def panels: List[PanelContribution] =
        List(PanelContribution(FeatureId.Clusters, "broker.tabs", _ => div(), tabLabel = Some("Elsewhere")))
    }

    assertEquals(
      labelsOf(GuestTabs.of(Val(List(elsewhere)), FeatureId.Clusters, FeatureSlots.TopicTabs, context)),
      Nil
    )
  }

  test("anUnloadedGuestContributesNoTabAndIsNotFetched") {
    // The tab list is derived from the *loaded* features and nothing else, so a host page is never a
    // reason to download another feature.
    assertEquals(labelsOf(GuestTabs.of(Val(Nil), FeatureId.Clusters, FeatureSlots.TopicTabs, context)), Nil)
  }

  test("aGuestsTabAppearsOnceItsFeatureHasLoaded") {
    val features = Var(List.empty[KuiFeature])
    val tabs = GuestTabs.of(features.signal, FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    var seen = List.empty[List[String]]
    val subscription = tabs.foreach(current => seen = seen :+ current.map(_.label))(using unsafeWindowOwner)

    features.set(List(guest(1, Some("Messages"))))

    try assertEquals(seen, List(Nil, List("Messages")))
    finally subscription.kill()
  }

  test("aGuestsPanelIsNotBuiltUntilItsTabIsOpened") {
    // `Tab.body` is a thunk for a reason: a Consumers tab issues requests when it is created, and
    // building every panel up front would fire several screens' worth of traffic for a user who looks
    // at one.
    val own = Val(List(Tab("overview", "Overview", () => div(dataAttr("testid") := "overview"))))
    val merged =
      GuestTabs.merged(own, Val(List(guest(1, Some("Messages")))), FeatureId.Clusters, FeatureSlots.TopicTabs, context)
    val selected = Var("overview")

    mounted(Tabs(merged, selected)) { root =>
      assertEquals(Option(root.querySelector("[data-testid='guest-body']")), None)
      selected.set(FeatureId.Clusters.value)
      // And when it is opened, it gets the host's context — including the topic name, under the key
      // both sides take from `FeatureSlots` rather than typing twice.
      assertEquals(byTestId(root, "guest-body").textContent, "orders")
    }
  }

  test("everySlotIdIsDeclaredOnce") {
    assert(FeatureSlots.all.contains(FeatureSlots.TopicTabs))
    assertEquals(FeatureSlots.all.distinct, FeatureSlots.all)
  }
}
