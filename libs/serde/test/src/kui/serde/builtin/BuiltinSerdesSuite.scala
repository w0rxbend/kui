package kui.serde.builtin

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.TopicName
import kui.serde.*
import kui.testkit.KuiSuite

/** The eight built-in formats, and the rule that binds them: a serde says `Left` rather than rendering
  * something that is nearly right.
  */
final class BuiltinSerdesSuite extends KuiSuite {

  private val topic: TopicName = TopicName.unsafe("orders")

  private def decode(serde: Serde[IO], bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
    serde.deserializer(topic, Target.Value).flatMap(_.deserialize(Nil, bytes)).unsafeRunSync()

  private def encode(serde: Serde[IO], text: String): Either[SerializeFailure, Array[Byte]] =
    serde.serializer(topic, Target.Value, Map.empty).flatMap(_.serialize(text, Nil)).unsafeRunSync()

  private def roundTrip(serde: Serde[IO], text: String): Either[String, String] =
    encode(serde, text).left
      .map(_.cause)
      .flatMap(bytes => decode(serde, bytes).left.map(_.cause).map(_.text))

  private def int32Bytes(value: Int): Array[Byte] = ByteBuffer.allocate(4).putInt(value).array()
  private def int64Bytes(value: Long): Array[Byte] = ByteBuffer.allocate(8).putLong(value).array()

  // ---------------------------------------------------------------- round trips

  private val cases: List[(Serde[IO], Gen[String])] = List(
    NumberSerdes.int32[IO] -> Arbitrary.arbitrary[Int].map(_.toString),
    NumberSerdes.int64[IO] -> Arbitrary.arbitrary[Long].map(_.toString),
    NumberSerdes.uint32[IO] -> Arbitrary.arbitrary[Int].map(java.lang.Integer.toUnsignedString),
    NumberSerdes.uint64[IO] -> Arbitrary.arbitrary[Long].map(java.lang.Long.toUnsignedString),
    UuidSerde[IO] -> Gen.uuid.map(_.toString),
    JsonSerde[IO] -> Gen.const("""{"a":1,"b":["x"]}"""),
    StringSerde[IO] -> Arbitrary.arbitrary[String],
    Base64Serde[IO] -> Gen.listOf(Arbitrary.arbitrary[Byte]).map(bs =>
      java.util.Base64.getEncoder.encodeToString(bs.toArray)
    ),
    HexSerde[IO] -> Gen.listOf(Arbitrary.arbitrary[Byte]).map(bs => HexSerde.toHex(bs.toArray))
  )

  cases.foreach { (serde, valid) =>
    property(s"${serde.name.value} round-trips every value it accepts") {
      forAll(valid) { text => roundTrip(serde, text) == Right(text) }
    }
  }

  // ---------------------------------------------------------------- the numbers

  private val numbers: List[(Serde[IO], Int)] = List(
    NumberSerdes.int32[IO] -> 4,
    NumberSerdes.uint32[IO] -> 4,
    NumberSerdes.int64[IO] -> 8,
    NumberSerdes.uint64[IO] -> 8
  )

  numbers.foreach { (serde, width) =>
    property(s"${serde.name.value} refuses any length but $width rather than truncating") {
      forAll(Gen.choose(0, 32).suchThat(_ != width)) { length =>
        decode(serde, Array.fill(length)(0.toByte)).isLeft
      }
    }
  }

  test("a four-byte serde handed eight bytes fails rather than reading half a number") {
    // The failure is the feature. Reading the first four bytes would render a plausible value that is
    // simply wrong, with nothing on screen to say so.
    assertEquals(decode(NumberSerdes.int32[IO], Array.fill(8)(0.toByte)).isLeft, true)
  }

  test("uint64 renders values above Long.MaxValue as themselves, not as negative numbers") {
    val aboveSignedMax = int64Bytes(-1L) // 0xFFFF… — the largest unsigned 64-bit value
    assertEquals(decode(NumberSerdes.uint64[IO], aboveSignedMax).map(_.text), Right("18446744073709551615"))
    assertEquals(decode(NumberSerdes.int64[IO], aboveSignedMax).map(_.text), Right("-1"))
  }

  test("uint32 does the same at 32 bits") {
    assertEquals(decode(NumberSerdes.uint32[IO], int32Bytes(-1)).map(_.text), Right("4294967295"))
  }

  test("a number that does not fit its width is a serialize failure naming the width") {
    val failed = encode(NumberSerdes.int32[IO], "99999999999")
    assert(failed.swap.exists(_.cause.contains("32-bit")), failed.toString)
  }

  // ---------------------------------------------------------------- JSON

  test("the JSON serde marks its result as JSON and sends it compact") {
    val result = decode(JsonSerde[IO], """{ "a" :  1 }""".getBytes(StandardCharsets.UTF_8))
    assertEquals(result.map(_.kind), Right(PayloadKind.Json))
    // Compact, not pretty: pretty-printing on the server triples the bytes on the wire, and the browser
    // re-formats for display anyway because only the browser knows how wide the window is.
    assertEquals(result.map(_.text), Right("""{"a":1}"""))
  }

  test("the JSON serde refuses a bare number and a bare string, which are valid JSON but not documents") {
    assert(decode(JsonSerde[IO], "123".getBytes).isLeft)
    assert(decode(JsonSerde[IO], "\"hello\"".getBytes).isLeft)
    assert(decode(JsonSerde[IO], "not json at all".getBytes).isLeft)
  }

  test("the JSON serde accepts an array as well as an object") {
    assertEquals(decode(JsonSerde[IO], "[1,2]".getBytes).map(_.kind), Right(PayloadKind.Json))
  }

  test("producing a bare number to a JSON topic is refused, so KUI can always read back what it sent") {
    assert(encode(JsonSerde[IO], "123").isLeft)
  }

  // ---------------------------------------------------------------- UUID

  test("sixteen bytes with no RFC 4122 version and variant are refused") {
    val notAUuid = Array.fill(16)(0x7f.toByte)
    assert(decode(UuidSerde[IO], notAUuid).isLeft, "0x7f repeated has version 7 but variant 01, not 10")
  }

  test("the nil UUID is admitted explicitly: it is legal and fails both structural checks") {
    assertEquals(
      decode(UuidSerde[IO], Array.fill(16)(0.toByte)).map(_.text),
      Right("00000000-0000-0000-0000-000000000000")
    )
  }

  property("every random UUID decodes to its own canonical form") {
    forAll(Gen.uuid) { (uuid: UUID) =>
      val bytes = ByteBuffer
        .allocate(16)
        .putLong(uuid.getMostSignificantBits)
        .putLong(uuid.getLeastSignificantBits)
        .array()
      decode(UuidSerde[IO], bytes).map(_.text) == Right(uuid.toString)
    }
  }

  // ---------------------------------------------------------------- hex and base64

  property("hex is lowercase, unseparated and exactly two characters per byte") {
    forAll { (bytes: Array[Byte]) =>
      val text = decode(HexSerde[IO], bytes).map(_.text)
      text.exists(t => t.length == bytes.length * 2 && t == t.toLowerCase && t.forall("0123456789abcdef".contains(_)))
    }
  }

  property("hex parses either case back to the same bytes") {
    forAll { (bytes: Array[Byte]) =>
      val hex = HexSerde.toHex(bytes)
      encode(HexSerde[IO], hex.toUpperCase).map(_.toSeq) == Right(bytes.toSeq)
    }
  }

  test("hex refuses an odd number of characters and a non-hex character") {
    assert(encode(HexSerde[IO], "abc").isLeft)
    assert(encode(HexSerde[IO], "zz").isLeft)
  }

  property("base64 is standard and padded, and parses back to the same bytes") {
    forAll { (bytes: Array[Byte]) =>
      val text = decode(Base64Serde[IO], bytes).map(_.text)
      text.exists(t => t == java.util.Base64.getEncoder.encodeToString(bytes)) &&
      text.flatMap(t => encode(Base64Serde[IO], t).left.map(_.cause)).map(_.toSeq) == Right(bytes.toSeq)
    }
  }

  // ---------------------------------------------------------------- the String serde

  test("the String serde refuses bytes that are not valid UTF-8, which is what the fallback is for") {
    assert(decode(StringSerde[IO], Array[Byte](0xc3.toByte, 0x28.toByte)).isLeft)
  }

  // ---------------------------------------------------------------- everything, together

  property("no serde throws on any input: every decode returns a decision") {
    forAll(Gen.oneOf(BuiltinSerdes.all[IO]), Arbitrary.arbitrary[Array[Byte]]) { (serde, bytes) =>
      serde
        .deserializer(topic, Target.Value)
        .flatMap(_.deserialize(Nil, bytes))
        .attempt
        .unsafeRunSync()
        .isRight
    }
  }

  test("the empty payload is handled by every serde: a decision, never a crash") {
    BuiltinSerdes.all[IO].foreach { serde =>
      val outcome = serde
        .deserializer(topic, Target.Value)
        .flatMap(_.deserialize(Nil, Array.emptyByteArray))
        .attempt
        .unsafeRunSync()
      assert(outcome.isRight, clue = serde.name.value)
    }
  }

  test("every built-in describes itself, and the list of names matches the list of serdes") {
    assertEquals(BuiltinSerdes.all[IO].map(_.name), BuiltinSerdes.names)
    BuiltinSerdes.all[IO].foreach { serde =>
      assertEquals(serde.describe.name, serde.name)
      assert(serde.describe.description.length > 40, clue = serde.name.value)
    }
  }

  test("the fallback is not one of the built-ins: it is where resolution ends, not a candidate in it") {
    assert(!BuiltinSerdes.names.contains(SerdeName.Fallback))
  }
}
