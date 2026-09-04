package kui.serde

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.TopicName
import kui.testkit.KuiSuite

/** That no way of failing escapes.
  *
  * `Deserializers.attempt` is the only place a `Deserializer[F]` is run, so every failure mode a serde has —
  * a returned `Left`, a raised error, a thrown exception inside the effect's construction — has to be turned
  * into a value here, or it ends a browse.
  */
final class DeserializersSuite extends KuiSuite {

  private val topic: TopicName = TopicName.unsafe("orders")
  private val name: SerdeName = SerdeName.unsafe("Boom")

  private def deserializer(run: (List[RawHeader], Array[Byte]) => IO[Either[DeserializeFailure, DeserializeResult]])
      : Deserializer[IO] =
    new Deserializer[IO] {
      val serde: SerdeName = name
      def deserialize(
          headers: List[RawHeader],
          bytes: Array[Byte]
      ): IO[Either[DeserializeFailure, DeserializeResult]] = run(headers, bytes)
    }

  private val raises: Deserializer[IO] =
    deserializer((_, _) => IO.raiseError(new RuntimeException("the payload is truncated")))

  private val throwsEagerly: Deserializer[IO] =
    deserializer((_, _) => throw new ArrayIndexOutOfBoundsException(7))

  private val returnsLeft: Deserializer[IO] =
    deserializer((_, _) => IO.pure(Left(DeserializeFailure(name, "not my format"))))

  private val fallback: IO[Deserializer[IO]] = FallbackSerde[IO].deserializer(topic, Target.Value)

  test("an exception raised in the effect becomes a failure naming the deserializer's serde") {
    val result = Deserializers.attempt(raises, Nil, Some(Array[Byte](1))).unsafeRunSync()
    assertEquals(result, Left(DeserializeFailure(name, "the payload is truncated")))
  }

  test("an exception thrown while building the effect is caught too") {
    // `IO.defer` is what makes this work. A serde that throws before returning its `IO` is not exotic:
    // it is what a decoder written in Java does when it validates its argument first.
    val result = Deserializers.attempt(throwsEagerly, Nil, Some(Array[Byte](1))).unsafeRunSync()
    assert(result.isLeft, result.toString)
  }

  test("an exception with no message is described by its class rather than by an empty string") {
    val silent = deserializer((_, _) => IO.raiseError(new NoSuchElementException()))
    assertEquals(
      Deserializers.attempt(silent, Nil, Some(Array[Byte](1))).unsafeRunSync(),
      Left(DeserializeFailure(name, "NoSuchElementException"))
    )
  }

  test("a returned failure is passed through unchanged") {
    assertEquals(
      Deserializers.attempt(returnsLeft, Nil, Some(Array[Byte](1))).unsafeRunSync(),
      Left(DeserializeFailure(name, "not my format"))
    )
  }

  test("a null payload is the empty string, not a failure: a tombstone is a record") {
    assertEquals(Deserializers.attempt(raises, Nil, None).unsafeRunSync(), Right(Deserializers.NullPayload))
  }

  test("withFallback returns both the fallback's text and the failure that caused it") {
    val (result, failure) =
      fallback.flatMap(f => Deserializers.withFallback(raises, f, Nil, Some("hello".getBytes))).unsafeRunSync()
    assertEquals(result.text, "hello")
    assertEquals(result.kind, PayloadKind.Text)
    assertEquals(failure, Some(DeserializeFailure(name, "the payload is truncated")))
  }

  test("withFallback reports no failure when the primary succeeded") {
    val ok = deserializer((_, _) => IO.pure(Right(DeserializeResult.json("""{"a":1}"""))))
    val (result, failure) =
      fallback.flatMap(f => Deserializers.withFallback(ok, f, Nil, Some(Array[Byte](1)))).unsafeRunSync()
    assertEquals(result.kind, PayloadKind.Json)
    assertEquals(failure, None)
  }

  /** Every way a deserializer can misbehave, as a generator. */
  private val brokenDeserializers: Gen[Deserializer[IO]] = Gen.oneOf(
    raises,
    throwsEagerly,
    returnsLeft,
    deserializer((_, _) => IO.raiseError(new IllegalStateException(null: String))),
    deserializer((_, _) => IO.raiseError(new StringIndexOutOfBoundsException(-1)))
  )

  property("withFallback never fails, whatever the primary does and whatever the bytes are") {
    forAll(brokenDeserializers, Arbitrary.arbitrary[Option[Array[Byte]]]) { (broken, bytes) =>
      val (result, failure) =
        fallback.flatMap(f => Deserializers.withFallback(broken, f, Nil, bytes)).unsafeRunSync()
      // A null payload short-circuits before the primary runs, so there is nothing to fall back from.
      val expectedFailure = bytes.isDefined
      result.kind == PayloadKind.Text && failure.isDefined == expectedFailure
    }
  }

  property("attempt turns every misbehaviour into a Left rather than an escaped throwable") {
    forAll(brokenDeserializers) { broken =>
      Deserializers.attempt(broken, Nil, Some(Array[Byte](1, 2, 3))).unsafeRunSync().isLeft
    }
  }
}
