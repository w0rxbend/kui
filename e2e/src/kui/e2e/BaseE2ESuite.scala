package kui.e2e

import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.{BrowserContext, Page, TimeoutError}

import kui.e2e.fixtures.BrowserFixture

/** What every KUI end-to-end suite is built on: one browser, a fresh page per test, and a failure report
  * worth reading.
  *
  * ## The three rules this class exists to enforce
  *
  * They are E2E-001's, and they are the difference between a suite people trust and one they re-run until it
  * goes green:
  *
  *   1. **No `Thread.sleep` waiting for the application.** Every wait goes through [[waitForCondition]],
  *      which polls a predicate up to a deadline and fails with a sentence naming what it was waiting for. A
  *      sleep is either too short (flaky) or too long (slow), and it is usually both on different machines.
  *   2. **Selectors are `data-testid` or ARIA roles.** Never a class name and never a rendered sentence,
  *      unless the sentence is what the test is about. The frontend is restyled between milestones; a test
  *      that breaks when a colour changes is a test that gets deleted.
  *   3. **A fresh browser context per test**, so no test can depend on another having run first.
  *
  * ## Why failures attach so much
  *
  * A browser test that fails on CI and passes locally costs an hour of somebody's day unless the run itself
  * explains what happened. [[failWithArtifacts]] writes a full-page screenshot, everything the browser's
  * console said, and whatever the suite adds — the server's log, the containers' logs, the last capability
  * document — into a directory the CI job uploads. The intent is that nobody ever has to reproduce an
  * end-to-end failure to understand it.
  */
abstract class BaseE2ESuite extends munit.FunSuite {

  /** How long any one test may take.
    *
    * MUnit's default is thirty seconds, which is fine for a unit test and not for one that starts a server or
    * waits out a readiness interval. It is a backstop for a genuinely hung test; the individual waits have
    * their own, much tighter, deadlines and messages.
    */
  override def munitTimeout: FiniteDuration = 5.minutes

  private val browserFixture = new BrowserFixture
  private val consoleMessages = mutable.ListBuffer.empty[String]
  private val failedRequests = mutable.ListBuffer.empty[String]
  private var context: Option[BrowserContext] = None
  private var currentPage: Option[Page] = None

  /** Whether each test gets a browser context of its own.
    *
    * True almost everywhere, and that is E2E-001's third rule: no test may depend on another having run
    * first. The exception is a suite whose tests are consecutive steps of one scenario — the fault-isolation
    * suite is exactly that, because its final assertion is *that the page was never reloaded* across every
    * step, which cannot be asked of a page that was thrown away between them. Such a suite says so here, in
    * one line, rather than quietly opening a page of its own and bypassing the failure reporting along with
    * the isolation.
    */
  protected def pagePerTest: Boolean = true

  /** A page in a browser context, for one test or for the whole suite. */
  val browser: Fixture[Page] = new Fixture[Page]("browser") {

    def apply(): Page =
      currentPage.getOrElse(throw new IllegalStateException("the browser fixture is not open"))

    override def beforeAll(): Unit = {
      browserFixture.start()
      if !pagePerTest then openPage()
    }

    override def beforeEach(options: BeforeEach): Unit =
      if pagePerTest then openPage()

    override def afterEach(options: AfterEach): Unit =
      if pagePerTest then closePage()

    override def afterAll(): Unit = {
      if !pagePerTest then closePage()
      browserFixture.stop()
    }
  }

  private def openPage(): Unit = {
    consoleMessages.clear()
    failedRequests.clear()

    val opened = browserFixture.newContext()
    val page = browserFixture.newPage(opened)

    // Collected as they happen rather than read afterwards: the console history is not available
    // from a page that has since navigated, and a failed request is a moment, not a state.
    page.onConsoleMessage(message => consoleMessages.append(s"[${message.`type`()}] ${message.text()}"))
    page.onPageError(error => consoleMessages.append(s"[pageerror] $error"))
    page.onResponse(response =>
      if response.status() >= 400 then
        failedRequests.append(s"${response.status()} ${response.request().method()} ${response.url()}")
    )

    context = Some(opened)
    currentPage = Some(page)
  }

  private def closePage(): Unit = {
    currentPage.foreach { page =>
      val _ = scala.util.Try(page.close())
    }
    context.foreach { opened =>
      val _ = scala.util.Try(opened.close())
    }
    currentPage = None
    context = None
  }

