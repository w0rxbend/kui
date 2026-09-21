package kui.message.domain

import java.util.regex.Pattern

import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

import kui.kernel.TopicName

/** Whether one decoded record is a hit for one track query (ET-001).
  *
  * ==Why this is in the domain and pure==
  *
  * Because it is the whole meaning of a track: everything else — which topics, which window, how many hits —
  * is bounding, and this is the question being asked. It runs once per record over a scan that may read a
  * million of them, so it takes a *prepared* matcher rather than re-reading the query each time: a regular
  * expression compiled per record is the difference between a scan that finishes and one that does not.
  *
  * ==What it deliberately is not==
  *
  * It is not the smart filter. Three operators over one field, and no expression language, because a screen
  * that offered both would be two filter languages side by side and a user would have to know which one this
  * box speaks. An arbitrary predicate belongs in ADR-017's CEL filter, and the two features say so to each
  * other.
  */
final class PreparedMatch private (matcher: TrackMatch, compiled: Option[Pattern]) {

  /** Whether this record is a hit.
    *
    * A record whose searched field is absent — a key-less record under a key search, a header the record does
    * not carry — is **not** a hit for a positive operator and **is** a hit for a negative one, which is what
    * "this record does not contain X" has to mean if it is to be usable for finding the messages that are
    * missing something.
    */
  def matches(record: DecodedRecord): Boolean = {
    val subject: Option[String] =
      matcher.source match {
        case MatchSource.Value => Some(record.value.text)
        case MatchSource.Key => Some(record.key.text)
        case MatchSource.Header(name) => record.headers.find(_.key == name).map(_.value)
      }

    matcher.operator match {
      case MatchOperator.Contains => subject.exists(_.contains(matcher.value))
      case MatchOperator.NotContains => !subject.exists(_.contains(matcher.value))
      case MatchOperator.Equals => subject.contains(matcher.value)
      case MatchOperator.NotEquals => !subject.contains(matcher.value)
      // A pattern that failed to compile cannot happen here — `TrackQuery.of` refuses the request — and is
      // still answered rather than thrown, because a matcher that threw would fail a scan at its millionth
      // record with the first 999,999 already delivered.
      case MatchOperator.Regex =>
        compiled.exists(pattern => subject.exists(text => find(pattern, text)))
    }
  }

  /** Runs the match under a wall-clock budget rather than trusting the pattern to terminate.
    *
    * `java.util.regex` has no built-in deadline, and a user-supplied pattern is free to be one that
    * catastrophically backtracks (`(a+)+$` and its relatives) — which turns one record into an
    * exponential-time hang and a 7-day, multi-topic scan into a stuck fiber. Wrapping the input in a
    * `CharSequence` that checks the clock on every character access catches that blow-up almost immediately,
    * because a pathological match reads the same characters an astronomical number of times, while a
    * well-behaved pattern never notices the check.
    */
  private def find(pattern: Pattern, text: String): Boolean =
    try
      boundary[Boolean] {
        pattern
          .matcher(new PreparedMatch.DeadlineGuardedInput(text, summon[boundary.Label[Boolean]]))
          .find()
      }
    catch { case NonFatal(_) => false }
}

object PreparedMatch {

  /** How long one record's regex match may run before it is abandoned as a hit-less record.
    *
    * Short enough that a scan of a million records paying it on every one of them stays a matter of seconds,
    * long enough that no pattern anyone would write on purpose ever brushes it — this exists for the pattern
    * nobody would write on purpose.
    */
  private val MatchBudget: java.time.Duration = java.time.Duration.ofMillis(50)

  /** A `CharSequence` view of `text` that raises once `deadline` has passed.
    *
    * `Matcher.find()` re-reads the sequence character by character with no hook of its own to interrupt, so
    * the deadline is enforced the only place available: every `charAt`. A pattern that backtracks
    * catastrophically calls this far more times than there are characters in `text`, so the check fires long
    * before the match itself ever would.
    */
  final private class DeadlineGuardedInput(
      text: String,
      label: boundary.Label[Boolean],
      deadline: Long = System.nanoTime() + MatchBudget.toNanos
  ) extends CharSequence {

    private def guarded[A](value: => A): A =
      if System.nanoTime() > deadline then break(false)(using label)
      else value

    def length(): Int = text.length
    def charAt(index: Int): Char = guarded(text.charAt(index))
    def subSequence(start: Int, end: Int): CharSequence =
      guarded(new DeadlineGuardedInput(text.subSequence(start, end).toString, label, deadline))
  }

  /** Compiles the matcher once, for the whole scan.
    *
    * The pattern is known to be valid because `TrackQuery.of` compiled it when it validated the request; this
    * compiles it a second time rather than threading a `Pattern` through the query, because a domain value
    * that carried a mutable `java.util.regex.Matcher` factory would stop being comparable and serialisable
    * for the sake of one compile per request.
    */
  def of(matcher: TrackMatch): PreparedMatch =
    matcher.operator match {
      case MatchOperator.Regex =>
        val compiled =
          try Some(Pattern.compile(matcher.value))
          catch { case NonFatal(_) => None }

        new PreparedMatch(matcher, compiled)
      case _ => new PreparedMatch(matcher, None)
    }
}

/** One record a track found, with the topic it came from.
  *
  * The topic is on the hit rather than on the answer, because a track's whole purpose is that its results
  * come from several topics at once: "where did this order go" is answered by a list in time order, and
  * grouping by topic would throw that order away.
  */
final case class TrackHit(topic: TopicName, record: DecodedRecord)

object TrackHit {
  given CanEqual[TrackHit, TrackHit] = CanEqual.derived
}
