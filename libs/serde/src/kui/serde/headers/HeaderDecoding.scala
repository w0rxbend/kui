package kui.serde.headers

import java.nio.ByteBuffer

import kui.serde.builtin.{HexSerde, Payloads}

/** How one header's bytes become the string a user reads. */
enum HeaderRendering {

  /** The value decoded as text. */
  case Text(value: String)

  /** The value read as a binary number. `raw` is the hex, kept so the detail view can show the bytes that
    * produced the number without decoding them a second time.
    */
  case Number(value: Long, raw: String)

  /** The value as hex, because it is neither a known number nor legible text. */
  case Binary(hex: String)

  /** What travels on the wire and appears in a table cell (ADR-035: headers are a map of strings). */
  def display: String = this match {
    case Text(value) => value
    case Number(value, _) => value.toString
    case Binary(hex) => hex
  }
}

object HeaderRendering {
  given CanEqual[HeaderRendering, HeaderRendering] = CanEqual.derived
}

/** Spring Kafka's dead-letter and retry headers, rendered as the numbers they are.
  *
  * Spring Kafka writes `kafka_original-offset` as eight raw bytes, not as the text `41892`. Every other Kafka
  * UI renders that as four characters of mojibake, which makes the dead-letter screen — the one screen a
  * Spring user opens when something has gone wrong — the least legible screen in the product. This table
  * fixes exactly that, for the nine headers Spring actually writes.
  *
  * Two rules keep it from being confidently wrong:
  *
  *   - **A header in the table whose value is not the expected length falls through to the general rule.**
  *     Producers do get this wrong, and rendering the wrong number with the authority of a known header name
  *     is worse than rendering hex.
  *   - **An unknown header is text when it decodes as legible text and hex otherwise.** Never an error, never
  *     an empty cell: a header a user cannot see is a header they will assume is absent.
  *
  * The set is fixed and not configurable. It is four configuration keys for a table that changes about once a
  * year, and `docs/operations/serdes.md` is generated from [[HeaderDecoding.NumericHeaders]] rather than
  * retyped, so the document and the behaviour cannot drift.
  *
  * Masking runs **after** this rendering (ADR-023): a masked numeric header is a masked string.
  */
object HeaderDecoding {

  private val EightByteHeaders: Set[String] = Set(
    "kafka_original-offset",
    "kafka_original-timestamp",
    "kafka_dlt-original-offset",
    "kafka_dlt-original-timestamp",
    "retry_topic-original-timestamp"
  )

  private val FourByteHeaders: Set[String] = Set(
    "kafka_original-partition",
    "kafka_dlt-original-partition",
    "kafka_delivery-attempt",
    "retry_topic-attempts"
  )

  /** Every header name whose value is a binary number rather than text.
    *
    * Public because `docs/operations/serdes.md` is generated from it (MSG-048) and because the produce form
    * uses it to decide whether a header a user types should be encoded as bytes.
    */
  val NumericHeaders: Set[String] = EightByteHeaders ++ FourByteHeaders

  /** The number of bytes a named header's value must have to be read as a number, if it is a numeric header
    * at all.
    */
  def widthOf(name: String): Option[Int] =
    if EightByteHeaders.contains(name) then Some(8)
    else if FourByteHeaders.contains(name) then Some(4)
    else None

  /** Never fails. Every name and every byte array has a rendering, which is why the return type is
    * `HeaderRendering` and not `Either`.
    *
    * An absent value renders as `Text("")`, matching a Kafka header whose value is null: the header was
    * present, and that is what the row should say.
    */
  def render(name: String, value: Option[Array[Byte]]): HeaderRendering =
    value match {
      case None => HeaderRendering.Text("")
      case Some(bytes) => renderBytes(name, bytes)
    }

  private def renderBytes(name: String, bytes: Array[Byte]): HeaderRendering =
    widthOf(name) match {
      case Some(width) if bytes.length == width =>
        val buffer = ByteBuffer.wrap(bytes)
        val number = if width == 4 then buffer.getInt.toLong else buffer.getLong
        HeaderRendering.Number(number, HexSerde.toHex(bytes))
      // A numeric header of the wrong length falls through: rendering the wrong number under a name the
      // user recognises is the failure mode this rule exists to prevent.
      case _ => general(bytes)
    }

  /** The rule for every header the table does not name: text when it is legible text, hex otherwise.
    *
    * "Legible" is [[Payloads.looksLikeText]] — valid UTF-8 with no control characters beyond tab, newline and
    * carriage return — which is the same test the `String` serde's auto-detection uses, so a header value and
    * a payload of the same bytes are never described differently.
    */
  private def general(bytes: Array[Byte]): HeaderRendering =
    if Payloads.looksLikeText(bytes) then
      HeaderRendering.Text(Payloads.asUtf8(bytes).getOrElse(HexSerde.toHex(bytes)))
    else HeaderRendering.Binary(HexSerde.toHex(bytes))
}
