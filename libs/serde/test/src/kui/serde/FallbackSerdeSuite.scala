package kui.serde

import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll

import kui.kernel.TopicName
import kui.testkit.KuiSuite

/** That the fallback cannot fail.
  *
  * Everything else in the milestone leans on this: the browse pipeline's promise that a record is always
  * shown, `Deserializers.withFallback`'s promise that it has no failure path, and the resolution order's
  * promise that it always terminates. All three are false if one byte array exists that this serde refuses.
  */
final class FallbackSerdeSuite extends KuiSuite {

  private val topic: TopicName = TopicName.unsafe("orders")

  private def decode(bytes: Array[Byte]): Either[DeserializeFailure, DeserializeResult] =
    FallbackSerde[IO].deserializer(topic, Target.Value).flatMap(_.deserialize(Nil, bytes)).unsafeRunSync()

  property("no byte array fails to decode") {
    forAll { (bytes: Array[Byte]) => decode(bytes).isRight }
  }

  test("the empty payload decodes to the empty string") {
    assertEquals(decode(Array.emptyByteArray).map(_.text), Right(""))
  }

  test("bytes that are not valid UTF-8 decode to replacement characters rather than failing") {
    val invalid = Array[Byte](0xc3.toByte, 0x28.toByte)
    val text = decode(invalid).map(_.text)
    assert(text.exists(_.contains('�')), text.toString)
  }

  test("the kind is Text even for bytes that are JSON") {
    // Detecting JSON is the Json serde's job. A fallback that guessed would make "the fallback was used"
    // invisible in the table view, which is the one state the marker exists to show.
    val json = """{"a":1}""".getBytes(StandardCharsets.UTF_8)
    assertEquals(decode(json).map(_.kind), Right(PayloadKind.Text))
  }

  property("valid UTF-8 round-trips unchanged") {
    forAll { (s: String) =>
      decode(s.getBytes(StandardCharsets.UTF_8)).map(_.text) == Right(s)
    }
  }

  property("decoding is idempotent: decoding the decoded text's bytes gives the same text") {
    forAll { (bytes: Array[Byte]) =>
      val once = decode(bytes).map(_.text)
      val twice = once.flatMap(t => decode(t.getBytes(StandardCharsets.UTF_8)).map(_.text))
      once == twice
    }
  }

  test("it carries no properties, so nothing downstream can mistake it for a serde that knows a schema") {
    assertEquals(decode("x".getBytes(StandardCharsets.UTF_8)).map(_.properties), Right(Map.empty[String, io.circe.Json]))
  }

  test("it is never a candidate: not preferable, and it claims no sample") {
    val serde = FallbackSerde[IO]
    assertEquals(serde.preferable(topic, Target.Value).unsafeRunSync(), false)
    serde match {
      case detector: SampleDetector => assertEquals(detector.claims("anything".getBytes), false)
      case _                        => fail("the fallback is built on SimpleSerde and so is a SampleDetector")
    }
  }

  test("it does not offer to serialize: it is where resolution ends, not something a user picks") {
    assertEquals(FallbackSerde[IO].canSerialize(topic, Target.Value).unsafeRunSync(), false)
  }

  test("its description names itself and admits it has no integration coverage") {
    val described = FallbackSerde[IO].describe
    assertEquals(described.name, SerdeName.Fallback)
    assertEquals(described.coveredByIntegrationTest, false)
    assert(described.description.nonEmpty)
  }
}
