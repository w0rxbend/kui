package kui.message.application.produce

import java.nio.charset.StandardCharsets

import cats.effect.kernel.Async
import cats.syntax.all.*

import kui.kernel.error.{ApplicationError, FieldError, KuiError}
import kui.kernel.serde.Target
import kui.kernel.{PartitionId, TopicName}
import kui.message.application.RawHeader
import kui.message.domain.ports.SerdeSource
import kui.message.domain.{ProduceRequest, ProducedAt}
import kui.security.audit.MutationKind

/** Publishing a record to a topic (MP-001, MP-002).
  *
  * ==What the caller gets back, and why it is a list==
  *
  * One [[ProducedAt]] per record that landed, in the order they were sent, each carrying the partition, the
  * offset and the timestamp the *broker* assigned. Not a count: a caller that wants the count has `.length`,
  * and a caller that wants to link straight to the record it just wrote cannot get an offset back from a
  * number. The message browser does exactly that — publish, then jump to the offset.
  *
  * `records.length` can be smaller than the `count` that was asked for, and that is a real answer rather than
  * a bug. There is no rollback: a record the broker has accepted is written, so a batch that fails halfway
  * reports what landed instead of pretending nothing did. Only a batch where *nothing* landed comes back as a
  * `Left`, because then there is a single failure worth naming.
  *
  * ==The order of the checks, which is the part that matters==
  *
  *   1. the read-only refusal, inside [[MutationGuard]], **before a producer exists** (ADR-047);
  *   2. the destination's partition count, so `partition = 7` on a four-partition topic is a validation error
  *      naming the count rather than an `UnknownTopicOrPartitionException` at send time;
  *   3. serialisation, which is terminal when it fails — bytes KUI could not encode would put a record in a
  *      topic that outlives the mistake, so unlike a *decode* failure this one is never softened;
  *   4. the write.
  *
  * ==What this deliberately does not do==
  *
  * It does not mask (ADR-023). Masking is a rule about what leaves KUI towards a person; applying it on the
  * way *in* would write the mask into the operator's topic, where it would outlive every screen that showed
  * it. And it does not expand placeholders: `{{uuid}}`, `{{count}}` and `{{timestamp}}` are a browser feature
  * (ADR-029), so that what a user sees in the form is exactly what lands in the topic.
  */
trait ProduceUseCase[F[_]] {
  def produce(request: ProduceRequest): F[Either[KuiError, List[ProducedAt]]]
}

object ProduceUseCase {

  def make[F[_]: Async](
      producers: RecordProducers[F],
      serdes: SerdeSource[F],
      guard: MutationGuard[F]
  ): ProduceUseCase[F] =
    new ProduceUseCase[F] {

      def produce(request: ProduceRequest): F[Either[KuiError, List[ProducedAt]]] =
        guard.guard(
          cluster = request.cluster,
          kind = MutationKind.Produce,
          resource = request.topic.value,
          // The count and the partition, because "who wrote a thousand records to production at 3am"
          // is the question an audit trail exists to answer. Never the key and never the value: an
          // audit log is read by more people than the topic it describes (ADR-023).
          detail = Map(
            "count" -> request.count.toString,
            "partition" -> request.partition.fold("any")(_.value.toString),
            "tombstone" -> request.value.isEmpty.toString
          )
        ) {
          producers.forCluster(request.cluster).use {
            case Left(error) => error.asLeft[List[ProducedAt]].pure[F]
            case Right(producer) => write(producer, request)
          }
        }

      private def write(
          producer: RecordProducer[F],
          request: ProduceRequest
      ): F[Either[KuiError, List[ProducedAt]]] =
        producer.partitionCount(request.topic).flatMap {
          case Left(error) => error.asLeft[List[ProducedAt]].pure[F]
          case Right(partitions) =>
            partitionWithin(request.partition, partitions, request.topic) match {
              case Left(error) => error.asLeft[List[ProducedAt]].pure[F]
              case Right(_) => serializeAndSend(producer, request)
            }
        }

      private def serializeAndSend(
          producer: RecordProducer[F],
          request: ProduceRequest
      ): F[Either[KuiError, List[ProducedAt]]] =
        (
          serdes.serialize(
            request.cluster,
            request.topic,
            Target.Key,
            request.keySerde,
            request.keySerdeProperties,
            request.key
          ),
          serdes.serialize(
            request.cluster,
            request.topic,
            Target.Value,
            request.valueSerde,
            request.valueSerdeProperties,
            request.value
          )
        ).tupled.flatMap {
          case (Left(error), _) => error.asLeft[List[ProducedAt]].pure[F]
          case (_, Left(error)) => error.asLeft[List[ProducedAt]].pure[F]
          case (Right(key), Right(value)) =>
            val record = RawProducerRecord(
              topic = request.topic,
              partition = request.partition,
              key = key,
              value = value,
              headers = request.headers.map((name, text) =>
                RawHeader(name, Some(text.getBytes(StandardCharsets.UTF_8)))
              )
            )

            // `List.fill`, through one producer. `count` copies of one record is the "fill a topic while
            // testing" case (MP-002), and the server produces exactly what it was handed, n times.
            producer.send(List.fill(request.count)(record)).map(_.flatMap(landed))
        }

      /** What actually landed, or the single failure worth naming when nothing did. */
      private def landed(results: List[Either[KuiError, ProducedAt]]): Either[KuiError, List[ProducedAt]] = {
        val written = results.collect { case Right(at) => at }

        if written.nonEmpty then Right(written)
        else results.collectFirst { case Left(error) => error }.toLeft(Nil)
      }
    }

  /** Whether the topic has the partition the caller named.
    *
    * The count is in the message on purpose. "Partition 7 does not exist" sends an operator to look at their
    * cluster; "this topic has 4 partitions, numbered 0 to 3" sends them to look at their form, which is where
    * the mistake is.
    */
  def partitionWithin(
      partition: Option[PartitionId],
      count: Int,
      topic: TopicName
  ): Either[KuiError, Unit] =
    partition match {
      case Some(chosen) if chosen.value >= count =>
        Left(
          ApplicationError.Invalid(
            s"topic ${topic.value} has $count partitions, numbered 0 to ${count - 1}",
            List(FieldError.of("partition", s"a partition between 0 and ${count - 1}, or none at all"))
          )
        )
      case _ => Right(())
    }
}
