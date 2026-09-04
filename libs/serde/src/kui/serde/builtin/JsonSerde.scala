package kui.serde.builtin

import java.nio.charset.StandardCharsets

import cats.Applicative
import io.circe.parser

import kui.serde.*

/** JSON, and only JSON that is an object or an array.
  *
  * A bare `123` and a bare `"hello"` are both valid JSON documents, and neither is what a user means when
  * they say a topic is a JSON topic. Accepting them would make this serde claim every number payload and
  * every quoted string, and — more damagingly — mark them `PayloadKind.Json`, which is what the table view
  * uses to decide it can flatten a record into columns. A table view offered a column layout for a payload of
  * `123` is a table view with one nameless column.
  *
  * The text KUI sends is **compact**, not pretty-printed. Pretty-printing on the server multiplies the bytes
  * on the wire by roughly three for no gain: the browser re-formats for display anyway (MSG-036), and it is
  * the browser that knows how wide the window is.
  */
object JsonSerde {

  private val Summary: String =
    "Parses the payload as JSON and marks it as JSON, which is what lets the table view flatten records " +
      "into columns. Only objects and arrays are accepted: a bare number or a bare quoted string is valid " +
      "JSON but is not a JSON document in the sense a topic means. Sent compact; the browser formats it."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  /** Whether these bytes parse as a JSON object or array. Shared with `claims` so the two cannot disagree. */
  private def parseDocument(bytes: Array[Byte]): Either[String, io.circe.Json] =
    Payloads.asUtf8(bytes) match {
      case None => Left("the payload is not valid UTF-8, so it cannot be JSON")
      case Some(text) =>
        parser.parse(text) match {
          case Left(failure) => Left(failure.message)
          case Right(json) =>
            if json.isObject || json.isArray then Right(json)
            else Left("the payload is valid JSON but is neither an object nor an array")
        }
    }

  final private class Impl[F[_]: Applicative] extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.Json
    val summary: String = Summary

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      parseDocument(bytes) match {
        case Left(reason) => failure(reason)
        case Right(json) => Right(DeserializeResult.json(json.noSpaces))
      }

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      // Encoding is held to the same rule as decoding on purpose. A produce that accepted a bare `123`
      // would put a record in the topic that this serde then refuses to read back, and the user would see
      // their own message rendered through the fallback a second after sending it.
      parseDocument(input.getBytes(StandardCharsets.UTF_8)) match {
        case Left(reason) => encodeFailure(reason)
        case Right(json) => Right(json.noSpaces.getBytes(StandardCharsets.UTF_8))
      }

    def claims(sample: Array[Byte]): Boolean = parseDocument(sample).isRight
  }
}
