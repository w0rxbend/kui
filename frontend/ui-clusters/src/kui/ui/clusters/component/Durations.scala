package kui.ui.clusters.component

/** Kafka's millisecond settings, as a person reads them.
  *
  * `log.retention.ms = 604800000` is a number nobody parses at a glance; `7 d` is the same fact and is read
  * without counting zeroes. The raw value is always kept beside the formatted one — an operator comparing
  * this against a value they are about to type needs the digits, and a formatter that hid them would be
  * making the screen prettier and less useful at the same time.
  */
object Durations {

  private val Second = 1000L
  private val Minute = 60 * Second
  private val Hour = 60 * Minute
  private val Day = 24 * Hour

  /** What Kafka means by `-1` in a setting that bounds time or size. */
  val Unlimited: String = "unlimited"

  /** The largest whole unit that loses no precision, or `None` when the value is not a number.
    *
    * `None` rather than a guess: a setting whose value is not numeric is not a duration, whatever its name
    * ends in, and the caller falls back to showing it verbatim. Formatting `"none"` as `0 ms` would invent a
    * fact about the broker.
    */
  def fromMillis(raw: String): Option[String] =
    raw.trim.toLongOption.map {
      case -1L => Unlimited
      case millis if millis == 0L => "0 ms"
      case millis if millis % Day == 0 => s"${millis / Day} d"
      case millis if millis % Hour == 0 => s"${millis / Hour} h"
      case millis if millis % Minute == 0 => s"${millis / Minute} min"
      case millis if millis % Second == 0 => s"${millis / Second} s"
      case millis => s"$millis ms"
    }
}
