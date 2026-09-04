package kui.message.domain

import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, Offset, OffsetRange, PartitionId, TopicName}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The three things M3 does that change a cluster, and the rules that stop each of them being asked for by
  * accident.
  *
  * The distinction this suite pins is the one that separates a mutation's validation from a read's: a browse
  * clamps a quantity that is out of range, and a produce refuses one. A user who asked for a million records
  * and silently got a hundred has seen a short page; a user who asked for a million records to be *published*
  * and silently got a thousand has written a thousand records they did not mean to write, and there is no
  * undo for that.
  */
final class MutationsSuite extends ScalaCheckSuite {

  private val cluster = ClusterId.unsafe("local")
  private val orders = TopicName.unsafe("orders")
  private val archive = TopicName.unsafe("orders-archive")
  private val p0 = PartitionId.unsafe(0)

  private def produce(count: Option[Int], headers: List[(String, String)] = Nil) =
    ProduceRequest.of(
      cluster = cluster,
      topic = orders,
      partition = None,
      key = Some("k"),
      value = Some("v"),
      headers = headers,
      keySerde = None,
      valueSerde = None,
      keySerdeProperties = Map.empty,
      valueSerdeProperties = Map.empty,
      count = count
    )

  private def range(from: Long, until: Long): OffsetRange =
    OffsetRange.from(Offset.unsafe(from), Offset.unsafe(until)).toOption.get

  private def resend(
      source: SourceRange = SourceRange(orders, p0, range(0, 10)),
      destination: Destination = Destination(archive, None)
  ) = ResendRequest.of(cluster, source, destination, keepHeaders = true)

  test("countDefaultsToOneAndIsBoundedRatherThanClamped") {
    assertEquals(produce(None).map(_.count), Right(1))
    assertEquals(produce(Some(1)).map(_.count), Right(1))
    assertEquals(produce(Some(ProduceRequest.DefaultMaxCount)).map(_.count), Right(ProduceRequest.DefaultMaxCount))

    assert(produce(Some(0)).isLeft)
    assert(produce(Some(-1)).isLeft)
    assert(produce(Some(ProduceRequest.DefaultMaxCount + 1)).isLeft)
  }

  test("aRefusedCountNamesTheLimit") {
    produce(Some(Int.MaxValue)) match {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assert(error.details.exists(_.field.contains("count")))
        assert(error.details.exists(_.restrictions.exists(_.contains(ProduceRequest.DefaultMaxCount.toString))))
      case Right(request) => fail(s"expected a rejection, got $request")
    }
  }

  test("aTombstoneIsProducibleAndIsNotAnEmptyString") {
    // On a compacted topic a null value deletes the key and an empty string does not. A form that maps one
    // to the other silently breaks compaction for whoever uses it, so the two stay distinguishable here.
    val tombstone = ProduceRequest.of(
      cluster,
      orders,
      None,
      Some("k"),
      None,
      Nil,
      None,
      None,
      Map.empty,
      Map.empty,
      Some(1)
    )

    assertEquals(tombstone.map(_.value), Right(None))
    assertNotEquals(tombstone.map(_.value), Right(Some("")))
  }

  test("aHeaderWithNoNameIsRefused") {
    assert(produce(Some(1), headers = List("" -> "v")).isLeft)
    assert(produce(Some(1), headers = List("k" -> "")).isRight, "an empty header value is legal")
  }

  test("everyProduceCarriesItsMutationKind") {
    // ADR-047: the marker is what M5's read-only policy and M6's RBAC key on, so it ships with the first
    // mutation rather than being retrofitted onto a service that has already shipped.
    assertEquals(produce(Some(1)).map(_.kind), Right(MutationKind.Produce))
    assertEquals(resend().map(_.kind), Right(MutationKind.Resend))
    assertEquals(PurgeRequest(cluster, orders, None).kind, MutationKind.Purge)
    assertEquals(MutationKind.All.map(_.wire).sorted, List("PRODUCE", "PURGE", "RESEND"))
  }

  test("anEmptySourceRangeIsRefused") {
    assert(resend(source = SourceRange(orders, p0, range(10, 10))).isLeft)
    assert(resend(source = SourceRange(orders, p0, range(10, 11))).isRight)
  }

  test("copyingAPartitionOntoItselfIsRefused") {
    // It would append every record it reads and then read what it appended.
    assert(resend(destination = Destination(orders, Some(p0))).isLeft)
    // The same topic, a different partition, is a legal (if unusual) request.
    assert(resend(destination = Destination(orders, Some(PartitionId.unsafe(1)))).isRight)
    assert(resend(destination = Destination(archive, Some(p0))).isRight)
  }

  property("aWellFormedResendIsAcceptedForAnyNonEmptyWindow") {
    forAll(Gen.chooseNum(0L, 10000L), Gen.chooseNum(1L, 500L)) { (from, width) =>
      assert(resend(source = SourceRange(orders, p0, range(from, from + width))).isRight)
    }
  }

  test("anInvertedResendWindowIsNotEvenRepresentable") {
    // The range type refuses it, so no request can carry one and no use case has to check for it.
    assert(OffsetRange.from(Offset.unsafe(10), Offset.unsafe(9)).isLeft)
  }
}
