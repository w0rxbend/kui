package kui.message.infrastructure

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all.*

import kui.kernel.browse.IsolationLevel
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.application.RawRecord
import kui.message.domain.TimestampType

/** An in-memory Kafka log, behind the browse port.
  *
  * It exists so that the two things worth testing about a browse can be tested at all: the arithmetic — which
  * offsets a seek lands on, how a backward walk moves its window, when a bounded read knows it has finished —
  * and the lifetime, which is that a cancelled browse closes its consumer. Neither needs a broker, and
  * neither is visible in a suite that has one.
  *
  * It polls one record at a time on purpose. A consumer that handed the whole assignment back in a single
  * poll would let a termination bug pass unnoticed: the loop would happen to have everything it needed
  * before its stopping condition was ever consulted.
  */
final class FakeBrowseConsumer(
    log: Ref[IO, Map[PartitionId, Vector[RawRecord]]],
    assigned: Ref[IO, List[PartitionId]],
    positions: Ref[IO, Map[PartitionId, Long]],
    polls: Ref[IO, Int]
) extends BrowseConsumer[IO] {

  def partitions(topic: TopicName): IO[Either[KuiError, List[PartitionId]]] =
    log.get.map(_.keys.toList.sortBy(_.value).asRight[KuiError])

  def beginningOffsets(
      topic: TopicName,
      partitions: List[PartitionId]
  ): IO[Either[KuiError, Map[PartitionId, Long]]] =
    log.get.map(current =>
      partitions
        .map(partition => partition -> current.get(partition).flatMap(_.headOption).fold(0L)(_.offset.value))
        .toMap
        .asRight[KuiError]
    )

  def endOffsets(
      topic: TopicName,
      partitions: List[PartitionId]
  ): IO[Either[KuiError, Map[PartitionId, Long]]] =
    log.get.map(current =>
      partitions
        .map(partition =>
          partition -> current.get(partition).flatMap(_.lastOption).fold(0L)(_.offset.value + 1L)
        )
        .toMap
        .asRight[KuiError]
    )

  def offsetsForTimes(
      topic: TopicName,
      partitions: List[PartitionId],
      millis: Long
  ): IO[Either[KuiError, Map[PartitionId, Option[Long]]]] =
    log.get.map(current =>
      partitions
        .map(partition =>
          partition -> current
            .getOrElse(partition, Vector.empty)
            .find(_.timestamp.toEpochMilli >= millis)
            .map(_.offset.value)
        )
        .toMap
        .asRight[KuiError]
    )

  /** Writes one more record into the log, the way a producer would while a tail is open.
    *
    * This is what makes live tailing testable without a broker: a bounded browse reads a log that was already
    * complete when it started, and a tail is defined by the records that arrive after that moment. Nothing
    * else in this fake changes; the poll loop simply finds a record at a position that had nothing at it
    * before.
    */
  def append(record: RawRecord): IO[Unit] =
    log.update(current =>
      current.updated(record.partition, current.getOrElse(record.partition, Vector.empty) :+ record)
    )

  def assign(topic: TopicName, partitions: List[PartitionId]): IO[Either[KuiError, Unit]] =
    assigned.set(partitions).as(().asRight[KuiError])

  def seek(topic: TopicName, partition: PartitionId, offset: Long): IO[Either[KuiError, Unit]] =
    positions.update(_.updated(partition, offset)).as(().asRight[KuiError])

  /** One record per poll, round-robin over the assignment; an empty list when every assigned partition has
    * been read to its end.
    */
  def poll(timeout: FiniteDuration): IO[Either[KuiError, List[RawRecord]]] =
    polls.update(_ + 1) *> (for {
      partitions <- assigned.get
      current <- positions.get
      records <- log.get
      next = partitions.collectFirst(Function.unlift { partition =>
        val at = current.getOrElse(partition, 0L)
        records.getOrElse(partition, Vector.empty).find(_.offset.value == at).map(partition -> _)
      })
      _ <- next.traverse_((partition, record) => positions.update(_.updated(partition, record.offset.value + 1L)))
    } yield next.map(_._2).toList.asRight[KuiError])

  /** How many polls this browse made, for the suite that asserts a bounded read stops. */
  val pollCount: IO[Int] = polls.get
}

object FakeBrowseConsumer {

  val Topic: TopicName = TopicName.unsafe("orders.v1")
  val Cluster: ClusterId = ClusterId.unsafe("local")

  /** A partition holding `count` records, offsets `0` upwards, one millisecond apart. */
  def partition(id: Int, count: Int, from: Long = 0L): (PartitionId, Vector[RawRecord]) =
    PartitionId.unsafe(id) -> Vector.tabulate(count)(index => record(id, from + index.toLong))

  def record(partition: Int, offset: Long): RawRecord =
    RawRecord(
      partition = PartitionId.unsafe(partition),
      offset = Offset.unsafe(offset),
      timestamp = Instant.ofEpochMilli(offset),
      timestampType = TimestampType.CreateTime,
      key = Some(s"key-$partition-$offset".getBytes("UTF-8")),
      value = Some(s"value-$partition-$offset".getBytes("UTF-8")),
      headers = Nil,
      keySize = 8,
      valueSize = 16,
      headersSize = 0
    )

  def of(log: Map[PartitionId, Vector[RawRecord]]): IO[FakeBrowseConsumer] =
    Ref.of[IO, Map[PartitionId, Vector[RawRecord]]](log).flatMap(of)

  def of(log: Ref[IO, Map[PartitionId, Vector[RawRecord]]]): IO[FakeBrowseConsumer] =
    (Ref.of[IO, List[PartitionId]](Nil), Ref.of[IO, Map[PartitionId, Long]](Map.empty), Ref.of[IO, Int](0))
      .mapN(new FakeBrowseConsumer(log, _, _, _))

  /** The consumer, as the `Resource` a browse opens — with a flag that records the close.
    *
    * The flag is the whole point of the cancellation suite: a `Resource` that is never released is a Kafka
    * consumer that is never closed, and the symptom in production is a broker running out of connections
    * hours after the tabs that opened them were shut.
    */
  def opening(
      log: Map[PartitionId, Vector[RawRecord]],
      closed: Ref[IO, Boolean]
  ): (ClusterId, IsolationLevel) => Resource[IO, Either[KuiError, BrowseConsumer[IO]]] =
    (_, _) =>
      Resource
        .make(of(log))(_ => closed.set(true))
        .map(consumer => (consumer: BrowseConsumer[IO]).asRight[KuiError])

  /** The same thing over a log the test can still write to after the browse has started.
    *
    * A live tail cannot be tested against a fixed log: the records it exists to deliver are the ones written
    * while it is open. The `Ref` is the test's handle on the log, and every consumer this opens reads through
    * it, so an append made at any point during the browse is visible to the next poll.
    */
  def openingGrowing(
      log: Ref[IO, Map[PartitionId, Vector[RawRecord]]],
      closed: Ref[IO, Boolean]
  ): (ClusterId, IsolationLevel) => Resource[IO, Either[KuiError, BrowseConsumer[IO]]] =
    (_, _) =>
      Resource
        .make(of(log))(_ => closed.set(true))
        .map(consumer => (consumer: BrowseConsumer[IO]).asRight[KuiError])
}
