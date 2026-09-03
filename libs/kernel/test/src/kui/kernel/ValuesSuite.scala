package kui.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The arithmetic and the bounds of the kernel's value objects.
  *
  * `OffsetRange` gets the most attention because it is the one place where an off-by-one is both
  * easy to write and expensive to find: every message-browsing page in M3 is a range, and a range
  * that is one record short is a bug nobody notices until a user says "a message is missing".
  */
final class ValuesSuite extends ScalaCheckSuite {

  private def offset(n: Long): Offset = Offset.unsafe(n)

  test("a range whose start is after its end is refused, with the rule spelled out") {
    assertEquals(
      OffsetRange.from(offset(10), offset(4)),
      Left(
        ValidationError.Invariant(
          "offsetRange",
          "the start offset 10 must not be after the end offset 4"
        )
      )
    )
  }

  test("a range whose start equals its end is legal and empty") {
    val range = OffsetRange.from(offset(7), offset(7))
    assertEquals(range.map(_.isEmpty), Right(true))
    assertEquals(range.map(_.size), Right(0L))
  }

  property("the size of a half-open range is exactly end minus start") {
    forAll(Gen.chooseNum(0L, 1000000L), Gen.chooseNum(0L, 1000000L)) { (a, b) =>
      val begin = math.min(a, b)
      val end   = math.max(a, b)
      assertEquals(OffsetRange.from(offset(begin), offset(end)).map(_.size), Right(end - begin))
    }
  }

  property("a half-open range contains its start and excludes its end") {
    forAll(Gen.chooseNum(0L, 1000000L), Gen.chooseNum(1L, 10000L)) { (begin, size) =>
      val range = OffsetRange.from(offset(begin), offset(begin + size))
      assertEquals(range.map(_.contains(offset(begin))), Right(true))
      assertEquals(range.map(_.contains(offset(begin + size))), Right(false))
      assertEquals(range.map(_.contains(offset(begin + size - 1))), Right(true))
    }
  }

  test("a range cannot be built around its smart constructor") {
    assert(
      compileErrors("""val r: OffsetRange = OffsetRange(Offset.unsafe(9), Offset.unsafe(1))""").nonEmpty,
      "the case class constructor must be private so that an inverted range is unrepresentable"
    )
  }

  test("port 0 and port 65536 are out of range, 1 and 65535 are not") {
    assertEquals(Port.from(0), Left(ValidationError.Range("port", Some("1"), Some("65535"), "0")))
    assertEquals(
      Port.from(65536),
      Left(ValidationError.Range("port", Some("1"), Some("65535"), "65536"))
    )
    assertEquals(Port.from(1).map(_.value), Right(1))
    assertEquals(Port.from(65535).map(_.value), Right(65535))
  }

  test("a positive int refuses zero and negatives") {
    assertEquals(PositiveInt.from(0), Left(ValidationError.Range("positiveInt", Some("1"), None, "0")))
    assert(PositiveInt.from(-1).isLeft)
    assertEquals(PositiveInt.from(1).map(_.value), Right(1))
  }

  test("a byte size allows zero but refuses a negative count") {
    assertEquals(ByteSize.from(0L).map(_.value), Right(0L))
    assertEquals(
      ByteSize.from(-1L),
      Left(ValidationError.Range("byteSize", Some("0"), None, "-1"))
    )
  }

  test("a host accepts the forms that appear in a bootstrap-servers list") {
    val legal = List("localhost", "broker-1.kafka.svc.cluster.local", "10.0.0.7", "[2001:db8::1]")
    legal.foreach(host => assertEquals(Host.from(host).map(_.value), Right(host), clue = host))
  }

  test("a host refuses the shapes that are a mis-split configuration line") {
    List("", "host name", "host/path", "x" * 254).foreach { raw =>
      assert(Host.from(raw).isLeft, s"'$raw' should not be a legal host")
    }
  }

  test("topic partitions sort by topic first and then by partition number") {
    val partitions = List(
      TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(2)),
      TopicPartition(TopicName.unsafe("audit"), PartitionId.unsafe(10)),
      TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(1))
    )
    assertEquals(
      partitions.sorted.map(tp => s"${tp.topic.value}-${tp.partition.value}"),
      List("audit-10", "orders-1", "orders-2")
    )
  }
}
