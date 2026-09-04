package kui.ui.messages.table

import io.circe.Json
import io.circe.parser.parse

import kui.contracts.message.DecodedPayloadDto
import kui.message.contract.MessageDto

/** The one place a decoded record becomes something the flattener can spread across columns.
  *
  * It is separate from [[JsonFlattener]] because the flattener is pure over JSON and is property-tested as
  * such, while this knows the wire type — and separate from the table because the table should not be reading
  * `kind` strings. The whole of the conversion is here, in three rules.
  */
object RecordSource {

  /** One record, ready to flatten.
    *
    * The three rules, each of which is a decision about a payload KUI cannot fully trust:
    *
    *   - **An absent payload is `null`.** A tombstone's value and a keyless record's key both flatten to a
    *     single cell reading `null`, which is what they are, rather than to no cell at all — an empty column
    *     and a missing column look identical in a table and mean opposite things.
    *   - **A payload the service called JSON is parsed, and if it will not parse it is text.** The `kind`
    *     field is documented as a rendering *hint*, so a client that trusted it and then threw on a malformed
    *     document would turn one bad record into a broken screen.
    *   - **Anything else is a string at the root**, which flattens to exactly one `K` or `V` column holding
    *     the text. That is the documented degraded behaviour for a binary or plain-text topic: the table view
    *     still works, it simply has one column per side instead of many.
    */
  def of(record: MessageDto): FlatSource =
    FlatSource(
      // Sorted by name so that the columns of a record with headers written in a different order are the
      // same columns. Header order is not meaningful in Kafka and letting it through would make two
      // identical records seed two different tables.
      headers = record.headers.toVector.sortBy(_._1),
      key = payload(record.key),
      value = payload(record.value)
    )

  def all(records: List[MessageDto]): Vector[FlatSource] = records.toVector.map(of)

  private def payload(decoded: DecodedPayloadDto): Json =
    if decoded.kind == DecodedPayloadDto.Kind.Absent then Json.Null
    else if decoded.kind == DecodedPayloadDto.Kind.Json then
      parse(decoded.text).getOrElse(Json.fromString(decoded.text))
    else Json.fromString(decoded.text)
}
