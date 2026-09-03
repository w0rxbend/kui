package kui.ui.kernel.prefs

import scala.concurrent.duration.*

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*

import kui.ui.kernel.theme.RootPreference

/** How often a screen re-reads the server's snapshot on its own.
  *
  * ## The decision this type encodes
  *
  * KUI's browser does not poll clusters. The server re-scrapes each cluster every thirty seconds, the browser
  * reads that snapshot and shows its `scrapedAt`, and the user's way of asking for fresher data is a button.
  * The reference product polls every five seconds from every open tab, which is load on somebody's brokers
  * that no user asked for, and it draws a full-page loader on each refetch, which is worse than useless.
  *
  * So what this setting re-reads is an already-computed server-side snapshot. It costs one cached HTTP
  * response, it cannot reach a broker at all, and it is `Off` unless somebody turns it on. The shortest
  * interval offered is thirty seconds, matching the server's own cadence, because a faster one would return
  * identical bytes.
  *
  * Two rules keep that distinction real rather than a matter of trust:
  *
  *   - a tick never asks the server to re-scrape — that is the refresh *button*, not the refresh *rate*;
  *   - a refetch never puts a loader over data that is already on screen. The old rows stay until the new
  *     ones arrive.
  */
enum RefreshRate(val storageValue: String, val label: String, val interval: Option[FiniteDuration]) {
  case Off extends RefreshRate("off", "Off", None)
  case Every30s extends RefreshRate("30s", "Every 30 seconds", Some(30.seconds))
  case Every1m extends RefreshRate("1m", "Every minute", Some(1.minute))
  case Every5m extends RefreshRate("5m", "Every 5 minutes", Some(5.minutes))
}

object RefreshRate {

  /** The `localStorage` key. Namespaced, because a KUI deployment may share an origin. */
  val StorageKey: String = "kui.refreshRate"

  /** Anything unrecognised reads as `Off`.
    *
    * Not a defensive nicety: `localStorage` outlives upgrades, so a value written by a later version of KUI
    * can be read by an earlier one. Of the two ways to be wrong, starting no timer is the safe one — a
    * corrupted preference must never start traffic the user did not ask for.
    */
  def fromStorage(raw: String): RefreshRate =
    values.find(_.storageValue == raw).getOrElse(Off)

  /** `lazy` so that merely importing this object does not touch `localStorage`. */
  private lazy val persisted: Var[RefreshRate] =
    persistedChoice(WebStorageVar.localStorage(StorageKey, syncOwner = None))

  /** What the user asked for. Writing to it persists the choice. */
  def choice: Var[RefreshRate] = persisted

  /** Ticks at the chosen interval, and emits nothing at all while the choice is `Off`. */
  def ticks: EventStream[Unit] = ticksOf(choice.signal, periodic)

  /** The same, with the timer supplied.
    *
    * `flatMapSwitch` is what makes changing the rate *replace* the timer rather than add a second one: the
    * previous stream is unsubscribed the moment a new choice arrives. A test can hand this a fake timer and
    * count ticks without waiting for a real minute to pass.
    */
  def ticksOf(
      choice: Signal[RefreshRate],
      timer: FiniteDuration => EventStream[Unit]
  ): EventStream[Unit] =
    choice.flatMapSwitch(rate => rate.interval.fold(EventStream.empty)(timer))

  private[prefs] def persistedChoice(storage: WebStorageBuilder): Var[RefreshRate] =
    RootPreference.persisted(storage, _.storageValue, fromStorage, Off)

  /** Airstream's periodic stream emits its first index the moment it is subscribed to; that first emission is
    * dropped, because subscribing must not itself be a refresh — the screen has just fetched, which is why it
    * is on screen.
    */
  private def periodic(every: FiniteDuration): EventStream[Unit] =
    EventStream.periodic(every.toMillis.toInt).drop(1).mapTo(())

  given CanEqual[RefreshRate, RefreshRate] = CanEqual.derived
}
