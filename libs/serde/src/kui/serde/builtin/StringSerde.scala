package kui.serde.builtin

import java.nio.charset.StandardCharsets

import cats.Applicative

import kui.serde.*

/** UTF-8 text, strictly.
  *
  * The difference between this and `FallbackSerde` is the whole reason both exist. This one **refuses** bytes
  * that are not valid UTF-8; the fallback replaces them and never refuses. If `String` also accepted
  * everything, then a Protobuf topic decoded with the default serde would render as mojibake with no marker
  * on the row, and the user would have no way to tell KUI's guess from KUI's giving up.
  */
object StringSerde {

  private val Summary: String =
    "Reads the payload as UTF-8 text and refuses bytes that are not valid UTF-8. The default for " +
      "topics carrying plain text, and the last serde tried before the fallback."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  final private class Impl[F[_]: Applicative] extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.String
    val summary: String = Summary

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      Payloads
        .asUtf8(bytes)
        .map(DeserializeResult.text)
        .toRight(DeserializeFailure(name, "the payload is not valid UTF-8"))

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      Right(input.getBytes(StandardCharsets.UTF_8))

    def claims(sample: Array[Byte]): Boolean = Payloads.looksLikeText(sample)
  }
}
