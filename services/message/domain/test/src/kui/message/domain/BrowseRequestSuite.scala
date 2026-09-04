package kui.message.domain

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** What a browse request refuses, and what it quietly corrects.
  *
  * The split between the two is the interesting part and is asserted rather than described: a *quantity* out
  * of range is clamped, because "give me a million records" means "give me as many as you allow" and a 400
  * there is a worse answer than a page; anything that is a *contradiction* is refused, because there is no
  * sensible value to substitute for it.
  */
final class BrowseRequestSuite extends ScalaCheckSuite {

  private val cluster = ClusterId.unsafe("local")
  private val topic = TopicName.unsafe("orders")
  private val limits = BrowseLimits.Default

  private def build(
      limit: Option[Int] = None,
      seek: SeekMode = SeekMode.Latest,
      direction: Option[Direction] = None,
      partitions: Option[Set[PartitionId]] = None,
      stringFilter: Option[String] = None,
      live: Boolean = false
  ) =
    BrowseRequest.of(
      cluster = cluster,
      topic = topic,
      seek = seek,
      direction = direction,
      partitions = partitions,
      limit = limit,
      isolation = None,
      keySerde = None,
      valueSerde = None,
      stringFilter = stringFilter,
      filter = None,
      live = live,
      limits = limits
    )

  test("limitClampingTable") {
    val table: List[(Option[Int], Int)] = List(
      None -> limits.default,
      Some(0) -> limits.default,
      Some(-1) -> limits.default,
      Some(Int.MinValue) -> limits.default,
      Some(1) -> 1,
      Some(limits.max) -> limits.max,
      Some(limits.max + 1) -> limits.max,
      Some(Int.MaxValue) -> limits.max
    )

    table.foreach { case (asked, expected) =>
      assertEquals(build(limit = asked).map(_.limit), Right(expected), s"for a requested limit of $asked")
    }
  }

  property("theLimitIsAlwaysInsideItsBounds") {
    forAll(Gen.option(Gen.chooseNum(Int.MinValue, Int.MaxValue))) { asked =>
      build(limit = asked) match {
        case Right(request) =>
          assert(request.limit >= 1 && request.limit <= limits.max, s"limit was ${request.limit}")
        case Left(error) => fail(s"a limit should never be refused, got $error")
      }
    }
  }

  test("liveModeRejectsAnOffsetSeek") {
    // Accepting a start position for a tail and then ignoring it would show the user a live view they
    // believe is anchored somewhere it is not.
    assert(build(seek = SeekMode.AtOffset(Offset.unsafe(42)), live = true).isLeft)
    assert(build(seek = SeekMode.AtTimestamp(1700000000000L), live = true).isLeft)
    assert(build(seek = SeekMode.AtOffsets(Map(PartitionId.unsafe(0) -> Offset.unsafe(1))), live = true).isLeft)

    assert(build(seek = SeekMode.Latest, live = true).isRight)
    assert(build(seek = SeekMode.Beginning, live = true).isRight)

    // The same seek is perfectly legal when it is not a tail.
    assert(build(seek = SeekMode.AtOffset(Offset.unsafe(42)), live = false).isRight)
  }

  test("partitionSubsetMustBeNonEmptyWhenPresent") {
    assert(build(partitions = Some(Set.empty)).isLeft)
    assert(build(partitions = None).isRight)
    assertEquals(
      build(partitions = Some(Set(PartitionId.unsafe(2), PartitionId.unsafe(0)))).map(_.partitions.map(_.length)),
      Right(Some(2))
    )
  }

  property("everyRejectionNamesTheField") {
    // These become 400 responses, and a 400 whose body says only "invalid request" sends the user back to
    // guess which of nine parameters was wrong.
    val rejections = Gen.oneOf(
      build(partitions = Some(Set.empty)),
      build(seek = SeekMode.AtOffset(Offset.unsafe(1)), live = true),
      build(seek = SeekMode.AtTimestamp(1L), live = true)
    )

    forAll(rejections) { attempt =>
      attempt match {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.Validation)
          assert(error.details.nonEmpty, "the error carries no field detail")
          assert(error.details.forall(_.field.exists(_.nonEmpty)), s"a detail names no field: ${error.details}")
        case Right(request) => fail(s"expected a rejection, got $request")
      }
    }
  }

  property("theDirectionComesFromTheSeekModeUnlessItIsGiven") {
    forAll(MessageGenerators.seekMode, Gen.option(Gen.oneOf(Direction.All))) { (seek, asked) =>
      build(seek = seek, direction = asked, live = false).map(_.direction) match {
        case Right(direction) =>
          assertEquals(direction, asked.getOrElse(SeekMode.defaultDirection(seek)))
        case Left(error) => fail(s"unexpected rejection: $error")
      }
    }
  }

  test("anEmptyStringFilterIsNoFilterAtAll") {
    // A blank search box arrives as `""`. Carrying it as a filter would make every record fail to match it
    // in one implementation and match it in another.
    assertEquals(build(stringFilter = Some("")).map(_.stringFilter), Right(None))
    assertEquals(build(stringFilter = Some("boom")).map(_.stringFilter), Right(Some("boom")))
  }

  test("theIsolationLevelDefaultsToWhatApplicationsSee") {
    assertEquals(build().map(_.isolation), Right(IsolationLevel.ReadUncommitted))
  }

  test("aFilterIdKuiCouldNotHaveMintedIsRefused") {
    assert(FilterRef.of("not-a-hash", None).isLeft)
    assert(FilterRef.of("0123456789ABCDEF", None).isLeft, "uppercase is not what FilterId mints")
    assert(FilterRef.of("0123456789abcde", None).isLeft, "fifteen characters")
    assert(FilterRef.of("0123456789abcdef", Some("record.partition == 0")).isRight)
  }
}
