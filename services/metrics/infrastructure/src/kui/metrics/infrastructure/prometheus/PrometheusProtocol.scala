package kui.metrics.infrastructure.prometheus

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{DateTimeException, Instant}

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** A bounded, stable name for one server-owned logical query.
  *
  * Query ids, rather than PromQL or resource labels, are the only query identity that telemetry and logs may
  * carry. The deliberately small alphabet makes the value safe as a bounded metric attribute.
  */
opaque type QueryId = String

object QueryId {
  private val MaxLength: Int = 128
  private val Pattern = "^[a-z][a-z0-9._-]*$".r

  def from(raw: String): Either[PrometheusProtocolProblem, QueryId] =
    Option(raw)
      .filter(value => value.nonEmpty && value.length <= MaxLength && Pattern.matches(value))
      .toRight(PrometheusProtocolProblem.InvalidQueryId)

  extension (id: QueryId) def value: String = id

  given Ordering[QueryId] = Ordering.String
  given CanEqual[QueryId, QueryId] = CanEqual.derived
}

/** PromQL compiled from a server-owned catalog entry.
  *
  * Construction and expression access stop at the infrastructure package boundary. Public services can pass a
  * compiled query around, but cannot turn browser input into PromQL or accidentally put the expression in a
  * diagnostic. The digest lets the cache distinguish a changed template that retained its stable id.
  */
final class CompiledPromQuery private[infrastructure] (
    val id: QueryId,
    private[infrastructure] val expression: String,
    private[infrastructure] val expressionDigest: String
) {

  @nowarn("msg=pattern selector")
  override def equals(other: Any): Boolean = other match {
    case that: CompiledPromQuery => id == that.id && expressionDigest == that.expressionDigest
    case _ => false
  }

  override def hashCode(): Int = 31 * id.hashCode + expressionDigest.hashCode

  override def toString: String = s"CompiledPromQuery(id=${id.value})"
}

object CompiledPromQuery {
  val MaxExpressionBytes: Int = 64 * 1024

  private[infrastructure] def compile(
      id: QueryId,
      expression: String
  ): Either[PrometheusProtocolProblem, CompiledPromQuery] =
    Option(expression) match {
      case None => Left(PrometheusProtocolProblem.EmptyExpression)
      case Some(value) if value.trim.isEmpty => Left(PrometheusProtocolProblem.EmptyExpression)
      case Some(value) if value.getBytes(StandardCharsets.UTF_8).length > MaxExpressionBytes =>
        Left(PrometheusProtocolProblem.ExpressionTooLarge(MaxExpressionBytes))
      case Some(value) => Right(new CompiledPromQuery(id, value, digestOf(value)))
    }

