package kui.metrics.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec as TapirCodec, DecodeResult, Schema as TapirSchema}

import kui.contracts.{KernelDecodeFailure, Section}
import kui.kernel.ValidationError

/** How far back a throughput request reaches, on the wire.
  *
  * The contract's own copy of the domain's range, because `services/metrics/contract` may not depend on
  * `services/metrics/domain` (rule A2) — the browser compiles this file and must not be made to compile the
  * bucket arithmetic behind it. The `api` module maps between the two, which is the one place the two
  * spellings can disagree and therefore the one place a test can pin them together.
  *
  * Three values and not a free `from`/`to` pair. The step is chosen with the window (see the domain's
  * `ThroughputRange`), so an arbitrary window would be a window with no defined resolution.
  */
enum ThroughputRangeDto(val wire: String) {
  case Last24Hours extends ThroughputRangeDto("24h")
  case Last7Days extends ThroughputRangeDto("7d")
  case Last30Days extends ThroughputRangeDto("30d")
}

object ThroughputRangeDto {

  val All: List[ThroughputRangeDto] = List(Last24Hours, Last7Days, Last30Days)

  val Default: ThroughputRangeDto = Last24Hours

  /** The spellings, in the order the OpenAPI document lists them. Public because the endpoint's own
    * description prints them and a client generator reads the validator built from them.
    */
  val Wires: List[String] = All.map(_.wire)

  def fromWire(raw: String): Option[ThroughputRangeDto] = {
    val normalised = raw.trim.toLowerCase
    All.find(_.wire == normalised)
  }

  given Codec[ThroughputRangeDto] = Codec.from(
    Decoder[String].emap(raw => fromWire(raw).toRight(s"'$raw' is not a throughput range")),
    Encoder[String].contramap(_.wire)
  )

  /** The documented shape. The three spellings are in the description rather than left for a reader to infer,
    * because the query parameter is the only part of this endpoint a caller has to type by hand.
    */
  given TapirSchema[ThroughputRangeDto] =
    TapirSchema.string[ThroughputRangeDto].description(s"one of ${Wires.mkString(", ")}")

  /** An unrecognised range is **refused**, not defaulted.
    *
    * Answering `?range=90d` with twenty-four hours of data would draw a chart the caller labelled "90 days"
    * out of one day of samples, and nothing on the screen would say so. The refusal travels as the kernel's
    * own validation error so that `libs/http` renders it as a `KUI-VALIDATION` envelope naming the parameter,
    * exactly as a malformed cluster id is rendered.
    *
    * @param field
    *   the query parameter's own name, because two endpoints spell this vocabulary differently — `?range=` on
    *   throughput and `?window=` on latency — and a refusal that named the wrong one would send a caller to
    *   look at a parameter they did not send.
    */
  def codecFor(field: String): TapirCodec[String, ThroughputRangeDto, TextPlain] =
    TapirCodec.string.mapDecode(raw =>
      fromWire(raw) match {
        case Some(range) => DecodeResult.Value(range)
        case None =>
          val expected = s"one of ${Wires.mkString(", ")}"
          DecodeResult.Error(raw, KernelDecodeFailure(ValidationError.Format(field, expected, raw)))
      }
    )(_.wire)

  /** The default spelling, for the endpoint that named the parameter `range`. */
  given TapirCodec[String, ThroughputRangeDto, TextPlain] = codecFor("range")

  given CanEqual[ThroughputRangeDto, ThroughputRangeDto] = CanEqual.derived
}

/** One step of the time axis, on the wire.
  *
  * Every rate is nullable and every `null` means **not measured**. That is the whole reason this DTO is
  * shaped this way rather than as three plain numbers: a bucket KUI never sampled has to break the bar, and a
  * zero would claim the cluster was idle. The screens draw the two differently and cannot invent the
  * difference from a number.
  */
final case class ThroughputBucketDto(
    startingAt: Instant,
    bytesInPerSecond: Option[Double],
    bytesOutPerSecond: Option[Double],
    recordsPerSecond: Option[Double]
)

object ThroughputBucketDto {

