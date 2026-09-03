package kui.config.store

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptySet
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.effect.{Async, Clock, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import fs2.io.file.Files
import fs2.kafka.KafkaConsumer
import io.circe.Json
import io.circe.parser.parse
import org.apache.kafka.common.TopicPartition
import org.typelevel.log4cats.{Logger, LoggerFactory}

import kui.config.{StoreConfig, StoreKafkaConfig}
import kui.kernel.error.KuiError

/** The Kafka-backed `ConfigStore`: replay the whole log, then follow its tail forever.
  *
  * Acquiring this `Resource` **is** the bootstrap step of ADR-042 §1. A caller holding the value knows which
  * clusters exist, because nothing is handed out until replay has reached the end of the log. That ordering
  * is what lets a service gate its readiness on this resource and never report itself Ready with an empty
  * registry — "you have no clusters" and "your clusters were deleted" look identical to a user.
  *
  * The other thing this file is shaped by is the worst startup failure a service can have, which is a hang.
  * Replay takes the end offset once, before it consumes anything, so it is never chasing a moving target; and
  * the whole of it runs under a timeout that fails with `KUI-STORE-REPLAY-TIMEOUT` naming how far it got,
  * where it was going, and how long it took.
  */
object KafkaConfigStore {

  /** One record as it comes off the log: the key, the value (absent for a physical tombstone) and the offset
    * it sat at.
    */
  final case class LogRecord(key: String, value: Option[String], offset: Long)

  /** The log, as the replay and the follower see it.
    *
    * It exists so that the two things this file has to get right — that replay terminates, and that
    * cancelling it releases everything — can be tested without a broker. Driving them through a real consumer
    * would mean the only tests of the milestone's highest-risk loop needed a container, ran for seconds, and
    * could not express "the log never produces the record replay is waiting for".
    */
  private[store] trait StoreLog[F[_]] {

    /** Where the log ends, read once before anything is consumed. */
    def endOffset: F[Long]

    /** Every record from the current position onward. Does not complete while the log is open. */
    def records: Stream[F, LogRecord]
  }

  /** How many changes a subscriber may fall behind before it starts losing the oldest ones.
    *
    * A slow subscriber must never back-pressure the fold, because the fold is what every read depends on. 256
    * is more than any burst of metadata edits a person can produce and small enough to be free. A subscriber
    * that overflows it has lost changes and has to re-read from the store, so every consumer of `changes` is
    * written to be re-readable.
    */
  val ChangeBufferSize: Int = 256

  def resource[F[_]: {Async, Files, LoggerFactory}](
      config: StoreConfig,
      kafka: StoreKafkaConfig,
      crypto: FieldCrypto[F],
      clientId: String
  ): Resource[F, ConfigStore[F]] = {
    val logger = LoggerFactory[F].getLogger
    val topic = config.configTopic
    val partition = new TopicPartition(topic, 0)

    for {
      state <- Resource.eval(Ref.of[F, StoreState](StoreState.empty))
      changes <- Resource.eval(Topic[F, StoreChange])
      consumer <- StoreClients.consumer[F](kafka, clientId)
      log = kafkaLog(consumer, topic, partition)
      endOffset <- Resource.eval(prepare(log, topic, config.replayTimeout, logger))
      _ <- Resource.eval(replay(log, state, changes, crypto, topic, endOffset, config.replayTimeout, logger))
      // The follower is started under a supervisor owned by this resource, so releasing the resource
      // cancels the fiber *before* the consumer resource above it is finalized. A consumer closed
      // underneath a running poll is how an orderly shutdown turns into a stack trace.
      supervisor <- Supervisor[F]
      _ <- Resource.eval(
        supervisor.supervise(
          follow(log, state, changes, crypto, logger)
            .handleErrorWith(error =>
              logger.error(
                s"store tail follower stopped: topic=$topic reason=${error.getClass.getSimpleName}; " +
                  "reads continue from the last replayed state and writes will be rejected"
              )
            )
        )
      )
    } yield view(state, changes)
  }

  /** Assign the single partition, rewind, and find out where the log ends.
    *
    * **Assign, never subscribe**, and this is not a style choice. A consumer group would make replay wait for
    * a rebalance, would let a second replica take the partition away from the first, and would mean each
    * replica saw only part of the log — the exact opposite of "every replica replays the whole thing".
    */
  private[store] def kafkaLog[F[_]: Async](
      consumer: KafkaConsumer[F, String, Option[String]],
      topic: String,
      partition: TopicPartition
  ): StoreLog[F] =
    new StoreLog[F] {
      def endOffset: F[Long] =
        for {
          _ <- consumer.assign(topic, NonEmptySet.one(partition.partition))
          _ <- consumer.seekToBeginning
          offsets <- consumer.endOffsets(Set(partition))
        } yield offsets.getOrElse(partition, 0L)

      def records: Stream[F, LogRecord] =
        consumer.records.map(committable =>
          LogRecord(committable.record.key, committable.record.value, committable.record.offset)
        )
    }

  private def prepare[F[_]: Async](
      log: StoreLog[F],
      topic: String,
      timeout: FiniteDuration,
      logger: Logger[F]
  ): F[Long] =
    for {
      // Taken once, before a single record is consumed. Replay that chased a moving end offset would
      // never terminate on a busy topic, which is the other way a startup hangs.
      end <- log.endOffset
      _ <- logger.info(s"store replay started: topic=$topic endOffset=$end timeoutMs=${timeout.toMillis}")
    } yield end

  /** Fold the log into the state until the end offset is reached, or fail by name.
    *
    * An empty log completes immediately. Waiting for a record that will never come is precisely the hang this
    * whole design is arranged to avoid.
    */
  private[store] def replay[F[_]: Async](
      log: StoreLog[F],
      state: Ref[F, StoreState],
      changes: Topic[F, StoreChange],
      crypto: FieldCrypto[F],
      topic: String,
      endOffset: Long,
      timeout: FiniteDuration,
      logger: Logger[F]
  ): F[Unit] =
    if endOffset <= 0L then
      logger.info(s"store replay complete: topic=$topic records=0 endOffset=0 (empty log)")
    else
      for {
        started <- Clock[F].monotonic
        outcome <- log.records
          .through(applyEach(state, changes, crypto, logger))
          .takeThrough(offset => offset < endOffset - 1L)
          .compile
          .drain
          .timeout(timeout)
          .attempt
        elapsed <- Clock[F].monotonic.map(_ - started)
        current <- state.get
        _ <- outcome match {
          case Right(_) =>
            logger.info(
              s"store replay complete: topic=$topic reached=${current.lastAppliedOffset} " +
                s"endOffset=$endOffset unreadable=${current.unreadable.size} elapsedMs=${elapsed.toMillis}"
            )
          case Left(_: java.util.concurrent.TimeoutException) =>
            val error =
              StoreError.ReplayTimeout(topic, current.lastAppliedOffset, endOffset, elapsed.toMillis)
            logger.error(s"${error.code.wire}: ${error.message}") *>
              Async[F].raiseError[Unit](new StoreFailure(error))
          case Left(other) => Async[F].raiseError[Unit](other)
        }
      } yield ()

  /** The tail follower: the same fold, forever, from where replay stopped.
    *
    * It is the only writer of the state `Ref`. A write (STORE-007) produces to the topic and then waits for
    * its own record to come back around through this loop, which is what makes read-your-writes a real read
    * of the log rather than a hopeful local mutation.
    */
  private[store] def follow[F[_]: Async](
      log: StoreLog[F],
      state: Ref[F, StoreState],
      changes: Topic[F, StoreChange],
      crypto: FieldCrypto[F],
      logger: Logger[F]
  ): F[Unit] =
    log.records.through(applyEach(state, changes, crypto, logger)).compile.drain

  /** Decodes, decrypts and folds one record, publishing what changed. Yields each record's offset.
    *
    * Every failure here costs one key and no more. One record that will not decode, or that was encrypted
    * under a key that has since been rotated away, must not stop KUI from serving the other ninety-nine
    * clusters — that is exactly the "keep serving from last known state" behaviour ADR-042 §8 is about.
    */
  private def applyEach[F[_]: Async](
      state: Ref[F, StoreState],
      changes: Topic[F, StoreChange],
      crypto: FieldCrypto[F],
      logger: Logger[F]
  ): fs2.Pipe[F, LogRecord, Long] =
    _.evalMap { record =>
      val offset = record.offset
      decode(record.key, record.value, crypto).flatMap { decoded =>
        val folded = decoded match {
          case Right(store) =>
            // Uncancelable: the state update and the change publication are one observable step. A
            // cancellation between them would leave a subscriber never told about a record that reads
            // can already see.
            Async[F].uncancelable(_ =>
              state.modify(_.apply(store, offset)).flatMap {
                case StoreApplied.Accepted(change) =>
                  logger.info(
                    s"store change applied: key=${store.key.render} version=${store.version} offset=$offset"
                  ) *>
                    changes.publish1(change).void
                case ignored @ StoreApplied.Ignored(key, recordVersion, expectedVersion) =>
                  logger.warn(
                    s"store record ignored: key=${key.render} recordVersion=$recordVersion " +
                      s"expectedVersion=$expectedVersion offset=$offset"
                  ) *> Async[F].pure(ignored).void
                case _ => Async[F].unit
              }
            )
          case Left((key, reason)) =>
            Async[F].uncancelable(_ =>
              state.modify(_.markUnreadable(key, offset, reason)) *>
                logger.warn(s"store record unreadable: key=${key.render} offset=$offset reason=$reason")
            )
        }
        folded.as(offset)
      }
    }

  /** Turns one Kafka record into a decrypted `StoreRecord`, or names the key and why it could not.
    *
    * A `null` value is a physical tombstone. KUI writes logical ones — a `null` carries no timestamp and no
    * author, so it cannot answer "who deleted this cluster" — but a compacted log or a hand-edited topic will
    * contain them, so they are honoured: the key is deleted at whatever version it is now at.
    */
  private def decode[F[_]: Async](
      rawKey: String,
      rawValue: Option[String],
      crypto: FieldCrypto[F]
  ): F[Either[(StoreKey, String), StoreRecord]] =
    StoreKey.parse(rawKey) match {
      case Left(error) =>
        // A key that will not parse cannot be named, so it is reported under a placeholder rather than
        // dropped in silence.
        Async[F].pure(Left((StoreKey(StoreSection.Other("unparseable"), "key"), error.message)))
      case Right(key) =>
        rawValue match {
          case None =>
            Async[F].pure(Right(StoreRecord.tombstone(key, 0L, "compaction", java.time.Instant.EPOCH)))
          case Some(text) =>
            parse(text) match {
              case Left(failure) => Async[F].pure(Left((key, s"it is not valid JSON (${failure.message})")))
              case Right(json) =>
                StoreRecord.fromJsonWithKey(rawKey, json) match {
                  case Left(error) => Async[F].pure(Left((key, error.message)))
                  case Right(record) =>
                    crypto.decryptPayload(key, record.payload).map {
                      case Right(payload) => Right(record.copy(payload = payload))
                      case Left(error) => Left((key, error.message))
                    }
                }
            }
        }
    }

  /** The read-only `ConfigStore` view over the replayed state.
    *
    * `put` and `delete` are wired in STORE-007. Until then they refuse by name rather than half-working,
    * which is what keeps every task ending on a green build without shipping a broken write.
    */
  private def view[F[_]: Async](
      state: Ref[F, StoreState],
      changeTopic: Topic[F, StoreChange]
  ): ConfigStore[F] =
    new ConfigStore[F] {
      def get(key: StoreKey): F[Option[StoreRecord]] = state.get.map(_.get(key))

      def list(section: StoreSection): F[List[StoreRecord]] = state.get.map(_.list(section))

      def put(
          key: StoreKey,
          payload: Json,
          baseVersion: Option[Long],
          updatedBy: String
      ): F[Either[KuiError, StoreRecord]] = Async[F].pure(Left(writesNotWiredYet))

      def delete(key: StoreKey, baseVersion: Long, updatedBy: String): F[Either[KuiError, Unit]] =
        Async[F].pure(Left(writesNotWiredYet))

      def changes: Stream[F, StoreChange] = changeTopic.subscribe(ChangeBufferSize)

      def health: F[StoreHealth] =
        state.get.map(current => StoreHealth.Healthy(current.lastAppliedOffset, java.time.Instant.EPOCH))
    }

  private val writesNotWiredYet: KuiError =
    kui.kernel.error.ApplicationError.Remote(
      kui.kernel.error.ErrorCode.StoreNotConfigured,
      "the store's write path is not wired yet (STORE-007)",
      Nil
    )
}
