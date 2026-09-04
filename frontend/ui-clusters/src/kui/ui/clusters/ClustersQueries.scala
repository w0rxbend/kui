package kui.ui.clusters

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.cluster.contract.dto.*
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.kernel.{BrokerId, ClusterId}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.query.QueryCache
import kui.ui.kernel.state.{CallScope, HealthReporting}

/** This feature's server state: one cache per resource, and the only place it calls anything.
  *
  * ## Why one object holds all of them
  *
  * The dashboard, the broker list and the broker detail page all read the same resources. Given a cache each,
  * walking from the dashboard into a broker and back would refetch what was fetched a second ago; sharing one
  * per resource means a navigation costs nothing while the data is still fresh. It also means there is
  * exactly one file in the feature that issues a request, which is what makes the reporting rule below
  * checkable by reading rather than by grepping.
  *
  * ## Why it is a class
  *
  * The same reason `ClustersState` is (PLAN §21): a global cache is shared by every instance of the feature
  * and outlives all of them, so two tabs would fight over one list and a test would inherit the previous
  * test's rows. `ClustersFeature` creates exactly one and hands it to the pages.
  *
  * ## The cadence, and why nothing here polls
  *
  * Every cache is 30 seconds stale-after, which is the server's own snapshot cadence (`ARCHITECTURE.md` §9):
  * asking more often returns the same bytes. Nothing in this file retries, backs off or polls. `QueryCache`'s
  * five-second negative TTL is left at its default everywhere, which is short enough that a recovered service
  * is picked up on the next interaction and long enough that a failing endpoint is not hit by every component
  * on the page at once.
  */
final class ClustersQueries(api: ApiClient) {

  /** Every configured cluster. One key, so the bound is nominal. */
  val clusters: QueryCache[Unit, ClusterOverviewDto] =
    QueryCache.make(_ => call(ClustersApi.clusters, ()), staleAfter = ClustersQueries.Cadence, maxEntries = 4)

  /** One cluster's brokers, one entry per cluster visited in this session. */
  val brokers: QueryCache[ClusterId, BrokersResponse] =
    QueryCache.make(
      cluster => call(ClustersApi.brokers, cluster),
      staleAfter = ClustersQueries.Cadence,
      maxEntries = 8
    )

  /** One broker's settings. The `docs` flag is part of the key, because the configs tab asks for
    * documentation and nothing else does, and the two answers are different sizes.
    */
  val brokerConfigs: QueryCache[(ClusterId, BrokerId, Boolean), BrokerConfigsResponse] =
    QueryCache.make(
      key => call(ClustersApi.brokerConfigs, key),
      staleAfter = ClustersQueries.Cadence,
      maxEntries = 32
    )

  /** Log directories, for one broker or — with `None` — for every broker in the cluster. */
  val logDirs: QueryCache[(ClusterId, Option[BrokerId]), LogDirsResponse] =
    QueryCache.make(
      key => call(ClustersApi.logDirs, key),
      staleAfter = ClustersQueries.Cadence,
      maxEntries = 32
    )

  /** Asks the server to read this cluster now, and reports the outcome.
    *
    * The answer is a 202 carrying the time the request was taken. It does not mean the snapshot is new, which
    * is why the caller has to say so rather than immediately claiming fresh data.
    */
  def requestRefresh(cluster: ClusterId): EventStream[Either[ApiError, RefreshAcceptedDto]] =
    call(ClustersApi.refresh, cluster)

  /** Drops every cached entry belonging to one cluster, so the next subscription refetches.
    *
    * Prefix invalidation rather than "invalidate everything": after refreshing cluster `A`, every cached
    * answer about `A` is suspect and every answer about `B` is still perfectly good. Clearing both would be
    * correct and would also refetch the whole application.
    */
  def invalidateCluster(cluster: ClusterId): Unit = {
    clusters.invalidate(())
    brokers.invalidate(cluster)
    brokerConfigs.invalidateWhere((id, _, _) => id == cluster)
    logDirs.invalidateWhere((id, _) => id == cluster)
  }

  /** Every request this feature makes, with its health report attached.
    *
    * `CallScope.Feature`, never `CallScope.Shell`. A failure here means this feature cannot show its data; it
    * must never be able to take the whole application away from the user (ADR-032). A *success* is still
    * evidence that the gateway is reachable, which is why the outcome is reported either way.
    */
  private def call[I, O](
      endpoint: sttp.tapir.PublicEndpoint[I, kui.contracts.ErrorEnvelope, O, Any],
      input: I
  ): EventStream[Either[ApiError, O]] =
    api.call(endpoint, input).map { outcome =>
      HealthReporting.report(CallScope.Feature, outcome)
      outcome
    }
}

object ClustersQueries {

  /** How long an answer is trusted. The server re-reads each cluster on this cadence. */
  val Cadence: FiniteDuration = 30.seconds
}
