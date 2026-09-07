package kui.metrics.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** How large a record on this cluster is, on average.
  *
  * ==A mean, and the design asked for a distribution==
  *
  * `SCREENS-V4.md` §3.5 draws a twelve-bucket histogram with `p50 · 1.1 KB`, `p99 · 18 KB` and `max · 0.9 MB`
  * chips. Kafka publishes **no** record-size distribution of any kind: a stock-ruleset exposition of the
  * quickstart broker carries 680 Kafka families and not one histogram bucket among them (ADR-052). What it
  * does publish is a bytes-in rate and a records-in rate, whose quotient is a mean record size. There is no
  * `p50`, no `p99` and no `max` on this DTO, and the absence is the point: a browser cannot draw a percentile
  * it was never sent, and the card says in words that the distribution cannot be measured.
  *
  * @param meanBytes
  *   `bytesInPerSecond / recordsPerSecond`. `null` when either rate is absent **and** when the record rate is
  *   zero — a broker receiving no records has no mean record size, and dividing by zero would put `Infinity`
  *   on a card.
  * @param bytesInPerSecond
  *   the numerator, sent so that a card can say what the figure is instead of leaving a reader to assume it
  *   is a median
  * @param recordsPerSecond
  *   the denominator, and the reason `meanBytes` is null when it is zero
  */
final case class RecordSizeDto(
    meanBytes: Option[Double],
    bytesInPerSecond: Option[Double],
    recordsPerSecond: Option[Double]
)

object RecordSizeDto {

  given Codec[RecordSizeDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        mean <- cursor.getOrElse[Option[Double]]("meanBytes")(None)
        bytes <- cursor.getOrElse[Option[Double]]("bytesInPerSecond")(None)
        records <- cursor.getOrElse[Option[Double]]("recordsPerSecond")(None)
      } yield RecordSizeDto(mean, bytes, records),
    (reading: RecordSizeDto) =>
      Json.obj(
        "meanBytes" -> reading.meanBytes.asJson,
        "bytesInPerSecond" -> reading.bytesInPerSecond.asJson,
        "recordsPerSecond" -> reading.recordsPerSecond.asJson
      )
  )

  given TapirSchema[RecordSizeDto] = TapirSchema
    .derived[RecordSizeDto]
    .description(
      "The mean size of a record, as bytes in over records in, with the two rates it came from. A mean " +
        "and never a distribution: Kafka publishes no record-size histogram (ADR-052)"
    )

  given CanEqual[RecordSizeDto, RecordSizeDto] = CanEqual.derived
}

/** The record-size endpoint's whole answer. */
final case class RecordSizeResponse(recordSize: Section[RecordSizeDto])

object RecordSizeResponse {

  given Codec[RecordSizeResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[RecordSizeDto]]("recordSize").map(RecordSizeResponse(_)),
    (response: RecordSizeResponse) => Json.obj("recordSize" -> response.recordSize.asJson)
  )

  given TapirSchema[RecordSizeResponse] = TapirSchema
    .derived[RecordSizeResponse]
    .description("The mean record size, or the reason there is none to show")

  given CanEqual[RecordSizeResponse, RecordSizeResponse] = CanEqual.derived
}