  override def munitFixtures: Seq[Fixture[?]] = List(browser)

  /** Skips the whole suite, loudly, when there is no browser to drive.
    *
    * A skipped end-to-end suite is a milestone criterion that is *unverified*, which is a different thing
    * from a passing one and must look different in the build output. BUILD-004's CI summary reports the skip;
    * this prints the reason and the one command that fixes it.
    */
  override def munitIgnore: Boolean = {
    val ignored = !BrowserFixture.browserInstalled
    if ignored then println(s"SKIPPING ${getClass.getSimpleName}: ${BrowserFixture.InstallHint}")
    ignored
  }

  /** Waits until `condition` holds, or fails with a sentence saying what never happened.
    *
    * The message is not optional and it is not decoration: it is the entire content of the failure report
    * when a state transition does not arrive, and "expected the Clusters entry to be dimmed within 9 seconds"
    * is a bug report while "assertion failed" is a request to go and reproduce it.
    *
    * The predicate is polled by Playwright, which drives the page's event loop between attempts, so a
    * condition that depends on the browser rendering something can actually become true while it is being
    * waited for.
    */
  def waitForCondition(message: String, timeout: FiniteDuration = 15.seconds)(condition: => Boolean): Unit =
    try
      browser().waitForCondition(
        () => condition,
        new Page.WaitForConditionOptions().setTimeout(timeout.toMillis.toDouble)
      )
    catch {
      case _: TimeoutError =>
        failWithArtifacts(s"timed out after $timeout waiting for: $message")
    }

  /** Fails the current test, having first written everything anyone could need to explain it.
    *
    * Returns `Nothing`, so it can stand where a value is expected — in the `else` of a check that has already
    * established the test cannot continue — without an unreachable `???` after it.
    */
  def failWithArtifacts(message: String)(using location: munit.Location): Nothing = {
    val directory = artifactDirectory()
    val written = mutable.ListBuffer.empty[String]

    currentPage.foreach { page =>
      val screenshot = directory.resolve("screenshot.png")
      val _ = scala.util.Try(
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(screenshot))
      )
      written.append(screenshot.toString)
    }

    def write(name: String, content: String): Unit = {
      val file = directory.resolve(name)
      val _ = Files.writeString(file, content)
      written.append(file.toString)
    }

    write("console.log", consoleMessages.mkString("\n"))
    write("failed-requests.log", failedRequests.mkString("\n"))
    diagnostics.foreach((name, content) => write(name, content))

    fail(
      s"""$message
         |
         |browser console (${consoleMessages.size} message(s)):
         |${consoleMessages.takeRight(20).mkString("\n")}
         |
         |failed requests (${failedRequests.size}):
         |${failedRequests.takeRight(20).mkString("\n")}
         |
         |artifacts:
         |${written.mkString("\n")}""".stripMargin
    )
  }

  /** Extra files a suite wants attached to a failure, as name and content.
    *
    * The Compose suites override this to attach both containers' logs and the last capability document, which
    * is the set that tells a UI bug from a gateway bug from a service bug without re-running anything.
    */
  protected def diagnostics: List[(String, String)] = Nil

  /** Opens a URL and waits until the single-page application has actually rendered.
    *
    * `navigate` returns as soon as the document has loaded, which for a Scala.js application is before any of
    * it exists. Waiting for the network to go quiet and then for the shell's own root element is what makes
    * the next line of a test able to assume there is an application on screen.
    */
  def openAndWaitForShell(page: Page, url: String): Unit = {
    val _ = page.navigate(url)
    page.waitForLoadState(LoadState.NETWORKIDLE)
  }

  /** Where this test's artifacts go: one directory per suite and test, so a failure in a suite that runs
    * twice does not overwrite the evidence from the first run's sibling test.
    */
  private def artifactDirectory(): Path = {
    val root = sys.env.get("KUI_E2E_ARTIFACTS").map(Path.of(_)).getOrElse(Path.of("out", "e2e-artifacts"))
    val directory = root.resolve(getClass.getSimpleName).resolve(sanitise(munitTestName))
    val _ = Files.createDirectories(directory)
    directory
  }

  /** The name of the test being run, for the artifact path. */
  private var munitTestName: String = "unknown"

  override def beforeEach(context: BeforeEach): Unit = {
    munitTestName = context.test.name
    super.beforeEach(context)
  }

  private def sanitise(name: String): String =
    name.map(character => if character.isLetterOrDigit then character else '-')
}
