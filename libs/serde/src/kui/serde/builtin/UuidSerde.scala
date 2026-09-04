package kui.serde.builtin

import java.nio.ByteBuffer
import java.util.UUID

import scala.util.control.NonFatal

import cats.Applicative

import kui.serde.*

/** The sixteen-byte binary form of a UUID, as `UUIDSerializer` writes it: the most significant eight bytes
  * then the least significant eight, both big-endian.
  *
  * **Sixteen bytes are not automatically a UUID.** Any sixteen bytes can be rendered as
  * `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`, so a serde that accepted all of them would turn every sixteen-byte
  * payload in every topic into an authoritative-looking identifier. RFC 4122 puts a version in the top nibble
  * of byte 6 and a variant in the top bits of byte 8, and checking them is what makes the difference between
  * "this is a UUID" and "this is sixteen bytes". The all-zero nil UUID is admitted explicitly, because it is
  * a legal UUID that fails both checks.
  */
object UuidSerde {

  private val Summary: String =
    "Reads sixteen bytes as a UUID in the binary form Kafka's UUIDSerializer writes, and renders it in " +
      "the canonical dashed form. Sixteen bytes whose RFC 4122 version and variant are not valid are " +
      "refused, because any sixteen bytes can be printed as a UUID and most of them are not one."

  def apply[F[_]: Applicative]: Serde[F] = new Impl[F]

  /** Whether these sixteen bytes carry a version and variant RFC 4122 allows. */
  private def isPlausible(bytes: Array[Byte]): Boolean = {
    val nil = bytes.forall(_ == 0)
    val version = (bytes(6) & 0xf0) >>> 4
    val variant = (bytes(8) & 0xc0) >>> 6
    nil || (version >= 1 && version <= 8 && variant == 2)
  }

  private def read(bytes: Array[Byte]): UUID = {
    val buffer = ByteBuffer.wrap(bytes)
    val high = buffer.getLong
    val low = buffer.getLong
    new UUID(high, low)
  }

  final private class Impl[F[_]: Applicative] extends SimpleSerde[F] {

    val name: SerdeName = SerdeName.Uuid
    val summary: String = Summary

    def decode(headers: List[RawHeader], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
      if bytes.length != 16 then failure(s"expected exactly 16 bytes, got ${bytes.length}")
      else if !isPlausible(bytes) then
        failure("these 16 bytes carry no RFC 4122 version and variant, so they are not a UUID")
      else Right(DeserializeResult.text(read(bytes).toString))

    def encode(input: String, headers: List[RawHeader]): Either[SerializeFailure, Array[Byte]] =
      try {
        val uuid = UUID.fromString(input.trim)
        Right(
          ByteBuffer
            .allocate(16)
            .putLong(uuid.getMostSignificantBits)
            .putLong(uuid.getLeastSignificantBits)
            .array()
        )
      } catch {
        case NonFatal(_) => encodeFailure(s"'$input' is not a UUID in the canonical dashed form")
      }

    def claims(sample: Array[Byte]): Boolean = sample.length == 16 && isPlausible(sample)
  }
}
