package kui.metrics.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** One step of the latency axis, on the wire.
  *
  * Both percentiles are nullable and every `null` means **not measured**, exactly as it does on a throughput
  * bucket: a step KUI did not sample has to break the line, and a zero would claim the broker answered
  * instantly. The screens draw the two differently and cannot invent the difference from a number.
  *
  * Milliseconds, said in the field name. The broker's `TotalTimeMs` is already in milliseconds and a unit
  * that travels in the name is one a browser cannot get wrong by a factor of a thousand.
  */
final case class LatencyBucketDto(
    startingAt: Instant,
    produceP99Millis: Option[Double],
    fetchP99Millis: Option[Double]
)

object LatencyBucketDto {

  given Codec[LatencyBucketDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        startingAt <- cursor.get[Instant]("startingAt")
        // Absent and null both decode to "not measured". A document written by an older build that
        // omitted a percentile must land on the gap rendering rather than on a decode failure.
        produce <- cursor.getOrElse[Option[Double]]("produceP99Millis")(None)
        fetch <- cursor.getOrElse[Option[Double]]("fetchP99Millis")(None)
      } yield LatencyBucketDto(startingAt, produce, fetch),
    (bucket: LatencyBucketDto) =>
      Json.obj(
        "startingAt" -> bucket.startingAt.asJson,
        "produceP99Millis" -> bucket.produceP99Millis.asJson,
        "fetchP99Millis" -> bucket.fetchP99Millis.asJson
      )
  )

  given TapirSchema[LatencyBucketDto] = TapirSchema
    .derived[LatencyBucketDto]
    .description(
      "One step of the axis. A null percentile is a step KUI did not measure, never a measured zero"
    )

  given CanEqual[LatencyBucketDto, LatencyBucketDto] = CanEqual.derived
}

/** A cluster's p99 request latency over one window.
  *
  * `from`, `to` and `stepSeconds` travel with the buckets for the reason they do on a throughput series: a
  * window whose every bucket is absent still has to draw a full axis.
  *
  * ==The value in a bucket is the worst percentile in it, not the mean of them==
  *
  * Several scrapes land in one step and each carries a p99. The mean of four p99s is a p99 of nothing —
  * percentiles do not average — so the bucket carries the largest, which stays true of the data it came from.
  * A browser must not re-fold these; the field name says what the number is.
  */
final case class LatencySeriesDto(
    window: ThroughputRangeDto,
    from: Instant,
    to: Instant,
    stepSeconds: Long,
    buckets: List[LatencyBucketDto]
)

object LatencySeriesDto {

  given Codec[LatencySeriesDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        window <- cursor.get[ThroughputRangeDto]("window")
        from <- cursor.get[Instant]("from")
        to <- cursor.get[Instant]("to")
        stepSeconds <- cursor.get[Long]("stepSeconds")
        buckets <- cursor.getOrElse[List[LatencyBucketDto]]("buckets")(Nil)
      } yield LatencySeriesDto(window, from, to, stepSeconds, buckets),
    (series: LatencySeriesDto) =>
      Json.obj(
        "window" -> series.window.asJson,
        "from" -> series.from.asJson,
        "to" -> series.to.asJson,
        "stepSeconds" -> series.stepSeconds.asJson,
        "buckets" -> series.buckets.asJson
      )
  )

  given TapirSchema[LatencySeriesDto] = TapirSchema
    .derived[LatencySeriesDto]
    .description(
      "The worst p99 of produce and consumer-fetch request time in each step of the requested window"
    )

  given CanEqual[LatencySeriesDto, LatencySeriesDto] = CanEqual.derived
}

/** The latency endpoint's whole answer.
  *
  * `unavailable` here carries a meaning throughput's does not: the exporter answered and served no
  * `RequestMetrics` family at all, so the card's sentence names a whitelist to widen rather than an axis to
  * wait for. `not_configured` still means what it means everywhere in this service — this deployment named no
  * metrics source, and nothing is wrong.
  */
final case class LatencyResponse(latency: Section[LatencySeriesDto])

object LatencyResponse {

  given Codec[LatencyResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[LatencySeriesDto]]("latency").map(LatencyResponse(_)),
    (response: LatencyResponse) => Json.obj("latency" -> response.latency.asJson)
  )

  given TapirSchema[LatencyResponse] = TapirSchema
    .derived[LatencyResponse]
    .description("The cluster's p99 request latency, or the reason there is none to show")

  given CanEqual[LatencyResponse, LatencyResponse] = CanEqual.derived
}