  given Codec[ThroughputBucketDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        startingAt <- cursor.get[Instant]("startingAt")
        // Absent and null both decode to "not measured". A document written by an older build that
        // omitted a rate must land on the gap rendering rather than on a decode failure.
        bytesIn <- cursor.getOrElse[Option[Double]]("bytesInPerSecond")(None)
        bytesOut <- cursor.getOrElse[Option[Double]]("bytesOutPerSecond")(None)
        records <- cursor.getOrElse[Option[Double]]("recordsPerSecond")(None)
      } yield ThroughputBucketDto(startingAt, bytesIn, bytesOut, records),
    (bucket: ThroughputBucketDto) =>
      Json.obj(
        "startingAt" -> bucket.startingAt.asJson,
        "bytesInPerSecond" -> bucket.bytesInPerSecond.asJson,
        "bytesOutPerSecond" -> bucket.bytesOutPerSecond.asJson,
        "recordsPerSecond" -> bucket.recordsPerSecond.asJson
      )
  )

  given TapirSchema[ThroughputBucketDto] = TapirSchema
    .derived[ThroughputBucketDto]
    .description("One step of the axis. A null rate is a gap KUI did not measure, never a measured zero")

  given CanEqual[ThroughputBucketDto, ThroughputBucketDto] = CanEqual.derived
}

/** A cluster's throughput over one range.
  *
  * `from`, `to` and `stepSeconds` travel with the buckets rather than being derived from them, because a
  * series whose every bucket is absent still has to draw a full axis: without them a day with no samples
  * would render as an empty box instead of as an empty day.
  */
final case class ThroughputSeriesDto(
    range: ThroughputRangeDto,
    from: Instant,
    to: Instant,
    stepSeconds: Long,
    buckets: List[ThroughputBucketDto]
)

object ThroughputSeriesDto {

  given Codec[ThroughputSeriesDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        range <- cursor.get[ThroughputRangeDto]("range")
        from <- cursor.get[Instant]("from")
        to <- cursor.get[Instant]("to")
        stepSeconds <- cursor.get[Long]("stepSeconds")
        buckets <- cursor.getOrElse[List[ThroughputBucketDto]]("buckets")(Nil)
      } yield ThroughputSeriesDto(range, from, to, stepSeconds, buckets),
    (series: ThroughputSeriesDto) =>
      Json.obj(
        "range" -> series.range.asJson,
        "from" -> series.from.asJson,
        "to" -> series.to.asJson,
        "stepSeconds" -> series.stepSeconds.asJson,
        "buckets" -> series.buckets.asJson
      )
  )

  given TapirSchema[ThroughputSeriesDto] = TapirSchema
    .derived[ThroughputSeriesDto]
    .description("Bytes in, bytes out and records per second, bucketed over the requested range")

  given CanEqual[ThroughputSeriesDto, ThroughputSeriesDto] = CanEqual.derived
}

/** The throughput endpoint's whole answer.
  *
  * The series is inside a `Section`, so a cluster with no metrics source answers **200** with
  * `not_configured` and a cluster whose exporter is down answers 200 with `unavailable` and a reason. Both
  * are things a card can draw. A 404 for the first would be indistinguishable from a typo in the URL, and a
  * 500 for the second tells a browser only that something went wrong somewhere.
  *
  * One field and not a bare `Section`, for the same reason `BrokersResponse` has one: an object that started
  * as a bare section could never grow a second field without breaking every client that had shipped against
  * it. Latency did not end up here — it is its own endpoint, so that one dead exporter family costs one card
  * — but the property is worth keeping for whatever throughput acquires next.
  */
final case class ThroughputResponse(throughput: Section[ThroughputSeriesDto])

object ThroughputResponse {

  given Codec[ThroughputResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[ThroughputSeriesDto]]("throughput").map(ThroughputResponse(_)),
    (response: ThroughputResponse) => Json.obj("throughput" -> response.throughput.asJson)
  )

  given TapirSchema[ThroughputResponse] = TapirSchema
    .derived[ThroughputResponse]
    .description("The cluster's throughput, or the reason there is none to show")

  given CanEqual[ThroughputResponse, ThroughputResponse] = CanEqual.derived
}
