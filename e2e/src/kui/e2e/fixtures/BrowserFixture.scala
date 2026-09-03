package kui.e2e.fixtures

import java.nio.file.{Files, Path}

import com.microsoft.playwright.{Browser, BrowserContext, BrowserType, Page, Playwright}

/** One Chromium, launched once, handing out a fresh browser context per test.
  *
  * ## Why a context per test and one browser per suite
  *
  * Launching a browser costs about a second; creating a context costs a few milliseconds. A context is a
  * complete profile — its own cookies, its own `localStorage`, its own permission grants — so a test that
  * switches the theme, or one that establishes a session, cannot leak either into the next test. That is
  * E2E-001's third rule ("each test starts from a fresh browser context, so ordering never matters") and it
  * is bought here for almost nothing.
  *
  * ## Headless
  *
  * Always. A headful browser needs a display server, which CI does not have, and a suite that only runs on a
  * developer's laptop is a suite that stops being run.
  */
final class BrowserFixture {

  private var playwright: Option[Playwright] = None
  private var browser: Option[Browser] = None

  def start(): Unit = {
    val created = Playwright.create()
    playwright = Some(created)
    browser = Some(
      created
        .chromium()
        .launch(new BrowserType.LaunchOptions().setHeadless(true))
    )
  }

  def stop(): Unit = {
    browser.foreach(_.close())
    playwright.foreach(_.close())
    browser = None
    playwright = None
  }

  /** A new, empty profile.
    *
    * The viewport is stated rather than left to the default so that a layout assertion means the same thing
    * on every machine, and it is large enough that the navigation drawer is not collapsed into its
    * small-screen form — which would be a perfectly good thing to test, and is a different test.
    */
  def newContext(): BrowserContext =
    browser
      .getOrElse(throw new IllegalStateException("the browser fixture was used before it was started"))
      .newContext(new Browser.NewContextOptions().setViewportSize(1440, 900))

  def newPage(context: BrowserContext): Page = context.newPage()
}

object BrowserFixture {

  /** Whether the pinned Chromium build has been downloaded.
    *
    * Checked by looking for the cache directory rather than by launching a browser and catching the failure,
    * because the answer decides whether the suites *run at all*, and it has to be cheap enough to ask before
    * every one of them.
    *
    * When the answer is no, the suites skip with the install command printed. Skipping and not failing,
    * because a developer who has never run the end-to-end tests should not have a red build they did not
    * cause; skipping *loudly*, because a green build that silently ran no browser test is how the milestone's
    * central claim stops being checked without anybody deciding to stop checking it.
    */
  def browserInstalled: Boolean = {
    val root = sys.env
      .get("PLAYWRIGHT_BROWSERS_PATH")
      .map(Path.of(_))
      .getOrElse(Path.of(System.getProperty("user.home"), ".cache", "ms-playwright"))

    Files.isDirectory(root) && {
      val listing = Files.list(root)
      try listing.anyMatch(entry => entry.getFileName.toString.startsWith("chromium-"))
      finally listing.close()
    }
  }

  val InstallHint: String =
    "the pinned Chromium build is not installed. Run `./mill e2e.installBrowser` " +
      "(add `--with-deps` on a fresh CI runner)."
}