  private def digestOf(expression: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(expression.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  given CanEqual[CompiledPromQuery, CompiledPromQuery] = CanEqual.derived
}

/** An exact Prometheus Unix timestamp at Java's nanosecond resolution. */
opaque type PrometheusTimestamp = Instant

object PrometheusTimestamp {
  private val NanosPerSecond: BigDecimal = BigDecimal(1000000000)
  private val MinimumUnixSeconds: BigDecimal = BigDecimal(Instant.MIN.getEpochSecond)
  private val MaximumUnixSecondsExclusive: BigDecimal = BigDecimal(Instant.MAX.getEpochSecond) + 1

  def fromInstant(instant: Instant): PrometheusTimestamp = instant

  /** Converts an untrusted JSON timestamp without passing through `Double` and losing precision. */
  def fromUnixSeconds(raw: BigDecimal): Either[PrometheusProtocolProblem, PrometheusTimestamp] = {
    val significantScale = raw.bigDecimal.stripTrailingZeros.scale

    if raw < MinimumUnixSeconds || raw >= MaximumUnixSecondsExclusive then
      Left(PrometheusProtocolProblem.TimestampOutOfRange)
    else if significantScale > 9 then Left(PrometheusProtocolProblem.TimestampPrecision)
    else {
      val seconds = raw.setScale(0, BigDecimal.RoundingMode.FLOOR)
      val nanos = (raw - seconds) * NanosPerSecond

      (seconds.toBigIntExact, nanos.toBigIntExact) match {
        case (_, None) => Left(PrometheusProtocolProblem.TimestampPrecision)
        case (Some(epochSecond), Some(nanosecond))
            if epochSecond.isValidLong && nanosecond.isValidInt && nanosecond >= 0 && nanosecond < 1000000000 =>
          Try(Instant.ofEpochSecond(epochSecond.longValue, nanosecond.longValue)).toEither.left.map {
            case _: DateTimeException => PrometheusProtocolProblem.TimestampOutOfRange
            case _: ArithmeticException => PrometheusProtocolProblem.TimestampOutOfRange
            case _ => PrometheusProtocolProblem.TimestampOutOfRange
          }
        case _ => Left(PrometheusProtocolProblem.TimestampOutOfRange)
      }
    }
  }

  extension (timestamp: PrometheusTimestamp) {
    def instant: Instant = timestamp

    def unixSeconds: BigDecimal =
      BigDecimal(timestamp.getEpochSecond) + BigDecimal(timestamp.getNano) / NanosPerSecond

    def wireValue: String = unixSeconds.bigDecimal.stripTrailingZeros.toPlainString

    private[prometheus] def totalNanoseconds: BigInt =
      BigInt(timestamp.getEpochSecond) * BigInt(1000000000) + BigInt(timestamp.getNano)
  }

  given Ordering[PrometheusTimestamp] = Ordering.by(_.instant)
  given CanEqual[PrometheusTimestamp, PrometheusTimestamp] = CanEqual.derived
}

enum NonFiniteKind {
  case NaN, PositiveInfinity, NegativeInfinity
}

object NonFiniteKind {
  given CanEqual[NonFiniteKind, NonFiniteKind] = CanEqual.derived
}

/** One Prometheus sample value. A legal non-finite value is data of a different kind, never a `Double`
  * smuggled into later arithmetic.
  */
sealed trait SampleValue

object SampleValue {

  final class Finite private[SampleValue] (val value: Double) extends SampleValue {
    @nowarn("msg=pattern selector")
    override def equals(other: Any): Boolean = other match {
      case that: Finite => java.lang.Double.compare(value, that.value) == 0
      case _ => false
    }

    override def hashCode(): Int = java.lang.Double.hashCode(value)
    override def toString: String = s"Finite($value)"
  }

  object Finite {
    def unapply(value: Finite): Some[Double] = Some(value.value)
  }

  final case class NonFinite(kind: NonFiniteKind) extends SampleValue

  private val Decimal = "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$".r

  def finite(value: Double): Either[PrometheusProtocolProblem, Finite] =
    Either.cond(value.isFinite, new Finite(value), PrometheusProtocolProblem.InvalidSampleValue)

  def parse(raw: String): Either[PrometheusProtocolProblem, SampleValue] = raw match {
    case "NaN" => Right(NonFinite(NonFiniteKind.NaN))
    case "Inf" | "+Inf" => Right(NonFinite(NonFiniteKind.PositiveInfinity))
    case "-Inf" => Right(NonFinite(NonFiniteKind.NegativeInfinity))
    case Decimal() =>
      raw.toDoubleOption
        .filter(value => value != 0.0 || isLexicalZero(raw))
        .toRight(PrometheusProtocolProblem.InvalidSampleValue)
        .flatMap(finite)
    case _ => Left(PrometheusProtocolProblem.InvalidSampleValue)
  }

  private def isLexicalZero(raw: String): Boolean =
    !raw
      .takeWhile(character => character != 'e' && character != 'E')
      .exists(character => character >= '1' && character <= '9')

  given CanEqual[SampleValue, SampleValue] = CanEqual.derived
}

/** An instant request is valid by construction: both its expression and evaluation time have already crossed
  * their validation boundaries.
  */
final case class InstantQuery(query: CompiledPromQuery, at: PrometheusTimestamp)

enum RangeBoundary {
  case From, To
}

object RangeBoundary {
  given CanEqual[RangeBoundary, RangeBoundary] = CanEqual.derived
}

/** A range request whose alignment and inclusive point budget have already been checked.
  *
  * The constructor is not public, so the transport added later cannot accidentally perform I/O for a range
  * that was never bounded.
  */
final class RangeQuery private (
    val query: CompiledPromQuery,
    val from: PrometheusTimestamp,
    val to: PrometheusTimestamp,
    val step: FiniteDuration,
    val expectedPoints: Int,
    val stepSeconds: String
)

object RangeQuery {

  def create(
      query: CompiledPromQuery,
      from: Instant,
      to: Instant,
      step: FiniteDuration,
      maxPoints: Int
  ): Either[PrometheusProtocolProblem, RangeQuery] = {
    val fromTimestamp = PrometheusTimestamp.fromInstant(from)
    val toTimestamp = PrometheusTimestamp.fromInstant(to)
    val stepNanoseconds = nanosOf(step)

    if maxPoints <= 0 then Left(PrometheusProtocolProblem.InvalidPointLimit)
    else if stepNanoseconds <= 0 then Left(PrometheusProtocolProblem.NonPositiveStep)
    else if from.isAfter(to) then Left(PrometheusProtocolProblem.ReversedRange)
    else if fromTimestamp.totalNanoseconds % stepNanoseconds != 0 then
      Left(PrometheusProtocolProblem.UnalignedRange(RangeBoundary.From))
    else if toTimestamp.totalNanoseconds % stepNanoseconds != 0 then
      Left(PrometheusProtocolProblem.UnalignedRange(RangeBoundary.To))
    else {
      val expected = (toTimestamp.totalNanoseconds - fromTimestamp.totalNanoseconds) / stepNanoseconds + 1
      if expected > maxPoints then Left(PrometheusProtocolProblem.TooManyPoints(expected, maxPoints))
      else
        Right(
          new RangeQuery(
            query,
            fromTimestamp,
            toTimestamp,
            step,
            expected.toInt,
            decimalSeconds(stepNanoseconds)
          )
        )
    }
  }

  private def nanosOf(duration: FiniteDuration): BigInt =
    BigInt(duration.length) * BigInt(duration.unit.toNanos(1L))

  private def decimalSeconds(nanoseconds: BigInt): String =
    (BigDecimal(nanoseconds) / BigDecimal(1000000000)).bigDecimal.stripTrailingZeros.toPlainString
}

/** Labels are kept internal and typed so response decoding cannot confuse them with query parameters. */
final case class MetricLabels(values: Map[String, String])

final case class QuerySample(at: PrometheusTimestamp, value: SampleValue)

final case class InstantSeries(labels: MetricLabels, sample: QuerySample)

final case class RangeSeries(labels: MetricLabels, samples: Vector[QuerySample])

final case class InstantQueryResult(series: Vector[InstantSeries])

final case class RangeQueryResult(series: Vector[RangeSeries])

/** Fresh data records its retrieval boundary; stale fallback additionally records when it was served. */
enum QueryFreshness {
  case Fresh(retrievedAt: Instant)
  case Stale(retrievedAt: Instant, servedAt: Instant)
}

object QueryFreshness {
  given CanEqual[QueryFreshness, QueryFreshness] = CanEqual.derived
}

/** Counts only. Prometheus warning and info text may contain PromQL or label values and never crosses this
  * protocol boundary.
  */
final case class QueryDiagnostics(warningCount: Int, infoCount: Int)

object QueryDiagnostics {
  val Empty: QueryDiagnostics = QueryDiagnostics(0, 0)
}

final case class QueryAnswer[A](result: A, freshness: QueryFreshness, diagnostics: QueryDiagnostics)

/** Validation failures contain only bounded categories and numeric limits, never raw PromQL or sample text.
  */
enum PrometheusProtocolProblem {
  case InvalidQueryId
  case EmptyExpression
  case ExpressionTooLarge(maximumBytes: Int)
  case TimestampPrecision
  case TimestampOutOfRange
  case InvalidSampleValue
  case InvalidPointLimit
  case NonPositiveStep
  case ReversedRange
  case UnalignedRange(boundary: RangeBoundary)
  case TooManyPoints(expected: BigInt, maximum: Int)
}

object PrometheusProtocolProblem {
  given CanEqual[PrometheusProtocolProblem, PrometheusProtocolProblem] = CanEqual.derived
}
