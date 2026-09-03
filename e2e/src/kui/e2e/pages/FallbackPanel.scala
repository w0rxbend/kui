package kui.e2e.pages

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page

/** What a route renders instead of a feature whose service is not there (ADR-032).
  *
  * The four things on it are the whole reason ADR-032 amended the original plan from "disable the entry" to
  * "dim it and keep it clickable": a disabled entry has nowhere to put the reason, the "since", a retry or a
  * list of what still works, and the user is left with a grey word. So this page object exposes exactly those
  * four, and E2E-002 asserts all of them.
  */
final class FallbackPanel(page: Page) {

  def isVisible: Boolean = page.locator("[data-testid='feature-fallback']").count() > 0

  /** The explanation, as a sentence. */
  def reason: String = page.locator("[data-testid='fallback-reason']").innerText().trim

  /** The "since" line, absent when the gateway did not report one. */
  def since: Option[String] = {
    val element = page.locator("[data-testid='fallback-since']")
    if element.count() == 0 then None else Some(element.first().innerText().trim)
  }

  /** The machine-readable timestamp inside the "since" line, which is the value the capability API published.
    * Comparing the two is what proves the panel is showing what the gateway said rather than something it
    * computed for itself.
    */
  def sinceTimestamp: Option[String] =
    Option(page.locator("[data-testid='fallback-since'] time").getAttribute("data-datetime"))

  def hasRetryButton: Boolean = page.locator("[data-testid='fallback-retry']").count() > 0

  def retry(): Unit = page.locator("[data-testid='fallback-retry']").click()

  /** The error shown next to the retry button when the probe failed again. */
  def retryError: Option[String] = {
    val element = page.locator("[data-testid='fallback-retry-error']")
    if element.count() == 0 then None else Some(element.first().innerText().trim)
  }

  /** The other features that still work — the single most useful sentence on the panel for a user who came to
    * do something else.
    */
  def whatStillWorks: List[String] =
    page
      .locator("[data-testid='fallback-still-works'] li")
      .allInnerTexts()
      .asScala
      .toList
      .map(_.trim)
}
