package kui.cluster.domain

import java.time.Instant

import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** How the metadata store itself is doing, as the cluster domain sees it.
  *
  * The domain needs this because the capability report must distinguish "KUI is serving you cluster
  * definitions it replayed an hour ago and cannot currently accept a change" from "everything is fine". It is
  * deliberately *not* the store's own richer health type from `libs/config`: rule A1 forbids the domain
  * seeing it, and this is the subset the domain reasons about.
  */
enum StoreHealth {

  /** Replayed, following the tail, writes accepted. */
  case Online

  /** Last known state is being served and writes are rejected. `since` drives the "how long" the UI shows;
    * `reason` is display text.
    */
  case Degraded(reason: String, since: Instant)

  /** No store is configured at all — the file adapter, or nothing. Writes report `NotConfigured`. This is not
    * a health verdict and must never render as broken.
    */
  case NotConfigured

  def isDegraded: Boolean = this match {
    case Degraded(_, _) => true
    case Online | NotConfigured => false
  }
}

object StoreHealth {
  given CanEqual[StoreHealth, StoreHealth] = CanEqual.derived
}

/** The cluster profiles KUI has been told to remember, and the one write M1 ships.
  *
  * The domain does not know that this is a compacted Kafka topic, that records are envelope-encrypted, or
  * that the version is an offset — it knows only that a profile has a version, that a stale version loses,
  * and that a write may be refused because there is nowhere to write to.
  */
trait ClusterConfigStore[F[_]] {

  /** Every profile the store currently holds. A store that has replayed and found nothing returns an empty
    * list, which is a normal first start and not an error.
    */
  def list: F[Either[KuiError, List[ClusterProfile]]]

  def get(id: ClusterId): F[Either[KuiError, Option[ClusterProfile]]]

  /** Writes a profile, refusing when `expected` is not the version currently stored.
    *
    * Returns the stored profile with its **new** version on success — the caller needs it, and returning
    * `Unit` would force a read-back at every call site.
    *
    * Failure modes:
    *   - `ApplicationError.Conflict` with `ErrorCode.ConfigVersionConflict` — another writer got there first.
    *     The loser of a two-replica race sees exactly this.
    *   - `ApplicationError.Unsupported` — the file adapter is in use and there is nowhere to write.
    *   - `InfrastructureError.*` — the store cluster is unreachable. Writes are *rejected*, never buffered: a
    *     queued configuration change that silently applies twenty minutes later is worse than a refusal.
    *
    * It returns only after the write is readable back from the store; the domain states that as a contract
    * here and the adapter implements it.
    */
  def put(profile: ClusterProfile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]]

  /** Removes a profile, refusing when `expected` is not the version currently stored.
    *
    * The same optimistic check as [[put]], for the same reason: an unconditional delete races with an edit,
    * and the operator who loses the race sees the cluster they were fixing disappear.
    *
    * `Right(())` for a profile that is already gone. Deletion is stated as *idempotent* because the caller is
    * a retry loop as often as it is a person: a second attempt that reports "no such cluster" would turn a
    * successful removal into an error message on the screen of the person who performed it.
    *
    * The same failure modes as [[put]]: `Conflict` for a stale version, `Unsupported` where there is no
    * store, `InfrastructureError.*` when the store cluster is unreachable. A delete is never buffered either.
    *
    * It removes the profile from the *store*. A cluster that is also written into this deployment's static
    * configuration file comes straight back on the next resolve, and the use case above says so rather than
    * pretending the removal failed.
    */
  def delete(id: ClusterId, expected: ProfileVersion): F[Either[KuiError, Unit]]

  /** Registers a handler invoked with the full resolved profile list on every store change, and returns the
    * action that deregisters it.
    *
    * Callback registration rather than a stream because a stream is a concrete type from a concrete runtime,
    * and a domain port stated over an abstract `F[_]` needs no runtime at all. `ClusterRegistry`, in the
    * application layer, turns these callbacks into the stream its subscribers want.
    *
    * The returned `F[Unit]` must be idempotent: releasing a resource twice is normal, and a deregistration
    * that fails the second time turns a shutdown into a crash.
    */
  def onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]]

  def health: F[StoreHealth]
}
