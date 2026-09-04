package kui.message.domain

import java.util.regex.Pattern

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

  private def find(pattern: Pattern, text: String): Boolean =
    try pattern.matcher(text).find()
    catch { case NonFatal(_) => false }
}

object PreparedMatch {

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
