package kui.message.application

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Clock, Concurrent, Ref}
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import kui.kernel.browse.{Direction, PollBudget, SeekMode}
import kui.kernel.error.KuiError
import kui.kernel.serde.Target
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.application.cursor.{BrowseCursor, CursorCodec}
import kui.message.domain.ports.{ClusterProfileSource, SerdeSource}
import kui.message.domain.{BrowseLimits, BrowseRequest, DecodeError, DecodedRecord, FilterRef, RenderedHeader}

/** Why a browse stopped. */
enum BrowseEnd {

  /** The caller's `limit` was reached and there is more where that came from. */
  case Limit

  /** Every selected partition was read to its end. Asking again returns nothing new. */
  case Exhausted
}

object BrowseEnd {
  given CanEqual[BrowseEnd, BrowseEnd] = CanEqual.derived
}

/** One thing a browse has to say, in the vocabulary of this layer rather than of the wire.
  *
  * These are not SSE events and they deliberately do not know that SSE exists: rule A3 keeps `libs/http` and
  * the contract out of this module, and `services/message/api` is the one place that maps a [[BrowseEvent]]
  * onto a frame (ADR-033). The mapping is one `match`, and having it in one place is what lets the use case
  * be tested by reading a list.
  */
enum BrowseEvent {

  /** What the browse is doing, in words, for the status line of a stream that has not produced a record yet.
    * A browse that said nothing until its first record is indistinguishable from a hung one.
    */
  case Phase(name: String)

  case Record(record: DecodedRecord)

  /** Progress. `read` counts records taken from Kafka, `delivered` counts the ones that survived the filter;
    * the gap between them is the number that tells a user their filter is doing something.
    */
  case Consumed(bytes: Long, read: Long, delivered: Long, elapsed: FiniteDuration, budget: PollBudget)

  /** The browse ended on purpose. `cursor` is the signed continuation of ADR-026, and it is `None` whenever
    * asking again would be pointless.
    */
  case Finished(reason: BrowseEnd, cursor: Option[String])

  /** The browse ended because something broke. It carries the ordinary `KuiError`, so the layer above renders
    * it with the code it already knows rather than with a second error shape (ADR-034).
    */
  case Failed(error: KuiError)
}

object BrowseEvent {
  given CanEqual[BrowseEvent, BrowseEvent] = CanEqual.derived
}

/** Browsing a topic: resolve the cluster, read records, decode them, filter them, and account for what was
  * spent.
  *
  * ==The rule that shapes the whole file==
  *
  * **Decoding never fails a browse.** A record no serde can read is delivered through the fallback with the
  * failure attached to it, and the stream carries on (ADR-035). The quickstart seeds `audit.log.raw` with
  * deliberately non-JSON payloads for exactly this reason: a browser asked to show that topic must show its
  * bytes, not an error page, and must certainly not stop at the first line.
  *
  * Nothing here materialises a topic. Records arrive one at a time from [[RecordSource]] and leave one at a
  * time; the only state kept is a handful of counters and the first and last offset seen per partition, which
  * is what the continuation cursor is built from.
  */
trait BrowseUseCase[F[_]] {
  def browse(request: BrowseRequest, budget: PollBudget): Stream[F, BrowseEvent]

  /** The browse that continues where a finished one stopped (ADR-026).
    *
    * A browse ends by emitting a signed cursor naming, per partition, the offset the next page starts at.
    * Handing that cursor back is what "load more" is: the client does not compute the next offsets, and
    * cannot, because forward and backward boundaries are different numbers for the same place and getting
    * that arithmetic wrong duplicates or skips exactly one record per page.
    *
    * It lives on the use case rather than in the API layer because the cursor is verified and decoded by the
    * `CursorCodec` this object already holds. A route that decoded one itself would need the signing key,
    * which is precisely the thing the API layer must not have.
    *
    * `stringFilter` is *not* carried by the cursor and is taken again here. The cursor names a position, a
    * direction, a page size, the serdes and the saved filter — the things that decide which records exist in
    * the next page. A plain substring is applied to the records after they are decoded, so it can be changed
    * between pages without invalidating the position, and a client that narrows its filter while paging gets
    * what it asked for rather than a rejected cursor.
    */
  def resume(
      cluster: ClusterId,
      topic: TopicName,
      cursor: String,
      stringFilter: Option[String],
      limits: BrowseLimits
  ): F[Either[KuiError, BrowseRequest]]
}

object BrowseUseCase {

  /** How many delivered records go by between two `consumed` events.
    *
    * Often enough that a long filtered scan visibly moves, rarely enough that a fast browse does not spend
    * its bandwidth on progress reports about itself.
    */
  val ProgressEvery: Int = 25

  /** How long a minted cursor stays usable. `BrowseCursor.DefaultTtlSeconds`, as a duration. */
  val CursorTtl: FiniteDuration = FiniteDuration(BrowseCursor.DefaultTtlSeconds, "seconds")

