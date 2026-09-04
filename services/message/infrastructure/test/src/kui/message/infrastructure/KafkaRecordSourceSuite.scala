package kui.message.infrastructure

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*

import kui.kernel.browse.{Direction, PollBudget, SeekMode}
import kui.kernel.error.KuiError
import kui.kernel.{Offset, PartitionId}
import kui.message.application.RawRecord
import kui.message.domain.BrowseRequest
import kui.testkit.KuiIOSuite

/** The browse arithmetic, and the promise that a cancelled browse closes its consumer.
  *
  * Every test here runs against [[FakeBrowseConsumer]] rather than a broker, which is the point of the port:
  * an off-by-one at a window boundary duplicates or drops exactly one record per page, and that is a defect a
  * demo survives and a suite does not.
  */
final class KafkaRecordSourceSuite extends KuiIOSuite {

  private val budget: PollBudget = PollBudget.unsafe(10_000, 1L << 20, 30.seconds)

  private def sourceOver(
      log: Map[PartitionId, Vector[RawRecord]],
      closed: Ref[IO, Boolean]
  ): KafkaRecordSource[IO] =
    new KafkaRecordSource[IO](
      FakeBrowseConsumer.opening(log, closed),
      BrowseTuning(pollTimeout = 1.milli, emptyPollsBeforeEnd = 0)
    )

  private def growingSourceOver(
      log: Ref[IO, Map[PartitionId, Vector[RawRecord]]],
      closed: Ref[IO, Boolean],
      tuning: BrowseTuning = BrowseTuning(pollTimeout = 1.milli, emptyPollsBeforeEnd = 0)
  ): KafkaRecordSource[IO] =
    new KafkaRecordSource[IO](FakeBrowseConsumer.openingGrowing(log, closed), tuning)

  private def request(
      seek: SeekMode,
      direction: Direction,
      limit: Int,
      partitions: Option[Set[PartitionId]] = None,
      live: Boolean = false
  ): BrowseRequest =
    BrowseRequest
      .of(
        cluster = FakeBrowseConsumer.Cluster,
        topic = FakeBrowseConsumer.Topic,
        seek = seek,
        direction = Some(direction),
        partitions = partitions,
        limit = Some(limit),
        isolation = None,
        keySerde = None,
        valueSerde = None,
        stringFilter = None,
        filter = None,
        live = live
      )
      .getOrElse(fail("the request under test is not a legal browse"))

  private def browse(
      log: Map[PartitionId, Vector[RawRecord]],
      of: BrowseRequest
  ): IO[List[Either[KuiError, RawRecord]]] =
    Ref.of[IO, Boolean](false).flatMap(closed => sourceOver(log, closed).browse(of, budget).compile.toList)

  private def offsets(records: List[Either[KuiError, RawRecord]]): List[(Int, Long)] =
    records.collect { case Right(record) => (record.partition.value, record.offset.value) }

  // -------------------------------------------------------------------------------------- forward

  test("a forward browse from the beginning reads a partition in order and stops at its end") {
    val log = Map(FakeBrowseConsumer.partition(0, 5))

    browse(log, request(SeekMode.Beginning, Direction.Forward, limit = 50)).map(records =>
      // Fifty were asked for and five exist. The browse ends because the log ended, not because it was
      // still waiting: a bounded read that could not tell the two apart would hang on every small topic.
      assertEquals(offsets(records), List((0, 0L), (0, 1L), (0, 2L), (0, 3L), (0, 4L)))
    )
  }

  test("a forward browse stops at the caller's limit rather than at the end of the log") {
    val log = Map(FakeBrowseConsumer.partition(0, 20))

    browse(log, request(SeekMode.Beginning, Direction.Forward, limit = 3)).map(records =>
      assertEquals(offsets(records), List((0, 0L), (0, 1L), (0, 2L)))
    )
  }

  test("a forward browse from an offset starts there and not at the beginning") {
    val log = Map(FakeBrowseConsumer.partition(0, 10))

    browse(log, request(SeekMode.AtOffset(Offset.unsafe(7)), Direction.Forward, limit = 10)).map(records =>
      assertEquals(offsets(records), List((0, 7L), (0, 8L), (0, 9L)))
    )
  }

  test("an offset past the end of the log is clamped rather than refused") {
    // A user can type an offset retention has since deleted, and a cursor can outlive the records it
    // names. Clamping answers the question they meant; refusing answers none of them.
    val log = Map(FakeBrowseConsumer.partition(0, 4))

    browse(log, request(SeekMode.AtOffset(Offset.unsafe(9_999)), Direction.Forward, limit = 10))
      .map(records => assertEquals(offsets(records), Nil))
  }

  test("a timestamp seek starts at the first record at or after it") {
    val log = Map(FakeBrowseConsumer.partition(0, 6))

    browse(log, request(SeekMode.AtTimestamp(3L), Direction.Forward, limit = 10)).map(records =>
      assertEquals(offsets(records), List((0, 3L), (0, 4L), (0, 5L)))
    )
  }

