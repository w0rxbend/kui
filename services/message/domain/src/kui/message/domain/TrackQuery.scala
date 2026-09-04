package kui.message.domain

import java.time.{Duration, Instant}

import scala.util.control.NonFatal

import cats.data.NonEmptyList

import kui.kernel.browse.IsolationLevel
import kui.kernel.error.{DomainError, FieldError, KuiError}
import kui.kernel.{ClusterId, TopicName}

/** Which part of a record a track query looks at.
  *
  * It is mandatory and it has no default. The reference product sends an empty string for "the value", which
  * means an omitted header name silently turns a header search into a value search — and that does not fail,
  * it returns plausible wrong answers. Making the source explicit costs the caller four characters (DEVPLAN
  * §10 D12).
  */
enum MatchSource {
  case Value
  case Key
  case Header(name: String)
}

object MatchSource {
  given CanEqual[MatchSource, MatchSource] = CanEqual.derived
}

enum MatchOperator(val wire: String) {
  case Contains extends MatchOperator("CONTAINS")
  case NotContains extends MatchOperator("NOT_CONTAINS")
  case Equals extends MatchOperator("EQUALS")
  case NotEquals extends MatchOperator("NOT_EQUALS")
  case Regex extends MatchOperator("REGEX")
}

object MatchOperator {
  val All: List[MatchOperator] = values.toList

  def from(wire: String): Either[KuiError, MatchOperator] =
    All.find(_.wire == wire) match {
      case Some(operator) => Right(operator)
      case None =>
        Left(
          DomainError.InvariantViolation(
            s"'$wire' is not a match operator",
            List(FieldError.of("operator", s"one of ${All.map(_.wire).mkString(", ")}"))
          )
        )
    }

  given CanEqual[MatchOperator, MatchOperator] = CanEqual.derived
}

/** What counts as a hit: where to look, how to compare, and what to compare against. */
final case class TrackMatch(source: MatchSource, operator: MatchOperator, value: String)

object TrackMatch {
  given CanEqual[TrackMatch, TrackMatch] = CanEqual.derived
}

/** How to tell two records that belong to the same business event apart from two that do not.
  *
  * It adds a `group` string to each hit and nothing more. There is no server-side join and no graph: that is
  * M9's work, and building the join here on the assumption that this was one is the mistake this comment
  * exists to prevent (ADR-029).
  */
enum CorrelationKey {
  case Header(name: String)
  case JsonPath(path: String)
}

object CorrelationKey {
  given CanEqual[CorrelationKey, CorrelationKey] = CanEqual.derived
}

/** A bounded scan for one business event across several topics inside a time window.
  *
  * Every field that bounds the scan is mandatory, because an unbounded scan across a production cluster is
  * exactly what PLAN §22 forbids: the window is required and ordered, its width has a ceiling, and the number
  * of hits has a cap.
  */
final case class TrackQuery private (
    cluster: ClusterId,
    topics: NonEmptyList[TopicName],
    from: Instant,
    until: Instant,
    matcher: TrackMatch,
    limit: Int,
    isolation: IsolationLevel,
    correlationKey: Option[CorrelationKey]
)

object TrackQuery {

  /** The widest window a scan may cover, unless configuration says otherwise (`kui.message.track.maxWindow`).
    *
    * Seven days is not a guess about what people want; it is a guess about what a broker can be asked for
    * without a person having meant it. A wider window is expressible by narrowing the topic list and asking
    * twice, which is a conversation, where an accidental month-long scan of forty topics is an outage.
    */
  val DefaultMaxWindow: Duration = Duration.ofDays(7)

  /** How many hits a scan returns before it stops and says why (Kouncil's `EVENTS_SANITY_LIMIT`). */
  val DefaultLimit: Int = 1000

  def of(
      cluster: ClusterId,
      topics: List[TopicName],
      from: Instant,
      until: Instant,
      matcher: TrackMatch,
      limit: Option[Int],
      isolation: Option[IsolationLevel],
      correlationKey: Option[CorrelationKey],
      maxWindow: Duration = DefaultMaxWindow,
      maxLimit: Int = DefaultLimit
  ): Either[KuiError, TrackQuery] =
    for {
      chosen <- atLeastOneTopic(topics)
      _ <- windowIsOrdered(from, until)
      _ <- windowIsNotTooWide(from, until, maxWindow)
      _ <- matcherIsUsable(matcher)
    } yield TrackQuery(
      cluster = cluster,
      topics = chosen,
      from = from,
      until = until,
      matcher = matcher,
      limit = clamp(limit, maxLimit),
      isolation = isolation.getOrElse(IsolationLevel.Default),
      correlationKey = correlationKey
    )

  private def clamp(limit: Option[Int], maxLimit: Int): Int =
    limit match {
      case Some(value) if value > 0 && value <= maxLimit => value
      case Some(value) if value > maxLimit => maxLimit
      case _ => maxLimit
    }

  private def atLeastOneTopic(topics: List[TopicName]): Either[KuiError, NonEmptyList[TopicName]] =
    NonEmptyList.fromList(topics.distinct) match {
      case Some(nonEmpty) => Right(nonEmpty)
      case None =>
        Left(
          DomainError.InvariantViolation(
            "a track query names no topics",
            List(FieldError.of("topics", "at least one topic"))
          )
        )
    }

  private def windowIsOrdered(from: Instant, until: Instant): Either[KuiError, Unit] =
    if until.isAfter(from) then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "the end of the window is not after its start",
          List(FieldError.of("until", s"an instant after $from"))
        )
      )

  private def windowIsNotTooWide(from: Instant, until: Instant, maxWindow: Duration): Either[KuiError, Unit] =
    if Duration.between(from, until).compareTo(maxWindow) <= 0 then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "the window is wider than this deployment allows",
          List(FieldError.of("until", s"a window no wider than $maxWindow"))
        )
      )

  /** A regular expression is compiled here, once, rather than per record.
    *
    * An invalid pattern is a mistake in the request and deserves a 400 that names it. Discovered per record
    * it would instead be a failure on every one of a million records, reported as a scan that found nothing.
    */
  private def matcherIsUsable(matcher: TrackMatch): Either[KuiError, Unit] =
    matcher match {
      case TrackMatch(_, MatchOperator.Regex, pattern) =>
        try {
          val _ = java.util.regex.Pattern.compile(pattern)
          Right(())
        } catch {
          case NonFatal(error) =>
            Left(
              DomainError.InvariantViolation(
                "the search pattern is not a valid regular expression",
                List(FieldError.of("match.value", Option(error.getMessage).getOrElse("a valid pattern")))
              )
            )
        }
      case TrackMatch(MatchSource.Header(name), _, _) if name.isEmpty =>
        Left(
          DomainError.InvariantViolation(
            "a header search names no header",
            List(FieldError.of("match.source", "a header name"))
          )
        )
      case _ => Right(())
    }

  given CanEqual[TrackQuery, TrackQuery] = CanEqual.derived
}
