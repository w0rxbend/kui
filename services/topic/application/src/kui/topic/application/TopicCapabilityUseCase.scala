package kui.topic.application

import java.time.Instant

import cats.effect.kernel.Concurrent
import cats.syntax.all.*

import kui.cache.{Snapshot, SnapshotStatus}
import kui.kernel.ClusterId
import kui.topic.domain.{ClusterProfiles, TopicSnapshot}

/** What the topic service can currently do, per cluster, for the gateway's capability registry (ADR-039).
  *
  * Per **cluster**, not per service. A Kafka cluster the topic service cannot reach is a section of a screen
  * that cannot render, not a feature that should disappear from the sidebar: dimming the whole Topics entry
  * because one of four clusters is down would hide three working screens (DEVPLAN §10 D11). The *service's*
  * own capability — whether `kui-topic-service` is answering at all — is the gateway's readiness poller's
  * business and is not computed here.
  */
enum TopicCapability {

  /** The last scrape succeeded. */
  case Available(scrapedAt: Instant)

  /** Scrapes are failing and a previous snapshot is still being served, greyed and timestamped.
    *
    * @param since
    *   **sticky**: when this run of failures began, not when the latest attempt failed. An operator's
    *   question is "how long has this been broken", and a field that moved with every retry would answer "a
    *   second ago" during an outage that started at breakfast. It comes from `SnapshotStatus.Offline`, which
    *   is sticky for the same reason, rather than being recomputed here
    */
  case Degraded(reason: String, since: Instant, lastScrapedAt: Option[Instant])

  /** Nothing has ever been scraped for this cluster, so there is nothing to show at all. */
  case Unavailable(reason: String, since: Instant)
}

object TopicCapability {
  given CanEqual[TopicCapability, TopicCapability] = CanEqual.derived
}

trait TopicCapabilityUseCase[F[_]] {

  /** One entry per configured cluster, in the order the profiles report them. Never fails and never partly
    * fails: a cluster that cannot be read contributes a `Degraded` or `Unavailable` entry rather than
    * removing itself from the answer.
    */
  def report: F[List[(ClusterId, TopicCapability)]]
}

object TopicCapabilityUseCase {

  def make[F[_]: Concurrent](
      profiles: ClusterProfiles[F],
      snapshots: TopicSnapshots[F]
  ): TopicCapabilityUseCase[F] =
    new TopicCapabilityUseCase[F] {

      def report: F[List[(ClusterId, TopicCapability)]] =
        profiles.all.flatMap(_.traverse(ref => capabilityOf(ref.id).map(ref.id -> _)))

      private def capabilityOf(cluster: ClusterId): F[TopicCapability] =
        snapshots.of(cluster).flatMap {
          case None => notConfigured.pure[F]
          case Some(cell) => cell.get.map(fold)
        }

      private val notConfigured: TopicCapability =
        TopicCapability.Unavailable("no snapshot has been started for this cluster", Instant.EPOCH)
    }

  /** The fold, as a total function over the snapshot's four meaningful states.
    *
    * Pure and public so that the table is asserted directly rather than through four effectful scenarios —
    * the same reason `ClusterTopologyUseCase.freshnessOf` is, one service over. The row that matters is the
    * second: a failing scrape with data still in hand is `Degraded`, which is what keeps a screen rendering,
    * greyed and timestamped, while a cluster is down.
    */
  def fold(snapshot: Snapshot[TopicSnapshot]): TopicCapability =
    (snapshot.value, snapshot.status, snapshot.scrapedAt) match {
      case (Some(_), SnapshotStatus.Offline(error, since), at) =>
        TopicCapability.Degraded(error.message, since, at)
      case (_, SnapshotStatus.Offline(error, since), _) => TopicCapability.Unavailable(error.message, since)
      case (Some(_), _, Some(at)) => TopicCapability.Available(at)
      case _ => TopicCapability.Unavailable("the first scrape has not finished yet", Instant.EPOCH)
    }
}
