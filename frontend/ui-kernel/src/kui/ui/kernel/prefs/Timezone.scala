package kui.ui.kernel.prefs

import java.time.Instant

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*

import kui.ui.kernel.theme.RootPreference
import kui.ui.kernel.time.Timestamps

/** The timezone every timestamp in the product is rendered in.
  *
  * ## Why this is a preference and not a guess
  *
  * Every cluster screen shows when its data was scraped, and the operator reading it is usually comparing
  * that against a broker log. If the two are in different zones the comparison is arithmetic done under
  * pressure, which is how the wrong conclusion gets reached. One setting, applied to every timestamp in the
  * product, removes the arithmetic.
  *
  * ## Why an unset preference is not stored
  *
  * The default is the browser's own zone, read fresh each time. Writing it into storage at start-up would
  * freeze yesterday's zone into a laptop that has since been carried across an ocean. Nothing is stored until
  * the user actually chooses something.
  */
object Timezone {

  /** The `localStorage` key. */
  val StorageKey: String = "kui.timezone"

  private lazy val persisted: Var[String] =
    persistedChoice(WebStorageVar.localStorage(StorageKey, syncOwner = None), Timestamps.systemZone())

  /** The chosen zone, or the browser's own when nothing has been chosen. Writing persists. */
  def choice: Var[String] = persisted

  /** Every zone the runtime offers, as `(id, label)` sorted by offset then id.
    *
    * Falls back to the browser's own zone plus `UTC` when `Intl.supportedValuesOf` is unavailable.
    */
  def available(): List[(String, String)] =
    TimeZoneList.entries(TimeZoneList.supportedByRuntime(), Timestamps.systemZone(), Instant.now())

  /** @param systemZone
    *   what to use when nothing has been chosen. A parameter so that a suite can pin it.
    */
  private[prefs] def persistedChoice(storage: WebStorageBuilder, systemZone: String): Var[String] =
    RootPreference.persisted(
      storage,
      identity,
      // A stored zone the runtime does not recognise reads as UTC, the same rule `Timestamps`
      // applies when it formats. A preference written by a newer build, or edited by hand, must
      // degrade to a zone that exists rather than to one that silently formats as UTC anyway while
      // the control claims otherwise.
      raw => if Timestamps.isKnownZone(raw) then raw else TimeZoneList.Utc,
      systemZone
    )
}
