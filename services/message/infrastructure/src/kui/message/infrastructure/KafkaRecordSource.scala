package kui.message.infrastructure

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.EitherT
import cats.effect.kernel.{Resource, Temporal}
import cats.syntax.all.*
import fs2.Stream

import kui.kernel.browse.{Direction, IsolationLevel, PollBudget, SeekMode}
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.application.{RawRecord, RecordSource}
import kui.message.domain.BrowseRequest

/** How a browse polls, as configuration rather than as constants scattered through the loop.
  *
  * @param pollTimeout
  *   how long one `poll` waits. Short, because it is also how long a cancelled browse takes to notice: the
  *   chain from a closed browser tab to a closed Kafka consumer runs between two polls, never through one
  * @param emptyPollsBeforeEnd
  *   how many polls may return nothing before a bounded browse concludes it has read everything there is.
  *   It is not zero because the first poll after an assignment routinely returns nothing while the consumer
  *   finds the leaders, and a browse that gave up there would report an empty topic that is not empty
  */
final case class BrowseTuning(pollTimeout: FiniteDuration, emptyPollsBeforeEnd: Int)

object BrowseTuning {
  val Default: BrowseTuning = BrowseTuning(pollTimeout = 250.millis, emptyPollsBeforeEnd = 8)

  given CanEqual[BrowseTuning, BrowseTuning] = CanEqual.derived
}

/** The browse port over a Kafka consumer: seek resolution, the forward read, and the backward window walk.
  *
  * ==Two promises, and how each is kept==
  *
  * **It never materialises a topic.** A forward browse seeks and streams, emitting each record as it is
  * polled. A backward browse — which Kafka cannot do, because a consumer only ever moves forward — walks
  * each partition in *windows*: it seeks to `high - limit`, reads that window forwards, hands it back
  * newest-first, and only if the caller wants more does it drop the window down and read the one below.
  * Neither path ever holds more than one window of one round in memory, whatever the size of the topic.
  *
  * **Cancellation reaches the consumer.** The consumer is a `Resource`, opened by [[open]] inside the
  * stream, so fs2 closes it when the stream completes *or is cancelled*. The poll loop is a sequence of
  * short polls rather than one long one precisely so that a cancellation lands promptly between two of
  * them: a browser tab that goes away closes a Kafka consumer within one `pollTimeout`.
  *
  * @param open
  *   a consumer for one cluster at one isolation level. It returns `Either` because a cluster nobody
  *   configured, or one whose credentials are wrong, is a failure the browse reports as an event rather
  *   than as a raised exception.
  */
