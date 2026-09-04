package kui.serde.headers

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.testkit.KuiSuite

/** The Spring dead-letter header table, and the promise that nothing here can fail. */
final class HeaderDecodingSuite extends KuiSuite {

  private def longBytes(value: Long): Array[Byte] = ByteBuffer.allocate(8).putLong(value).array()
  private def intBytes(value: Int): Array[Byte] = ByteBuffer.allocate(4).putInt(value).array()

  test("the table: every eight-byte header reads as a decimal number and keeps its hex") {
    assertEquals(
      HeaderDecoding.render("kafka_original-offset", Some(longBytes(41892L))),
      HeaderRendering.Number(41892L, "000000000000a3a4")
    )
    List(
      "kafka_original-timestamp",
      "kafka_dlt-original-offset",
      "kafka_dlt-original-timestamp",
      "retry_topic-original-timestamp"
    ).foreach { name =>
      assertEquals(
        HeaderDecoding.render(name, Some(longBytes(1L))),
        HeaderRendering.Number(1L, "0000000000000001"),
        clue = name
      )
    }
  }

  test("the table: every four-byte header reads as a decimal number") {
    List(
      "kafka_original-partition",
      "kafka_dlt-original-partition",
      "kafka_delivery-attempt",
      "retry_topic-attempts"
    ).foreach { name =>
      assertEquals(
        HeaderDecoding.render(name, Some(intBytes(3))),
        HeaderRendering.Number(3L, "00000003"),
        clue = name
      )
    }
  }

  test("a numeric header of the wrong length falls through to hex rather than to a wrong number") {
    // Producers do get this wrong. Rendering half of a long under a name the user recognises would be
    // confidently incorrect, which is the one outcome worse than being unreadable.
    assertEquals(
      HeaderDecoding.render("kafka_original-offset", Some(Array[Byte](1, 2))),
      HeaderRendering.Binary("0102")
    )
  }

  property("every numeric header falls through at every length but its own") {
    val widths = HeaderDecoding.NumericHeaders.toList.map(name => (name, HeaderDecoding.widthOf(name).get))
    forAll(Gen.oneOf(widths), Gen.choose(0, 24)) { case ((name, width), length) =>
      val rendering = HeaderDecoding.render(name, Some(Array.fill(length)(0xff.toByte)))
      val isNumber = rendering match {
        case _: HeaderRendering.Number => true
        case _                         => false
      }
      isNumber == (length == width)
    }
  }

  test("an unknown header with legible text is text") {
    assertEquals(
      HeaderDecoding.render("trace-id", Some("abc".getBytes(StandardCharsets.UTF_8))),
      HeaderRendering.Text("abc")
    )
  }

  test("an unknown header with control characters is hex") {
    assertEquals(
      HeaderDecoding.render("weird", Some(Array[Byte](0, 1, 2))),
      HeaderRendering.Binary("000102")
    )
  }

  test("an absent value is the empty string: the header was there, and the row should say so") {
    assertEquals(HeaderDecoding.render("kafka_original-offset", None), HeaderRendering.Text(""))
    assertEquals(HeaderDecoding.render("anything", None), HeaderRendering.Text(""))
  }

  test("an empty value is the empty string too, not hex of nothing") {
    assertEquals(HeaderDecoding.render("anything", Some(Array.emptyByteArray)), HeaderRendering.Text(""))
  }

  test("the table has exactly the nine headers Spring Kafka writes") {
    assertEquals(HeaderDecoding.NumericHeaders.size, 9)
    assert(HeaderDecoding.NumericHeaders.forall(n => n.startsWith("kafka_") || n.startsWith("retry_topic-")))
  }

  property("rendering never throws, for any name and any bytes") {
    forAll(Arbitrary.arbitrary[String], Arbitrary.arbitrary[Option[Array[Byte]]]) { (name, value) =>
      HeaderDecoding.render(name, value).display != null
    }
  }

  property("rendering is deterministic") {
    forAll(Arbitrary.arbitrary[String], Arbitrary.arbitrary[Option[Array[Byte]]]) { (name, value) =>
      HeaderDecoding.render(name, value) == HeaderDecoding.render(name, value)
    }
  }

  property("the hex form is always an even number of lowercase hex characters") {
    forAll { (bytes: Array[Byte]) =>
      HeaderDecoding.render("unknown-name", Some(bytes)) match {
        case HeaderRendering.Binary(hex) =>
          hex.length == bytes.length * 2 && hex.forall("0123456789abcdef".contains(_))
        case _ => true
      }
    }
  }
}
