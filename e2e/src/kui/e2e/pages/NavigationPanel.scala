package kui.e2e.pages

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page

/** One navigation entry, as ADR-032's five rendering rules describe it.
  *
  * The fields mirror the ADR on purpose, so that an assertion reads like the rule it is checking rather than
  * like a query against markup: `entry.dimmed && !entry.disabled` is, word for word, "the `Unavailable` entry
  * is dimmed and still clickable".
  *
  * @param dimmed
  *   the `Unavailable` rendering. Read from the entry's `data-state` attribute rather than from a class name,
  *   because class names belong to the visual design and change with it.
  * @param disabled
  *   the `Forbidden` rendering: no `href` at all, and `aria-disabled`. Not reachable by keyboard, on purpose,
  *   which is the distinction from `dimmed`.
  * @param hasAmberDot
  *   the `Degraded` rendering: the page works, and there is a warning beside its name.
  * @param tooltip
  *   the sentence a user gets on hover, when there is one.
  */
final case class NavEntrySnapshot(
    label: String,
    dimmed: Boolean,
    disabled: Boolean,
    hasAmberDot: Boolean,
    tooltip: Option[String]
)

/** The navigation drawer.
  *
  * Everything here is read through `data-testid` and `data-state`. `data-state` is the attribute the sidebar
  * writes to say which of ADR-032's five states an entry is in; it exists for this test and survives any
  * restyle, which a class name would not.
  */
final class NavigationPanel(page: Page) {

  /** Every entry currently drawn, in the order the sidebar draws them.
    *
    * The order is part of what is being checked. ADR-032 requires that a feature going down changes how its
    * entry looks and never where it is, because a user aims at the position their muscle memory learned.
    */
  def entries: List[NavEntrySnapshot] = {
    // Scoped to the main navigation landmark by its ARIA name — rule 2's other permitted selector —
    // because a small-screen drawer can hold a second copy of the same entries, and counting them
    // twice would turn "the navigation is intact" into a assertion that passes for the wrong reason.
    val elements = page.locator("nav[aria-label='Main'] [data-testid^='nav-']").all().asScala.toList

    elements.map { element =>
      val testId = Option(element.getAttribute("data-testid")).getOrElse("")
      val state = Option(element.getAttribute("data-state")).getOrElse("")
      val tooltip = page.locator(s"#$testId-reason")

      NavEntrySnapshot(
        label = element.locator("span").first().innerText().trim,
        dimmed = state == "unavailable",
        disabled = state == "forbidden",
        hasAmberDot = state == "degraded",
        tooltip =
          if tooltip.count() > 0 && !tooltip.first().isHidden then Some(tooltip.first().innerText().trim)
          else None
      )
    }
  }

  /** One entry by the feature's identifier — `clusters`, `home`, `settings`. */
  def entry(feature: String): Option[NavEntrySnapshot] = {
    val element = page.locator(s"[data-testid='nav-$feature']")
    if element.count() == 0 then None
    else {
      val state = Option(element.first().getAttribute("data-state")).getOrElse("")
      val tooltip = page.locator(s"#nav-$feature-reason")
      Some(
        NavEntrySnapshot(
          label = element.first().locator("span").first().innerText().trim,
          dimmed = state == "unavailable",
          disabled = state == "forbidden",
          hasAmberDot = state == "degraded",
          tooltip =
            if tooltip.count() > 0 && !tooltip.first().isHidden then Some(tooltip.first().innerText().trim)
            else None
        )
      )
    }
  }

  /** Clicks an entry, exactly as a user would.
    *
    * Deliberately not "navigate to the entry's href": E2E-002's central assertion is that a *dimmed* entry is
    * still clickable, and following its address directly would prove nothing about the element being
    * clickable at all.
    */
  def click(feature: String): Unit = page.locator(s"[data-testid='nav-$feature']").click()

  /** The labels on screen, which is what "the navigation is intact" means. */
  def labels: List[String] = entries.map(_.label)
}
