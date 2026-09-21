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
  *   how many polls may return nothing before a bounded browse concludes it has read everything there is. It
  *   is not zero because the first poll after an assignment routinely returns nothing while the consumer
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
  * polled. A backward browse — which Kafka cannot do, because a consumer only ever moves forward — walks each
  * partition in one-offset *windows*, reads those windows forwards, and sorts the bounded result
  * newest-first. Only while the aggregate raw scan budget has room does it drop the windows down by one.
  * Neither path ever holds more than the request's raw budget in memory, whatever the size of the topic or
  * its partition count.
  *
  * **Cancellation reaches the consumer.** The consumer is a `Resource`, opened by [[open]] inside the stream,
  * so fs2 closes it when the stream completes *or is cancelled*. The poll loop is a sequence of short polls
  * rather than one long one precisely so that a cancellation lands promptly between two of them: a browser
  * tab that goes away closes a Kafka consumer within one `pollTimeout`.
  *
  * @param open
  *   a consumer for one cluster at one isolation level. It returns `Either` because a cluster nobody
  *   configured, or one whose credentials are wrong, is a failure the browse reports as an event rather than
  *   as a raised exception.
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
                case Direction.Forward => forward(consumer, request, windows, budget)
                case Direction.Backward => backward(consumer, request, windows, budget)
              }
          }
      }
      // The budget's deadline, applied to the whole read. It is the last line of defence rather than the
      // first: the application normally cancels this stream after delivering `limit` matches, and a browse
      // that hits this one has been scanning without matching, which is exactly when a user needs it to stop
      // by itself.
      //
      // A tail is exempt, and it is the one read that has to be. Its whole purpose is to still be open in
      // ten minutes' time; a sixty-second deadline would close it just as the user stopped watching it, and
      // the screen would show a stream that had ended for no reason anybody could name. What ends a tail is
      // the user — the Stop button, or the tab closing — and that arrives as a cancellation, which closes
      // the consumer through the same `Resource` this deadline would have.
      .through(stream => if request.live then stream else stream.interruptAfter(budget.deadline))

  /** The offset every assigned partition actually starts reading from, per [[RecordSource.assignedStarts]] —
    * the same arithmetic [[plan]] uses to build a window's low bound, without needing a poll loop to answer
    * it.
    */
  def assignedStarts(request: BrowseRequest): F[Either[KuiError, Map[PartitionId, Offset]]] =
    open(request.cluster, request.isolation).use {
      case Left(error) => error.asLeft[Map[PartitionId, Offset]].pure[F]
      case Right(consumer) =>
        resolveStarts(consumer, request).value
          .map(_.map(starts => starts.map((partition, offset) => partition -> Offset.unsafe(offset))))
    }

  // ------------------------------------------------------------------------------------ planning

  /** Every partition this request is assigned, with the low and high broker offsets and the offset the seek
    * resolves to for each — the three numbers both [[plan]]'s windows and [[assignedStarts]]'s boundaries are
    * built from, fetched once so neither has to ask the broker twice for the same answer.
    */
  private def resolvePlan(consumer: BrowseConsumer[F], request: BrowseRequest): EitherT[F, KuiError, Plan] =
    for {
      all <- selected(consumer, request)
      chosen = named(request, all)
      beginning <- EitherT(consumer.beginningOffsets(request.topic, chosen))
      end <- EitherT(consumer.endOffsets(request.topic, chosen))
      starts <- startOffsets(consumer, request, chosen, end)
    } yield Plan(chosen, beginning, end, starts)

  /** Turns a seek into a concrete half-open offset range per partition.
    *
    * Every range is clamped into `[beginning, end)` because every one of the three inputs can be outside it:
    * a user can type an offset that retention has deleted, a cursor can outlive the records it names, and a
    * timestamp can name a moment before the topic existed. Clamping is right where refusing is not — "the
    * oldest record you still have" is what the user meant — and it is done once, here, so that no later
    * arithmetic has to wonder.
    */
  private def plan(consumer: BrowseConsumer[F], request: BrowseRequest): EitherT[F, KuiError, List[Window]] =
    resolvePlan(consumer, request).map { resolved =>
      resolved.chosen.flatMap { partition =>
        val (low, high, start) = bounds(resolved, partition)

        request.direction match {
          // A tail has no upper bound, and that is the difference between it and every other read. An
          // ordinary forward browse stops at `high`, the end of the log as it stood when the browse was
          // planned. A tail wants precisely the records written *after* that moment, so its window runs to
          // infinity — and it is emitted even when `start == high`, which is the normal case for a tail
          // started from `Latest` on a quiet topic. Dropping that window is what made the Follow control
          // deliver an immediately-empty stream.
          case _ if request.live => Some(Window(partition, start, Long.MaxValue))
          // Forwards: from where the seek landed, up to the end of the log.
          case Direction.Forward => Option.when(start < high)(Window(partition, start, high))
          // Backwards: from the oldest record still held, up to — but not including — where the seek
          // landed. Half-open in the same direction as the forward case, which is what makes a cursor
          // minted by one readable by the other without an off-by-one.
          case Direction.Backward => Option.when(low < start)(Window(partition, low, start))
        }
      }
    }

  /** Every assigned partition's clamped starting offset — the boundary `plan` would seek it to, whichever
    * side of the read that partition ends up on.
    */
  private def resolveStarts(
      consumer: BrowseConsumer[F],
      request: BrowseRequest
  ): EitherT[F, KuiError, Map[PartitionId, Long]] =
    resolvePlan(consumer, request).map(resolved =>
      resolved.chosen.map(partition => partition -> bounds(resolved, partition)._3).toMap
    )

  private def bounds(resolved: Plan, partition: PartitionId): (Long, Long, Long) = {
    val low = resolved.beginning.getOrElse(partition, 0L)
    val high = resolved.end.getOrElse(partition, low)
    (low, high, clamp(resolved.starts.getOrElse(partition, low), low, high))
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
    * legitimately have no answer: nothing was written to it at or after that moment. Such a partition starts
    * at its end offset, so a forward browse shows nothing from it rather than showing it from the beginning —
    * which is what treating the missing answer as zero would do.
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
          chosen
            .map(partition => partition -> found.get(partition).flatten.getOrElse(endOf(end, partition)))
            .toMap
        )
    }

  // ------------------------------------------------------------------------------------- forward

  /** Assign, seek, and stream what arrives until every partition has reached its window's end. */
  private def forward(
      consumer: BrowseConsumer[F],
      request: BrowseRequest,
      windows: List[Window],
      budget: PollBudget
  ): Stream[F, Either[KuiError, RawRecord]] =
    Stream
      .eval(assignAndSeek(consumer, request.topic, windows.map(window => window.partition -> window.low)))
      .flatMap {
        case Left(error) => Stream.emit(Left(error))
        case Right(_) => polling(consumer, windows, budget, request.live)
      }

  /** The poll loop, as a stream, so a record reaches the browser as it arrives rather than when the last one
    * does.
    *
    * @param live
    *   a tail. Every one of the reasons an ordinary browse stops is a reason a tail must not: it spent its
    *   raw scan budget (a tail has no total, only a rate), several polls in a row came back empty (a quiet
    *   topic is the ordinary state of a tail, not the end of one), and every partition reached its window's
    *   end (a tail's window has no end). So a tail stops for exactly one reason — the caller went away — and
    *   that arrives as cancellation rather than as a decision made here.
    */
  private def polling(
      consumer: BrowseConsumer[F],
      windows: List[Window],
      budget: PollBudget,
      live: Boolean
  ): Stream[F, Either[KuiError, RawRecord]] = {
    val bounds = windows.map(window => window.partition -> window.high).toMap

    Stream
      .unfoldLoopEval(Progress.empty) { progress =>
        consumer.poll(tuning.pollTimeout).map {
          case Left(error) => (List(Left(error)), None)
          case Right(polled) =>
            val next = progress.after(polled)
            val candidates = polled.filter(inside(bounds))
            val selected =
              if live then Selected(candidates, bytes = 0L)
              else
                within(
                  candidates,
                  recordsLeft = budget.recordsLeft - progress.emitted,
                  bytesLeft = budget.bytesLeft - progress.bytes
                )
            val advanced = next.copy(
              emitted = next.emitted + selected.records.size,
              bytes = addBytes(next.bytes, selected.bytes)
            )
            val done =
              !live && (
                exhausted(advanced.emitted, advanced.bytes, budget) ||
                  advanced.empties > tuning.emptyPollsBeforeEnd ||
                  reachedEnd(advanced, bounds)
              )

            (selected.records.map(_.asRight[KuiError]), Option.unless(done)(advanced))
        }
      }
      .flatMap(Stream.emits)
  }

  // ------------------------------------------------------------------------------------ backward

  /** The window walk.
    *
    * One round reads a one-offset window per active partition. One offset is deliberate: if an aggregate
    * budget ends a round after partition 3, partitions 4 onward remain untouched, and every partition that
    * did contribute completed its whole window. A continuation can therefore resume every partition at its
    * emitted or untouched boundary without skipping the newer half of a partly-read window.
    *
    * A chunk accumulates at most `limit` candidates per active partition (or the smaller aggregate budget),
    * then sorts and emits them together. Keeping the original candidate depth preserves deterministic
    * newest-first ordering when partitions' timestamps differ; chunking lets an ordinary page cancel the
    * source as soon as it fills, while a selective filter can pull another chunk and keep scanning toward the
    * aggregate budget.
    */
  private def backward(
      consumer: BrowseConsumer[F],
      request: BrowseRequest,
      windows: List[Window],
      budget: PollBudget
  ): Stream[F, Either[KuiError, RawRecord]] = {
    def fill(walk: Walk, target: Int): F[Either[KuiError, (Walk, Selected)]] =
      Temporal[F].tailRecM(Fill.from(walk)) { filling =>
        if filling.remaining.isEmpty ||
          exhausted(filling.emitted, filling.bytes, budget) ||
          filling.chunkRecords >= target
        then
          Right(
            Right(
              Walk(
                filling.remaining ++ filling.deferredReversed.reverse,
                filling.emitted,
                filling.bytes
              ) ->
                Selected(filling.reversed.reverse, filling.chunkBytes)
            )
          ).pure[F]
        else {
          val room = target - filling.chunkRecords
          val (chosen, waiting) = filling.remaining.splitAt(room)
          val split = chosen.flatMap(_.newest(size = 1L))
          val round = split.map((window, _) => window)

          if round.isEmpty then Left(filling.copy(remaining = waiting)).pure[F]
          else
            readWindows(
              consumer,
              request.topic,
              round,
              recordsLeft = room,
              bytesLeft = budget.bytesLeft - filling.bytes
            ).map {
              case Left(error) => Right(Left(error))
              case Right(selected) =>
                val depths = split.foldLeft(filling.depths)((seen, pair) =>
                  seen.updated(pair._1.partition, seen.getOrElse(pair._1.partition, 0) + 1)
                )
                val (continuing, deferred) = split
                  .flatMap((_, below) => below)
                  .partition(window => depths.getOrElse(window.partition, 0) < request.limit)

                Left(
                  Fill(
                    // Partitions not chosen for this depth stay ahead of the partitions that just moved
                    // down one. That is the round-robin boundary which prevents a small aggregate budget
                    // from starving the tail of a high-partition assignment.
                    remaining = waiting ++ continuing,
                    deferredReversed = deferred.reverse ::: filling.deferredReversed,
                    depths = depths,
                    emitted = filling.emitted + selected.records.size,
                    bytes = addBytes(filling.bytes, selected.bytes),
                    reversed = selected.records.reverse ::: filling.reversed,
                    chunkRecords = filling.chunkRecords + selected.records.size,
                    chunkBytes = addBytes(filling.chunkBytes, selected.bytes)
                  )
                )
            }
        }
      }

    Stream
      .unfoldLoopEval(Walk(windows, emitted = 0, bytes = 0L)) { walk =>
        if walk.remaining.isEmpty || exhausted(walk.emitted, walk.bytes, budget) then
          (List.empty[Either[KuiError, RawRecord]], Option.empty[Walk]).pure[F]
        else {
          val recordsRemaining = budget.recordsLeft - walk.emitted
          val target = aggregateChunkSize(request.limit, walk.remaining.size, recordsRemaining)

          fill(walk, target).map {
            case Left(error) => (List(Left(error)), None)
            case Right((next, selected)) =>
              val newestFirst = selected.records.sorted(using Newest).map(_.asRight[KuiError])
              val more = Option.when(
                next.remaining.nonEmpty && !exhausted(next.emitted, next.bytes, budget)
              )(next)
              (newestFirst, more)
          }
        }
      }
      .flatMap(Stream.emits)
  }

  /** Reads one bounded window on each of several partitions, to their ends.
    *
    * The one-offset windows drain one partition at a time. A completed partition is therefore unassigned
    * before it can feed records above its window while another partition is still catching up, and the
    * aggregate record and byte allowance can stop between two complete windows without creating a cursor gap.
    * Accumulation prepends into a reversed list, so the cost stays linear in the bounded result.
    */
  private def readWindows(
      consumer: BrowseConsumer[F],
      topic: TopicName,
      round: List[Window],
      recordsLeft: Int,
      bytesLeft: Long
  ): F[Either[KuiError, Selected]] = {
    def readWindow(window: Window, recordRoom: Int, byteRoom: Long): F[Either[KuiError, Selected]] = {
      val bounds = Map(window.partition -> window.high)

      def drain(progress: Progress, reversed: List[RawRecord], bytes: Long): F[Either[KuiError, Selected]] =
        if progress.empties > tuning.emptyPollsBeforeEnd ||
          reachedEnd(progress, bounds) ||
          reversed.size >= recordRoom ||
          bytes >= byteRoom
        then Selected(reversed.reverse, bytes).asRight[KuiError].pure[F]
        else
          consumer.poll(tuning.pollTimeout).flatMap {
            case Left(error) => error.asLeft[Selected].pure[F]
            case Right(polled) =>
              val selected = within(
                polled,
                recordsLeft = recordRoom - reversed.size,
                bytesLeft = byteRoom - bytes,
                include = record => window.holds(record.partition, record.offset)
              )
              drain(
                progress.after(polled),
                selected.records.reverse ::: reversed,
                addBytes(bytes, selected.bytes)
              )
          }

      assignAndSeek(consumer, topic, List(window.partition -> window.low)).flatMap {
        case Left(error) => error.asLeft[Selected].pure[F]
        case Right(_) => drain(Progress.empty, reversed = Nil, bytes = 0L)
      }
    }

    Temporal[F].tailRecM(WindowDrain(round, reversed = Nil, records = 0, bytes = 0L)) { draining =>
      if draining.remaining.isEmpty || draining.records >= recordsLeft || draining.bytes >= bytesLeft then
        Right(Right(Selected(draining.reversed.reverse, draining.bytes))).pure[F]
      else
        readWindow(
          draining.remaining.head,
          recordRoom = recordsLeft - draining.records,
          byteRoom = bytesLeft - draining.bytes
        ).map {
          case Left(error) => Right(Left(error))
          case Right(selected) =>
            Left(
              WindowDrain(
                remaining = draining.remaining.tail,
                reversed = selected.records.reverse ::: draining.reversed,
                records = draining.records + selected.records.size,
                bytes = addBytes(draining.bytes, selected.bytes)
              )
            )
        }
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

  /** The raw broker answers `resolvePlan` gathers once, before either `plan` or `resolveStarts` turns them
    * into the numbers each one actually needs.
    */
  final private case class Plan(
      chosen: List[PartitionId],
      beginning: Map[PartitionId, Long],
      end: Map[PartitionId, Long],
      starts: Map[PartitionId, Long]
  )

  /** One partition's half-open offset range, `[low, high)`. */
  final case class Window(partition: PartitionId, low: Long, high: Long) {

    def holds(other: PartitionId, offset: Offset): Boolean =
      other == partition && offset.value >= low && offset.value < high

    /** Splits this window into the newest `size` records and whatever is left below them.
      *
      * The pair is the whole of the backward walk's arithmetic and the one place an off-by-one here would
      * duplicate or drop exactly one record per page: the two halves are `[from, high)` and `[low, from)`, so
      * they are adjacent, half-open in the same direction, and cover the window exactly once.
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

  /** The state of a backward walk: which windows are still to be read, and how much raw input it consumed. */
  final private case class Walk(remaining: List[Window], emitted: Int, bytes: Long)

  /** A bounded backward output chunk while it is being filled one complete offset-depth at a time. */
  final private case class Fill(
      remaining: List[Window],
      deferredReversed: List[Window],
      depths: Map[PartitionId, Int],
      emitted: Int,
      bytes: Long,
      reversed: List[RawRecord],
      chunkRecords: Int,
      chunkBytes: Long
  )

  private object Fill {
    def from(walk: Walk): Fill =
      Fill(
        remaining = walk.remaining,
        deferredReversed = Nil,
        depths = Map.empty,
        emitted = walk.emitted,
        bytes = walk.bytes,
        reversed = Nil,
        chunkRecords = 0,
        chunkBytes = 0L
      )
  }

  /** Aggregate state while the one-offset windows selected for a backward round drain in order. */
  final private case class WindowDrain(
      remaining: List[Window],
      reversed: List[RawRecord],
      records: Int,
      bytes: Long
  )

  /** How far each partition has been read, how many polls returned nothing, and the raw input consumed. */
  final private case class Progress(next: Map[PartitionId, Long], empties: Int, emitted: Int, bytes: Long) {

    def after(polled: List[RawRecord]): Progress =
      Progress(
        next = polled.foldLeft(next)((seen, record) =>
          seen.updated(
            record.partition,
            math.max(seen.getOrElse(record.partition, 0L), record.offset.value + 1L)
          )
        ),
        empties = if polled.isEmpty then empties + 1 else 0,
        emitted = emitted,
        bytes = bytes
      )
  }

  private object Progress {
    val empty: Progress = Progress(Map.empty, 0, 0, 0L)
  }

  /** A raw prefix that fits the record budget and reaches (or stays below) the byte budget.
    *
    * Kafka hands a record to the consumer atomically, so a record larger than the remaining byte allowance is
    * included and exhausts the browse. This is the same saturating, one-chunk overshoot semantics as
    * [[PollBudget.consume]]; it guarantees progress past a large record while bounding an overshoot to one
    * broker record.
    */
  final private case class Selected(records: List[RawRecord], bytes: Long)

  private def within(
      records: List[RawRecord],
      recordsLeft: Int,
      bytesLeft: Long,
      include: RawRecord => Boolean = _ => true
  ): Selected = {
    @annotation.tailrec
    def loop(
        remaining: List[RawRecord],
        recordRoom: Int,
        byteRoom: Long,
        selected: List[RawRecord],
        bytes: Long
    ): Selected =
      if recordRoom <= 0 || byteRoom <= 0L then Selected(selected.reverse, bytes)
      else
        remaining match {
          case Nil => Selected(selected.reverse, bytes)
          case record :: tail if !include(record) =>
            loop(tail, recordRoom, byteRoom, selected, bytes)
          case record :: tail =>
            val size = serialisedSize(record)
            loop(tail, recordRoom - 1, byteRoom - size, record :: selected, addBytes(bytes, size))
        }

    loop(records, math.max(0, recordsLeft), math.max(0L, bytesLeft), Nil, 0L)
  }

  private def exhausted(records: Int, bytes: Long, budget: PollBudget): Boolean =
    records >= budget.recordsLeft || bytes >= budget.bytesLeft

  private def serialisedSize(record: RawRecord): Long =
    math.max(0, record.keySize).toLong +
      math.max(0, record.valueSize).toLong +
      math.max(0, record.headersSize).toLong

  private def addBytes(left: Long, right: Long): Long =
    if right >= Long.MaxValue - left then Long.MaxValue else left + right

  /** The old backward read needed `limit` candidates from every partition to preserve newest-first order.
    * Keep that depth when the aggregate record budget can afford it, but never let multiplication by a large
    * assignment escape the request-wide cap.
    */
  private def aggregateChunkSize(limit: Int, partitions: Int, recordsRemaining: Int): Int = {
    val candidates = math.max(1L, limit.toLong) * math.max(1L, partitions.toLong)
    math.max(1L, math.min(math.max(1L, recordsRemaining.toLong), candidates)).toInt
  }

  /** Newest first, and by offset within a partition.
    *
    * The second half is not decoration. Records that share a timestamp are ordinary — a batch written in one
    * millisecond — and an order that used the timestamp alone would reorder a partition, which shows a user a
    * reply above the request that caused it.
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
