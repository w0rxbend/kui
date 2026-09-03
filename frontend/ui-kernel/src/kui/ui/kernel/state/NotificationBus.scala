package kui.ui.kernel.state

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.ui.kernel.component.Tone

/** Something the user needs to be told about, out of band.
  *
  * @param dedupKey
  *   two notifications with the same key, raised within `Notifications.DedupWindow` of each other, collapse
  *   into one. ADR-032 needs this: the capability stream can report the same service going down several times
  *   in a few seconds, and three identical "Schema registry unavailable" toasts tell the user nothing that
  *   one does not.
  * @param dismissAfter
  *   `None` means it stays until dismissed by hand. `Notifications.defaultDismissAfter` supplies the usual
  *   answer per tone, and errors are never dismissed automatically.
  */
final case class Notification(
    tone: Tone,
    title: String,
    message: Option[String] = None,
    dedupKey: Option[String] = None,
    dismissAfter: Option[FiniteDuration] = None
)

/** A notification the bus is currently holding, with the identity the UI needs to key it by. */
final case class ActiveNotification(id: String, notification: Notification, raisedAt: Long)

/** The application's notification queue.
  *
  * One of the five kernel-owned `Var`s the plan allows (PLAN §21). It is global because a notification's
  * whole point is that it outlives the screen that raised it: a request started on the topics page and
  * failing after the user has navigated to consumers must still be reported.
  *
  * ## Why this is a class as well as an object
  *
  * Deduplication and automatic dismissal are both about *time*, and a test that has to wait 30 real seconds
  * to check a 30-second window is a test nobody runs. The clock and the timer are therefore constructor
  * parameters: the application passes the browser's, and the tests pass their own and step time forward by
  * hand.
  *
  * @param now
  *   milliseconds since the epoch.
  * @param schedule
  *   runs a thunk after a delay. The browser's `setTimeout`, or a test's queue.
  */
final class Notifications(now: () => Long, schedule: (FiniteDuration, () => Unit) => Unit) {

  /** Everything the bus is holding, newest first. */
  private val held = Var(List.empty[ActiveNotification])

  /** When each `dedupKey` was last raised.
    *
    * Kept separately from `held` on purpose: the deduplication window is about the *event*, not about what
    * happens to be on screen. Deriving it from `held` would let a repeat reappear the moment the user
    * dismissed the first one, which is precisely the behaviour that makes a flapping service unbearable.
    */
  private var lastRaised: Map[String, Long] = Map.empty

  /** What the toast stack shows: the newest few. */
  val current: Signal[List[ActiveNotification]] = held.signal.map(_.take(Notifications.MaxVisible))

  /** How many are waiting behind the visible ones. Rendered as "+3 more" by the toast stack. */
  val queued: Signal[Int] = held.signal.map(all => (all.size - Notifications.MaxVisible).max(0))

  /** Raises a notification, unless an identical one is already recent.
    *
    * Nothing is ever dropped to keep the list short: the bound in `current` is about how many are *shown*,
    * and the rest wait. That matters most for `Danger`, which is the tone a user must not miss, and which
    * arrives in bursts precisely when several things are going wrong at once.
    */
  def push(notification: Notification): Unit = {
    val at = now()

    // Forget keys older than the window, so the map cannot grow for the lifetime of the page.
    lastRaised = lastRaised.filter((_, raisedAt) => (at - raisedAt).millis < Notifications.DedupWindow)

    val isDuplicate = notification.dedupKey.exists(lastRaised.contains)

    if !isDuplicate then {
      notification.dedupKey.foreach(key => lastRaised = lastRaised.updated(key, at))
      val id = Notifications.nextId()
      held.update(existing => ActiveNotification(id, notification, at) :: existing)

      notification.dismissAfter
        .orElse(Notifications.defaultDismissAfter(notification.tone))
        .foreach(delay => schedule(delay, () => dismiss(id)))
    }
  }

  def dismiss(id: String): Unit = held.update(_.filterNot(_.id == id))

  def dismissAll(): Unit = held.set(Nil)
}

object Notifications {

  /** Repeats of the same `dedupKey` inside this window collapse into one (ADR-032). */
  val DedupWindow: FiniteDuration = 30.seconds

  /** How many toasts are on screen at once. More than a handful is a wall of text nobody reads. */
  val MaxVisible: Int = 5

  /** How long a toast stays, by tone.
    *
    * Errors never leave on their own. Everything else is transient by nature — "Topic created" has done its
    * job in a few seconds — but a failure has to survive the user looking away, because the whole reason to
    * tell them is that something needs their attention.
    */
  def defaultDismissAfter(tone: Tone): Option[FiniteDuration] =
    tone match {
      case Tone.Danger => None
      case Tone.Neutral | Tone.Info | Tone.Success => Some(6.seconds)
      case Tone.Warning => Some(10.seconds)
    }

  private var counter: Int = 0

  private def nextId(): String = {
    counter += 1
    s"kui-notification-$counter"
  }
}

/** The application's notification bus, wired to the browser's clock and timer. */
object NotificationBus {

  private lazy val browser: Notifications =
    new Notifications(
      now = () => System.currentTimeMillis(),
      schedule = (delay, action) => dom.window.setTimeout(() => action(), delay.toMillis.toDouble): Unit
    )

  def push(notification: Notification): Unit = browser.push(notification)

  def dismiss(id: String): Unit = browser.dismiss(id)

  def current: Signal[List[ActiveNotification]] = browser.current

  def queued: Signal[Int] = browser.queued
}
