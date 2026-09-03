package kui.cluster.infrastructure.store

import java.time.Instant

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import fs2.Stream
import io.circe.Json

import kui.config.store.{ConfigStore, StoreChange, StoreHealth, StoreKey, StoreRecord, StoreSection}
import kui.kernel.error.KuiError

/** A `ConfigStore` a test can drive: records it holds, changes it pushes, and the write outcome it returns.
  *
  * It is not a working store. The store's own behaviour — replay, optimistic versioning, read-your-writes,
  * encryption — is `libs/config`'s and is tested there against a broker. What this module has to prove is
  * that its adapter reads and writes the right keys, refuses to be taken down by one bad record, and turns
  * the store's health into the three cases the domain reasons about.
  */
final class StubConfigStore(
    val records: Ref[IO, Map[StoreKey, StoreRecord]],
    val writes: Ref[IO, List[(StoreKey, Option[Long])]],
    writeOutcome: Ref[IO, Either[KuiError, Long]],
    healthRef: Ref[IO, StoreHealth],
    pushed: Queue[IO, StoreChange]
) extends ConfigStore[IO] {

  def get(key: StoreKey): IO[Option[StoreRecord]] = records.get.map(_.get(key))

  def list(section: StoreSection): IO[List[StoreRecord]] =
    records.get.map(
      _.toList.filter((key, _) => key.section == section).sortBy((key, _) => key.render).map(_._2)
    )

  def put(
      key: StoreKey,
      payload: Json,
      baseVersion: Option[Long],
      updatedBy: String
  ): IO[Either[KuiError, StoreRecord]] =
    for {
      _ <- writes.update((key, baseVersion) :: _)
      outcome <- writeOutcome.get
      result <- outcome match {
        case Left(error) => IO.pure(Left(error))
        case Right(version) =>
          val record = StoreRecord(1, key, version, StubConfigStore.At, updatedBy, deleted = false, payload)
          records.update(_ + (key -> record)).as(Right(record))
      }
    } yield result

  def delete(key: StoreKey, baseVersion: Long, updatedBy: String): IO[Either[KuiError, Unit]] =
    IO.pure(Left(ConfigStore.notConfigured))

  def changes: Stream[IO, StoreChange] = Stream.fromQueueUnterminated(pushed)

  def health: IO[StoreHealth] = healthRef.get

  // ---------------------------------------------------------------- the test's controls

  def push(change: StoreChange): IO[Unit] = pushed.offer(change)

  def hold(record: StoreRecord): IO[Unit] = records.update(_ + (record.key -> record))

  def failWritesWith(error: KuiError): IO[Unit] = writeOutcome.set(Left(error))

  def setHealth(value: StoreHealth): IO[Unit] = healthRef.set(value)
}

object StubConfigStore {

  val At: Instant = Instant.parse("2026-09-04T10:15:00Z")

  def apply(nextVersion: Long = 1L): IO[StubConfigStore] =
    for {
      records <- Ref.of[IO, Map[StoreKey, StoreRecord]](Map.empty)
      writes <- Ref.of[IO, List[(StoreKey, Option[Long])]](Nil)
      outcome <- Ref.of[IO, Either[KuiError, Long]](Right(nextVersion))
      health <- Ref.of[IO, StoreHealth](StoreHealth.Healthy(0L, At, Nil))
      queue <- Queue.unbounded[IO, StoreChange]
    } yield new StubConfigStore(records, writes, outcome, health, queue)
}
