package kui.e2e

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.e2e.fixtures.RunningStack
import kui.e2e.pages.ShellPage

/** The fault-isolation story, once, for any service.
  *
  * PLAN §46 asks that "fault-isolation tests pass for every service introduced", which means this scenario is
  * written eleven times over the life of the project. Written out longhand each time it would be eleven
  * chances to check something slightly different and eleven places to fix when the shell's behaviour changes.
  * Written here it is one description, and M1's cluster test, M2's topic test and every later one are a few
  * lines each:
  *
  * {{{
  * val scenario = FaultIsolationScenario(
  *   serviceContainer = "kui-topics",
  *   serviceId = "topics",
  *   featureId = "topics",
  *   featureLabel = "Topics",
  *   unaffectedCheck = shell => { shell.navigation.click("settings"); assert(shell.settings.isVisible) },
  *   readinessInterval = 3.seconds
  * )
  * }}}
  *
  * ## Why the steps are public and `run` merely calls them in order
  *
  * Because the acceptance criterion for this task is a list of eight named test results, and a single test
  * called "the fault isolation scenario passes" would report one line — so a failure at the seventh step
  * would be indistinguishable from a failure at the first. A suite that wants the detailed report calls the
  * steps as separate tests; a later milestone that only wants the guarantee calls [[run]].
  *
  * ## Why every wait is expressed in readiness intervals
  *
  * Nothing here happens instantly, and nothing here happens at a fixed time either. The gateway notices a
  * dead service on its next readiness poll, which is somewhere between zero and one full interval away, and
  * the browser then has to be told. Three intervals is comfortably more than that and still short enough that
  * a genuinely stuck transition is reported rather than waited out. A fixed sleep would be flaky at one end
  * and slow at the other.
  *
  * @param serviceContainer
  *   the container to stop, by the name the Compose topology gives it: `kui-cluster`.
  * @param serviceId
  *   the service's identifier as the capability document names it. It is not always the feature's own
  *   identifier — the `clusters` feature is served by the `cluster` service — and conflating the two would
  *   make an assertion silently look up a service that does not exist and conclude nothing.
  * @param featureId
  *   the feature's identifier, which is also what its navigation entry's test id ends with.
  * @param featureLabel
  *   the label the sidebar shows, and the name the fallback panel is titled with.
  * @param unaffectedCheck
  *   what must keep working while the service is down. Supplied by the caller because only the caller knows
  *   what is unrelated to *this* feature; it is the assertion that turns "the feature degraded" into "the
  *   feature degraded and nothing else did".
  * @param readinessInterval
  *   how often the gateway polls, in the configuration under test. Every deadline is derived from it.
  */
