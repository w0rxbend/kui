package kui.ui.kernel.time

import java.time.Instant

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.Try

/** Turning an instant into the two strings a user needs at the same time.
  *
  * ## Why both forms, always
  *
  * "8 minutes ago" is what somebody reads off a badge without thinking about it. `2026-09-03 14:05:11
  * UTC+02:00` is what they paste into a ticket when the number on screen turns out to matter. Showing only
  * the first loses the evidence; showing only the second makes the reader do arithmetic to answer "is this
  * current?". So every stale badge in the product shows the relative form and carries the absolute form as
  * its `title`.
  *
  * ## Where the zones come from, and why the characters are still ours
  *
  * `java.time` on Scala.js knows no time zones unless a megabyte of compiled zone database is shipped into
  * the browser with it, so `ZoneId.of("Europe/Warsaw")` throws and every timestamp would silently render in
  * UTC — which would make the timezone preference a setting that does nothing. The browser already carries
  * that database, behind `Intl`.
  *
  * So `Intl` is used as a *zone database* and not as a *formatter*: every field is requested at a pinned
  * numeric width under the 24-hour cycle, which makes the answer digits and nothing else, and this file then
  * assembles the string. Letting `Intl` format would put a month's name, a locale's separators and an
  * engine's version between the data and the screen, and would put a character-level assertion out of reach.
  *
  * ## Why nothing here throws
  *
  * A zone id arrives from a stored preference, which means it arrives from data a previous version of this
  * application wrote, or that somebody edited by hand. A bad one must degrade to UTC, not blank the page that
  * was trying to say when its data was fetched.
  */
object Timestamps {

  /** The zone used whenever the requested one cannot be resolved. */
  val FallbackZone: String = "UTC"

  /** One field of a formatted date, as `Intl` hands it back: `("month", "09")`. */
  @js.native
  private trait FormatPart extends js.Object {
    val `type`: String = js.native
    val value: String = js.native
  }

  @js.native
  private trait ResolvedOptions extends js.Object {
    val timeZone: js.UndefOr[String] = js.native
  }

  /** The browser's own zone database and calendar arithmetic, typed.
    *
    * A `js.native` facade rather than `js.Dynamic`, because a dynamic call needs an `asInstanceOf` at every
    * field and the project's scalafix rules forbid those outright — for the good reason that an
    * `asInstanceOf` on a value the compiler knows nothing about fails at the point of use rather than at the
    * point of the mistake.
    */
  // A native JS class declares its constructor for the call site's benefit; nothing on the Scala
  // side ever reads the parameters, which is exactly what `-Wunused` is complaining about.
  @nowarn("msg=unused explicit parameter")
  @js.native
  @JSGlobal("Intl.DateTimeFormat")
  private class DateTimeFormat(locale: String, options: js.Object) extends js.Object {
    def formatToParts(date: js.Date): js.Array[FormatPart] = js.native
    def resolvedOptions(): ResolvedOptions = js.native
  }

  /** `2026-09-03 14:05:11 UTC+02:00` in the given IANA zone.
    *
    * An unknown or unsupported zone id falls back to `UTC` rather than throwing.
    */
  def absolute(at: Instant, zone: String): String = {
    val fields = partsOf(at, zone).orElse(partsOf(at, FallbackZone)).getOrElse(utcFields(at))
    val local = f"${fields.year}%04d-${fields.month}%02d-${fields.day}%02d " +
      f"${fields.hour}%02d:${fields.minute}%02d:${fields.second}%02d"
    s"$local ${offsetOf(fields.offsetSeconds(at))}"
  }

  /** The wall-clock fields of one instant in one zone. */
  final private case class Fields(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) {

    /** How far this zone is from UTC at this instant, in seconds.
      *
      * Derived rather than asked for: the wall clock the zone shows, minus the instant itself, *is* the
      * offset. Computing it this way means daylight saving is handled by whoever maintains the browser's zone
      * database rather than by this file.
      */
    def offsetSeconds(at: Instant): Int = {
      val asUtc = js.Date.UTC(year, month - 1, day, hour, minute, second)
      math.round((asUtc - at.toEpochMilli.toDouble) / 1000.0).toInt
    }
  }

