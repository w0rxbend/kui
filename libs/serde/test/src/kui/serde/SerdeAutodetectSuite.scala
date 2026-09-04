package kui.serde

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.TopicName
import kui.serde.builtin.BuiltinSerdes
import kui.testkit.KuiSuite

/** The detection table of MSG-010, asserted row by row, plus the two properties that make the result usable:
  * it is stable, and it never recommends a serde that would then fail.
  */
final class SerdeAutodetectSuite extends KuiSuite {

  private val topic: TopicName = TopicName.unsafe("orders")
  private val candidates: List[Serde[IO]] = BuiltinSerdes.all[IO]

  private def rank(sample: Array[Byte]): List[SerdeName] =
    SerdeAutodetect.rank(candidates, topic, Target.Value, Some(sample)).unsafeRunSync()

  private def detected(sample: Array[Byte]): Option[SerdeName] = rank(sample).headOption

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  test("the detection table") {
    // `{"a":1}` and `[1,2]` parse as JSON documents.
    assertEquals(detected(utf8("""{"a":1}""")), Some(SerdeName.Json))
    assertEquals(detected(utf8("[1,2]")), Some(SerdeName.Json))

    // A bare number is valid JSON and is not a JSON document, so the Json serde does not claim it. Three
    // printable bytes are not a fixed-width integer either, so what is left is text.
    assertEquals(detected(utf8("123")), Some(SerdeName.String))

    // Four bytes that are not text are a signed 32-bit integer.
    assertEquals(detected(ByteBuffer.allocate(4).putInt(41892).array()), Some(SerdeName.Int32))

    // The same four bytes with the top bit set read as a surprising negative number when signed, so the
    // unsigned serde is the one that claims them.
    assertEquals(detected(ByteBuffer.allocate(4).putInt(-1).array()), Some(SerdeName.UInt32))

    // Eight bytes, likewise.
    assertEquals(detected(ByteBuffer.allocate(8).putLong(41892L).array()), Some(SerdeName.Int64))

    // Sixteen bytes that carry a valid version and variant are a UUID.
    val uuid = java.util.UUID.randomUUID()
    val uuidBytes =
      ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits).putLong(uuid.getLeastSignificantBits).array()
    assertEquals(detected(uuidBytes), Some(SerdeName.Uuid))

    // Sixteen bytes that do not are not a UUID, and are not text either.
    assertEquals(detected(Array.fill(16)(0x7f.toByte)), None)

    // Valid UTF-8 with no control characters is text.
    assertEquals(detected(utf8("a plain sentence")), Some(SerdeName.String))

