package kui.e2e

import scala.concurrent.duration.DurationInt

import kui.e2e.pages.ShellPage

/** The milestone's central claim, proven in a browser against real separate processes.
  *
  * > Killing a service dims its entry, explains why, offers a retry, and leaves everything else
  * > working — and bringing it back heals the UI with no reload.
  *
  * ## Why this suite's tests are consecutive steps rather than independent cases
  *
  * They are one story told in eight lines. Every step needs the state the previous one left: the
  * service is down because the second test stopped it, and the seventh test can only ask "was the
  * page ever reloaded?" because the first test wrote a sentinel into the very page the seventh is
  * still looking at. Made independent, each test would have to stop and start containers of its own,
  * turning a ninety-second suite into a ten-minute one and testing the recovery path seven times
  * while testing the story once.
  *
  * The cost is that a failure early on cascades. That is paid for by the step names: the run output
  * says which step of the story broke, which is the thing a person reading a CI log needs to know.
  */
final class ClusterServiceDownSuite extends ComposeE2ESuite {

  /** The reusable shape. M1's cluster test, M2's topic test and every later one differ from this in
    * the four values above and in nothing else.
    */
  private val scenario = FaultIsolationScenario(
    serviceContainer = "kui-cluster",
    serviceId = "cluster",
    featureId = "clusters",
    featureLabel = "Clusters",
    unaffectedCheck = checkSettingsStillWorks,
    // Three seconds, set by `docker-compose.e2e.yml` for the tests only. The shipped default of ten
    // is asserted separately by CFG-001's defaults test, so this suite cannot come to depend on a
    // non-default configuration without the default still being checked.
    readinessInterval = 3.seconds
  )

  /** The sentinel written into the browser at the start, read back after recovery. */
  private var sentinel: String = ""

  /** The gateway's account of the outage, captured when it appears and compared against the panel. */
  private var reported: Option[Capability] = None

  private def page: ShellPage = shell

  test("entry is normal while the service is up") {
    sentinel = scenario.start(this, stack(), page)
    assert(sentinel.nonEmpty, "the session sentinel was not written, so no reload assertion is possible")
  }

  test("stopping the service dims the entry within the readiness interval") {
    scenario.stopService(this, stack(), page)
  }

  test("the capability API reports unavailable with a reason and a since") {
    reported = Some(scenario.capabilityReportsUnavailable(this, stack()))
  }

  test("the fallback panel shows reason, since, retry and what-still-works") {
    val capability = reported.getOrElse(fail("the previous step captured no capability"))
    scenario.fallbackPanel(this, page, capability)
  }

  test("settings and the shell keep working while the service is down") {
    // The assertion that separates "the feature degraded" from "the product degraded". One service
    // is dead; every part of KUI that does not depend on it must be untouched, and the way to find
    // out is to use one.
    scenario.unaffectedCheck(page)
  }

  test("retry while down probes and reports still-unavailable") {
    scenario.retryWhileDown(this, page)
  }

  test("starting the service restores the entry with no page reload") {
    scenario.recover(this, stack(), page, sentinel)
  }

  test("the dashboard fetches through the restarted service again") {
    // The last step of the story: not only is the entry no longer dimmed, a real request made after
    // recovery reaches the service and comes back. Before M1 this step pinged the sample feature;
    // it now loads the dashboard, which is the same round trip through the same chain.
    page.navigation.click("clusters")
    waitForCondition("the clusters dashboard to render after recovery") { page.clusters.isVisible }

    waitForCondition("a successful load through the restarted service") {
      page.clusters.onlineCount.isDefined && page.clusters.error.isEmpty
    }
  }

  /** What must keep working while the cluster service is down: a shell page with no service behind
    * it. If this breaks, the failure has escaped the feature it belongs to, which is precisely the
    * thing KUI claims cannot happen.
    */
  private def checkSettingsStillWorks(shell: ShellPage): Unit = {
    shell.navigation.click("settings")
    waitForCondition("Settings to render while the cluster service is down") {
      shell.settings.isVisible
    }
    assert(
      shell.navigation.labels.contains("Clusters"),
      "the navigation lost an entry while a service was down; the outage has spread to the frame"
    )
  }
}