  private def partsOf(at: Instant, zone: String): Option[Fields] =
    Try {
      val options = js.Dynamic.literal(
        timeZone = zone,
        hourCycle = "h23",
        year = "numeric",
        month = "2-digit",
        day = "2-digit",
        hour = "2-digit",
        minute = "2-digit",
        second = "2-digit"
      )
      val formatter = new DateTimeFormat("en-US", options)
      val byType = formatter
        .formatToParts(new js.Date(at.toEpochMilli.toDouble))
        .iterator
        .map(part => part.`type` -> part.value)
        .toMap
      Fields(
        year = byType("year").toInt,
        month = byType("month").toInt,
        day = byType("day").toInt,
        // Some engines render midnight as hour 24 even under `h23`. Both spellings mean the same
        // moment and only one of them is a valid clock reading.
        hour = byType("hour").toInt % 24,
        minute = byType("minute").toInt,
        second = byType("second").toInt
      )
    }.toOption

  /** The last resort, used only if the runtime has no usable `Intl` at all. */
  private def utcFields(at: Instant): Fields = {
    val date = new js.Date(at.toEpochMilli.toDouble)
    Fields(
      date.getUTCFullYear().toInt,
      date.getUTCMonth().toInt + 1,
      date.getUTCDate().toInt,
      date.getUTCHours().toInt,
      date.getUTCMinutes().toInt,
      date.getUTCSeconds().toInt
    )
  }

  /** `just now`, `8 minutes ago`, `3 hours ago`, `2 days ago`; and `in 5 seconds`, `in 2 minutes` for an
    * instant in the future.
    *
    * The future case is not a curiosity. The instant comes from a server and the comparison happens on a
    * laptop, and the two clocks disagree by a second or two as a matter of course. Rendering that
    * disagreement as `-2 seconds ago` would make the product look broken over something that is entirely
    * normal.
    */
  def relative(at: Instant, now: Instant): String = {
    val seconds = at.getEpochSecond - now.getEpochSecond
    if seconds > 0 then future(seconds) else past(-seconds)
  }

  /** The badge's own line: `Last updated 8 minutes ago`, or `Never refreshed`. */
  def lastUpdated(at: Option[Instant], now: Instant): String =
    at match {
      case Some(instant) => s"Last updated ${relative(instant, now)}"
      case None => NeverRefreshed
    }

  /** What [[lastUpdated]] says when there is no timestamp at all. */
  val NeverRefreshed: String = "Never refreshed"

  /** The zone the browser is in, from `Intl.DateTimeFormat().resolvedOptions().timeZone`, falling back to
    * `UTC` when the runtime does not supply one.
    */
  def systemZone(): String =
    Try(new DateTimeFormat("en-US", js.Object()).resolvedOptions().timeZone.toOption).toOption.flatten
      .filter(_.nonEmpty)
      .getOrElse(FallbackZone)

  /** A `js.Date` — what the browser's timers and `QueryCache` deal in — as an `Instant`, which is what
    * everything that formats a time deals in. One conversion, in one place, so that no screen writes
    * millisecond arithmetic of its own.
    */
  def instantOf(date: js.Date): Instant = Instant.ofEpochMilli(date.getTime().toLong)

  /** `UTC+02:00`, `UTC-05:00`, `UTC+00:00`.
    *
    * Written by hand rather than by a pattern letter because `java.time`'s own localized forms render the
    * zero offset as the bare letter `Z` and the others with a `GMT` prefix, so a column of timestamps would
    * not line up and two of them would not say the same word.
    */
  private def offsetOf(total: Int): String = {
    val sign = if total < 0 then "-" else "+"
    val absolute = math.abs(total)
    f"UTC$sign${absolute / 3600}%02d:${(absolute % 3600) / 60}%02d"
  }

  private def past(seconds: Long): String =
    seconds match {
      case s if s < 60 => "just now"
      case s if s < 3600 => plural(s / 60, "minute") + " ago"
      case s if s < 86400 => plural(s / 3600, "hour") + " ago"
      case s => plural(s / 86400, "day") + " ago"
    }

  private def future(seconds: Long): String =
    seconds match {
      case s if s < 60 => "in " + plural(s, "second")
      case s if s < 3600 => "in " + plural(s / 60, "minute")
      case s if s < 86400 => "in " + plural(s / 3600, "hour")
      case s => "in " + plural(s / 86400, "day")
    }

  private def plural(count: Long, unit: String): String =
    if count == 1 then s"1 $unit" else s"$count ${unit}s"
}
