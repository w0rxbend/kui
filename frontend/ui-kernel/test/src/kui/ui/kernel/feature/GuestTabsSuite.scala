package kui.ui.kernel.feature

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import io.circe.{HCursor, Json}
import munit.FunSuite

import kui.ui.kernel.component.{Mounted, Tab, Tabs}

/** The host half of the `topic.tabs` slot: a host's own tabs, plus one per registered guest.
  *
  * This is the seam M3's Messages tab and M4's Consumers tab both land on, and neither of them may edit a
  * file in the topics feature. The cases below are written from the guest's side on purpose: they assert what
  * a registration produces, which is the only thing a guest milestone can check for itself.
  *
  * The defect they were rewritten for is the one a user could see. The strip used to be derived from the
  * *loaded* features, so a topic page opened in a fresh browser tab had two tabs and the same topic page
  * opened after a visit to Consumers had three. Which screens exist is not a fact about where the user has
  * been, so the strip now comes from the static registrations — the same data the sidebar is drawn from —
  * and only opening a guest's tab downloads that guest.
  */
final class GuestTabsSuite extends FunSuite with Mounted {

  private val context =
    PanelContext(cluster = Some("production"), params = Map(FeatureSlots.TopicParam -> "orders"))

  /** A guest's *static* registration: the half that exists before anything has been downloaded. */
  private def registration(
      feature: FeatureId,
      label: String,
      host: FeatureId = FeatureId.Topics,
      slot: String = FeatureSlots.TopicTabs
  ): FeatureRoutes = new FeatureRoutes {
    def id: FeatureId = feature
    def nav: NavEntry = NavEntry(feature, label, () => svg.svg(), 1, requiresCluster = false)
    def landing: Page = new Page {}
    def routes(uiPrefix: String): List[Route[? <: Page, ?]] = Nil
    def encodePage(page: Page): Option[Json] = None
    def decodePage(tag: String, cursor: HCursor): Option[Page] = None
    override def guestTabs: List[GuestTab] = List(GuestTab(host, slot, label))
  }

  /** A body that records that it was asked for, so "nothing is fetched until the tab is opened" can be
    * asserted without a real dynamic import.
    */
  private def recordingBody(asked: scala.collection.mutable.ListBuffer[FeatureId]): GuestTabs.Body =
    (guest, _, _, ctx) => {
      asked.append(guest): Unit
      div(dataAttr("testid") := "guest-body", ctx.params.getOrElse(FeatureSlots.TopicParam, ""))
    }

  private def labelsOf(tabs: Signal[List[Tab]]): List[String] = {
    var seen = List.empty[String]
    val subscription = tabs.foreach(current => seen = current.map(_.label))(using unsafeWindowOwner)
    try seen
    finally subscription.kill()
  }

  private def noBody: GuestTabs.Body = (_, _, _, _) => div()

  test("aRegisteredGuestBecomesATabWithoutItsFeatureHavingBeenDownloaded") {
    // The defect, as an assertion. Nothing here is loaded: the only input is the static registration, and
    // the tab has to be there anyway.
    val tabs = GuestTabs.of(
      List(registration(FeatureId.Messages, "Messages")),
      FeatureId.Topics,
      FeatureSlots.TopicTabs,
      noBody,
      context
    )
    assertEquals(tabs.map(_.label), List("Messages"))
  }

  test("theStripIsTheSameWhicheverFeaturesHappenToBeLoaded") {
    // Stated separately because it is the user-visible property: the same page, opened two different ways,
    // offers the same screens. `GuestTabs.of` takes no loaded-feature list at all, which is what makes it
    // true rather than merely usually true.
    val statics = List(registration(FeatureId.Messages, "Messages"), registration(FeatureId.Consumers, "Consumers"))
    val first = GuestTabs.of(statics, FeatureId.Topics, FeatureSlots.TopicTabs, noBody, context)
    val second = GuestTabs.of(statics, FeatureId.Topics, FeatureSlots.TopicTabs, noBody, context)
    assertEquals(first.map(_.label), second.map(_.label))
    assertEquals(first.map(_.label), List("Messages", "Consumers"))
  }

