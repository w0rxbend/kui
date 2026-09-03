package kui.ui.kernel.component

import scala.collection.mutable
import scala.concurrent.duration.*

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite

import kui.ui.kernel.state.{Notification, Notifications}

/** A clock and a timer the test drives by hand.
  *
  * The alternative is a test that waits thirty real seconds to check a thirty-second deduplication
  * window, which is a test nobody runs and therefore a test that proves nothing.
  */
private final class FakeClock {
  private var current: Long                                     = 0L
  private val pending: mutable.ListBuffer[(Long, () => Unit)]   = mutable.ListBuffer.empty

  def now(): Long = current

  def schedule(delay: FiniteDuration, action: () => Unit): Unit =
    pending.append((current + delay.toMillis, action)) : Unit

  /** Moves time forward and runs everything that was due along the way, in order. */
  def advance(by: FiniteDuration): Unit = {
    current += by.toMillis
    val due = pending.filter(_._1 <= current).toList.sortBy(_._1)
    pending.filterInPlace(_._1 > current)
    due.foreach(_._2())
  }
}

final class NotificationBusSuite extends FunSuite {

  private val owner = new ManualOwner

  private def busWith(clock: FakeClock): Notifications =
    new Notifications(() => clock.now(), (delay, action) => clock.schedule(delay, action))

  private def info(title: String, dedupKey: Option[String] = None): Notification =
    Notification(Tone.Info, title, dedupKey = dedupKey)

  test("dedupCollapsesRepeatsWithinTheWindowAndAllowsThemAfter") {
    // ADR-032: the capability stream can report the same service going down several times in a few
    // seconds, and three identical toasts tell the user nothing that one does not.
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    // Danger, so that automatic dismissal does not remove the first one and confuse the counts.
    def unavailable = Notification(Tone.Danger, "Schema registry unavailable", dedupKey = Some("cap:schema"))

    bus.push(unavailable)
    clock.advance(5.seconds)
    bus.push(unavailable)

    assertEquals(shown.now().size, 1)

    // Past the window, the same event is news again.
    clock.advance(Notifications.DedupWindow + 1.second)
    bus.push(unavailable)

    assertEquals(shown.now().size, 2)
  }

  test("notifications without a dedup key are never collapsed") {
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    bus.push(info("Topic created"))
    bus.push(info("Topic created"))

    assertEquals(shown.now().size, 2)
  }

  test("autoDismissRemovesAfterTheDelay") {
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    bus.push(Notification(Tone.Success, "Topic created", dismissAfter = Some(3.seconds)))
    assertEquals(shown.now().size, 1)

    clock.advance(2.seconds)
    assertEquals(shown.now().size, 1)

    clock.advance(2.seconds)
    assertEquals(shown.now().size, 0)
  }

  test("dangerToastsDoNotAutoDismiss") {
    // The whole reason to report a failure is that it needs attention, so it has to survive the user
    // looking away.
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    bus.push(Notification(Tone.Danger, "Failed to delete topic"))
    clock.advance(10.minutes)

    assertEquals(shown.now().size, 1)
  }

  test("orderIsNewestFirstAndTheListIsBounded") {
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    (1 to 7).foreach(n => bus.push(Notification(Tone.Danger, s"error $n")))

    assertEquals(shown.now().size, Notifications.MaxVisible)
    assertEquals(shown.now().head.notification.title, "error 7")
  }

  test("a Danger notification is never lost to the visible bound") {
    // The bound is about how many are *shown*. Dropping the rest would lose exactly the tone the
    // user must not miss, and errors arrive in bursts precisely when several things are wrong.
    val clock  = new FakeClock
    val bus    = busWith(clock)
    val shown  = bus.current.observe(using owner)
    val queued = bus.queued.observe(using owner)

    (1 to 7).foreach(n => bus.push(Notification(Tone.Danger, s"error $n")))
    assertEquals(queued.now(), 2)

    // Dismissing the visible ones brings the queued ones forward; nothing was thrown away.
    shown.now().foreach(active => bus.dismiss(active.id))

    assertEquals(shown.now().map(_.notification.title), List("error 2", "error 1"))
    assertEquals(queued.now(), 0)
  }

  test("dismissing a notification does not let its repeat straight back in") {
    // The window is about the event, not about what is on screen. Otherwise a user who dismisses a
    // toast from a flapping service is handed the same toast again a second later.
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    bus.push(Notification(Tone.Danger, "Down", dedupKey = Some("cap:schema")))
    bus.dismiss(shown.now().head.id)
    bus.push(Notification(Tone.Danger, "Down", dedupKey = Some("cap:schema")))

    assertEquals(shown.now().size, 0)
  }

  test("dismiss removes exactly one notification") {
    val clock = new FakeClock
    val bus   = busWith(clock)
    val shown = bus.current.observe(using owner)

    bus.push(info("first"))
    bus.push(info("second"))
    bus.dismiss(shown.now().head.id)

    assertEquals(shown.now().map(_.notification.title), List("first"))
  }
}
