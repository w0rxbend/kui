package kui.ui.topics.detail

import scala.util.Try

import kui.ui.kernel.component.Bytes

/** Rendering a configuration value the way an operator reads it.
  *
  * `retention.ms = 604800000` is a number nobody parses at a glance. The unit is inferred from the key's
  * suffix and a hint is shown beside the raw value — "7 days" — never instead of it: an operator comparing
  * this against a setting they are about to type needs the number they will type.
  *
  * ## Why a negative or zero value gets no hint
  *
  * `-1` in Kafka means "no limit", and `retention.ms = -1` rendered as "-1 milliseconds" is worse than the
  * raw number: it looks like an interval and reads as nonsense. Zero is the same argument in a milder form.
  * Both are left alone, and the raw value is on screen either way.
  *
  * ## Why the mask is fixed-width
  *
  * A sensitive value never leaves the server at all — `TopicConfigEntryDto`'s encoder drops it — so what is
  * masked here is the *absence*, and the mask is a constant. Rendering one asterisk per character would tell
  * a reader how long the secret is, which is information a screen has no business giving away and which the
  * server did not send anyway.
  */
object ConfigValue {

  /** What a sensitive key's value looks like. Six characters, and never the value's own length. */
  val masked: String = "••••••"

  /** The accessible name that goes with [[masked]], because a screen reader announcing six bullets says
    * nothing at all.
    */
  val maskedLabel: String = "hidden because this setting is sensitive"

  private val Second: Long = 1000L
  private val Minute: Long = 60L * Second
  private val Hour: Long = 60L * Minute
  private val Day: Long = 24L * Hour

  /** A human hint for a key whose name says what its number means, or `None`.
    *
    * `None` for an unknown key, for anything that is not a number, and for a value that is negative or zero.
    * A hint is an aid; a wrong one is worse than none, and a screen that guessed at an unrecognised key would
    * be confidently wrong on the first broker setting KUI has not heard of.
    */
  def hint(name: String, value: String): Option[String] =
    Try(value.trim.toLong).toOption
      .filter(_ > 0L)
      .flatMap { number =>
        if name.endsWith(".ms") then Some(duration(number))
        else if name.endsWith(".seconds") then Some(duration(number * Second))
        else if name.endsWith(".bytes") then Some(Bytes.format(Some(number)))
        else None
      }

  /** The largest whole unit that fits, and only that one.
    *
    * "7 days" rather than "6 days 23 hours 60 minutes". The hint exists so a number can be recognised at a
    * glance, and a hint that has to be read carefully has failed at the one thing it is for; the exact figure
    * is beside it for anyone who needs it.
    */
  private def duration(millis: Long): String =
    if millis >= Day then plural(millis / Day, "day")
    else if millis >= Hour then plural(millis / Hour, "hour")
    else if millis >= Minute then plural(millis / Minute, "minute")
    else if millis >= Second then plural(millis / Second, "second")
    else plural(millis, "millisecond")

  private def plural(count: Long, unit: String): String =
    if count == 1L then s"1 $unit" else s"$count ${unit}s"
}
