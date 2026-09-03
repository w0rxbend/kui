package kui.ui.shell

import scala.concurrent.duration.*
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.ui.kernel.api.ApiError

/** Whose request failed.
  *
  * This distinction is the whole point of `ShellHealth`, and it is worth stating plainly because getting it
  * wrong is both easy and very visible. The full-screen "cannot reach gateway" state takes the entire
  * application away from the user. It is the right thing to do when the gateway is genuinely not there —
  * nothing works, and pretending otherwise wastes the user's time — and it is a catastrophe when it is
  * triggered by one feature's endpoint being down, because everything *else* still worked and the user has
  * just been thrown out of it.
  *
  * So a call declares which it is, and only the shell's own calls — `/auth/me`, `/info`, the capability
  * endpoints — can lead to the full-screen state. A feature's failure is the feature's fallback panel's job
  * (ADR-032).
  */
enum CallScope {

  /** The shell's own calls. Their failure means KUI itself cannot talk to the gateway. */
  case Shell

  /** A feature's call. Its failure means that feature cannot show its data, and nothing more. */
  case Feature
}

/** Whether KUI can talk to the gateway at all.
  *
  * @param lastContact
  *   when something last answered. Shown on the full-screen state, because "a moment ago" and "at 09:14" mean
  *   very different things to somebody deciding whether to call an operator.
  * @param nextRetryInSeconds
  *   counts down to the next automatic attempt. Visible, so that the page is demonstrably doing something — a
  *   screen that says "cannot connect" and then sits still reads as frozen, and the user reloads, which
  *   throws away every bit of state the application still had.
  */
enum ShellConnectivity {
  case Connected(lastContact: js.Date)
  case Lost(since: js.Date, lastContact: js.Date, nextRetryInSeconds: Int)
}

object ShellConnectivity {
  given CanEqual[ShellConnectivity, ShellConnectivity] = CanEqual.derived
}

/** Tracks whether the gateway is answering, and schedules the automatic retry.
  *
  * ## Why three failures and not one
  *
  * A single failed request is not an outage. A laptop's wifi hiccups, a proxy drops one connection, a phone
  * changes cell — and if one failure took the whole application away from the user, the full-screen state
  * would flash on screen several times a day for people whose network is merely ordinary. Three consecutive
  * failures with no success in between is a much better signal, and it costs at most a few seconds of delay
  * in the case that really is an outage.
  *
  * ## Why the clock and the timer are parameters
  *
  * Everything here is about time — a countdown, a doubling backoff, a cap — and a suite that waits real
  * seconds for a thirty-second cap is a suite nobody runs. The same arrangement `Notifications`, `Theme` and
  * `Capabilities` already use.
  *
  * @param onRetry
  *   what an attempt actually is. The shell passes a function that re-runs its own start-up calls; a suite
  *   passes a counter. Nothing here knows how to make an HTTP request.
  */