final class KafkaRecordSource[F[_]: Temporal](
    open: (ClusterId, IsolationLevel) => Resource[F, Either[KuiError, BrowseConsumer[F]]],
    tuning: BrowseTuning = BrowseTuning.Default
) extends RecordSource[F] {

  import KafkaRecordSource.*

  def browse(request: BrowseRequest, budget: PollBudget): Stream[F, Either[KuiError, RawRecord]] =
    Stream
      .resource(open(request.cluster, request.isolation))
      .flatMap {
        case Left(error) => Stream.emit(Left(error))
        case Right(consumer) =>
          Stream.eval(plan(consumer, request).value).flatMap {
            case Left(error) => Stream.emit(Left(error))
            // No windows is not a failure. An empty topic, a partition subset that holds nothing, a
            // timestamp after the last record: all of them are "there is nothing to show", which is a
            // finished stream with no records and not an error anybody can act on.
            case Right(Nil) => Stream.empty
            case Right(windows) =>
              request.direction match {
                case Direction.Forward => forward(consumer, request, windows)
                case Direction.Backward => backward(consumer, request, windows)
              }
          }
      }
      // The budget's deadline, applied to the whole read. It is the last line of defence rather than the
      // first: `limit` normally ends a browse long before this does, and a browse that hits this one has
      // been scanning without matching, which is exactly when a user needs it to stop by itself.
      .interruptAfter(budget.deadline)

  // ------------------------------------------------------------------------------------ planning

  /** Turns a seek into a concrete half-open offset range per partition.
    *
    * Every range is clamped into `[beginning, end)` because every one of the three inputs can be outside
    * it: a user can type an offset that retention has deleted, a cursor can outlive the records it names,
    * and a timestamp can name a moment before the topic existed. Clamping is right where refusing is not —
    * "the oldest record you still have" is what the user meant — and it is done once, here, so that no
    * later arithmetic has to wonder.
    */
  private def plan(consumer: BrowseConsumer[F], request: BrowseRequest): EitherT[F, KuiError, List[Window]] =
    for {
      all <- selected(consumer, request)
      chosen = named(request, all)
      beginning <- EitherT(consumer.beginningOffsets(request.topic, chosen))
      end <- EitherT(consumer.endOffsets(request.topic, chosen))
      starts <- startOffsets(consumer, request, chosen, end)
    } yield chosen.flatMap { partition =>
      val low = beginning.getOrElse(partition, 0L)
      val high = end.getOrElse(partition, low)
      val start = clamp(starts.getOrElse(partition, low), low, high)

      request.direction match {
        // Forwards: from where the seek landed, up to the end of the log.
        case Direction.Forward => Option.when(start < high)(Window(partition, start, high))
        // Backwards: from the oldest record still held, up to — but not including — where the seek
        // landed. Half-open in the same direction as the forward case, which is what makes a cursor
        // minted by one readable by the other without an off-by-one.
        case Direction.Backward => Option.when(low < start)(Window(partition, low, start))
      }
    }

  /** A per-partition seek names its partitions by naming their offsets.
    *
    * `seekTo=0::100&seekTo=3::250` is a request about partitions 0 and 3, and reading partition 1 as well —
    * from wherever its own default happened to be — would answer a question nobody asked, with records the
    * caller cannot place. Every other seek mode applies to whatever was selected.
    */
  private def named(request: BrowseRequest, chosen: List[PartitionId]): List[PartitionId] =
    request.seek match {
      case SeekMode.AtOffsets(perPartition) => chosen.filter(perPartition.contains)
      case _ => chosen
    }

  private def selected(
      consumer: BrowseConsumer[F],
      request: BrowseRequest
  ): EitherT[F, KuiError, List[PartitionId]] =
    request.partitions match {
      case Some(chosen) => EitherT.rightT[F, KuiError](chosen.toSortedSet.toList)
      case None => EitherT(consumer.partitions(request.topic)).map(_.sortBy(_.value))
    }

  /** Where each partition starts, before clamping.
    *
    * `AtTimestamp` is the only mode that has to ask the broker, and the only one where a partition can
    * legitimately have no answer: nothing was written to it at or after that moment. Such a partition
    * starts at its end offset, so a forward browse shows nothing from it rather than showing it from the
    * beginning — which is what treating the missing answer as zero would do.
    */
  private def startOffsets(
      consumer: BrowseConsumer[F],
      request: BrowseRequest,
      chosen: List[PartitionId],
      end: Map[PartitionId, Long]
  ): EitherT[F, KuiError, Map[PartitionId, Long]] =
    request.seek match {
      case SeekMode.Beginning => EitherT.rightT[F, KuiError](Map.empty[PartitionId, Long])

      case SeekMode.Latest =>
        EitherT.rightT[F, KuiError](end)

      case SeekMode.AtOffset(offset) =>
        EitherT.rightT[F, KuiError](chosen.map(_ -> offset.value).toMap)

      case SeekMode.AtOffsets(perPartition) =>
        EitherT.rightT[F, KuiError](perPartition.map((partition, offset) => partition -> offset.value))

      case SeekMode.AtTimestamp(millis) =>
        EitherT(consumer.offsetsForTimes(request.topic, chosen, millis)).map(found =>
          chosen.map(partition => partition -> found.get(partition).flatten.getOrElse(endOf(end, partition))).toMap
        )
    }

  // ------------------------------------------------------------------------------------- forward

  /** Assign, seek, and stream what arrives until every partition has reached its window's end. */
  private def forward(
      consumer: BrowseConsumer[F],
      request: BrowseRequest,
      windows: List[Window]
  ): Stream[F, Either[KuiError, RawRecord]] =
    Stream
      .eval(assignAndSeek(consumer, request.topic, windows.map(window => window.partition -> window.low)))
      .flatMap {
        case Left(error) => Stream.emit(Left(error))
        case Right(_) => polling(consumer, windows, request.limit)
      }

  /** The poll loop, as a stream, so a record reaches the browser as it arrives rather than when the last
    * one does.
    */
  private def polling(
      consumer: BrowseConsumer[F],
      windows: List[Window],
      limit: Int
  ): Stream[F, Either[KuiError, RawRecord]] = {
    val bounds = windows.map(window => window.partition -> window.high).toMap

    Stream
      .unfoldLoopEval(Progress.empty) { progress =>
        consumer.poll(tuning.pollTimeout).map {
          case Left(error) => (List(Left(error)), None)
          case Right(polled) =>
            val next = progress.after(polled)
            val room = limit - progress.emitted
            val kept = polled.filter(inside(bounds)).take(math.max(0, room))
            val advanced = next.copy(emitted = next.emitted + kept.size)
            val done =
              advanced.emitted >= limit ||
                advanced.empties > tuning.emptyPollsBeforeEnd ||
                reachedEnd(advanced, bounds)

            (kept.map(_.asRight[KuiError]), Option.unless(done)(advanced))
        }
      }
      .flatMap(Stream.emits)
  }

  // ------------------------------------------------------------------------------------ backward

  /** The window walk.
    *
    * One round reads at most `limit` records per partition and hands them back newest-first. If the caller
    * still wants more, the next round starts where this one began and reads the window below it. A
    * partition that has reached its oldest retained record drops out; when they all have, the walk ends.
    *
    * The bound is what makes this safe on a topic of any size: the walk never reads from the beginning and
    * never keeps more than one round.
    */
  private def backward(
      consumer: BrowseConsumer[F],
      request: BrowseRequest,
      windows: List[Window]
  ): Stream[F, Either[KuiError, RawRecord]] = {
    val size = math.max(1, request.limit).toLong

    Stream
      .unfoldLoopEval(Walk(windows, 0)) { walk =>
        val split = walk.remaining.flatMap(_.newest(size))
        val round = split.map((window, _) => window)

        if round.isEmpty || walk.emitted >= request.limit then
          (List.empty[Either[KuiError, RawRecord]], Option.empty[Walk]).pure[F]
        else
          readWindows(consumer, request.topic, round).map {
            case Left(error) => (List(Left(error)), None)
            case Right(records) =>
              val room = request.limit - walk.emitted
              val newestFirst = records.sorted(using Newest).take(math.max(0, room))
              val next = Walk(split.flatMap((_, below) => below), walk.emitted + newestFirst.size)

              (newestFirst.map(_.asRight[KuiError]), Option.when(next.remaining.nonEmpty)(next))
          }
      }
      .flatMap(Stream.emits)
  }

  /** Reads one bounded window on each of several partitions, to their ends.
    *
    * Unlike the forward path this one accumulates, because the round has to be sorted before any of it can
    * be shown: a record is only "the newest" once every partition in the round has been read. The
    * accumulation is bounded by the round, which is bounded by the caller's limit.
    */
  private def readWindows(
      consumer: BrowseConsumer[F],
      topic: TopicName,
      round: List[Window]
  ): F[Either[KuiError, List[RawRecord]]] = {
    val bounds = round.map(window => window.partition -> window.high).toMap

    def drain(progress: Progress, taken: List[RawRecord]): F[Either[KuiError, List[RawRecord]]] =
      if progress.empties > tuning.emptyPollsBeforeEnd || reachedEnd(progress, bounds) then
        taken.asRight[KuiError].pure[F]
      else
        consumer.poll(tuning.pollTimeout).flatMap {
          case Left(error) => error.asLeft[List[RawRecord]].pure[F]
          case Right(polled) =>
            val kept = polled.filter(record =>
              round.exists(window => window.holds(record.partition, record.offset))
            )
            drain(progress.after(polled), taken ++ kept)
        }

    assignAndSeek(consumer, topic, round.map(window => window.partition -> window.low)).flatMap {
      case Left(error) => error.asLeft[List[RawRecord]].pure[F]
      case Right(_) => drain(Progress.empty, List.empty)
    }
  }

  // -------------------------------------------------------------------------------------- shared

  private def assignAndSeek(
      consumer: BrowseConsumer[F],
      topic: TopicName,
      positions: List[(PartitionId, Long)]
  ): F[Either[KuiError, Unit]] =
    (for {
      _ <- EitherT(consumer.assign(topic, positions.map(_._1)))
      _ <- positions.traverse((partition, offset) => EitherT(consumer.seek(topic, partition, offset)))
    } yield ()).value
}

