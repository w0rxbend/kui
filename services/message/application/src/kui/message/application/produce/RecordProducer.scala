package kui.message.application.produce

import cats.effect.kernel.Resource

import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, PartitionId, TopicName}
import kui.message.application.RawHeader
import kui.message.domain.ProducedAt

/** One record on its way to a broker: bytes, and nothing that could turn back into text.
  *
  * The mirror of [[kui.message.application.RawRecord]], and deliberately the same shape. `key` and `value`
  * are `Option[Array[Byte]]` because Kafka's are nullable, and the two absences mean different things: a
  * record with no key is ordinary, and a record with no *value* is a tombstone — the record that tells a
  * compacted topic to forget a key. A form that mapped "the user left the box empty" to an empty byte array
  * would break compaction for whoever relied on it, silently, forever.
  *
  * @param partition
  *   `None` lets Kafka's own partitioner choose: hash the key, or round-robin when there is no key. Naming a
  *   partition is the unusual case and reads as one.
  */
final case class RawProducerRecord(
    topic: TopicName,
    partition: Option[PartitionId],
    key: Option[Array[Byte]],
    value: Option[Array[Byte]],
    headers: List[RawHeader]
)

/** Writing records, stated so that the use cases above never see a Kafka type.
  *
  * ==Why a batch method and not only a single one==
  *
  * Because both features that use it write more than one record. Publishing `count` copies of a form
  * submission and copying a range of records into another topic are the same operation from here — a list of
  * records through **one** producer. Producing five hundred records through five hundred producers is five
  * hundred connections, five hundred metadata fetches and five hundred chances to fail, and a port that
  * offered only `produce(one)` would make that the obvious implementation.
  *
  * ==What an implementation promises==
  *
  *   - **it never raises.** Every failure comes back as a value: a `Left` on the outside for a request that
  *     could not start at all, and a `Left` per record for one the broker refused. Exceptions are translated
  *     at the adapter boundary and nowhere above it.
  *   - **it reports per record, in input order.** A resend that copied four hundred of five hundred records
  *     has to be able to say which four hundred, so that an operator can resume from a known offset rather
  *     than from the beginning. There is no rollback and there cannot be one: a record the broker has
  *     accepted is written.
  */
trait RecordProducer[F[_]] {

  /** How many partitions the topic has, or `KUI-TOPIC-NOT-FOUND` when it has none.
    *
    * It is on this port rather than looked up separately because both callers need it *before* they write: a
    * produce naming partition 7 of a four-partition topic must be a validation error naming the count, not an
    * `UnknownTopicOrPartitionException` at send time, and a resend must discover that its destination does
    * not exist before it reads a million records it has nowhere to put.
    */
  def partitionCount(topic: TopicName): F[Either[KuiError, Int]]

  /** Writes every record through one producer, in order, and says where each one landed.
    *
    * The outer `Left` is "this request never started" — no connection, no such topic. The inner ones are per
    * record, so a partial batch is a result rather than an exception.
    */
  def send(records: List[RawProducerRecord]): F[Either[KuiError, List[Either[KuiError, ProducedAt]]]]
}

/** Where a [[RecordProducer]] for one cluster comes from.
  *
  * A `Resource`, in the same shape as the browse consumer's, and for the same reason: a producer holds a
  * connection, a buffer and a sender thread, and every one of them has to go away when the request that
  * opened it ends — including when it is cancelled. A cancelled produce releases the producer; it does not
  * un-write a record the broker has already accepted, and nothing in KUI claims otherwise.
  *
  * The `Either` is inside the `Resource` rather than a raised failure because the two things that can go
  * wrong here — a cluster this deployment does not have, and connection material the client refuses — are
  * both ordinary answers a caller renders as an error response.
  */
trait RecordProducers[F[_]] {
  def forCluster(cluster: ClusterId): Resource[F, Either[KuiError, RecordProducer[F]]]
}