  test("a per-partition seek browses the partitions it names and no others") {
    // `seekTo=0::1&seekTo=2::0` is a request about two partitions. Reading a third — from wherever its
    // own default happened to be — answers a question nobody asked with records the caller cannot place.
    val log = Map(
      FakeBrowseConsumer.partition(0, 3),
      FakeBrowseConsumer.partition(1, 3),
      FakeBrowseConsumer.partition(2, 3)
    )

    val seek = SeekMode.AtOffsets(
      Map(PartitionId.unsafe(0) -> Offset.unsafe(1), PartitionId.unsafe(2) -> Offset.unsafe(0))
    )

    browse(log, request(seek, Direction.Forward, limit = 10)).map { records =>
      assertEquals(offsets(records).map(_._1).distinct.sorted, List(0, 2))
      assertEquals(offsets(records).filter(_._1 == 0).map(_._2), List(1L, 2L))
    }
  }

  // ------------------------------------------------------------------------------------- backward

  test("a backward browse from the end returns the newest records, newest first") {
    val log = Map(FakeBrowseConsumer.partition(0, 10))

    browse(log, request(SeekMode.Latest, Direction.Backward, limit = 3)).map(records =>
      assertEquals(offsets(records), List((0, 9L), (0, 8L), (0, 7L)))
    )
  }

  test("a backward browse walks down in windows and never reads below the oldest record") {
    // Twelve records, four at a time: three rounds and then the walk ends. The assertion that matters is
    // that every offset appears exactly once — a window boundary that overlapped by one would show a
    // duplicate row on every page, and one that gapped by one would hide a record for ever.
    val log = Map(FakeBrowseConsumer.partition(0, 12))

    browse(log, request(SeekMode.Latest, Direction.Backward, limit = 12)).map { records =>
      assertEquals(offsets(records).map(_._2), (11L to 0L by -1L).toList)
      assertEquals(offsets(records).distinct.size, 12)
    }
  }

  test("a backward browse from an offset stops just below it") {
    // The range is half-open in the same direction as the forward case, which is what lets a cursor
    // minted by one be read by the other with no record shown twice.
    val log = Map(FakeBrowseConsumer.partition(0, 10))

    browse(log, request(SeekMode.AtOffset(Offset.unsafe(5)), Direction.Backward, limit = 3)).map(records =>
      assertEquals(offsets(records), List((0, 4L), (0, 3L), (0, 2L)))
    )
  }

  test("a backward browse merges partitions newest first") {
    val log = Map(FakeBrowseConsumer.partition(0, 4), FakeBrowseConsumer.partition(1, 4))

    browse(log, request(SeekMode.Latest, Direction.Backward, limit = 4)).map { records =>
      // Timestamps are the offsets here, so the newest four records are offsets 3 and 2 of both
      // partitions — in that order, with the partition number breaking the tie.
      assertEquals(offsets(records).map(_._2), List(3L, 3L, 2L, 2L))
    }
  }

  test("a browse of a partition subset reads only those partitions") {
    val log = Map(
      FakeBrowseConsumer.partition(0, 3),
      FakeBrowseConsumer.partition(1, 3),
      FakeBrowseConsumer.partition(2, 3)
    )

    browse(
      log,
      request(SeekMode.Beginning, Direction.Forward, limit = 20, Some(Set(PartitionId.unsafe(1))))
    ).map(records => assertEquals(offsets(records).map(_._1).distinct, List(1)))
  }

  test("an empty topic is a finished browse with no records, not a failure") {
    browse(Map(FakeBrowseConsumer.partition(0, 0)), request(SeekMode.Latest, Direction.Backward, 10))
      .map(records => assertEquals(records, Nil))
  }

  // ----------------------------------------------------------------------------------------- live

  /** A tail over a log the test writes to while it is open.
    *
    * `expected` records are taken and the stream is then let go, because a tail does not end by itself — that
    * is the property under test, and a `compile.toList` over one would never return. The writes are made from
    * a second fiber after a short pause so that they land *after* the browse has planned its windows, which
    * is the only arrangement that distinguishes a tail from an ordinary forward read of a log that already
    * contained them.
    */
  private def tail(
      initial: Map[PartitionId, Vector[RawRecord]],
      writes: List[RawRecord],
      of: BrowseRequest,
      expected: Int,
      over: PollBudget = budget
  ): IO[List[(Int, Long)]] =
    for {
      log <- Ref.of[IO, Map[PartitionId, Vector[RawRecord]]](initial)
      closed <- Ref.of[IO, Boolean](false)
      producing = IO.sleep(50.millis) *> writes.traverse_(record =>
        log.update(current =>
          current.updated(record.partition, current.getOrElse(record.partition, Vector.empty) :+ record)
        )
      )
      reading = growingSourceOver(log, closed).browse(of, over).take(expected.toLong).compile.toList
      records <- IO.both(reading, producing).map(_._1)
    } yield offsets(records)

