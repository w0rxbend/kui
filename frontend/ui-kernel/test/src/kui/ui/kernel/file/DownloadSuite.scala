package kui.ui.kernel.file

import munit.FunSuite

/** That an export is a save and not a navigation.
  *
  * One assertion, and it is the one this file exists for. The anchor an export is handed to the browser
  * through used to carry `data-download` rather than `download`, because it was built with Laminar's
  * `dataAttr`, which prefixes every name with `data-`. Everything else worked: the CSV was correct, the blob
  * was correct, the click happened. The browser simply had no filename and no instruction to save, so the
  * person who pressed Export CSV got no file they could find. Nothing in the type system can tell those two
  * attribute names apart, so this test does.
  */
final class DownloadSuite extends FunSuite {

  test("the anchor carries the filename in the attribute that makes a browser save it") {
    val anchor = Download.anchorFor("messages-orders.csv", "blob:http://localhost/abc")

    assertEquals(anchor.getAttribute("download"), "messages-orders.csv")
    assertEquals(anchor.getAttribute("data-download"), null)
  }

  test("the anchor points at the object URL it was given, and is not visible while it exists") {
    val anchor = Download.anchorFor("messages.csv", "blob:http://localhost/abc")

    assertEquals(anchor.getAttribute("href"), "blob:http://localhost/abc")
    assertEquals(anchor.style.display, "none")
  }
}