    // Anything else is nobody's: the empty ranking is how the fallback becomes the answer.
    assertEquals(detected(Array[Byte](0xc3.toByte, 0x28.toByte, 0x00, 0x01, 0x02)), None)
  }

  test("Base64 and Hex are never detected, however unclaimed the payload is") {
    // They can render any payload, so a rule that ranked whatever decodes successfully would rank them
    // for everything — and being unable to fail is not the same as being right.
    val unclaimed = Array[Byte](0xc3.toByte, 0x28.toByte, 0x00, 0x01, 0x02)
    assertEquals(rank(unclaimed), Nil)
    assert(!rank(utf8("""{"a":1}""")).contains(SerdeName.Base64))
    assert(!rank(utf8("""{"a":1}""")).contains(SerdeName.Hex))
  }

  test("the fallback is never ranked, because it is never a candidate") {
    assert(!rank(utf8("anything")).contains(SerdeName.Fallback))
  }

  test("a JSON document is ranked ahead of String, because both are true and only one is useful") {
    assertEquals(rank(utf8("""{"a":1}""")), List(SerdeName.Json, SerdeName.String))
  }

  property("ranking is deterministic: the same sample gives the same order every time") {
    forAll { (bytes: Array[Byte]) => rank(bytes) == rank(bytes) }
  }

  property("every ranked serde actually decodes the sample it was ranked for") {
    forAll { (bytes: Array[Byte]) =>
      val ranked = rank(bytes).toSet
      candidates.filter(s => ranked.contains(s.name)).forall { serde =>
        serde.deserializer(topic, Target.Value).flatMap(_.deserialize(Nil, bytes)).unsafeRunSync().isRight
      }
    }
  }

  property("ranking preserves the candidate order, so the picker never reshuffles itself") {
    forAll { (bytes: Array[Byte]) =>
      val ranked = rank(bytes)
      ranked == BuiltinSerdes.names.filter(ranked.contains)
    }
  }

  test("with no sample to look at, no pure codec claims the topic") {
    // Nothing about the name `orders` says its values are integers, and a picker that guessed from a topic
    // name would be confidently wrong on the first topic whose name did not match its guess.
    assertEquals(SerdeAutodetect.rank(candidates, topic, Target.Value, None).unsafeRunSync(), Nil)
  }

  test("a serde that refuses the topic outright is never ranked, whatever the bytes say") {
    val refuses = new Serde[IO] {
      val name: SerdeName = SerdeName.unsafe("Refuses")
      val describe: SerdeDescription = SerdeDescription(name, "refuses this topic", false)
      def canDeserialize(t: TopicName, target: Target): IO[Boolean] = IO.pure(false)
      def canSerialize(t: TopicName, target: Target): IO[Boolean] = IO.pure(false)
      def preferable(t: TopicName, target: Target): IO[Boolean] = IO.pure(true)
      def schema(t: TopicName, target: Target): IO[Option[SchemaDescription]] = IO.pure(None)
      def parameters(t: TopicName, target: Target): IO[List[SerdeParameter]] = IO.pure(Nil)
      def deserializer(t: TopicName, target: Target): IO[Deserializer[IO]] = IO.pure(new Deserializer[IO] {
        val serde: SerdeName = name
        def deserialize(h: List[RawHeader], b: Array[Byte]): IO[Either[DeserializeFailure, DeserializeResult]] =
          IO.pure(Right(DeserializeResult.text("never asked")))
      })
      def serializer(t: TopicName, target: Target, p: Map[String, String]): IO[Serializer[IO]] =
        IO.raiseError(new UnsupportedOperationException)
    }
    assertEquals(
      SerdeAutodetect.rank(List(refuses), topic, Target.Value, Some(utf8("x"))).unsafeRunSync(),
      Nil
    )
  }

  test("a serde that claims a payload it cannot decode is dropped rather than recommended") {
    val liar = new Serde[IO] with SampleDetector {
      val name: SerdeName = SerdeName.unsafe("Liar")
      val describe: SerdeDescription = SerdeDescription(name, "claims everything, decodes nothing", false)
      def canDeserialize(t: TopicName, target: Target): IO[Boolean] = IO.pure(true)
      def canSerialize(t: TopicName, target: Target): IO[Boolean] = IO.pure(false)
      def preferable(t: TopicName, target: Target): IO[Boolean] = IO.pure(true)
      def schema(t: TopicName, target: Target): IO[Option[SchemaDescription]] = IO.pure(None)
      def parameters(t: TopicName, target: Target): IO[List[SerdeParameter]] = IO.pure(Nil)
      def claims(sample: Array[Byte]): Boolean = true
      def deserializer(t: TopicName, target: Target): IO[Deserializer[IO]] = IO.pure(new Deserializer[IO] {
        val serde: SerdeName = name
        def deserialize(h: List[RawHeader], b: Array[Byte]): IO[Either[DeserializeFailure, DeserializeResult]] =
          IO.raiseError(new RuntimeException("boom"))
      })
      def serializer(t: TopicName, target: Target, p: Map[String, String]): IO[Serializer[IO]] =
        IO.raiseError(new UnsupportedOperationException)
    }
    // It throws rather than returning a failure, which is the realistic case for a decoder written in Java,
    // and the point is that the picker survives it.
    assertEquals(SerdeAutodetect.rank(List(liar), topic, Target.Value, Some(utf8("x"))).unsafeRunSync(), Nil)
  }

  property("ranking never fails, whatever bytes it is handed") {
    forAll(Arbitrary.arbitrary[Array[Byte]], Gen.const(())) { (bytes, _) =>
      SerdeAutodetect.rank(candidates, topic, Target.Value, Some(bytes)).attempt.unsafeRunSync().isRight
    }
  }
}
