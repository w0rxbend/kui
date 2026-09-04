package kui.serde.builtin

import cats.Applicative

import kui.serde.*

/** The payload as lowercase hexadecimal, two characters per byte, no separators.
  *
  * Lowercase and unseparated is a choice, not an accident: it is what `xxd -p` and every debugger print, so a
  * value copied out of KUI can be pasted straight into one. Parsing accepts either case, because a person
  * typing a hex payload into the produce form should not have to care.
  *
  * Like Base64, it renders any payload and so is never auto-detected.
  */
object HexSerde {

  private val Digits: Array[Char] = "0123456789abcdef".toCharArray

  private val Summary: String =
    "Renders the payload as lowercase hexadecimal, two characters per byte and no separators, the form " +
      "xxd and most debuggers print. Reads hex text of either case back to bytes. Never auto-detected: it " +
      "can render any payload."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  /** The rendering, exposed because `HeaderDecoding` renders unprintable header values the same way and the
    * two must agree character for character.
    */
  def toHex(bytes: Array[Byte]): String = {
    val out = new StringBuilder(bytes.length * 2)
    bytes.foreach { b =>
      val _ = out.append(Digits((b >> 4) & 0x0f)).append(Digits(b & 0x0f))
    }
    out.toString
  }

  private def fromHex(text: String): Either[String, Array[Byte]] = {
    val trimmed = text.trim
    if trimmed.length % 2 != 0 then Left("hex text must have an even number of characters")
    else if !trimmed.forall(c => Character.digit(c, 16) >= 0) then
      Left("hex text may contain only the characters 0-9, a-f and A-F")
    else
      Right(
        trimmed
          .grouped(2)
          .map(pair => Integer.parseInt(pair, 16).toByte)
          .toArray
      )
  }

  final private class Impl[F[_]: Applicative] extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.Hex
    val summary: String = Summary

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      Right(DeserializeResult.text(toHex(bytes)))

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      fromHex(input).left.map(reason => SerializeFailure(name, reason))

    def claims(sample: Array[Byte]): Boolean = false
  }
}
