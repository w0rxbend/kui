package kui.ui.kernel.state

import com.raquo.airstream.web.WebStorageVar
import munit.FunSuite
import org.scalajs.dom

class ClusterColorsSuite extends FunSuite {

  override def beforeEach(context: BeforeEach): Unit = {
    ClusterColors.reset()
    dom.window.localStorage.clear()
  }

  test("aChosenColourPersistsAndIsReadBack") {
    ClusterColors.of("prod").set(ClusterColor.Danger)
    ClusterColors.reset()
    assertEquals(ClusterColors.of("prod").now(), ClusterColor.Danger)
  }

  test("anUnknownStoredValueReadsAsNone") {
    // `localStorage` outlives upgrades, so a value written by a later build can be read by an earlier one.
    // A corrupted preference must leave the sidebar working rather than blanking it.
    dom.window.localStorage.setItem(ClusterColors.keyOf("prod"), "chartreuse")
    assertEquals(ClusterColors.of("prod").now(), ClusterColor.None)
  }

  test("twoCallersForOneClusterShareOneVar") {
    // Otherwise the tag that shows the colour and the menu that changes it would be watching two values,
    // and only half the screen would move when somebody picked one.
    val tag = ClusterColors.of("prod")
    val picker = ClusterColors.of("prod")
    picker.set(ClusterColor.Warning)
    assertEquals(tag.now(), ClusterColor.Warning)
  }

  test("keysAreNamespacedPerCluster") {
    ClusterColors.of("prod").set(ClusterColor.Danger)
    assertEquals(ClusterColors.of("prod-eu").now(), ClusterColor.None)
    assertNotEquals(ClusterColors.keyOf("prod"), ClusterColors.keyOf("prod-eu"))
  }

  test("storageBeingUnavailableStillYieldsAWorkingVar") {
    // The private-window case, which the theme preference already handles the same way: the `Var` behaves
    // as an ordinary in-memory one and nothing is shown to the user, because a private window is not an
    // error state.
    val colour = ClusterColors.persisted(WebStorageVar.localStorage("kui.test.color", syncOwner = None))
    colour.set(ClusterColor.Success)
    assertEquals(colour.now(), ClusterColor.Success)
  }

  test("everyColourHasItsOwnClassAndTheDefaultHasOneToo") {
    val classes = ClusterColor.values.toList.map(ClusterColors.className)
    // Including `None`: the unset tag still occupies its space, so choosing a colour does not shift the row.
    assertEquals(classes.distinct.length, ClusterColor.values.length)
    assert(classes.forall(_.nonEmpty))
  }

  test("theNamesShownToAPersonAreColoursNotStates") {
    // Offering "Warning" as a marker would suggest the cluster *is* in a warning state, which is what the
    // status dot beside the tag actually means.
    assertEquals(ClusterColor.Warning.label, "Amber")
    assertEquals(ClusterColor.Danger.label, "Red")
  }
}
