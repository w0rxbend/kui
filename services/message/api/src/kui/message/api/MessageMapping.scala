package kui.message.api

import java.time.Instant

import io.circe.syntax.*

import kui.contracts.ErrorEnvelope
import kui.contracts.message.{DecodeErrorDto, DecodedPayloadDto}
import kui.contracts.sse.DoneReason
import kui.message.contract.BrowseAddress
import kui.http.sse.SseEvent
import kui.kernel.CorrelationId
import kui.kernel.serde.{PayloadKind, Target}
import kui.message.application.{BrowseEnd, BrowseEvent}
import kui.message.contract.{BudgetDto, ConsumedDto, MessageDto, PhaseDto}
import kui.message.domain.{Decoded, DecodeError, DecodedRecord, TimestampType}

/** A [[BrowseEvent]] as one frame of a server-sent-events stream.
  *
  * This file is the whole of ADR-033's mapping for the message service, and keeping it whole is the point:
  * the use case above it has never heard of an event name, and the browser below it decodes
  * `services/message/contract`'s documents. If the two ever disagree it will be because of a line in this
  * file, and there is only one of it.
  *
  * ==The two rules worth stating==
  *
  * `Failed` renders as `event: error` carrying the ordinary [[kui.contracts.ErrorEnvelope]] — the same
  * document a failed HTTP request would have carried, so a client renders a mid-stream failure with the code
  * it already knows (ADR-034). And `Finished` renders as `event: done` carrying the reason and the cursor,
  * so a client can tell "there is nothing more" from "this request stopped" (ADR-035). Every stream ends
  * with exactly one of the two.
  */
object MessageMapping {

  /** The event name each record arrives under. `addEventListener("message", …)` in the browser.
    *
    * Re-exported from `BrowseAddress` rather than spelled here, because the browser subscribes by these exact
    * names and cannot see this module: a name typed in two places is a browser sitting on an open connection
    * receiving nothing, which looks exactly like a topic with no records in it.
    */
  object EventNames {
    val Phase: String = BrowseAddress.Events.Phase
    val Message: String = BrowseAddress.Events.Message
    val Consumed: String = BrowseAddress.Events.Consumed
  }

  /** Everything but a failure, which needs a correlation id and a clock and so is [[failed]]. */
  def event(browseEvent: BrowseEvent): Option[SseEvent] = browseEvent match {
    case BrowseEvent.Phase(name) =>
      Some(SseEvent.data(EventNames.Phase, PhaseDto(name).asJson))

    case BrowseEvent.Record(record) =>
      Some(SseEvent.data(EventNames.Message, message(record).asJson))

    case BrowseEvent.Consumed(bytes, read, delivered, elapsed, budget) =>
      Some(
        SseEvent.data(
          EventNames.Consumed,
          ConsumedDto(
            bytes = bytes,
            records = read,
            elapsedMs = elapsed.toMillis,
            // Nothing evaluates a smart filter yet, so no filter can have thrown. Reporting the honest
            // zero is better than omitting the field: a client that had to tell "no errors" from "this
            // server does not count them" would have to know which build it was talking to.
            filterErrors = 0L,
            budget = BudgetDto(
              recordsLeft = math.max(0, budget.recordsLeft - read.toInt),
              bytesLeft = math.max(0L, budget.bytesLeft - bytes),
              millisLeft = math.max(0L, budget.deadline.toMillis - elapsed.toMillis)
            )
          ).asJson
        )
      )

    case BrowseEvent.Finished(reason, cursor) =>
      Some(SseEvent.done(doneReason(reason), cursor))

    // A failure needs the request's correlation id, which this function does not have. `failed` builds it.
    case BrowseEvent.Failed(_) => None
  }

  /** The terminal `error` frame. */
  def failed(error: kui.kernel.error.KuiError, correlationId: CorrelationId, now: Instant): SseEvent =
    SseEvent.error(ErrorEnvelope.of(error, correlationId, now))

  def doneReason(reason: BrowseEnd): DoneReason = reason match {
    case BrowseEnd.Limit => DoneReason.Limit
    case BrowseEnd.Exhausted => DoneReason.Exhausted
  }

  /** One record on the wire.
    *
    * `deserializeErrors` is always present, even when it is empty, because a decoder that defaulted it away
    * would turn "the producer wrote something this serde cannot read" into "this record decoded perfectly".
    */
  def message(record: DecodedRecord): MessageDto =
    MessageDto(
      partition = record.partition,
      offset = record.offset,
      timestamp = record.timestamp,
      timestampType = timestampType(record.timestampType),
      key = payload(record.key, record.keySize),
      value = payload(record.value, record.valueSize),
      headers = record.headers.map(header => header.key -> header.value).toMap,
      keySize = record.keySize,
      valueSize = record.valueSize,
      headersSize = record.headersSize,
      deserializeErrors = record.decodeErrors.map(decodeError)
    )

  /** A decoded half of a record.
    *
    * A payload with no bytes and no text is reported as `kind = "null"`, which is how a tombstone's absent
    * value and a record with no key are told apart from a value that really is the empty string. The two
    * render differently and mean entirely different things.
    */
  def payload(decoded: Decoded, serialisedSize: Int): DecodedPayloadDto =
    if serialisedSize == 0 && decoded.text.isEmpty then
      DecodedPayloadDto.absent(decoded.serde.value).copy(properties = decoded.properties)
    else
      DecodedPayloadDto(
        text = decoded.text,
        kind = kind(decoded.kind),
        serde = decoded.serde.value,
        properties = decoded.properties
      )

  def kind(payloadKind: PayloadKind): String = payloadKind match {
    case PayloadKind.Json => DecodedPayloadDto.Kind.Json
    case PayloadKind.Text => DecodedPayloadDto.Kind.Text
  }

  def decodeError(error: DecodeError): DecodeErrorDto =
    DecodeErrorDto(target = target(error.target), serde = error.serde.value, cause = error.cause)

  def target(value: Target): String = value match {
    case Target.Key => DecodeErrorDto.Target.Key
    case Target.Value => DecodeErrorDto.Target.Value
  }

  /** Kafka's two clocks, spelled the way the wire contract spells them. */
  def timestampType(value: TimestampType): String = value match {
    case TimestampType.CreateTime => MessageDto.TimestampType.CreateTime
    case TimestampType.LogAppendTime => MessageDto.TimestampType.LogAppendTime
    // A record written by a producer old enough not to have stamped one at all. It is reported as the
    // create-time case rather than as a third spelling, because the timestamp itself is what a client
    // renders and Kafka's own `-1` has already been turned into an instant by the time it reaches here.
    case TimestampType.NoTimestamp => MessageDto.TimestampType.CreateTime
  }
}