object KafkaRecordSource {

  /** One partition's half-open offset range, `[low, high)`. */
  final case class Window(partition: PartitionId, low: Long, high: Long) {

    def holds(other: PartitionId, offset: Offset): Boolean =
      other == partition && offset.value >= low && offset.value < high

    /** Splits this window into the newest `size` records and whatever is left below them.
      *
      * The pair is the whole of the backward walk's arithmetic and the one place an off-by-one here would
      * duplicate or drop exactly one record per page: the two halves are `[from, high)` and `[low, from)`,
      * so they are adjacent, half-open in the same direction, and cover the window exactly once.
      *
      * `None` for a window with nothing in it, which is how a partition that has been walked back to its
      * oldest retained record drops out of the round.
      */
    def newest(size: Long): Option[(Window, Option[Window])] = {
      val from = math.max(low, high - size)

      Option.when(from < high)(
        (Window(partition, from, high), Option.when(low < from)(Window(partition, low, from)))
      )
    }
  }

  /** The state of a backward walk: which windows are still to be read, and how much has been emitted. */
  final private case class Walk(remaining: List[Window], emitted: Int)

  /** How far each partition has been read, and how many polls in a row have returned nothing. */
  final private case class Progress(next: Map[PartitionId, Long], empties: Int, emitted: Int) {

    def after(polled: List[RawRecord]): Progress =
      Progress(
        next = polled.foldLeft(next)((seen, record) =>
          seen.updated(
            record.partition,
            math.max(seen.getOrElse(record.partition, 0L), record.offset.value + 1L)
          )
        ),
        empties = if polled.isEmpty then empties + 1 else 0,
        emitted = emitted
      )
  }

  private object Progress {
    val empty: Progress = Progress(Map.empty, 0, 0)
  }

  /** Newest first, and by offset within a partition.
    *
    * The second half is not decoration. Records that share a timestamp are ordinary — a batch written in
    * one millisecond — and an order that used the timestamp alone would reorder a partition, which shows a
    * user a reply above the request that caused it.
    */
  private val Newest: Ordering[RawRecord] =
    Ordering
      .by[RawRecord, (Long, Int, Long)](record =>
        (record.timestamp.toEpochMilli, record.partition.value, record.offset.value)
      )
      .reverse

  private def inside(bounds: Map[PartitionId, Long])(record: RawRecord): Boolean =
    bounds.get(record.partition).exists(record.offset.value < _)

  private def reachedEnd(progress: Progress, bounds: Map[PartitionId, Long]): Boolean =
    bounds.forall((partition, high) => progress.next.getOrElse(partition, Long.MinValue) >= high)

  private def clamp(value: Long, low: Long, high: Long): Long =
    if value < low then low else if value > high then high else value

  private def endOf(end: Map[PartitionId, Long], partition: PartitionId): Long =
    end.getOrElse(partition, 0L)
}
