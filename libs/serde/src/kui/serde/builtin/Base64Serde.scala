package kui.serde.builtin

import java.util.Base64

import scala.util.control.NonFatal

import cats.Applicative

import kui.serde.*

/** The payload rendered as standard Base64 with padding (RFC 4648 §4), and text parsed back the same way.
  *
  * Always succeeds on the way in — every byte sequence has a Base64 rendering — which is exactly why it is
  * **never auto-detected**. A serde that can read anything wins every detection contest and tells the user
  * nothing. It is an explicit choice: the one a person makes when they want the exact bytes in a form they
  * can paste into another tool.
  */
object Base64Serde {

  private val Summary: String =
    "Renders the payload as standard Base64 with padding, and reads Base64 text back to bytes. Never " +
      "auto-detected: it can render any payload, so detecting it would mean detecting it always. Pick it " +
      "when you want the exact bytes in a form you can paste elsewhere."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  final private class Impl[F[_]: Applicative] extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.Base64
    val summary: String = Summary

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      Right(DeserializeResult.text(Base64.getEncoder.encodeToString(bytes)))

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      try Right(Base64.getDecoder.decode(input.trim))
      catch {
        case NonFatal(_) => encodeFailure(s"'$input' is not standard Base64 with padding")
      }

    def claims(sample: Array[Byte]): Boolean = false
  }
}
