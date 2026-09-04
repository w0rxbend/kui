package kui.message.contract

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope
import kui.contracts.sse.DoneEvent

/** The payload of one `phase` event.
  *
  * A browse does several things before the first record arrives — resolve a cluster profile, create a
  * consumer, look up offsets, seek — and on a large or a sick cluster each of them can take seconds. A stream
  * that said nothing until the first record would be indistinguishable from a hung one, and the user's only
  * recourse would be to reload and start the whole thing again.
  *
  * It carries a sentence, not a code, because it is read by a person and never branched on by a program. A
  * code would have to be enumerated, and every new phase would then be a contract change for a string that
  * appears in a status line.
  */
final case class PhaseDto(name: String)

object PhaseDto {

  given Codec[PhaseDto] = Codec.from(
    (cursor: HCursor) => cursor.get[String]("name").map(PhaseDto.apply),
    (dto: PhaseDto) => Json.obj("name" -> dto.name.asJson)
  )

  given Schema[PhaseDto] =
    Schema.derived[PhaseDto].description("What the stream is doing right now, in words")

  given CanEqual[PhaseDto, PhaseDto] = CanEqual.derived
}

/** What a browse has left before it stops of its own accord.
  *
  * It is sent so that a client can say *why* a stream is about to end before it ends, and so that "no
  * results" can be told apart from "gave up looking". A filtered scan over a large topic routinely reads a
  * million records and matches none of them; without these numbers the screen is identical to a topic that is
  * empty.
  *
  * Every number counts *down*, and none of them goes negative — the budget saturates at zero, so a chunk that
  * overshoots ends the stream rather than wrapping into an unbounded one.
  *
  * @param millisLeft
  *   the deadline, as a duration rather than an instant, because the client's clock and the server's are not
  *   the same clock and the difference between them is exactly the sort of thing that is wrong by an hour
  */
final case class BudgetDto(recordsLeft: Int, bytesLeft: Long, millisLeft: Long)

object BudgetDto {

  given Codec[BudgetDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        recordsLeft <- cursor.get[Int]("recordsLeft")
        bytesLeft <- cursor.get[Long]("bytesLeft")
        millisLeft <- cursor.get[Long]("millisLeft")
      } yield BudgetDto(recordsLeft, bytesLeft, millisLeft),
    (dto: BudgetDto) =>
      Json.obj(
        "recordsLeft" -> dto.recordsLeft.asJson,
        "bytesLeft" -> dto.bytesLeft.asJson,
        "millisLeft" -> dto.millisLeft.asJson
      )
  )

  given Schema[BudgetDto] = Schema
    .derived[BudgetDto]
    .description("What this browse may still consume before it stops on its own")

  given CanEqual[BudgetDto, BudgetDto] = CanEqual.derived
}

/** The payload of one `consumed` event: how much work has been done so far.
  *
  * `records` is records **read from Kafka**, not records delivered — the two differ by everything a filter
  * rejected, and the gap between them is the number that tells a user their filter is working. `filterErrors`
  * is records whose filter expression *threw* rather than returning false; a filter that errors on every
  * record silently matches nothing, and this is the only field that would ever say so.
  *
  * @param elapsedMs
  *   wall-clock milliseconds since the stream opened, from the server's clock, because it is a duration and
  *   not a point in time
  */
final case class ConsumedDto(
    bytes: Long,
    records: Long,
    elapsedMs: Long,
    filterErrors: Long,
    budget: BudgetDto
)

object ConsumedDto {

  given Codec[ConsumedDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        bytes <- cursor.get[Long]("bytes")
        records <- cursor.get[Long]("records")
        elapsedMs <- cursor.get[Long]("elapsedMs")
        filterErrors <- cursor.getOrElse[Long]("filterErrors")(0L)
        budget <- cursor.get[BudgetDto]("budget")
      } yield ConsumedDto(bytes, records, elapsedMs, filterErrors, budget),
    (dto: ConsumedDto) =>
      Json.obj(
        "bytes" -> dto.bytes.asJson,
        "records" -> dto.records.asJson,
        "elapsedMs" -> dto.elapsedMs.asJson,
        "filterErrors" -> dto.filterErrors.asJson,
        "budget" -> dto.budget.asJson
      )
  )

  given Schema[ConsumedDto] = Schema
    .derived[ConsumedDto]
    .description("Progress: what has been read, what a filter rejected, and what budget is left")

  given CanEqual[ConsumedDto, ConsumedDto] = CanEqual.derived
}

/** The terminal events, named here and declared elsewhere.
  *
  * `done` and `error` are not message-specific and are not redeclared. Every KUI stream ends with one of
  * them, in the same two shapes, and `libs/contracts-core` is where both already live: `DoneEvent` carries
  * the reason a stream stopped and the cursor to resume from (ADR-026), and a mid-stream failure carries the
  * ordinary [[kui.contracts.ErrorEnvelope]] so that a client renders it with the code it already knows
  * (ADR-034, ADR-035).
  *
  * The aliases exist so that the message contract can be read as one page — the task list names `DoneDto` and
  * `ErrorDto` — without a second declaration of either shape. A second `done` payload would be two documents
  * that must agree about the word "exhausted", checked by nothing.
  */
type DoneDto = DoneEvent

/** See [[DoneDto]]: a mid-stream failure is the ordinary error envelope, deliberately. */
type ErrorDto = ErrorEnvelope
