package kui.serde

import java.nio.ByteBuffer
import java.nio.charset.{CharsetDecoder, CodingErrorAction, StandardCharsets}

import cats.Applicative

import kui.kernel.TopicName

/** The serde that always succeeds, and the reason a browse can never fail on a decode.
  *
  * It interprets the bytes as UTF-8, replacing anything that is not valid UTF-8 with the Unicode replacement
  * character. A Protobuf payload therefore renders as mojibake rather than as an empty cell — which is the
  * point. An empty cell tells a user nothing; mojibake plus the "Fallback serde was used" marker tells them
  * their serde is wrong for this topic, which is the one thing they need to know.
  *
  * `Fallback` is not one serde among many. It is the terminal case of resolution: it is never a candidate for
  * auto-detection (`preferable` is always false and it is not a [[SampleDetector]]), it is never returned by
  * `suggest`, and everything else in `libs/serde` is optional in a way that this is not. Every other serde
  * may return `Left`; `Deserializers.withFallback`, which ends here, may not.
  */
object FallbackSerde {

  private val Summary: String =
    "Renders the raw bytes as UTF-8 text, replacing anything that is not valid UTF-8 with '�'. " +
      "Never fails, and is what KUI shows when the serde that was supposed to read a record could not. " +
      "Seeing it on a row means the configured or detected serde is wrong for that topic."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  /** UTF-8 with replacement, done explicitly.
    *
    * `new String(bytes, UTF_8)` already replaces malformed input, but it does so as an undocumented side
    * effect of the default `CodingErrorAction`. Spelling it out means a future reader can see that the "never
    * fails" promise is a property of this decoder's configuration and not a hope about the JDK.
    *
    * A `CharsetDecoder` is stateful and not thread-safe, so a fresh one is made per call rather than shared.
    */
  private def decodeUtf8(bytes: Array[Byte]): String = {
    val decoder: CharsetDecoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)
    decoder.decode(ByteBuffer.wrap(bytes)).toString
  }

  final private class Impl[F[_]](using F: Applicative[F]) extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.Fallback

    val summary: String = Summary

    /** Always `Text`, even for bytes that happen to be JSON.
      *
      * Detecting JSON is the `Json` serde's job. A fallback that guessed would make "the fallback was used"
      * invisible in the table view, which is precisely the state the marker exists to make visible.
      */
    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      Right(DeserializeResult.text(decodeUtf8(bytes)))

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      Right(input.getBytes(StandardCharsets.UTF_8))

    /** Never offered as a choice: it is where resolution ends, not somewhere a user picks. */
    override def canSerialize(topic: TopicName, target: Target): F[Boolean] = F.pure(false)

    def claims(sample: Array[Byte]): Boolean = false
  }
}
