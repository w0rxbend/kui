package kui.ui.kernel.prefs

import scala.collection.mutable

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** That a star survives a reload, stays inside its cluster, and cannot take a page down with it. */
final class FavouritesSuite extends ScalaCheckSuite {

  /** A store in memory, so a case is not at the mercy of whatever an earlier one left in `localStorage`. */
  private final class Recording extends PreferenceStore {
    val entries: mutable.Map[String, String] = mutable.Map.empty
    def read(key: String): Option[String] = entries.get(key)
    def write(key: String, value: String): Unit = entries.update(key, value)
  }

  /** A browser with site data blocked: the accessor itself throws, before any read or write. */
  private val throwing: PreferenceStore = new PreferenceStore {
    def read(key: String): Option[String] = throw new RuntimeException("access to storage is denied")
    def write(key: String, value: String): Unit = throw new RuntimeException("access to storage is denied")
  }

  test("toggleAddsAndRemoves") {
    val favourites = new Favourites("kui.favourites.topics", new Recording)
    assert(!favourites.isFavourite("production", "orders"))
    favourites.toggle("production", "orders")
    assert(favourites.isFavourite("production", "orders"))
    favourites.toggle("production", "orders")
    assert(!favourites.isFavourite("production", "orders"))
  }

  test("aStarSurvivesAReload") {
    val store = new Recording
    new Favourites("kui.favourites.topics", store).toggle("production", "orders")
    // A second instance is what a page reload looks like from here.
    assert(new Favourites("kui.favourites.topics", store).isFavourite("production", "orders"))
  }

  test("favouritesArePerCluster") {
    val favourites = new Favourites("kui.favourites.topics", new Recording)
    favourites.toggle("production", "orders")
    assert(!favourites.isFavourite("staging", "orders"))
  }

  test("namespacesDoNotCollide") {
    // Topics' stars and, from M4, consumer groups' stars share one origin and one cluster id.
    val store = new Recording
    new Favourites("kui.favourites.topics", store).toggle("production", "orders")
    assert(!new Favourites("kui.favourites.groups", store).isFavourite("production", "orders"))
  }

  property("pinKeepsTheGivenOrderWithinEachGroup") {
    forAll(Gen.listOf(Gen.identifier), Gen.listOf(Gen.identifier)) {
      (names: List[String], starred: List[String]) =>
        val favourites = new Favourites("kui.favourites.topics", new Recording)
        starred.distinct.foreach(name => favourites.toggle("production", name))

        val pinned = favourites.pin("production", names)(identity)
        val (first, rest) = pinned.span(name => starred.contains(name))

        // Same items, no duplicates and none lost.
        assertEquals(pinned.sorted, names.sorted)
        // Starred first, and nothing starred after the first unstarred one.
        assert(!rest.exists(starred.contains), pinned.toString)
        // Stable within each group: each half is in the input's own order.
        assertEquals(first, names.filter(starred.contains))
        assertEquals(rest, names.filterNot(starred.contains))
    }
  }

  test("aThrowingLocalStorageDegradesToNothingStarred") {
    // In a browser with site data blocked, `window.localStorage` throws on property access. A
    // preference that cannot be stored must degrade to "nothing is starred", never to a broken page.
    val favourites = new Favourites("kui.favourites.topics", throwing)
    assert(!favourites.isFavourite("production", "orders"))
    favourites.toggle("production", "orders")
    assertEquals(favourites.pin("production", List("orders", "payments"))(identity), List("orders", "payments"))
  }

  test("aCorruptStoredValueIsIgnoredNotFatal") {
    // `localStorage` outlives upgrades and is shared by every KUI on the origin, so the value here may
    // have been written by a different version, or by something else entirely.
    List("not json at all", "{\"orders\":true}", "[1,2,3]", "", "null").foreach { corrupt =>
      val store = new Recording
      store.write("kui.favourites.topics.production", corrupt)
      val favourites = new Favourites("kui.favourites.topics", store)
      assert(!favourites.isFavourite("production", "orders"), s"survived: $corrupt")
    }
  }

  test("theSignalReflectsAToggle") {
    val favourites = new Favourites("kui.favourites.topics", new Recording)
    var seen = List.empty[Set[String]]
    val subscription =
      favourites.signal("production").foreach(set => seen = seen :+ set)(using com.raquo.laminar.api.L.unsafeWindowOwner)

    favourites.toggle("production", "orders")

    try assertEquals(seen, List(Set.empty[String], Set("orders")))
    finally subscription.kill()
  }
}