  def make[F[_]: {Concurrent, Clock}](
      clusters: ClusterProfileSource[F],
      serdes: SerdeSource[F],
      source: RecordSource[F],
      cursors: CursorCodec[F]
  ): BrowseUseCase[F] =
    new BrowseUseCase[F] {

      def resume(
          cluster: ClusterId,
          topic: TopicName,
          cursor: String,
          stringFilter: Option[String],
          limits: BrowseLimits
      ): F[Either[KuiError, BrowseRequest]] =
        Clock[F].realTimeInstant
          .flatMap(now => cursors.decode(cursor, (cluster, topic), now))
          .map(_.flatMap(decoded => requestOf(decoded, stringFilter, limits)))

      /** The cursor, as the browse it describes.
        *
        * Every partition resumes at its own offset, which is what `AtOffsets` is for and why the seek grammar
        * keeps a per-partition form the reference product dropped: a continuation that could only express one
        * offset for every partition could not express what a cursor already means.
        *
        * The partition subset is the cursor's own key set rather than "all of them". A partition added to the
        * topic since the first page must not appear halfway through a paged read with no start position of
        * its own — it would arrive from wherever the consumer happened to land.
        */
      private def requestOf(
          cursor: BrowseCursor,
          stringFilter: Option[String],
          limits: BrowseLimits
      ): Either[KuiError, BrowseRequest] =
        BrowseRequest.of(
          cluster = cursor.cluster,
          topic = cursor.topic,
          seek = SeekMode.AtOffsets(cursor.perPartitionNext),
          direction = Some(cursor.direction),
          partitions = Some(cursor.perPartitionNext.keySet),
          limit = Some(cursor.limit),
          isolation = Some(cursor.isolation),
          keySerde = cursor.keySerde,
          valueSerde = cursor.valueSerde,
          stringFilter = stringFilter,
          filter = cursor.filterId.flatMap(id => FilterRef.of(id, None).toOption),
          // A continuation is never a tail: `live` and a start position are mutually exclusive, and a cursor
          // is nothing but a start position.
          live = false,
          limits = limits
        )

      def browse(request: BrowseRequest, budget: PollBudget): Stream[F, BrowseEvent] =
        Stream.emit(BrowseEvent.Phase(ResolvingCluster)) ++
          Stream.eval(clusters.cluster(request.cluster)).flatMap {
            case Left(error) => Stream.emit(BrowseEvent.Failed(error))
            case Right(_) => reading(request, budget)
          }

      private def reading(request: BrowseRequest, budget: PollBudget): Stream[F, BrowseEvent] =
        Stream.emit(BrowseEvent.Phase(ReadingRecords)) ++
          Stream
            .eval((Clock[F].monotonic, Ref.of[F, State](State.empty)).tupled)
            .flatMap { case (startedAt, state) =>
              records(request, budget, state) ++ ending(request, budget, state, startedAt)
            }

      /** The record events, and the progress events between them. */
      private def records(
          request: BrowseRequest,
          budget: PollBudget,
          state: Ref[F, State]
      ): Stream[F, BrowseEvent] =
        source
          .browse(request, budget)
          // The first failure ends the stream, and is kept: `ending` turns it into the terminal `error`
          // event. `takeThrough` rather than `takeWhile` because the failing element is the one carrying
          // the reason.
          .takeThrough(_.isRight)
          .evalMap {
            case Left(error) => state.update(_.copy(failure = Some(error))).as(Step.stop)
            case Right(raw) => deliver(request, budget, state, raw)
          }
          .takeThrough(_.more)
          .flatMap(step => Stream.chunk(step.events))

      /** One record: decode it, account for it, and decide whether it is shown. */
      private def deliver(
          request: BrowseRequest,
          budget: PollBudget,
          state: Ref[F, State],
          raw: RawRecord
      ): F[Step] =
        for {
          record <- decode(request, raw)
          matched = matches(request, record)
          elapsed <- Clock[F].monotonic
          next <- state.updateAndGet(_.saw(raw, matched))
        } yield {
          val progress =
            if matched && next.delivered % ProgressEvery.toLong == 0L then
              Chunk.singleton(
                BrowseEvent.Consumed(next.bytes, next.read, next.delivered, elapsed, budget)
              )
            else Chunk.empty[BrowseEvent]

          Step(
            events =
              if matched then Chunk.singleton(BrowseEvent.Record(record)) ++ progress else Chunk.empty,
            // A tail has no total. `limit` is a page size, and a page is a thing a bounded browse has; a
            // browse that is still open after an hour has delivered whatever was written in that hour and
            // is not finished. The bound on a tail is on the *screen* — `BrowseSession.MaxRows` keeps the
            // newest five hundred rows and drops the rest — because that is where an unbounded stream can
            // be bounded without deciding on the user's behalf that they have watched enough.
            more = request.live || next.delivered < request.limit.toLong
          )
        }

      /** The terminal events: either the failure that stopped the browse, or the accounting and the cursor.
        */
      private def ending(
          request: BrowseRequest,
          budget: PollBudget,
          state: Ref[F, State],
          startedAt: FiniteDuration
      ): Stream[F, BrowseEvent] =
        Stream.eval((state.get, Clock[F].monotonic).tupled).flatMap { case (finalState, now) =>
          finalState.failure match {
            case Some(error) => Stream.emit(BrowseEvent.Failed(error))
            case None =>
              val elapsed = now - startedAt
              val reason =
                if finalState.delivered >= request.limit.toLong then BrowseEnd.Limit
                else BrowseEnd.Exhausted

              Stream.emit(
                BrowseEvent.Consumed(finalState.bytes, finalState.read, finalState.delivered, elapsed, budget)
              ) ++ Stream.eval(cursorFor(request, finalState, reason)).map(BrowseEvent.Finished(reason, _))
          }
        }

      /** A continuation, but only where continuing means anything.
        *
        * A browse that reached the end of every partition has nothing to continue, and handing the browser a
        * cursor there would put a "load more" button under a screen that can only ever answer "nothing". A
        * cursor that fails to sign is dropped rather than raised: the page the user is looking at is correct,
        * and the honest consequence is a missing button, not a failed browse.
        */
      private def cursorFor(
          request: BrowseRequest,
          state: State,
          reason: BrowseEnd
      ): F[Option[String]] =
        if reason != BrowseEnd.Limit || state.delivered == 0L then Option.empty[String].pure[F]
        else
          Clock[F].realTimeInstant.flatMap { now =>
            val cursor = request.direction match {
              case Direction.Forward => BrowseCursor.afterForward(request, state.last, now, CursorTtl)
              case Direction.Backward => BrowseCursor.beforeBackward(request, state.first, now, CursorTtl)
            }

            cursors.encode(cursor).map(_.toOption)
          }

      private def decode(request: BrowseRequest, raw: RawRecord): F[DecodedRecord] =
        for {
          key <- serdes.decode(request.cluster, request.topic, Target.Key, request.keySerde, raw.key)
          value <- serdes.decode(request.cluster, request.topic, Target.Value, request.valueSerde, raw.value)
        } yield DecodedRecord(
          partition = raw.partition,
          offset = raw.offset,
          timestamp = raw.timestamp,
          timestampType = raw.timestampType,
          key = key._1,
          value = value._1,
          headers = raw.headers.map(render),
          keySize = raw.keySize,
          valueSize = raw.valueSize,
          headersSize = raw.headersSize,
          decodeErrors = List(
            key._2.map(DecodeError(Target.Key, key._1.serde, _)),
            value._2.map(DecodeError(Target.Value, value._1.serde, _))
          ).flatten
        )
    }

