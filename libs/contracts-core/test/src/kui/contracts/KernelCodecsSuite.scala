package kui.contracts

import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Codec, Json}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen
import sttp.tapir.DecodeResult

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.*

/** That a kernel type survives the wire, and that an invalid one does not get on it.
  *
  * The properties are one per identifier because a shared helper would have proved that the helper
  * works. The interesting assertions are the negative ones: a decoder that accepts anything is worse
  * than no decoder, because it moves the failure from the edge to somewhere deep inside a service.
  */
final class KernelCodecsSuite extends ScalaCheckSuite {

  /** Round-trips a value through JSON and asserts it comes back unchanged. */
  private def roundTrips[A: Codec](value: A)(using munit.Location, CanEqual[A, A]): Unit =
    assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  private val slug: Gen[String] = Gen.oneOf("prod-eu", "staging", "a", "dev-2", "a" * 64)
  private val name: Gen[String] = Gen.oneOf("orders", "orders.v2", "a", "x" * 200)

  property("cluster ids round-trip and encode as a bare string") {
    forAll(slug) { raw =>
      val id = ClusterId.unsafe(raw)
      roundTrips(id)
      assertEquals(id.asJson, Json.fromString(raw))
    }
  }

  property("topic names round-trip") {
    forAll(name)(raw => roundTrips(TopicName.unsafe(raw)))
  }

  property("numeric identifiers round-trip and encode as bare numbers") {
    forAll(Gen.chooseNum(0, 100000)) { raw =>
      roundTrips(PartitionId.unsafe(raw))
      roundTrips(BrokerId.unsafe(raw))
      roundTrips(SchemaId.unsafe(raw))
      roundTrips(TaskId.unsafe(raw))
      assertEquals(PartitionId.unsafe(raw).asJson, Json.fromInt(raw))
    }
  }

  /** The largest integer a browser's `JSON.parse` reproduces exactly: 2^53 - 1.
    *
    * Beyond it, a JavaScript number is a double and the last digits are lost — a fact about the
    * platform, not about circe. Kafka offsets and byte counts are far below it (2^53 records in one
    * partition is not a number that happens), so KUI keeps them as JSON numbers rather than
    * stringifying every offset. The boundary is asserted below so that the limit is a documented,
    * tested property rather than a surprise found in M3.
    */
  private val MaxExactJsonInteger: Long = 9007199254740991L

  property("offsets and byte sizes round-trip for every value either platform represents exactly") {
    forAll(Gen.chooseNum(0L, MaxExactJsonInteger)) { raw =>
      roundTrips(Offset.unsafe(raw))
      roundTrips(ByteSize.unsafe(raw))
      assertEquals(decode[Offset](raw.toString).map(_.value), Right(raw))
    }
  }

  test("the largest exactly representable offset round-trips on both platforms") {
    val edge = Offset.unsafe(MaxExactJsonInteger)
    assertEquals(decode[Offset](edge.asJson.noSpaces).map(_.value), Right(MaxExactJsonInteger))
  }

  test("every remaining identifier round-trips") {
    roundTrips(KafkaClusterId.unsafe("MkU3OEVBNTcwNTJENDM2Qg"))
    roundTrips(GroupId.unsafe("orders-consumer"))
    roundTrips(Subject.unsafe("orders-value"))
    roundTrips(ConnectName.unsafe("main"))
    roundTrips(ConnectorName.unsafe("s3-sink"))
    roundTrips(CorrelationId.unsafe("3b1fa9c2e4d54f0b"))
    roundTrips(ServiceId.unsafe("topic"))
    roundTrips(UserName.unsafe("ada"))
    roundTrips(RoleName.unsafe("viewer"))
    roundTrips(Host.unsafe("broker-1.internal"))
    roundTrips(Port.unsafe(9092))
    roundTrips(PositiveInt.unsafe(3))
    roundTrips(PageToken.unsafe("abc"))
    roundTrips(TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(2)))
  }

  test("a value the kernel would refuse does not decode, and says why") {
    val failure = decode[ClusterId](""""Not A Slug"""")
    assert(failure.isLeft)
    assert(
      failure.swap.exists(_.getMessage.contains("clusterId")),
      s"the failure should name the field, got $failure"
    )
    assert(decode[TopicName](""""..."""").isRight)
    assert(decode[TopicName](""".."""").isLeft)
    assert(decode[Port]("0").isLeft)
    assert(decode[PositiveInt]("0").isLeft)
  }

  test("a sort order is a lowercase string, not an ordinal") {
    assertEquals(SortOrder.Asc.asJson, Json.fromString("asc"))
    assertEquals(SortOrder.Desc.asJson, Json.fromString("desc"))
    assertEquals(decode[SortOrder](""""desc""""), Right(SortOrder.Desc))
    assert(decode[SortOrder](""""ASC"""").isLeft)
    assert(decode[SortOrder]("0").isLeft)
  }

  test("a page encodes its metadata, with absent values as null rather than missing") {
    val encoded = Page(List(1, 2, 3), 1, 25, Some(3L), None).asJson

    assertEquals(encoded.hcursor.get[Int]("page"), Right(1))
    assertEquals(encoded.hcursor.get[Int]("pageSize"), Right(25))
    assertEquals(encoded.hcursor.get[Option[Long]]("totalItems"), Right(Some(3L)))
    assert(encoded.hcursor.downField("nextPageToken").focus.exists(_.isNull))
    assertEquals(decode[Page[Int]](encoded.noSpaces), Right(Page(List(1, 2, 3), 1, 25, Some(3L), None)))
  }

  test("a page of identifiers uses the identifier's own wire form") {
    val page = Page(List(TopicName.unsafe("orders")), 1, 25, Some(1L), None)
    assertEquals(page.asJson.hcursor.downField("items").as[List[String]], Right(List("orders")))
  }

  test("a path codec turns a valid segment into an identifier") {
    val codec = summon[sttp.tapir.Codec[String, ClusterId, sttp.tapir.CodecFormat.TextPlain]]
    assertEquals(codec.decode("prod-eu").map(_.value), DecodeResult.Value("prod-eu"))
    assertEquals(codec.encode(ClusterId.unsafe("prod-eu")), "prod-eu")
  }

  test("a path codec refuses an invalid segment and carries the reason with it") {
    val codec = summon[sttp.tapir.Codec[String, ClusterId, sttp.tapir.CodecFormat.TextPlain]]

    codec.decode("Not A Slug") match {
      case DecodeResult.Error(original, KernelDecodeFailure(error)) =>
        assertEquals(original, "Not A Slug")
        assertEquals(error.fieldName, "clusterId")
      case other => fail(s"expected a decode error carrying a ValidationError, got $other")
    }
  }

  test("a numeric path codec refuses a value outside its range") {
    val codec = summon[sttp.tapir.Codec[String, Port, sttp.tapir.CodecFormat.TextPlain]]
    assertEquals(codec.decode("9092").map(_.value), DecodeResult.Value(9092))
    assert(!codec.decode("0").isInstanceOf[DecodeResult.Value[?]])
    assert(!codec.decode("not-a-number").isInstanceOf[DecodeResult.Value[?]])
  }

  test("the sort-order query codec accepts only the two wire strings") {
    val codec = summon[sttp.tapir.Codec[String, SortOrder, sttp.tapir.CodecFormat.TextPlain]]
    assertEquals(codec.decode("asc"), DecodeResult.Value(SortOrder.Asc))
    assert(!codec.decode("ascending").isInstanceOf[DecodeResult.Value[?]])
  }
}
