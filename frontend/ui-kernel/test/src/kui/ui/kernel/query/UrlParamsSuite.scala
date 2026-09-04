package kui.ui.kernel.query

import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.scalajs.dom

/** That a screen's state can live in the address bar without one control erasing another's. */
final class UrlParamsSuite extends FunSuite {

  private def at(search: String): Unit = {
    dom.window.history.replaceState(null, "", s"/ui/clusters/production/topics$search")
    UrlParams.resync()
  }

  private def currentSearch: String = dom.window.location.search

  test("parseReadsAQueryString") {
    assertEquals(UrlParams.parse("?q=orders&page=3"), Map("q" -> "orders", "page" -> "3"))
  }

  test("parseTreatsAnEmptyValueAsAValue") {
    // `q=` means "the user cleared the search box", which is a different request from no `q` at all.
    assertEquals(UrlParams.parse("?q=&page=1"), Map("q" -> "", "page" -> "1"))
    assertEquals(UrlParams.parse(""), Map.empty[String, String])
    assertEquals(UrlParams.parse("?"), Map.empty[String, String])
  }

  test("parseDecodesEscapes") {
    assertEquals(UrlParams.parse("?q=order%20flow"), Map("q" -> "order flow"))
    assertEquals(UrlParams.parse("?q=order+flow"), Map("q" -> "order flow"))
  }

  test("renderIsStableSoTwoLinksToOneScreenCompareEqual") {
    val rendered = UrlParams.render(Map("page" -> "2", "q" -> "orders"))
    assertEquals(rendered, "?page=2&q=orders")
    assertEquals(UrlParams.render(Map("q" -> "orders", "page" -> "2")), rendered)
    assertEquals(UrlParams.render(Map.empty), "")
  }

  test("getReadsWhatIsInTheAddressBar") {
    at("?q=orders&page=3")
    assertEquals(UrlParams.get("q"), Some("orders"))
    assertEquals(UrlParams.get("mode"), None)
  }

  test("setWithNoneRemovesTheParameter") {
    at("?q=orders&page=3")
    UrlParams.set(Map("q" -> None))
    assertEquals(UrlParams.get("q"), None)
    assert(!currentSearch.contains("q="), currentSearch)
  }

  test("unrelatedParametersAreLeftAlone") {
    // The assertion that stops one control erasing another's state: the page-size selector knows
    // nothing about the search box, and must not clear it by writing the whole query string.
    at("?q=orders&page=3&showInternal=true")
    UrlParams.set(Map("pageSize" -> Some("100")))

    assertEquals(UrlParams.get("q"), Some("orders"))
    assertEquals(UrlParams.get("page"), Some("3"))
    assertEquals(UrlParams.get("showInternal"), Some("true"))
    assertEquals(UrlParams.get("pageSize"), Some("100"))
  }

  test("signalUpdatesOnHistoryChange") {
    at("?q=orders")
    var seen = List.empty[Option[String]]
    val subscription = UrlParams.signal("q").foreach(value => seen = seen :+ value)(using unsafeWindowOwner)

    UrlParams.set(Map("q" -> Some("payments")))
    // Back: the URL changes with no navigation, and `popstate` is the only notification of it.
    dom.window.history.back()

    try {
      assertEquals(seen.headOption, Some(Some("orders")))
      assert(seen.contains(Some("payments")), seen.toString)
    } finally subscription.kill()
  }

  test("settingAValueThatIsAlreadyThereAddsNoHistoryEntry") {
    // Otherwise a debounced keystroke that produced the same query as the last one would need two
    // presses of Back to undo one visible change.
    at("?q=orders")
    val before = dom.window.history.length
    UrlParams.set(Map("q" -> Some("orders")))
    assertEquals(dom.window.history.length, before)
  }
}
