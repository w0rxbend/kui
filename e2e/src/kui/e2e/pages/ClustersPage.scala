package kui.e2e.pages

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page

/** The sample feature's page: a text field, a Ping button and a table of replies.
  *
  * Small on purpose, and worth testing precisely because of what one click exercises — the browser's contract
  * client, the gateway's contract-derived routing, the signed principal header, the service itself, and the
  * answer coming back through all of it into a table. If ping round-trips, the whole chain M1 will hang real
  * features on is proven to be connected.
  */
final class ClustersPage(page: Page) {

  def isVisible: Boolean = page.locator("[data-testid='page-clusters']").count() > 0

  /** Types a message and presses Ping. */
  def ping(message: String): Unit = {
    page.locator("[data-testid='ping-message']").fill(message)
    page.locator("[data-testid='ping-button']").click()
  }

  /** Every cell in the replies table, flattened.
    *
    * Flattened rather than row-shaped because the assertion is "the message I sent came back", and a row
    * structure would add a way for the test to be wrong about which column it was reading.
    */
  def replies: List[String] =
    page.locator("[data-testid='ping-table'] tbody td").allInnerTexts().asScala.toList.map(_.trim)

  /** The inline error, when the last ping failed. */
  def error: Option[String] = {
    val element = page.locator("[data-testid='ping-error']")
    if element.count() == 0 then None else Some(element.first().innerText().trim)
  }
}