  test("a live browse stays open past the end of the log and delivers what is written next") {
    // The defect this stands for: a tail that plans its window against the end of the log as it stood
    // when the browse started reads an empty range, delivers nothing and stops — which is exactly what
    // the Follow live control did. Two records are written 50 ms after the browse begins.
    val initial = Map(FakeBrowseConsumer.partition(0, 3))
    val later = List(FakeBrowseConsumer.record(0, 3L), FakeBrowseConsumer.record(0, 4L))

    tail(initial, later, request(SeekMode.Latest, Direction.Forward, limit = 10, live = true), expected = 2)
      .map(seen => assertEquals(seen, List((0, 3L), (0, 4L))))
  }

  test("a live browse does not stop at the caller's limit") {
    // `limit` is a page size, and a tail has no pages. A tail that honoured it would deliver one record
    // and close, which on screen is a Follow control that works once.
    val initial = Map(FakeBrowseConsumer.partition(0, 1))
    val later = List(1L, 2L, 3L).map(FakeBrowseConsumer.record(0, _))

    tail(initial, later, request(SeekMode.Latest, Direction.Forward, limit = 1, live = true), expected = 3)
      .map(seen => assertEquals(seen, List((0, 1L), (0, 2L), (0, 3L))))
  }

  test("a live browse does not end when several polls in a row come back empty") {
    // `emptyPollsBeforeEnd` is 0 in this suite, so a bounded read gives up on the very first empty poll.
    // A quiet topic is the ordinary state of a tail rather than the end of one, and the 50 ms before the
    // first write is many empty polls at a 1 ms poll timeout.
    val initial = Map(FakeBrowseConsumer.partition(0, 0))

    tail(
      initial,
      List(FakeBrowseConsumer.record(0, 0L)),
      request(SeekMode.Beginning, Direction.Forward, limit = 10, live = true),
      expected = 1
    ).map(seen => assertEquals(seen, List((0, 0L))))
  }

  test("a live browse outlives the budget's deadline") {
    // The deadline is the last line of defence for a read that scans without matching. A tail is the one
    // read whose purpose is to still be open later, and closing it after a minute would end the stream
    // for a reason nothing on the screen could explain.
    val brief = PollBudget.unsafe(10_000, 1L << 20, 20.millis)
    val initial = Map(FakeBrowseConsumer.partition(0, 1))

    tail(
      initial,
      List(FakeBrowseConsumer.record(0, 1L)),
      request(SeekMode.Latest, Direction.Forward, limit = 10, live = true),
      expected = 1,
      over = brief
    ).map(seen => assertEquals(seen, List((0, 1L))))
  }

  test("cancelling a live browse closes the Kafka consumer") {
    // The one way a tail ends. Everything else about it is arranged so that it does not stop, which makes
    // this the only release path its consumer has.
    for {
      log <- Ref.of[IO, Map[PartitionId, Vector[RawRecord]]](Map(FakeBrowseConsumer.partition(0, 1)))
      closed <- Ref.of[IO, Boolean](false)
      started <- Deferred[IO, Unit]
      fiber <- growingSourceOver(log, closed)
        .browse(request(SeekMode.Beginning, Direction.Forward, limit = 10, live = true), budget)
        .evalTap(_ => started.complete(()).void)
        .compile
        .drain
        .start
      _ <- started.get
      _ <- fiber.cancel
      wasClosed <- closed.get
    } yield assert(wasClosed, "the consumer was still open after the tail was cancelled")
  }

  // --------------------------------------------------------------------------------- cancellation

  test("cancelling a browse closes the Kafka consumer") {
    // The chain this stands in for is: browser tab closed, gateway stream cancelled, service fiber
    // cancelled, consumer closed. This is its last link, and it is the one that leaks: a `Resource`
    // that is never released is a broker connection nobody notices until it runs out of them.
    val log = Map(FakeBrowseConsumer.partition(0, 100_000))

    for {
      closed <- Ref.of[IO, Boolean](false)
      reading <- Deferred[IO, Unit]
      fiber <- sourceOver(log, closed)
        .browse(request(SeekMode.Beginning, Direction.Forward, limit = 100_000), budget)
        .evalTap(_ => reading.complete(()).void)
        .compile
        .drain
        .start
      _ <- reading.get
      _ <- fiber.cancel
      wasClosed <- closed.get
    } yield assert(wasClosed, "the consumer was still open after the browse was cancelled")
  }

  test("a finished browse closes the Kafka consumer too") {
    val log = Map(FakeBrowseConsumer.partition(0, 3))

    for {
      closed <- Ref.of[IO, Boolean](false)
      _ <- sourceOver(log, closed)
        .browse(request(SeekMode.Beginning, Direction.Forward, limit = 3), budget)
        .compile
        .drain
      wasClosed <- closed.get
    } yield assert(wasClosed, "the consumer was left open by a browse that ended normally")
  }
}
