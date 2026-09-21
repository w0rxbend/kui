package kui.metrics.infrastructure.prometheus

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.annotation.tailrec

import io.circe.{parser, Json, JsonObject}

import kui.config.MetricsSourceSettings

/** The strict JSON boundary for successful Prometheus instant and range query responses.
  *
  * It deliberately retains no warning text, info text, error text or response fragment. Those strings may
  * contain raw PromQL and label values; callers receive bounded counts and typed failure categories instead.
  */
object PrometheusResponseDecoder {

  /** A response can report arbitrarily many diagnostics. The UI and telemetry only need to know that they
    * exist and roughly how many, so the count is saturated before it enters the typed answer.
    */
  val MaxDiagnosticCount: Int = 32

  def decodeInstant(
      body: String,
      retrievedAt: Instant,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, QueryAnswer[InstantQueryResult]] =
    for {
      _ <- validateLimits(limits)
      payload <- successPayload(body, ExpectedResult.Vector)
      _ <- requireSeriesLimit(payload.result, limits)
      _ <- requireTotalSampleLimit(payload.result.size.toLong, limits)
      series <- decodeAll(payload.result)(instantSeries(_, limits))
      _ <- rejectDuplicateSeries(series.map(_.labels))
    } yield QueryAnswer(InstantQueryResult(series), QueryFreshness.Fresh(retrievedAt), payload.diagnostics)

  def decodeRange(
      body: String,
      retrievedAt: Instant,
      query: RangeQuery,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, QueryAnswer[RangeQueryResult]] =
    for {
      _ <- validateLimits(limits)
      payload <- successPayload(body, ExpectedResult.Matrix)
      _ <- requireSeriesLimit(payload.result, limits)
      _ <- validateRangeShape(payload.result, query, limits)
      series <- decodeAll(payload.result)(rangeSeries(_, query, limits))
      _ <- rejectDuplicateSeries(series.map(_.labels))
    } yield QueryAnswer(RangeQueryResult(series), QueryFreshness.Fresh(retrievedAt), payload.diagnostics)

  private def validateLimits(limits: PrometheusDecodeLimits): Either[PrometheusDecodeProblem, Unit] =
    Either.cond(
      limits.maxSeries > 0 &&
        limits.maxPointsPerSeries > 0 &&
        limits.maxTotalSamples > 0 &&
        limits.maxLabelsPerSeries > 0 &&
        limits.maxLabelBytesPerSeries > 0,
      (),
      PrometheusDecodeProblem.InvalidLimits
    )

  private def successPayload(
      body: String,
      expected: ExpectedResult
  ): Either[PrometheusDecodeProblem, SuccessPayload] =
    for {
      _ <- DuplicateKeyDetector.validate(body)
      json <- parser.parse(body).left.map(_ => PrometheusDecodeProblem.InvalidJson)
      envelope <- json.asObject.toRight(PrometheusDecodeProblem.InvalidEnvelope)
      status <- envelope("status").flatMap(_.asString).toRight(PrometheusDecodeProblem.InvalidEnvelope)
      _ <- status match {
        case "success" => Right(())
        case "error" => Left(PrometheusDecodeProblem.UpstreamError)
        case _ => Left(PrometheusDecodeProblem.InvalidEnvelope)
      }
      data <- envelope("data").flatMap(_.asObject).toRight(PrometheusDecodeProblem.InvalidEnvelope)
      resultType <- data("resultType").flatMap(_.asString).toRight(PrometheusDecodeProblem.InvalidEnvelope)
      _ <- validateResultType(resultType, expected)
      result <- data("result").flatMap(_.asArray).toRight(PrometheusDecodeProblem.InvalidEnvelope)
      warningCount <- diagnosticCount(envelope, "warnings")
      infoCount <- diagnosticCount(envelope, "infos")
    } yield SuccessPayload(result, QueryDiagnostics(warningCount, infoCount))