final class Health(
    now: () => js.Date,
    schedule: (FiniteDuration, () => Unit) => Unit,
    onRetry: () => Unit
) {

  private val state: Var[ShellConnectivity] = Var(ShellConnectivity.Connected(now()))

  /** How many shell calls have failed in a row. Reset by any success. */
  private var consecutiveFailures: Int = 0

  /** How long the next wait is. Doubles per attempt, capped — see [[Health.backoffAfter]]. */
  private var currentBackoff: FiniteDuration = Health.FirstBackoff

  /** Whether a countdown is already running, so that two failures do not start two of them. */
  private var countingDown: Boolean = false

  val connectivity: Signal[ShellConnectivity] = state.signal

  /** Files the outcome of one call.
    *
    * A success from *either* scope counts as contact: if a feature's request came back, the gateway is
    * reachable, whatever the shell's own last attempt did. A failure only counts when it is the shell's own
    * and it is a transport failure — a `403` or a `404` is the gateway answering, and answering is the
    * opposite of being unreachable.
    */
  def report(scope: CallScope, outcome: Either[ApiError, Any]): Unit =
    outcome match {
      case Right(_) => reportSuccess()
      case Left(failure) if failure.isTransport && scope == CallScope.Shell => reportUnreachable()
      case Left(_) => ()
    }

  /** Something answered. Restores the application with no reload. */
  def reportSuccess(): Unit = {
    consecutiveFailures = 0
    currentBackoff = Health.FirstBackoff
    countingDown = false
    state.set(ShellConnectivity.Connected(now()))
  }

  /** One of the shell's own calls could not reach the gateway. */
  def reportUnreachable(): Unit = {
    consecutiveFailures += 1
    if consecutiveFailures >= Health.FailuresBeforeGivingUp then lose()
  }

  /** The user pressed "Try again". Attempts immediately and restarts the countdown from the first wait.
    *
    * Resetting the backoff is deliberate: a user pressing the button is evidence that they believe something
    * has changed — they have just reconnected to the wifi — and making them wait out a thirty-second timer
    * they did not choose is the sort of thing that gets an application reloaded.
    */
  def retryNow(): Unit = {
    currentBackoff = Health.FirstBackoff
    onRetry()
    state.update {
      case ShellConnectivity.Lost(since, lastContact, _) =>
        ShellConnectivity.Lost(since, lastContact, currentBackoff.toSeconds.toInt)
      case connected => connected
    }
  }

  private def lose(): Unit = {
    val lastContact = state.now() match {
      case ShellConnectivity.Connected(at) => at
      case ShellConnectivity.Lost(_, at, _) => at
    }

    state.update {
      // Already lost: keep the original `since`, because the question the user is asking is "how
      // long has this been broken?" and restamping it on every failed retry answers a different and
      // much less useful one.
      case lost: ShellConnectivity.Lost => lost
      case ShellConnectivity.Connected(_) =>
        ShellConnectivity.Lost(now(), lastContact, currentBackoff.toSeconds.toInt)
    }

    if !countingDown then {
      countingDown = true
      tick()
    }
  }

  /** One second of the countdown. When it reaches zero, an attempt is made and the wait doubles. */
  private def tick(): Unit =
    schedule(
      1.second,
      () =>
        if countingDown then {
          state.now() match {
            case ShellConnectivity.Lost(since, lastContact, remaining) if remaining <= 1 =>
              currentBackoff = Health.backoffAfter(currentBackoff)
              state.set(ShellConnectivity.Lost(since, lastContact, currentBackoff.toSeconds.toInt))
              onRetry()
            case ShellConnectivity.Lost(since, lastContact, remaining) =>
              state.set(ShellConnectivity.Lost(since, lastContact, remaining - 1))
            case ShellConnectivity.Connected(_) => countingDown = false
          }
          tick()
        }
    )
}

object Health {

  /** How many consecutive shell-call failures it takes. See the class comment for why it is not one. */
  val FailuresBeforeGivingUp: Int = 3

  val FirstBackoff: FiniteDuration = 2.seconds

  /** The longest KUI ever waits between attempts.
    *
    * Low, because the cost of an extra request against a gateway that is down is negligible and the cost of
    * making a user wait while it is already back is a reload.
    */
  val MaxBackoff: FiniteDuration = 30.seconds

  /** 2 s, 4 s, 8 s, 16 s, then 30 s for ever. */
  def backoffAfter(current: FiniteDuration): FiniteDuration =
    if current * 2 > MaxBackoff then MaxBackoff else current * 2
}

/** The application's one connectivity tracker. */
object ShellHealth {

  private var retryAction: () => Unit = () => ()

  private lazy val current: Health =
    new Health(
      now = () => new js.Date(),
      schedule = (delay, action) => dom.window.setTimeout(() => action(), delay.toMillis.toDouble): Unit,
      onRetry = () => retryAction()
    )

  /** Tells the tracker how to attempt contact. Called once by the shell, with its own start-up calls. */
  def onRetry(action: () => Unit): Unit = retryAction = action

  def connectivity: Signal[ShellConnectivity] = current.connectivity

  def report(scope: CallScope, outcome: Either[ApiError, Any]): Unit = current.report(scope, outcome)

  def reportSuccess(): Unit = current.reportSuccess()

  def reportUnreachable(): Unit = current.reportUnreachable()

  def retryNow(): Unit = current.retryNow()
}