  /** The plain-substring filter, applied after decoding and case-insensitively.
    *
    * After decoding and not before, because a user typing `order-4711` is looking for the text they can see
    * on the screen; a search of the raw bytes would miss it on every topic whose values are not plain text,
    * which is most of them.
    */
  def matches(request: BrowseRequest, record: DecodedRecord): Boolean =
    request.stringFilter match {
      case None => true
      case Some(needle) =>
        val wanted = needle.toLowerCase
        record.key.text.toLowerCase.contains(wanted) ||
        record.value.text.toLowerCase.contains(wanted) ||
        record.headers.exists(header =>
          header.key.toLowerCase.contains(wanted) || header.value.toLowerCase.contains(wanted)
        )
    }

  /** A header's bytes as text.
    *
    * UTF-8 with replacement, never an exception: a header nobody can read is still a header worth showing
    * beside the record, and a browse that failed on one would fail on every record of a topic whose producer
    * writes a binary trace id.
    */
  def render(header: RawHeader): RenderedHeader =
    RenderedHeader(header.key, header.value.fold("")(new String(_, StandardCharsets.UTF_8)))

  val ResolvingCluster: String = "resolving the cluster"
  val ReadingRecords: String = "reading records from Kafka"

  /** One record's worth of output, and whether the browse wants another. */
  final private case class Step(events: Chunk[BrowseEvent], more: Boolean)

  private object Step {

    /** What a failure produces: no events of its own — `ending` writes the terminal one — and no appetite for
      * more.
      */
    val stop: Step = Step(Chunk.empty, more = false)
  }

  /** Everything a browse remembers, which is deliberately not the records themselves.
    *
    * `first` and `last` are the boundary offsets per partition, and they are what a continuation cursor is
    * built from: a forward browse resumes after `last`, a backward one before `first`.
    */
  final private case class State(
      read: Long,
      bytes: Long,
      delivered: Long,
      first: Map[PartitionId, Offset],
      last: Map[PartitionId, Offset],
      failure: Option[KuiError]
  ) {

    def saw(raw: RawRecord, matched: Boolean): State =
      copy(
        read = read + 1L,
        bytes = bytes + raw.keySize.toLong + raw.valueSize.toLong + raw.headersSize.toLong,
        delivered = if matched then delivered + 1L else delivered,
        first = if first.contains(raw.partition) then first else first.updated(raw.partition, raw.offset),
        last = last.updated(raw.partition, raw.offset)
      )
  }

  private object State {
    val empty: State = State(0L, 0L, 0L, Map.empty, Map.empty, None)
  }
}