  private def validateResultType(
      resultType: String,
      expected: ExpectedResult
  ): Either[PrometheusDecodeProblem, Unit] =
    if resultType == expected.wire then Right(())
    else if resultType == ExpectedResult.Vector.wire || resultType == ExpectedResult.Matrix.wire then
      Left(PrometheusDecodeProblem.MismatchedResultType)
    else Left(PrometheusDecodeProblem.UnsupportedResultType)

  private def diagnosticCount(
      envelope: JsonObject,
      field: String
  ): Either[PrometheusDecodeProblem, Int] =
    envelope(field) match {
      case None => Right(0)
      case Some(json) =>
        json.asArray
          .filter(_.forall(_.asString.isDefined))
          .map(values => math.min(values.size, MaxDiagnosticCount))
          .toRight(PrometheusDecodeProblem.InvalidDiagnostics)
    }

  private def instantSeries(
      json: Json,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, InstantSeries] =
    for {
      series <- json.asObject.toRight(PrometheusDecodeProblem.InvalidSeries)
      _ <- rejectNativeHistogram(series)
      labels <- labelsOf(series, limits)
      value <- series("value").toRight(PrometheusDecodeProblem.InvalidSeries)
      sample <- sampleOf(value)
    } yield InstantSeries(labels, sample)

  private def rangeSeries(
      json: Json,
      query: RangeQuery,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, RangeSeries] =
    for {
      series <- json.asObject.toRight(PrometheusDecodeProblem.InvalidSeries)
      _ <- rejectNativeHistogram(series)
      labels <- labelsOf(series, limits)
      values <- series("values").flatMap(_.asArray).toRight(PrometheusDecodeProblem.InvalidSeries)
      samples <- decodeAll(values)(sampleOf)
      _ <- validateRangeSamples(samples, query)
    } yield RangeSeries(labels, samples)

