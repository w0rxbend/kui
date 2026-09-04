package kui.contracts.message

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** How the bytes of a record's key or value were read, and what came out.
  *
  * A record on a Kafka topic is two byte arrays and nothing else. Turning those bytes into something a person
  * can read is a guess — an informed one, made by a *serde* — and this record is the whole result of that
  * guess, including which guess was made. That is the difference between KUI and a viewer that prints text: a
  * user who sees `"serde": "String"` under an unreadable value knows to change the serde, where a user who
  * sees mojibake concludes the data is corrupt.
  *
  * ==Why it lives here and not in the message service's contract==
  *
  * `libs/contracts-core` is the home of a wire fragment with more than one producer (the rule that put
  * `TopicRowDto` here). A decoded payload is produced by the message service's browse, page and track
  * responses, and it is re-served — not re-derived — by the gateway wherever a record appears inside an
  * aggregate. A second declaration beside the second producer is the exact shape of the M1 dashboard defect:
  * two records that agree today, one field added to one of them tomorrow.
  *
  * @param text
  *   the decoded payload, ready to display. Never `null`: a record with no key at all is `kind = "null"` with
  *   an empty `text`, because a field that is sometimes absent teaches every client to write two code paths
  *   for one concept
  * @param kind
  *   what `text` is, so that a client knows whether to pretty-print it, syntax-highlight it or show it as a
  *   hex dump. One of `json`, `string`, `binary` or `null`. A string rather than an enum because it is a
  *   *hint* for rendering: a client that meets a `kind` it does not know shows plain text, which is always a
  *   safe reading, where an enum would make an added kind a decode failure for every old client
  * @param serde
  *   the name of the serde that produced `text` — `String`, `Int64`, `Avro`, or whatever the cluster
  *   configured. It is on every payload, not only on surprising ones, because "which serde was this?" is the
  *   first question asked about a value that looks wrong
  * @param properties
  *   whatever else that particular serde learned and a person might want: a schema id and subject for a
  *   registry-backed serde, a detected charset for a text one. Free-form because the set differs per serde
  *   and pushing every serde's fields onto this record would give every payload a dozen permanently-null ones
  */
final case class DecodedPayloadDto(
    text: String,
    kind: String,
    serde: String,
    properties: Map[String, String]
)

object DecodedPayloadDto {

  /** The values of `kind` that KUI itself produces. A client may meet others; see the field's doc. */
  object Kind {
    val Json: String = "json"
    val Text: String = "string"
    val Binary: String = "binary"
    val Absent: String = "null"

    val all: List[String] = List(Json, Text, Binary, Absent)
  }

  /** A record with no key (or no value — a tombstone), named once so that every producer of an absent payload
    * produces the identical document rather than each choosing its own empty.
    */
  def absent(serde: String): DecodedPayloadDto =
    DecodedPayloadDto(text = "", kind = Kind.Absent, serde = serde, properties = Map.empty)

  given Codec[DecodedPayloadDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        // `text` and `kind` are required. Defaulting a missing `text` to `""` would render a document that
        // never arrived as an empty payload, which is M1's second integration defect exactly: a browser
        // showing "nothing here" while the error it should have raised went nowhere. `properties` defaults
        // because a serde with nothing to add legitimately sends `{}` and older producers omitted it.
        text <- cursor.get[String]("text")
        kind <- cursor.get[String]("kind")
        serde <- cursor.get[String]("serde")
        properties <- cursor.getOrElse[Map[String, String]]("properties")(Map.empty)
      } yield DecodedPayloadDto(text, kind, serde, properties),
    (dto: DecodedPayloadDto) =>
      Json.obj(
        "text" -> dto.text.asJson,
        "kind" -> dto.kind.asJson,
        "serde" -> dto.serde.asJson,
        "properties" -> dto.properties.asJson
      )
  )

  given Schema[DecodedPayloadDto] = Schema
    .derived[DecodedPayloadDto]
    .description("One decoded key or value: the text, what it is, and which serde produced it")

  given CanEqual[DecodedPayloadDto, DecodedPayloadDto] = CanEqual.derived
}

/** A serde that was asked for a payload and could not produce one.
  *
  * This type is the reason a topic of undecodable bytes shows its bytes instead of an error page. A failed
  * decode is reported *beside* the record, on the record, and the record is still delivered — decoded by the
  * fallback serde, which never fails. A stream that aborted on the first unreadable value would make one bad
  * record hide every good one after it, and the operator would have no way to see which record was bad.
  *
  * @param target
  *   `key` or `value`: which half of the record failed
  * @param serde
  *   the serde that was tried and failed — not the fallback that succeeded, because the question this field
  *   answers is "what did I configure wrongly?"
  * @param cause
  *   the failure in one line, already stripped of anything sensitive by the producer. It is a message and not
  *   a code because there is no useful enumeration of the ways a byte array can fail to be an Avro record
  */
final case class DecodeErrorDto(target: String, serde: String, cause: String)

object DecodeErrorDto {

  /** The two halves of a record, so that `target` is spelled the same by every producer. */
  object Target {
    val Key: String = "key"
    val Value: String = "value"
  }

  given Codec[DecodeErrorDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        target <- cursor.get[String]("target")
        serde <- cursor.get[String]("serde")
        cause <- cursor.get[String]("cause")
      } yield DecodeErrorDto(target, serde, cause),
    (dto: DecodeErrorDto) =>
      Json.obj(
        "target" -> dto.target.asJson,
        "serde" -> dto.serde.asJson,
        "cause" -> dto.cause.asJson
      )
  )

  given Schema[DecodeErrorDto] = Schema
    .derived[DecodeErrorDto]
    .description("A serde that failed on this record; the record was delivered by the fallback serde anyway")

  given CanEqual[DecodeErrorDto, DecodeErrorDto] = CanEqual.derived
}

/** One header of a record being **written**.
  *
  * Reading and writing headers need different shapes, and this is the write one. Kafka's own header list
  * permits the same name several times and imposes no ordering rule, so a `Map` cannot express what a
  * producer is allowed to send: `{"trace": "a", "trace": "b"}` is not a JSON object anyone can rely on. A
  * list of name/value pairs can, and it keeps the order the caller chose.
  *
  * A record on the way *out* carries `Map[String, String]` instead, because a browser rendering a table of
  * headers wants them keyed, duplicates are vanishingly rare in practice, and the field would otherwise force
  * every consumer to fold a list into a map before it could show anything.
  *
  * @param value
  *   `None` is a header present with a null value, which Kafka distinguishes from a header that is absent and
  *   which some frameworks (Spring's DLT headers among them) rely on
  */
final case class HeaderDto(name: String, value: Option[String])

object HeaderDto {

  given Codec[HeaderDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        value <- cursor.get[Option[String]]("value")
      } yield HeaderDto(name, value),
    (dto: HeaderDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "value" -> dto.value.asJson
      )
  )

  given Schema[HeaderDto] = Schema
    .derived[HeaderDto]
    .description("One header to write: a name, and a value that may be explicitly null")

  given CanEqual[HeaderDto, HeaderDto] = CanEqual.derived
}
