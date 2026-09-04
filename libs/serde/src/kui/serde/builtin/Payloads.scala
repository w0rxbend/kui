package kui.serde.builtin

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

/** The two questions every built-in serde asks about a payload, answered in one place.
  *
  * They are here rather than duplicated in each serde because auto-detection depends on the exact answers:
  * `Int32` claims four bytes only when they are *not* text, and `String` claims bytes only when they are, so
  * the two rules have to be each other's complement or a four-byte payload is claimed by both or by neither.
  */
private[serde] object Payloads {

  /** The bytes as text, or `None` when they are not valid UTF-8.
    *
    * Strict, unlike `FallbackSerde`'s decoder: the whole point of the `String` serde is that it *refuses*
    * bytes that are not text, so that refusing is what makes the fallback marker appear.
    */
  def asUtf8(bytes: Array[Byte]): Option[String] = {
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Some(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch { case _: CharacterCodingException => None }
  }

  /** Whether these bytes look like something a person wrote: valid UTF-8, with no control characters other
    * than tab, newline and carriage return.
    *
    * A control character is the tell. Four bytes of a serialised integer are valid UTF-8 surprisingly often —
    * every value below 128 in each byte is — and what distinguishes them from the word `list` is that an
    * integer's bytes are usually below 32.
    */
  def looksLikeText(bytes: Array[Byte]): Boolean =
    asUtf8(bytes).exists(text => text.forall(c => !c.isControl || c == '\t' || c == '\n' || c == '\r'))
}
