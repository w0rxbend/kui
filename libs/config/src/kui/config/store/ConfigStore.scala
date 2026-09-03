package kui.config.store

import java.time.Instant

import cats.effect.Async
import fs2.Stream
import io.circe.Json

import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** KUI's own metadata — registered clusters, settings, roles, masking policies — as a port.
  *
  * **Reads do not fail.** Every implementation serves reads from an in-memory map that was filled before the
  * store was handed to anyone: the file adapter reads its directory inside its `Resource`, and the Kafka
  * adapter replays its log inside its `Resource` (ADR-042 §1's bootstrap order). So a read returning
  * `F[Option[...]]` rather than `F[Either[KuiError, Option[...]]]` is not optimism, it is that bootstrap
  * ordering expressed as a type. If the store is unreachable at startup the service does not start; if it
  * becomes unreachable later, reads keep working from the last replayed state (ADR-042 §8). What degrades is
  * `health`, and what fails is a write.
  *
  * **Payloads are plaintext.** A caller hands over and receives ordinary JSON, in which the `$secret` markers
  * of `SecretJson` say which strings are sensitive. The adapter encrypts and decrypts around them, and no
  * caller ever sees an `$enc` node.
  */
trait ConfigStore[F[_]] {

  def get(key: StoreKey): F[Option[StoreRecord]]

  /** Every live record in a section, in key order. Tombstoned keys are absent. */
  def list(section: StoreSection): F[List[StoreRecord]]

  /** Creates or replaces a record.
    *
    * `baseVersion` is `None` for "this key must not exist yet" and `Some(v)` for "the record I read was at
    * version v". Either way a lost race is `KUI-CONFIG-VERSION-CONFLICT`. The returned record carries the
    * version that was actually written, and — this is ADR-042 §3's read-your-writes contract — it is returned
    * only once the write has been read back from the log, so a caller that immediately calls `get` sees at
    * least this version.
    */
  def put(
      key: StoreKey,
      payload: Json,
      baseVersion: Option[Long],
      updatedBy: String
  ): F[Either[KuiError, StoreRecord]]

  /** Writes a tombstone. The same versioning rules apply.
    *
    * Deleting an absent key is a success, not an error: the caller's intent — "this key must not be there" —
    * already holds, and no caller can act on the difference. Idempotent deletes are what make a retry loop or
    * a GitOps reconciliation trivial to write.
    */
  def delete(key: StoreKey, baseVersion: Long, updatedBy: String): F[Either[KuiError, Unit]]

  /** Every change this process has applied, whoever wrote it — this process's own writes and another
    * replica's alike.
    *
    * Hot: it does not replay history, and it does not complete while the store is open. A slow consumer is
    * dropped rather than allowed to stall replay; STORE-008 fixes the buffer and its overflow policy.
    */
  def changes: Stream[F, StoreChange]

  def health: F[StoreHealth]
}

object ConfigStore {

  /** The error every read-only store returns from a write.
    *
    * 501 and its own code, rather than 403 or 405, because it is a statement about the deployment and not
    * about the caller or the resource: nothing the caller does differently will make the write succeed, and
    * the UI renders it as `NotConfigured` (ADR-032) keyed off exactly this code.
    */
  val notConfigured: KuiError =
    ApplicationError.Remote(
      ErrorCode.StoreNotConfigured,
      "no metadata store is configured, so this change cannot be persisted; set kui.store.kafka.* to enable it",
      Nil
    )

  /** A store with nothing in it that refuses every write.
    *
    * Used by tests and by the composition root when neither `kui.store.kafka.*` nor a store directory is
    * configured. It exists so that "no store" is an ordinary value of the port rather than an `Option` every
    * consumer has to remember to handle.
    */
  def empty[F[_]: Async]: ConfigStore[F] =
    readOnly(Map.empty, StoreHealth.ReadOnly("no metadata store is configured", Nil))

  /** The shared read-only implementation: a fixed map, no writes, no changes.
    *
    * Both `empty` and the file adapter are this, which is what makes the "the zero store obeys the same
    * contract" test a real assertion rather than a coincidence.
    */
  private[store] def readOnly[F[_]: Async](
      records: Map[StoreKey, StoreRecord],
      fixedHealth: StoreHealth
  ): ConfigStore[F] =
    new ConfigStore[F] {
      private val live: Map[StoreKey, StoreRecord] = records.filterNot((_, record) => record.deleted)

      def get(key: StoreKey): F[Option[StoreRecord]] = Async[F].pure(live.get(key))

      def list(section: StoreSection): F[List[StoreRecord]] =
        Async[F].pure(
          live.toList.filter((key, _) => key.section == section).sortBy((key, _) => key.render).map(_._2)
        )

      def put(
          key: StoreKey,
          payload: Json,
          baseVersion: Option[Long],
          updatedBy: String
      ): F[Either[KuiError, StoreRecord]] = Async[F].pure(Left(notConfigured))

      def delete(key: StoreKey, baseVersion: Long, updatedBy: String): F[Either[KuiError, Unit]] =
        Async[F].pure(Left(notConfigured))

      // Empty, but not terminated. A consumer written against the Kafka adapter — a `changes.foreach`
      // running for the life of the process — must behave identically here rather than falling out of
      // its loop the moment the file adapter is in use.
      def changes: Stream[F, StoreChange] = Stream.never[F]

      def health: F[StoreHealth] = Async[F].pure(fixedHealth)
    }

  /** The timestamp a store with no log reports. Kept here so the two read-only stores agree. */
  private[store] def noLogOffset: Long = -1L

  private[store] def epoch: Instant = Instant.EPOCH
}
