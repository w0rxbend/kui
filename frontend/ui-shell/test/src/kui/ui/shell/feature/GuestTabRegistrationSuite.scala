package kui.ui.shell.feature

import munit.FunSuite

import kui.ui.kernel.feature.{FeatureId, FeatureSlots, GuestTabs, PanelContext}
import kui.ui.shell.FeatureRegistryImpl

/** That the Consumers tab on the topic page exists in a browser that has downloaded nothing.
  *
  * ==The defect==
  *
  * The tab used to be contributed only by the consumers feature's *loaded* module. Open a topic page in a
  * fresh browser tab and the strip was Overview and Settings; visit Consumers first and come back and it was
  * Overview, Settings and Consumers. Whether a screen exists is not a fact about where the user has been, and
  * a tab nobody can find is a feature nobody has.
  *
  * ==Why this suite is in the shell==
  *
  * The shell is the one module that can see every feature's registration at once, so it is the only place
  * where "the product offers this tab" is a statement anybody can make. The kernel's own suite asserts the
  * mechanism against fabricated registrations; this one asserts the real ones, so a feature that stops
  * declaring its tab fails here rather than going quietly missing from a screen.
  *
  * Nothing is downloaded to run it, which is the other half of the point.
  */
final class GuestTabRegistrationSuite extends FunSuite {

  private val context =
    PanelContext(cluster = Some("prod-eu"), params = Map(FeatureSlots.TopicParam -> "orders.v1"))

  private def topicPageGuests: List[String] =
    GuestTabs
      .of(
        FeatureRegistryImpl.staticRoutes,
        FeatureId.Topics,
        FeatureSlots.TopicTabs,
        (_, _, _, _) => com.raquo.laminar.api.L.div(),
        context
      )
      .map(_.label)

  test("theTopicPageOffersAConsumersTabBeforeAnyFeatureHasBeenDownloaded") {
    assert(
      topicPageGuests.contains("Consumers"),
      s"the topic page's guest tabs are $topicPageGuests, with nothing loaded"
    )
  }

  test("theGuestTabsAreNotDuplicatedByAFeatureDeclaringItsTabTwice") {
    assertEquals(topicPageGuests.distinct, topicPageGuests)
  }
}
