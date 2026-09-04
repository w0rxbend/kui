package kui.ui.consumers

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.consumer.contract.GroupListParams
import kui.consumer.contract.dto.{GroupDetailDto, GroupPageDto}
import kui.contracts.ErrorEnvelope
import kui.kernel.{ClusterId, GroupId}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.query.QueryCache
import kui.ui.kernel.state.{CallScope, HealthReporting}

/** This feature's server state: one cache per resource, and the only place it calls anything.
  *
  * ## Why one object holds all of them
  *
  * The list screen and the detail screen are walked between constantly — an operator finds the group that is
  * behind, opens it, goes back, opens the next one — and that walk must not refetch what was fetched a second
  * ago. One cache per resource, shared by both screens, is what makes it free while the data is still fresh.
  * It also means there is exactly one file in the feature that issues a request, which makes the reporting
  * rule below checkable by reading rather than by grepping.
  *
  * ## Why it is a class
  *
  * A global cache would be shared by every instance of the feature and outlive all of them, so two tabs would
  * fight over one list and a test would inherit the previous test's rows (PLAN §21). `ConsumersFeature`
  * creates exactly one and hands it to the pages.
  *
  * ## The cadence, and why nothing here polls
  *
  * Both caches are 30 seconds stale-after, which is the consumer service's own snapshot cadence: asking more
  * often returns the same bytes, because every number on these screens comes from one snapshot pass rather
  * than from a live describe. Nothing here retries, backs off or polls — the snapshot's `observedAt` is on
  * screen and the refresh button is the user's control (DEVPLAN §10 D5).
  */
final class ConsumersQueries(api: ApiClient) {

  /** One page of one cluster's groups.
    *
    * The whole `GroupListParams` is the key, not just the cluster: the states, the search term, the sort and
    * the page are all applied by the server before the page is cut, so two different parameter sets are two
    * different answers and must not share an entry.
    */
  val groups: QueryCache[(ClusterId, GroupListParams), GroupPageDto] =
    QueryCache.make(
      key => call(ConsumersApi.list, key),
      staleAfter = ConsumersQueries.Cadence,
      maxEntries = 32
    )

  /** One group: its members, its assignments and its lag per partition. */
  val group: QueryCache[(ClusterId, GroupId), GroupDetailDto] =
    QueryCache.make(
      key => call(ConsumersApi.detail, key),
      staleAfter = ConsumersQueries.Cadence,
      maxEntries = 16
    )

  /** Drops every cached entry belonging to one cluster, so the next subscription refetches.
    *
    * Prefix invalidation rather than "invalidate everything": after refreshing cluster `A`, every cached
    * answer about `A` is suspect and every answer about `B` is still perfectly good. Clearing both would be
    * correct and would also refetch the whole application.
    */
  def invalidateCluster(cluster: ClusterId): Unit = {
    groups.invalidateWhere((id, _) => id == cluster)
    group.invalidateWhere((id, _) => id == cluster)
  }

  /** Every request this feature makes, with its health report attached.
    *
    * `CallScope.Feature`, never `CallScope.Shell`. A failure here means this feature cannot show its data; it
    * must never be able to take the whole application away from the user — that is the difference between a
    * dimmed sidebar entry and a full-screen "cannot reach the gateway" (ADR-032). A *success* is still
    * evidence that the gateway is reachable, which is why the outcome is reported either way.
    */
  private def call[I, O](
      endpoint: sttp.tapir.PublicEndpoint[I, ErrorEnvelope, O, Any],
      input: I
  ): EventStream[Either[ApiError, O]] =
    api.call(endpoint, input).map { outcome =>
      HealthReporting.report(CallScope.Feature, outcome)
      outcome
    }
}

object ConsumersQueries {

  /** How long an answer is trusted. The consumer service re-cuts its group snapshot on this cadence. */
  val Cadence: FiniteDuration = 30.seconds
}
