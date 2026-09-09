package kui.message.api

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import io.circe.Json
import munit.FunSuite

import kui.contracts.message.{DecodeErrorDto, DecodedPayloadDto}
import kui.kernel.browse.PollBudget
import kui.kernel.serde.{PayloadKind, SerdeName, Target}
import kui.kernel.{Offset, PartitionId}
import kui.message.application.BrowseEvent
import kui.message.contract.MessageDto
import kui.message.domain.{DecodeError, Decoded, DecodedRecord, TimestampType}

/** The field-level rules `MessageMapping` argues for at length, and which nothing asserted.
  *
  * `services/message/api` shipped one suite — `ServiceRbacGuardSuite` — so every rule in this file was
  * reachable only through a route that asserts a status code, which is not what a route suite looks at.
  * Five mutations were applied one at a time against `./mill services.message.__.test`, each leaving it at
  * 183/183 green: making `payload`'s absent branch unreachable, dropping `deserializeErrors` to `Nil`,
  * zeroing `filterErrors`, removing the floor under a spent budget, and mapping `NoTimestamp` to
  * `LOG_APPEND_TIME`.
  *
  * The budget and filter cases read the encoder's own output — the frame `MessageMapping.event` builds —
  * rather than a hand-written literal, because a hand-written literal on each side of a wire is what put
  * two of the metrics service's four cards on screen drawing nothing.
  */
final class MessageMappingSuite extends FunSuite {

  private val At: Instant = Instant.parse("2026-09-06T10:00:00Z")

  private val serde: SerdeName = SerdeName.unsafe("string")

  private def record(
      value: Decoded,
      valueSize: Int,
      errors: List[DecodeError] = Nil,
      stamped: TimestampType = TimestampType.CreateTime
  ): DecodedRecord =
    DecodedRecord(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(41L),
      timestamp = At,
      timestampType = stamped,
      key = Decoded.absent(serde),
      value = value,
      headers = Nil,
      keySize = 0,
      valueSize = valueSize,
      headersSize = 0,
      decodeErrors = errors
    )

  test("a tombstone's absent value is null on the wire and an empty string is not") {
    // One byte apart on the wire and opposite in meaning: a tombstone deletes the key at compaction, and
    // an empty value is a record somebody deliberately wrote.
    val tombstone = MessageMapping.payload(Decoded.absent(serde), 0)
    val empty = MessageMapping.payload(Decoded("", PayloadKind.Text, serde, Map.empty), 4)

    assertEquals(tombstone.kind, DecodedPayloadDto.Kind.Absent)
    assertEquals(empty.kind, DecodedPayloadDto.Kind.Text)
  }

  test("a payload that decoded carries its text, its kind and the serde that read it") {
    val decoded = MessageMapping.payload(Decoded("""{"a":1}""", PayloadKind.Json, serde, Map.empty), 7)

    assertEquals(decoded.text, """{"a":1}""")
    assertEquals(decoded.kind, DecodedPayloadDto.Kind.Json)
    assertEquals(decoded.serde, serde.value)
  }

  test("a record the serde could not read reaches the wire carrying the failure") {
    val failed = DecodeError(Target.Value, serde, "not valid Avro")
    val dto = MessageMapping.message(record(Decoded.absent(serde), 12, errors = List(failed)))

    // Dropping this list turns "the producer wrote something this serde cannot read" into "this record
    // decoded perfectly", which is the sentence the field exists to prevent.
    assertEquals(dto.deserializeErrors.map(_.cause), List("not valid Avro"))
    assertEquals(dto.deserializeErrors.map(_.target), List(DecodeErrorDto.Target.Value))
  }

  test("a record that decoded cleanly carries an empty error list, which is a different document") {
    val clean = MessageMapping.message(record(Decoded("hi", PayloadKind.Text, serde, Map.empty), 2))

    assertEquals(clean.deserializeErrors, Nil)
  }

  test("a filter that threw on every record says so, and is not a topic that matched nothing") {
    val body = frameOf(
      BrowseEvent.Consumed(
        bytes = 4096L,
        read = 500L,
        delivered = 0L,
        filterErrors = 500L,
        elapsed = FiniteDuration(1200L, "millis"),
        budget = PollBudget.unsafe(1000, 1024L * 1024L, FiniteDuration(30, "seconds"))
      )
    )

    // The pair that makes the sentence: five hundred records read, none delivered, and every one a throw.
    assertEquals(body.hcursor.get[Long]("records"), Right(500L))
    assertEquals(body.hcursor.get[Long]("filterErrors"), Right(500L))
  }

  test("a budget spent past its own size is reported as nothing left, never as a negative") {
    // `recordsLeft` is drawn as remaining work. A negative one renders as a bar running backwards and as
    // "-150 records left", which is not a state a browse can be in.
    val budget = frameOf(
      BrowseEvent.Consumed(
        bytes = 2048L,
        read = 250L,
        delivered = 250L,
        filterErrors = 0L,
        elapsed = FiniteDuration(90L, "seconds"),
        budget = PollBudget.unsafe(100, 1024L, FiniteDuration(30, "seconds"))
      )
    ).hcursor.downField("budget")

    assertEquals(budget.get[Int]("recordsLeft"), Right(0))
    assertEquals(budget.get[Long]("bytesLeft"), Right(0L))
    assertEquals(budget.get[Long]("millisLeft"), Right(0L))
  }

  test("a record whose producer stamped no timestamp is reported as create-time") {
    // Kafka's `-1` has already become an instant by the time it reaches here, so a third spelling would be
    // a value every client has to learn for no gain — but it has to be create-time and not the other one,
    // because log-append time is a claim about when the broker wrote the record.
    val unstamped = record(Decoded.absent(serde), 0, stamped = TimestampType.NoTimestamp)

    assertEquals(MessageMapping.message(unstamped).timestampType, MessageDto.TimestampType.CreateTime)
  }

  /** The frame the browser actually receives, so these assertions read the encoder's own output. */
  private def frameOf(event: BrowseEvent): Json =
    MessageMapping.event(event) match {
      case Some(frame) => frame.data
      case None => fail("this event must render a frame")
    }
}
