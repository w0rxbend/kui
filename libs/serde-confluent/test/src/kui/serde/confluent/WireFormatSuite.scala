package kui.serde.confluent

import munit.FunSuite

/** The five-byte header, in both directions and in every way it can be wrong.
  *
  * This is the one part of the module that decides whether a record is "ours" at all, and it runs against
  * every payload of every browse. Everything it can misread is here.
  */
final class WireFormatSuite extends FunSuite {

  private val body: Array[Byte] = Array[Byte](10, 20, 30)

  test("a framed payload reads back as the id and the body it was written with") {
    val framed = WireFormat.frame(42, body)
    assertEquals(framed.length, WireFormat.HeaderSize + body.length)
    WireFormat.read(framed) match {
      case Right(Framed(id, read)) =>
        assertEquals(id, 42)
        assertEquals(read.toSeq, body.toSeq)
      case Left(why) => fail(s"expected a framed payload, got: $why")
    }
  }

  test("a payload shorter than the header is refused, and the message says how short") {
    val tooShort = WireFormat.read(Array[Byte](0, 0, 0))
    assert(tooShort.isLeft)
    assert(tooShort.left.exists(_.contains("3 bytes")), tooShort)
  }

  test("a payload that does not start with the magic byte names the byte it did start with") {
    val notOurs = WireFormat.read("{\"id\":1}".getBytes("UTF-8"))
    assert(notOurs.left.exists(_.contains("0x7b")), notOurs)
  }

  test("four bytes that read as a non-positive id are rejected rather than asked about") {
    // A registry issues ids from one upwards, so this can only be four bytes of something else.
    val negative = Array[Byte](0, -1, -1, -1, -1, 9)
    assert(WireFormat.read(negative).left.exists(_.contains("not a Schema Registry payload")))
  }

  test("detection needs a body, not merely a header") {
    assert(!WireFormat.looksLikeRegistryPayload(WireFormat.frame(1, Array.empty)))
    assert(WireFormat.looksLikeRegistryPayload(WireFormat.frame(1, body)))
    assert(!WireFormat.looksLikeRegistryPayload("plain text".getBytes("UTF-8")))
  }
}
