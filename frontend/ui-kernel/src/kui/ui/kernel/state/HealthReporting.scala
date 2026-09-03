package kui.ui.kernel.state

import kui.ui.kernel.api.ApiError

/** Whose request failed.
  *
  * This distinction is small to write down and very visible to get wrong. The full-screen "cannot reach the
  * gateway" state takes the entire application away from the user. It is the right thing to do when the
  * gateway is genuinely not there — nothing works, and pretending otherwise wastes the user's time — and it
  * is a catastrophe when one feature's endpoint being down triggers it, because everything *else* still
  * worked and the user has just been thrown out of it.
  *
  * So a call declares which it is. Only the shell's own calls — `/auth/me`, `/info`, the capability endpoints
  * — can lead to the full-screen state; a feature's failure is that feature's fallback panel's job (ADR-032).
  */
enum CallScope {

  /** The shell's own calls. Their failure means KUI itself cannot talk to the gateway. */
  case Shell

  /** A feature's call. Its failure means that feature cannot show its data, and nothing more. */
  case Feature
}

object CallScope {
  given CanEqual[CallScope, CallScope] = CanEqual.derived
}

/** Where a feature files the outcome of one of its calls.
  *
  * ## Why this indirection exists
  *
  * The tracker that acts on these reports lives in the shell (`ShellHealth`), because deciding to take the
  * whole application off the air is the shell's decision to make. But a feature cannot call the shell: the
  * shell depends on every feature, so a feature depending on the shell would be a cycle, and the module
  * boundary that makes lazy loading possible would be gone.
  *
  * So the kernel — which both can see — holds the seam. The shell installs its tracker during start-up; a
  * feature reports through here and never learns what happens to the report. Before installation, and in a
  * unit test that installs nothing, reporting is a no-op: a feature's suite must not need a connectivity
  * tracker in order to make a request.
  */
object HealthReporting {

  private var reporter: (CallScope, Either[ApiError, Any]) => Unit = (_, _) => ()

  /** Called once, by the shell. */
  def install(report: (CallScope, Either[ApiError, Any]) => Unit): Unit = reporter = report

  def report(scope: CallScope, outcome: Either[ApiError, Any]): Unit = reporter(scope, outcome)
}