final case class FaultIsolationScenario(
    serviceContainer: String,
    serviceId: String,
    featureId: String,
    featureLabel: String,
    unaffectedCheck: ShellPage => Unit,
    readinessInterval: FiniteDuration = 3.seconds
) {

  /** How long any one transition is allowed to take. */
  private def settle: FiniteDuration = readinessInterval * 3

  /** Opens the shell and checks the starting position: the feature is normal, and the API agrees.
    *
    * Both halves matter. A sidebar entry that looked normal while the API said the service was down would be
    * a UI that had stopped listening, which is a worse bug than a service outage because nothing about it
    * looks wrong.
    *
    * Answers the sentinel written into the page, which the recovery step reads back.
    */
  def start(suite: BaseE2ESuite, stack: RunningStack, shell: ShellPage): String = {
    val _ = shell.open("/ui/")

    suite.waitForCondition(s"the $featureLabel entry to be normal while the service is up", settle) {
      shell.navigation.entry(featureId).exists(entry => !entry.dimmed && !entry.disabled)
    }

    val capability = Capabilities.of(stack.baseUrl, serviceId)
    if !capability.map(_.status).contains("available") then
      suite.failWithArtifacts(
        s"expected /api/v1/capabilities to report $serviceId available at the start, " +
          s"and it said: ${capability.map(_.status).getOrElse("nothing at all")}"
      )

    shell.markSession()
  }

  /** Kills the service, and waits for the entry to go dim while staying clickable.
    *
    * "Dimmed and still clickable" is ADR-032's amendment and the reason the ADR exists: a disabled entry has
    * nowhere to put the reason, the "since", the retry or the list of what still works, so the user is left
    * with a grey word and no way forward.
    */
  def stopService(suite: BaseE2ESuite, stack: RunningStack, shell: ShellPage): Unit = {
    stack.stopService(serviceContainer)

    suite.waitForCondition(s"the $featureLabel entry to be dimmed after $serviceContainer stopped", settle) {
      shell.navigation.entry(featureId).exists(_.dimmed)
    }

    val entry = shell.navigation.entry(featureId)
    if !entry.exists(current => !current.disabled) then
      suite.failWithArtifacts(
        s"the $featureLabel entry is dimmed but no longer clickable, which is exactly what ADR-032 " +
          "forbids: the fallback panel with the reason and the retry is then unreachable"
      )
  }

  /** The API's account of the same outage: a reason code and a `since`, not just "false". */
  def capabilityReportsUnavailable(suite: BaseE2ESuite, stack: RunningStack): Capability = {
    suite.waitForCondition(s"/api/v1/capabilities to report $serviceId unavailable", settle) {
      Capabilities.of(stack.baseUrl, serviceId).exists(_.status == "unavailable")
    }

    val capability = Capabilities
      .of(stack.baseUrl, serviceId)
      .getOrElse(suite.failWithArtifacts("the capability document lost its entry entirely"))

    if capability.reason.isEmpty then
      suite.failWithArtifacts(
        "the capability is unavailable with no reason code; there is nothing to show a user"
      )
    if capability.since.isEmpty then
      suite.failWithArtifacts("the capability is unavailable with no `since`; 'how long' is unanswerable")

    capability
  }

  /** Clicks the dimmed entry and checks the panel has all four of the things a disabled entry could not have
    * carried.
    */
  def fallbackPanel(suite: BaseE2ESuite, shell: ShellPage, capability: Capability): Unit = {
    shell.navigation.click(featureId)

    suite.waitForCondition(s"the $featureLabel fallback panel to render", settle) {
      shell.fallback.isVisible
    }

    val panel = shell.fallback
    if panel.reason.isEmpty then suite.failWithArtifacts("the fallback panel shows no reason")
    if panel.since.isEmpty then suite.failWithArtifacts("the fallback panel shows no 'since'")
    if !panel.hasRetryButton then suite.failWithArtifacts("the fallback panel offers no retry")
    if panel.whatStillWorks.isEmpty && !shell.has("fallback-still-works") then
      suite.failWithArtifacts("the fallback panel has no 'what still works' list")

    // The panel must be showing the gateway's own timestamp rather than one it invented. A panel that
    // rendered "since just now" on every visit would look perfectly reasonable and be a lie.
    if !panel.sinceTimestamp.exists(capability.since.contains) then
      suite.failWithArtifacts(
        s"the panel's 'since' is ${panel.sinceTimestamp.getOrElse("absent")} but the gateway " +
          s"published ${capability.since.getOrElse("nothing")}"
      )
  }

  /** Retries while the service is still down: the probe runs, the answer is still "no", and nothing catches
    * fire.
    *
    * The absence of a toast storm is as much a part of this as the retry working. A user pressing a button on
    * a service that stays down must not accumulate a stack of identical notifications, because that is how a
    * notification area becomes something people dismiss without reading.
    */
  def retryWhileDown(suite: BaseE2ESuite, shell: ShellPage): Unit = {
    // The caller may have navigated away to check that something unrelated still works, which is
    // exactly what it should have been doing. Coming back is part of the step rather than a
    // precondition the caller has to remember.
    if !shell.fallback.isVisible then {
      shell.navigation.click(featureId)
      suite.waitForCondition(s"the $featureLabel fallback panel to render again", settle) {
        shell.fallback.isVisible
      }
    }

    val before = shell.toastMessages.size

    shell.fallback.retry()

    suite.waitForCondition("the retry to come back having found the service still unavailable", settle) {
      shell.fallback.retryError.isDefined || shell.fallback.isVisible
    }

    if !shell.fallback.isVisible then
      suite.failWithArtifacts("the fallback panel disappeared after a retry that could not have succeeded")

    val after = shell.toastMessages.size
    if after > before + 1 then
      suite.failWithArtifacts(
        s"one retry raised ${after - before} toasts; a retry storm is a notification storm"
      )
  }

  /** Brings the service back, and checks the shell healed itself without a page load.
    *
    * The sentinel is the whole assertion. Nothing else observable from outside the browser distinguishes "the
    * application updated itself" from "the application reloaded and looks the same", and the difference is
    * everything: a reload throws away every other feature's state and the user's place in the application.
    */
  def recover(suite: BaseE2ESuite, stack: RunningStack, shell: ShellPage, sentinel: String): Unit = {
    stack.startService(serviceContainer)

    suite.waitForCondition(
      s"the $featureLabel entry to return to normal after $serviceContainer restarted",
      settle * 4
    ) {
      shell.navigation.entry(featureId).exists(entry => !entry.dimmed && !entry.disabled)
    }

    if shell.sessionMark != sentinel then
      suite.failWithArtifacts(
        "the page was reloaded during recovery. Healing must be an update, not a reload: a reload " +
          "discards every other feature's loaded state and the user's place in the application"
      )
  }

  /** The whole story, for a milestone that wants the guarantee rather than the eight-line report. */
  def run(suite: BaseE2ESuite, stack: RunningStack, shell: ShellPage): Unit = {
    val sentinel = start(suite, stack, shell)
    stopService(suite, stack, shell)
    val capability = capabilityReportsUnavailable(suite, stack)
    fallbackPanel(suite, shell, capability)
    unaffectedCheck(shell)
    retryWhileDown(suite, shell)
    recover(suite, stack, shell, sentinel)
  }
}
