package kui.e2e.pages

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page

/** The clusters feature's dashboard: a summary strip and one row per configured cluster.
  *
  * Worth testing precisely because of what rendering one row exercises — the browser's contract client, the
  * gateway's contract-derived routing, the signed principal header, the gateway's aggregation of the cluster
  * service's answer, and the answer coming back through all of it into a table. If a row appears, the whole
  * chain M1 hangs its features on is proven to be connected.
  *
  * Before M1 this page object drove a "Ping" sample feature. That feature was deleted with CLAPI-004, so
  * every selector here is the real dashboard's.
  */
final class ClustersPage(page: Page) {

  /** Whether the dashboard rendered at all. */
  def isVisible: Boolean = page.locator("[data-testid='page-clusters-dashboard']").count() > 0

  /** The number the summary strip shows for online clusters, when the strip has rendered.
    *
    * The figure's first `span` and not the whole element: the element holds the value and its label, so
    * reading it whole answers `"0\nonline"` rather than `"0"`.
    */
  def onlineCount: Option[String] = figureOf("cluster-summary-online")

  /** The number the summary strip shows for clusters that are not online. */
  def unavailableCount: Option[String] = figureOf("cluster-summary-unavailable")

  /** The cluster ids the table currently lists, in the order it lists them.
    *
    * Read from the row's own test id rather than from a cell, so the assertion cannot be wrong about which
    * column it was looking at.
    */
  def rowIds: List[String] =
    page
      .locator("[data-testid^='cluster-row-']:not([data-testid$='-link'])")
      .all()
      .asScala
      .toList
      .flatMap(row => Option(row.getAttribute("data-testid")))
      .map(_.stripPrefix("cluster-row-"))

  /** Every cell in the table, flattened. */
  def cells: List[String] =
    page.locator("[data-testid='clusters-table'] tbody td").allInnerTexts().asScala.toList.map(_.trim)

  /** The inline error shown when the very first load failed and there is nothing to fall back to. */
  def error: Option[String] = textOf("clusters-error")

  /** The panel the shell renders in place of the feature when the cluster capability is unavailable. */
  def unavailablePanel: Option[String] = textOf("clusters-unavailable")

  private def figureOf(testId: String): Option[String] = {
    val element = page.locator(s"[data-testid='$testId'] span").first()
    if element.count() == 0 then None else Some(element.innerText().trim)
  }

  private def textOf(testId: String): Option[String] = {
    val element = page.locator(s"[data-testid='$testId']")
    if element.count() == 0 then None else Some(element.first().innerText().trim)
  }
}
