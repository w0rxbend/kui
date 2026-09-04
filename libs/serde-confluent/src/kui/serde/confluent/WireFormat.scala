package kui.serde.confluent

import java.nio.ByteBuffer

/** The five bytes Confluent puts in front of every payload it writes, and how to read them back.
  *
  * A record produced by a Schema-Registry-aware producer is `0x00`, then the schema's id as four bytes
  * big-endian, then the encoded body. That is the entire framing: the schema itself is not in the record, so
  * a reader that does not know the id cannot decode the body at all, and a reader that does needs one
  * registry lookup per distinct id rather than one per record.
  *
  * It lives in its own object, away from any serde, because three separate things need it and none of them
  * should re-derive it: the deserializer (to find the id), the serializer (to write the id) and
  * auto-detection (to decide, from the bytes alone, whether this is even a registry payload). A magic byte
  * checked in three places is a magic byte that is eventually checked differently in one of them.
  */
object WireFormat {

  /** Confluent's version marker. It has been zero since the format was published and a payload starting with
    * anything else is not this format, whatever else it may be.
    */
  val MagicByte: Byte = 0

  /** Magic byte plus a four-byte id. */
  val HeaderSize: Int = 5

  /** Whether these bytes are, on their face, a Confluent payload.
    *
    * A cheap look and nothing more: a JSON document could in principle begin with a zero byte, and this would
    * claim it. That is acceptable precisely because the caller is `SampleDetector.claims`, whose result
    * auto-detection then verifies with a real decode (`SerdeAutodetect`). A test that is cheap and
    * occasionally over-eager, followed by a decode that is authoritative, is the shape the picker wants.
    *
    * `HeaderSize + 1` rather than `HeaderSize`: a record consisting of nothing but a header has no body, and
    * offering to decode it would only produce a failure one step later.
    */
  def looksLikeRegistryPayload(bytes: Array[Byte]): Boolean =
    bytes.length > HeaderSize && bytes(0) == MagicByte

  /** Splits a framed payload into its schema id and its body.
    *
    * `Left` carries display text, not an exception, because every caller of this is on the record-decoding
    * path where a failure belongs on the record rather than on the stream (ADR-035).
    */
  def read(bytes: Array[Byte]): Either[String, Framed] =
    if bytes.length < HeaderSize then
      Left(
        s"the payload is ${bytes.length} bytes, and a Schema Registry payload is at least $HeaderSize: " +
          "one magic byte, four bytes of schema id, then the encoded value. This record was almost " +
          "certainly not written by a Schema-Registry-aware producer"
      )
    else if bytes(0) != MagicByte then
      Left(
        f"the payload starts with 0x${bytes(0) & 0xff}%02x rather than 0x00, so it does not carry a Schema " +
          "Registry header. Choose a serde that matches how the record was actually written - String, " +
          "Json, or one of the number formats"
      )
    else {
      val buffer = ByteBuffer.wrap(bytes)
      val _ = buffer.get()
      val id = buffer.getInt()
      val body = new Array[Byte](bytes.length - HeaderSize)
      val _ = buffer.get(body)
      // A negative id cannot come from a registry, which allocates ids from one upwards. It can come from
      // four bytes of something else that happened to follow a zero byte, and saying so here is better than
      // asking the registry about schema -1091581184 and reporting its 404.
      if id <= 0 then
        Left(
          s"the four bytes after the magic byte read as schema id $id, and a Schema Registry never issues " +
            "an id below 1. These bytes are not a Schema Registry payload"
        )
      else Right(Framed(id, body))
    }

  /** Puts the header back on. The inverse of [[read]], used by the serializer. */
  def frame(schemaId: Int, body: Array[Byte]): Array[Byte] = {
    val out = ByteBuffer.allocate(HeaderSize + body.length)
    val _ = out.put(MagicByte)
    val _ = out.putInt(schemaId)
    val _ = out.put(body)
    out.array()
  }
}

/** One payload, unframed: which schema wrote it, and the bytes it wrote. */
final case class Framed(schemaId: Int, body: Array[Byte])