  private def labelsOf(
      series: JsonObject,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, MetricLabels] =
    series("metric").flatMap(_.asObject).toRight(PrometheusDecodeProblem.InvalidSeries).flatMap { labels =>
      val entries = labels.toVector
      if entries.size > limits.maxLabelsPerSeries then
        Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelsPerSeries))
      else
        entries
          .foldLeft[Either[PrometheusDecodeProblem, (Map[String, String], Long)]](Right(Map.empty -> 0L)) {
            case (read, (name, value)) =>
              for {
                decoded <- read
                label <- value.asString.toRight(PrometheusDecodeProblem.InvalidSeries)
                bytes = decoded._2 + utf8Bytes(name) + utf8Bytes(label)
                _ <- Either.cond(
                  bytes <= limits.maxLabelBytesPerSeries.toLong,
                  (),
                  PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelBytesPerSeries)
                )
              } yield decoded._1.updated(name, label) -> bytes
          }
          .map(decoded => MetricLabels(decoded._1))
    }

  private def utf8Bytes(value: String): Long =
    value.getBytes(StandardCharsets.UTF_8).length.toLong

  private def rejectNativeHistogram(series: JsonObject): Either[PrometheusDecodeProblem, Unit] =
    Either.cond(
      !series.contains("histogram") && !series.contains("histograms"),
      (),
      PrometheusDecodeProblem.NativeHistogramUnsupported
    )

  private def sampleOf(json: Json): Either[PrometheusDecodeProblem, QuerySample] =
    json.asArray.filter(_.size == 2).toRight(PrometheusDecodeProblem.InvalidSample).flatMap { tuple =>
      for {
        number <- tuple.head.asNumber.toRight(PrometheusDecodeProblem.InvalidSample)
        seconds <- number.toBigDecimal.toRight(PrometheusDecodeProblem.InvalidSample)
        timestamp <- PrometheusTimestamp
          .fromUnixSeconds(seconds)
          .left
          .map(_ => PrometheusDecodeProblem.InvalidSample)
        rawValue <- tuple(1).asString.toRight(PrometheusDecodeProblem.InvalidSample)
        value <- SampleValue.parse(rawValue).left.map(_ => PrometheusDecodeProblem.InvalidSample)
      } yield QuerySample(timestamp, value)
    }

  private def requireSeriesLimit(
      result: Vector[Json],
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, Unit] =
    Either.cond(
      result.size <= limits.maxSeries,
      (),
      PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.Series)
    )

  private def requireTotalSampleLimit(
      count: Long,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, Unit] =
    Either.cond(
      count <= limits.maxTotalSamples.toLong,
      (),
      PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.TotalSamples)
    )

  private def validateRangeShape(
      result: Vector[Json],
      query: RangeQuery,
      limits: PrometheusDecodeLimits
  ): Either[PrometheusDecodeProblem, Unit] = {
    val maximumPoints = math.min(query.expectedPoints, limits.maxPointsPerSeries)
    result
      .foldLeft[Either[PrometheusDecodeProblem, Long]](Right(0L)) { (counted, json) =>
        for {
          count <- counted
          series <- json.asObject.toRight(PrometheusDecodeProblem.InvalidSeries)
          _ <- rejectNativeHistogram(series)
          values <- series("values").flatMap(_.asArray).toRight(PrometheusDecodeProblem.InvalidSeries)
          _ <- Either.cond(
            values.size <= maximumPoints,
            (),
            PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.PointsPerSeries)
          )
          total = count + values.size.toLong
          _ <- requireTotalSampleLimit(total, limits)
        } yield total
      }
      .map(_ => ())
  }

  private def validateRangeSamples(
      samples: Vector[QuerySample],
      query: RangeQuery
  ): Either[PrometheusDecodeProblem, Unit] = {
    val insideWindow = samples.forall(sample =>
      !sample.at.instant.isBefore(query.from.instant) && !sample.at.instant.isAfter(query.to.instant)
    )
    val strictlyIncreasing = samples.iterator
      .zip(samples.iterator.drop(1))
      .forall { case (previous, next) => previous.at.instant.isBefore(next.at.instant) }

    if !insideWindow then Left(PrometheusDecodeProblem.SampleOutsideRange)
    else if !strictlyIncreasing then Left(PrometheusDecodeProblem.NonMonotonicSamples)
    else Right(())
  }

  private def rejectDuplicateSeries(labels: Vector[MetricLabels]): Either[PrometheusDecodeProblem, Unit] =
    Either.cond(labels.distinct.size == labels.size, (), PrometheusDecodeProblem.DuplicateSeries)

  private def decodeAll[A](
      values: Vector[Json]
  )(decode: Json => Either[PrometheusDecodeProblem, A]): Either[PrometheusDecodeProblem, Vector[A]] =
    values.foldLeft[Either[PrometheusDecodeProblem, Vector[A]]](Right(Vector.empty)) { (read, value) =>
      for {
        decoded <- read
        next <- decode(value)
      } yield decoded :+ next
    }

  final private case class SuccessPayload(result: Vector[Json], diagnostics: QueryDiagnostics)

  private enum ExpectedResult(val wire: String) {
    case Vector extends ExpectedResult("vector")
    case Matrix extends ExpectedResult("matrix")
  }

  /** Circe's default JSON object construction is last-key-wins. Reject ambiguity before construction so an
    * error status cannot be overwritten by success and two differently escaped copies of a label cannot
    * collapse silently.
    */
  private object DuplicateKeyDetector {
    sealed private trait Frame
    final private case class ObjectFrame(keys: Set[String], expectingKey: Boolean) extends Frame
    private case object ArrayFrame extends Frame

    def validate(body: String): Either[PrometheusDecodeProblem, Unit] = loop(body, 0, Nil)

    @tailrec
    private def loop(
        body: String,
        index: Int,
        frames: List[Frame]
    ): Either[PrometheusDecodeProblem, Unit] =
      if index >= body.length then Right(())
      else
        body.charAt(index) match {
          case '"' =>
            stringEnd(body, index + 1, escaped = false) match {
              case None => Right(()) // The JSON parser reports the malformed string.
              case Some(end) =>
                frames match {
                  case ObjectFrame(keys, true) :: tail =>
                    decodeKey(body.substring(index, end + 1)) match {
                      case Some(key) if keys.contains(key) =>
                        Left(PrometheusDecodeProblem.DuplicateObjectKey)
                      case Some(key) => loop(body, end + 1, ObjectFrame(keys + key, false) :: tail)
                      case None => loop(body, end + 1, frames)
                    }
                  case _ => loop(body, end + 1, frames)
                }
            }
          case '{' => loop(body, index + 1, ObjectFrame(Set.empty, true) :: frames)
          case '[' => loop(body, index + 1, ArrayFrame :: frames)
          case '}' | ']' => loop(body, index + 1, frames.drop(1))
          case ',' =>
            frames match {
              case ObjectFrame(keys, _) :: tail =>
                loop(body, index + 1, ObjectFrame(keys, true) :: tail)
              case _ => loop(body, index + 1, frames)
            }
          case _ => loop(body, index + 1, frames)
        }

    @tailrec
    private def stringEnd(body: String, index: Int, escaped: Boolean): Option[Int] =
      if index >= body.length then None
      else {
        val current = body.charAt(index)
        if escaped then stringEnd(body, index + 1, escaped = false)
        else if current == '\\' then stringEnd(body, index + 1, escaped = true)
        else if current == '"' then Some(index)
        else stringEnd(body, index + 1, escaped = false)
      }

    private def decodeKey(raw: String): Option[String] =
      parser.parse(raw).toOption.flatMap(_.asString)
  }
}

