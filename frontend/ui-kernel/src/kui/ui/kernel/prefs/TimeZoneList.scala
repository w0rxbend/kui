package kui.ui.kernel.prefs

import java.time.Instant

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.Try

import kui.ui.kernel.time.Timestamps

/** The list of time zones a user can choose from, and how each one is written down.
  *
  * ## Why the label carries the offset
  *
  * `Europe/Warsaw` on its own tells an operator nothing about whether it is the zone they want; the broker
  * log they are comparing against is stamped `+02:00`. So every entry reads `UTC+02:00 Europe/Warsaw`, and
  * the list is sorted by offset before name, which puts the zones near the one the user is already in next to
  * each other. That is the reference product's arrangement and it is the right one.
  *
  * ## Why the list can be two entries long
  *
  * `Intl.supportedValuesOf` is not universal. A runtime without it must not produce an empty dropdown, so the
  * fallback is the browser's own zone plus `UTC` — the two that always matter.
  */
object TimeZoneList {

  /** The zone that is always offered, whatever the runtime supports. */
  val Utc: String = "UTC"

  /** `UTC+02:00 Europe/Warsaw` for a zone id at a given instant.
    *
    * At *an instant*, because the offset of a zone is not a property of the zone: `Europe/Warsaw` is `+01:00`
    * in January and `+02:00` in July. Labelling with a fixed offset would be wrong for half the year.
    */
  def label(zoneId: String, at: Instant): String =
    s"${Timestamps.offsetLabel(zoneId, at)} $zoneId"

  /** By current UTC offset, then by id. */
  def ordering(at: Instant): Ordering[String] =
    Ordering.by(zoneId => (Timestamps.offsetSeconds(zoneId, at).getOrElse(0), zoneId))

  /** Every zone on offer, as `(id, label)`.
    *
    * @param supported
    *   what the runtime says it supports, or `None` when it cannot be asked. A parameter so that the fallback
    *   path is testable without a browser that lacks the API.
    * @param systemZone
    *   the browser's own zone, always present in the list even if the runtime named no others.
    */
  def entries(supported: Option[List[String]], systemZone: String, at: Instant): List[(String, String)] = {
    val ids = supported.filter(_.nonEmpty).getOrElse(Nil)
    (Utc :: systemZone :: ids).distinct
      .filter(Timestamps.isKnownZone)
      .sorted(using ordering(at))
      .map(zoneId => zoneId -> label(zoneId, at))
  }

  /** What `Intl.supportedValuesOf('timeZone')` answers, or `None` on a runtime that lacks it. */
  def supportedByRuntime(): Option[List[String]] =
    Try(Intl.supportedValuesOf("timeZone").toList).toOption.filter(_.nonEmpty)

  @js.native
  @JSGlobal("Intl")
  private object Intl extends js.Object {
    def supportedValuesOf(key: String): js.Array[String] = js.native
  }
}
