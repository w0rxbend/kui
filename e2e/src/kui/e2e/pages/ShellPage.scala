package kui.e2e.pages

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState

/** The application frame: whatever is on screen, there is one of these.
  *
  * ## What a page object is for here
  *
  * Every selector in the suites lives in one of these classes and nowhere else. That is the whole mechanism
  * by which a restyle — which is happening to this frontend right now — cannot break the tests: when the
  * markup moves, one line in one page object moves with it, and no assertion changes. A suite that reached
  * for `page.locator(".kui-sidebar__link--dimmed")` would have to be rewritten by whoever changed the
  * stylesheet, which is how end-to-end suites end up deleted.
  *
  * Selectors are `data-testid` attributes, which exist for exactly this purpose and are the one part of the
  * markup a restyle is not allowed to change.
  *
  * @param baseUrl
  *   where this KUI is listening. Taken as a parameter rather than read from the page, because [[open]] has
  *   to work before there is a page to read it from.
  */
final class ShellPage(page: Page, baseUrl: String) {

  /** Opens a path under the deployment and waits until the application has rendered.
    *
    * The wait matters more than it looks. `navigate` comes back when the HTML document has loaded, which for
    * a Scala.js application is before a single element of the interface exists, so a test that asserted
    * immediately would be asserting against an empty `<body>` — sometimes.
    */
  def open(path: String): ShellPage = {
    val _ = page.navigate(s"$baseUrl$path")
    page.waitForLoadState(LoadState.NETWORKIDLE)
    val _ = page.waitForSelector("[data-testid='brand-link']")
    this
  }

  def navigation: NavigationPanel = new NavigationPanel(page)

  def clusters: ClustersPage = new ClustersPage(page)

  def fallback: FallbackPanel = new FallbackPanel(page)

  def errorPage: ErrorPage = new ErrorPage(page)

  def settings: SettingsPage = new SettingsPage(page)

  /** The build the browser is running, as the header reports it. */
  def version: String = page.locator("[data-testid='build-version']").innerText().trim

  /** Every toast currently on screen, by its title.
    *
    * A list and not a count, because two of E2E-002's assertions are about *how many* notifications a single
    * transition raises, and a failure that says which ones were on screen is much easier to act on than one
    * that says "expected 1, got 3".
    */
  def toastMessages: List[String] =
    page.locator("[data-testid='toast']").allInnerTexts().asScala.toList.map(_.trim)

  /** The theme `<html>` is currently in: `light`, `dark`, or empty when following the system. */
  def themeAttribute: String =
    Option(page.locator("html").getAttribute("data-theme")).getOrElse("")

  /** Cycles the theme, through the control a user would use. */
  def switchTheme(): Unit = page.locator("[data-testid='theme-switch']").click()

  /** Reloads the page and waits for the application again — for the tests that are *about* a reload
    * surviving, and nothing else.
    */
  def reload(): ShellPage = {
    val _ = page.reload()
    page.waitForLoadState(LoadState.NETWORKIDLE)
    val _ = page.waitForSelector("[data-testid='brand-link']")
    this
  }

  /** Writes a value into `window` that a page load would destroy.
    *
    * This is how "the single-page application survived without reloading" becomes an assertion rather than an
    * impression. A navigation inside the application leaves the JavaScript context alone and the sentinel is
    * still there; anything that reloaded the document — including the application deciding to reload itself
    * in response to a failure — starts a new context, and the sentinel is gone. Nothing else observable from
    * the outside distinguishes the two.
    */
  def markSession(): String = {
    val marker = java.util.UUID.randomUUID().toString
    val _ = page.evaluate(s"() => { window.__kuiLoadedAt = '$marker'; }")
    marker
  }

  /** The sentinel [[markSession]] wrote, or empty when the document has been reloaded since. */
  def sessionMark: String =
    Option(page.evaluate("() => window.__kuiLoadedAt")).map(_.toString).getOrElse("")

  /** Whether an element with this test identifier is on screen. */
  def has(testId: String): Boolean = page.locator(s"[data-testid='$testId']").count() > 0
}