/** Structural limits applied after the HTTP layer has bounded the raw response body. */
final case class PrometheusDecodeLimits(
    maxSeries: Int,
    maxPointsPerSeries: Int,
    maxTotalSamples: Int,
    maxLabelsPerSeries: Int,
    maxLabelBytesPerSeries: Int
)

object PrometheusDecodeLimits {

  val MaxLabelsPerSeries: Int = 64
  val MaxLabelBytesPerSeries: Int = 16 * 1024

  /** Conservative baseline for tests and fixed callers. Query clients derive their validated source limits.
    */
  val Default: PrometheusDecodeLimits = PrometheusDecodeLimits(
    maxSeries = 200,
    maxPointsPerSeries = 600,
    maxTotalSamples = 120000,
    maxLabelsPerSeries = MaxLabelsPerSeries,
    maxLabelBytesPerSeries = MaxLabelBytesPerSeries
  )

  def fromSettings(settings: MetricsSourceSettings): PrometheusDecodeLimits = {
    val total = settings.maxSeriesPerQuery.toLong * settings.maxPointsPerSeries.toLong
    PrometheusDecodeLimits(
      maxSeries = settings.maxSeriesPerQuery,
      maxPointsPerSeries = settings.maxPointsPerSeries,
      maxTotalSamples = total.min(Int.MaxValue.toLong).toInt,
      maxLabelsPerSeries = MaxLabelsPerSeries,
      maxLabelBytesPerSeries = MaxLabelBytesPerSeries
    )
  }
}

enum PrometheusDecodeLimit {
  case Series
  case PointsPerSeries
  case TotalSamples
  case LabelsPerSeries
  case LabelBytesPerSeries
}

object PrometheusDecodeLimit {
  given CanEqual[PrometheusDecodeLimit, PrometheusDecodeLimit] = CanEqual.derived
}

/** Safe failure categories for the JSON boundary. No case carries source-controlled response text. */
enum PrometheusDecodeProblem {
  case InvalidLimits
  case InvalidJson
  case InvalidEnvelope
  case DuplicateObjectKey
  case UpstreamError
  case UnsupportedResultType
  case MismatchedResultType
  case NativeHistogramUnsupported
  case InvalidDiagnostics
  case InvalidSeries
  case InvalidSample
  case LimitExceeded(limit: PrometheusDecodeLimit)
  case DuplicateSeries
  case NonMonotonicSamples
  case SampleOutsideRange
}

object PrometheusDecodeProblem {
  given CanEqual[PrometheusDecodeProblem, PrometheusDecodeProblem] = CanEqual.derived
}
