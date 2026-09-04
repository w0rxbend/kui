package kui.serde.builtin

import java.nio.ByteBuffer

import scala.util.control.NonFatal

import cats.Applicative

import kui.serde.*

/** Fixed-width big-endian integers, the way Kafka's own `IntegerSerializer` and `LongSerializer` write them.
  *
  * **A serde of `n` bytes refuses anything that is not exactly `n` bytes.** It would be easy to read the
  * first four of eight bytes and render a number, and the result would be a plausible-looking value that is
  * simply wrong — half of a long, presented with the same confidence as a correct int. Refusing puts the
  * record on screen through the fallback with a marker saying which serde failed, which is information; a
  * wrong number is worse than none.
  *
  * The unsigned pair is not decoration. A producer writing a 64-bit sequence number past `Long.MaxValue`
  * renders as a negative number under a signed reading, and an operator reading "-9223372036854775800" as a
  * sequence number has no way to guess that the value is fine and the interpretation is not.
  */
object NumberSerdes {

  def int32[F[_]: Applicative]: Serde[F] = new Impl[F](
    SerdeName.Int32,
    width = 4,
    signed = true,
    "Reads exactly four bytes as a big-endian signed 32-bit integer, the format Kafka's own " +
      "IntegerSerializer writes. A payload of any other length is refused rather than truncated."
  )

  def int64[F[_]: Applicative]: Serde[F] = new Impl[F](
    SerdeName.Int64,
    width = 8,
    signed = true,
    "Reads exactly eight bytes as a big-endian signed 64-bit integer, the format Kafka's own " +
      "LongSerializer writes. A payload of any other length is refused rather than truncated."
  )

  def uint32[F[_]: Applicative]: Serde[F] = new Impl[F](
    SerdeName.UInt32,
    width = 4,
    signed = false,
    "Reads exactly four bytes as a big-endian unsigned 32-bit integer, for counters and ids that " +
      "pass 2 147 483 647 and would otherwise render as negative numbers."
  )

  def uint64[F[_]: Applicative]: Serde[F] = new Impl[F](
    SerdeName.UInt64,
    width = 8,
    signed = false,
    "Reads exactly eight bytes as a big-endian unsigned 64-bit integer, rendered through " +
      "Long.toUnsignedString so that values above 9 223 372 036 854 775 807 read correctly."
  )

  final private class Impl[F[_]: Applicative](
      val name: SerdeName,
      width: Int,
      signed: Boolean,
      val summary: String
  ) extends SimpleSerde[F] {

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      if bytes.length != width then failure(s"expected exactly $width bytes, got ${bytes.length}")
      else {
        val buffer = ByteBuffer.wrap(bytes)
        val rendered =
          if width == 4 then {
            val value = buffer.getInt
            if signed then value.toString else java.lang.Integer.toUnsignedString(value)
          } else {
            val value = buffer.getLong
            if signed then value.toString else java.lang.Long.toUnsignedString(value)
          }
        Right(DeserializeResult.text(rendered))
      }

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      try
        if width == 4 then {
          val value = if signed then input.trim.toInt else java.lang.Integer.parseUnsignedInt(input.trim)
          Right(ByteBuffer.allocate(4).putInt(value).array())
        } else {
          val value = if signed then input.trim.toLong else java.lang.Long.parseUnsignedLong(input.trim)
          Right(ByteBuffer.allocate(8).putLong(value).array())
        }
      catch {
        case NonFatal(_) =>
          val kind = if signed then "signed" else "unsigned"
          encodeFailure(s"'$input' is not a $kind ${width * 8}-bit integer")
      }

    /** Claimed only when the bytes are the right width, are not text, and the sign bit agrees with this
      * serde's signedness.
      *
      * The sign-bit half is what stops `Int32` and `UInt32` both claiming the same payload and leaving the
      * order to decide. Bytes whose top bit is clear read identically either way, so the signed serde takes
      * them; bytes whose top bit is set are the only ones where the choice is visible, and there the unsigned
      * reading is the one that is not a surprising negative number.
      */
    def claims(sample: Array[Byte]): Boolean =
      sample.length == width &&
        !Payloads.looksLikeText(sample) &&
        (((sample(0) & 0x80) == 0) == signed)
  }
}
