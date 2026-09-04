package kui.ui.topics

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.contracts.ErrorEnvelope
import kui.kernel.{ClusterId, TopicName}
import kui.topic.contract.TopicListParams
import kui.topic.contract.dto.*
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.query.QueryCache
import kui.ui.kernel.state.{CallScope, HealthReporting}

/** This feature's server state: one cache per resource, and the only place it calls anything.
  *
  * ## Why one object holds all of them
  *
  * The list screen and the detail screen read overlapping resources, and walking from the list into a topic
  * and back must not refetch what was fetched a second ago. One cache per resource, shared by both screens,
  * is what makes a navigation free while the data is still fresh. It also means there is exactly one file in
  * the feature that issues a request, which makes the reporting rule below checkable by reading rather than
  * by grepping.
  *
  * ## Why it is a class
  *
  * A global cache would be shared by every instance of the feature and outlive all of them, so two tabs would
  * fight over one list and a test would inherit the previous test's rows (PLAN §21). `TopicsFeature` creates
  * exactly one and hands it to the pages.
  *
  * ## The cadence, and why nothing here polls
  *
  * Every cache is 30 seconds stale-after, which is the topic service's own snapshot cadence: asking more
  * often returns the same bytes. Nothing here retries, backs off or polls — the snapshot's `fetchedAt` is on
  * screen and the refresh button is the user's control (DEVPLAN §10 D5).
  */
final class TopicsQueries(api: ApiClient) {

  /** One page of one cluster's topics.
    *
    * The whole `TopicListParams` is the key, not just the cluster. Every one of those parameters is a
    * *server* parameter — the search term, the mode, the internal filter, the sort and the page are all
    * applied before the page is cut — so two different parameter sets are two different answers and must not
    * share an entry. Thirty-two entries is roughly a screenful of paging and sorting in one session.
    */
  val topics: QueryCache[(ClusterId, TopicListParams), TopicsResponse] =
    QueryCache.make(
      key => call(TopicsApi.list, key),
      staleAfter = TopicsQueries.Cadence,
      maxEntries = 32
    )

  /** One topic, with the head of its partition table. */
  val topic: QueryCache[(ClusterId, TopicName), TopicDetailResponse] =
    QueryCache.make(key => call(TopicsApi.topic, key), staleAfter = TopicsQueries.Cadence, maxEntries = 16)

  /** One topic's settings.
    *
    * A cache of its own rather than a field of the detail answer, because the Settings tab is in the URL and
    * this query is not issued at all until somebody opens that tab.
    */
  val config: QueryCache[(ClusterId, TopicName), TopicConfigResponse] =
    QueryCache.make(key => call(TopicsApi.config, key), staleAfter = TopicsQueries.Cadence, maxEntries = 16)

  /** Every partition of one topic — the whole table, which the detail document only carries the head of. */
  val partitions: QueryCache[(ClusterId, TopicName), PartitionsResponse] =
    QueryCache.make(
      key => call(TopicsApi.partitions, key),
      staleAfter = TopicsQueries.Cadence,
      maxEntries = 16
    )

  /** Asks the server to re-read this cluster's topics now, and reports the outcome.
    *
    * The answer is a 202 carrying the time the request was taken. It does not mean the snapshot is new, which
    * is why the caller has to say so rather than immediately claiming fresh data.
    */
  def requestRefresh(cluster: ClusterId): EventStream[Either[ApiError, RefreshAcceptedDto]] =
    call(TopicsApi.refresh, cluster)

  /** Drops every cached entry belonging to one cluster, so the next subscription refetches.
    *
    * Prefix invalidation rather than "invalidate everything": after refreshing cluster `A`, every cached
    * answer about `A` is suspect and every answer about `B` is still perfectly good. Clearing both would be
    * correct and would also refetch the whole application.
    */
  def invalidateCluster(cluster: ClusterId): Unit = {
    topics.invalidateWhere((id, _) => id == cluster)
    topic.invalidateWhere((id, _) => id == cluster)
    config.invalidateWhere((id, _) => id == cluster)
    partitions.invalidateWhere((id, _) => id == cluster)
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

object TopicsQueries {

  /** How long an answer is trusted. The topic service re-reads each cluster on this cadence. */
  val Cadence: FiniteDuration = 30.seconds
}