  test("theHostsOwnTabsComeFirst") {
    val own = Val(List(Tab("overview", "Overview", () => div()), Tab("settings", "Settings", () => div())))
    val merged = GuestTabs.merged(
      own,
      FeatureId.Topics,
      FeatureSlots.TopicTabs,
      context,
      statics = List(registration(FeatureId.Messages, "Messages")),
      body = noBody
    )
    assertEquals(labelsOf(merged), List("Overview", "Settings", "Messages"))
  }

  test("guestsKeepTheOrderOfTheStaticRegistrations") {
    // Which is sidebar order, a product decision — and not the order two modules finished downloading in,
    // which would lay the strip out differently on a slow connection than on a fast one.
    val statics =
      List(registration(FeatureId.Messages, "Messages"), registration(FeatureId.Consumers, "Consumers"))
    val tabs = GuestTabs.of(statics, FeatureId.Topics, FeatureSlots.TopicTabs, noBody, context)
    assertEquals(tabs.map(_.label), List("Messages", "Consumers"))
  }

  test("aGuestRegisteredForAnotherSlotOrAnotherHostIsNotATab") {
    val elsewhere = registration(FeatureId.Messages, "Elsewhere", slot = "broker.tabs")
    val otherHost = registration(FeatureId.Consumers, "Other host", host = FeatureId.Clusters)

    assertEquals(
      GuestTabs.of(List(elsewhere, otherHost), FeatureId.Topics, FeatureSlots.TopicTabs, noBody, context),
      Nil
    )
  }

  test("aFeatureThatRegistersNoGuestTabContributesNothing") {
    val plain: FeatureRoutes = new FeatureRoutes {
      def id: FeatureId = FeatureId.Clusters
      def nav: NavEntry = NavEntry(FeatureId.Clusters, "Clusters", () => svg.svg(), 1, requiresCluster = false)
      def landing: Page = new Page {}
      def routes(uiPrefix: String): List[Route[? <: Page, ?]] = Nil
      def encodePage(page: Page): Option[Json] = None
      def decodePage(tag: String, cursor: HCursor): Option[Page] = None
    }
    assertEquals(GuestTabs.of(List(plain), FeatureId.Topics, FeatureSlots.TopicTabs, noBody, context), Nil)
  }

  test("aGuestsPanelIsNotBuiltUntilItsTabIsOpened") {
    // `Tab.body` is a thunk for a reason, and it now matters more than it did: opening the tab is what
    // downloads the guest's module. If the body were built while the strip was drawn, every visit to a
    // topic page would fetch the consumers feature — which is the thing ADR-012 exists to prevent.
    val asked = scala.collection.mutable.ListBuffer.empty[FeatureId]
    val own = Val(List(Tab("overview", "Overview", () => div(dataAttr("testid") := "overview"))))
    val merged = GuestTabs.merged(
      own,
      FeatureId.Topics,
      FeatureSlots.TopicTabs,
      context,
      statics = List(registration(FeatureId.Messages, "Messages")),
      body = recordingBody(asked)
    )
    val selected = Var("overview")

    mounted(Tabs(merged, selected)) { root =>
      assertEquals(Option(root.querySelector("[data-testid='guest-body']")), None)
      assertEquals(asked.toList, Nil, "the guest's panel was built before anybody opened its tab")

      selected.set(FeatureId.Messages.value)

      assertEquals(asked.toList, List(FeatureId.Messages))
      // And when it is opened, it gets the host's context — including the topic name, under the key both
      // sides take from `FeatureSlots` rather than typing twice.
      assertEquals(byTestId(root, "guest-body").textContent, "orders")
    }
  }

  test("everySlotIdIsDeclaredOnce") {
    assert(FeatureSlots.all.contains(FeatureSlots.TopicTabs))
    assertEquals(FeatureSlots.all.distinct, FeatureSlots.all)
  }
}
