package kui.ui.messages.row

import io.circe.parser.parse

import com.raquo.laminar.api.L.*

import kui.contracts.message.{DecodeErrorDto, DecodedPayloadDto}
import kui.message.contract.MessageDto
import kui.ui.messages.{Messages, MessagesCss}

/** One record, opened: its key, its value pretty-printed, its headers, and every serde failure that happened
  * while it was decoded.
  *
  * ## In place, not in a dialog
  *
  * The design draws it as a row that expands where it is, and that is the right shape for what people
  * actually do here: they open a record, read it, close it, open the next one, and compare the two. A dialog
  * makes each of those a modal that has to be dismissed, hides the rows behind it, and cannot be left open
  * while scrolling — so comparing two records means remembering the first one.
  *
  * ## Pretty-printed, and only when it is JSON
  *
  * The service says what it decoded each half into — `json`, `string`, `binary` or `null` — and this trusts
  * that rather than sniffing the text. Re-parsing is only for laying it out: a document that says it is JSON
  * and does not parse is shown exactly as it arrived, because the bytes on the topic are the truth and a
  * screen that hid them would be hiding the very thing somebody opened the record to see.
  */
object RecordDetail {

  def apply(record: MessageDto): HtmlElement =
    div(
      cls := MessagesCss.Detail,
      dataAttr("testid") := s"record-${record.partition.value}-${record.offset.value}-detail",
      // Every serde failure first, because it changes how everything under it should be read. A record that
      // could not be decoded is still delivered — the bytes are what the user came for — and the failure
      // travels with it rather than being swallowed into a blank cell.
      Option.when(record.deserializeErrors.nonEmpty)(
        div(
          cls := MessagesCss.DecodeError,
          role := "note",
          dataAttr("testid") := "record-decode-errors",
          record.deserializeErrors.map(problem => p(sentence(problem)))
        )
      ),
      payload(Messages.KeyHeading, record.key, "key"),
      payload(Messages.ValueHeading, record.value, "value"),
      headers(record)
    )

  private def payload(heading: String, decoded: DecodedPayloadDto, testId: String): HtmlElement =
    div(
      cls := MessagesCss.DetailSection,
      h4(cls := MessagesCss.DetailHeading, heading),
      pre(
        cls := MessagesCss.Payload,
        dataAttr("testid") := s"record-$testId-payload",
        // The serde that produced this text is on the block itself, because "which serde read this" is the
        // first question asked whenever the answer looks wrong.
        title := decoded.serde,
        format(decoded)
      )
    )

  private def headers(record: MessageDto): HtmlElement =
    div(
      cls := MessagesCss.DetailSection,
      h4(cls := MessagesCss.DetailHeading, Messages.HeadersHeading),
      if record.headers.isEmpty then p(dataAttr("testid") := "record-no-headers", Messages.NoHeaders)
      else
        dl(
          cls := MessagesCss.Headers,
          dataAttr("testid") := "record-headers",
          // Sorted by name, so that the same record read twice lists them in the same order — a map has no
          // order of its own and an arbitrary one makes two screenshots of one record look different.
          record.headers.toList.sortBy((name, _) => name).flatMap { (name, value) =>
            List(dt(cls := MessagesCss.HeaderName, name), dd(value))
          }
        )
    )

  /** The text of one payload, laid out.
    *
    * An absent payload is words and not an empty block: a record with no key and a tombstone are facts about
    * the record, and an empty `<pre>` says nothing at all.
    */
  private[messages] def format(decoded: DecodedPayloadDto): String =
    decoded.kind match {
      case DecodedPayloadDto.Kind.Absent => Messages.Tombstone
      case DecodedPayloadDto.Kind.Json => prettyJson(decoded.text)
      case _ => decoded.text
    }

  /** Pretty-prints, and falls back to the text exactly as it arrived.
    *
    * The fallback is the point. A payload the service labelled JSON that will not parse here is a
    * disagreement worth seeing, and showing the raw text lets the reader see what it actually is; throwing an
    * error, or showing nothing, would hide the evidence.
    */
  private[messages] def prettyJson(raw: String): String =
    parse(raw).fold(_ => raw, _.spaces2)

  private def sentence(problem: DecodeErrorDto): String =
    Messages.decodeFailed(problem.target, problem.serde, problem.cause)
}
