package kui.ui.consumers

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.consumer.contract.GroupListParams
import kui.consumer.contract.dto.{
  DeletedOffsetsDto,
  GroupDetailDto,
  GroupsResponse,
  LagDeltaDto,
  ResetApplyRequest,
  ResetPlanDto,
  ResetPlanRequest
}
import kui.contracts.ErrorEnvelope
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{ClusterId, GroupId, TopicName}
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
  val groups: QueryCache[(ClusterId, GroupListParams), GroupsResponse] =
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

  /** The topic page's Consumers tab: the gateway's topic overview, of which this feature reads one section.
    *
    * Keyed by cluster and topic, which is what the URL is keyed by, so opening the tab on a topic that was
    * looked at a minute ago is free and opening it on a new one is one request.
    */
  val topicOverview: QueryCache[(ClusterId, TopicName), TopicOverviewDto] =
    QueryCache.make(
      key => call(ConsumersApi.topicOverview, key),
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
    topicOverview.invalidateWhere((id, _) => id == cluster)
  }

  /** One lag poll. Not a cache: the answer is a *delta* against a token, so a second subscriber reading a
    * cached one would be reading somebody else's diff and would apply it to rows it does not have.
    *
    * It is here rather than in the poller for the reason the class comment gives — this is the one file in
    * the feature that issues a request, which is what makes the health-reporting rule checkable by reading
    * rather than by grepping.
    */
  def lagSince(
      cluster: ClusterId,
      groups: Set[GroupId],
      since: Option[String]
  ): EventStream[Either[ApiError, LagDeltaDto]] =
    call(ConsumersApi.lag, (cluster, groups, since))

  /** What a reset would do. Reads; changes nothing.
    *
    * Not a cache, and it must never become one. A plan is a statement about the cluster *now* and it carries
    * a token that expires in five minutes; serving a cached one would let an operator confirm a plan computed
    * against offsets that have since moved, which is the exact failure the two-phase flow exists to prevent.
    */
  def planReset(
      cluster: ClusterId,
      group: GroupId,
      request: ResetPlanRequest
  ): EventStream[Either[ApiError, ResetPlanDto]] =
    call(ConsumersApi.planReset, (cluster, group, request))

  /** Write the offsets a plan token names, and answer with what was written.
    *
    * The group's own cached detail is dropped on success, because its committed offsets and its lag have just
    * changed and everything on screen about them is now wrong.
    */
  def applyReset(
      cluster: ClusterId,
      group: GroupId,
      token: String
  ): EventStream[Either[ApiError, ResetPlanDto]] =
    call(ConsumersApi.applyReset, (cluster, group, ResetApplyRequest(token))).map { outcome =>
      if outcome.isRight then invalidateCluster(cluster)
      outcome
    }

  /** Remove the group. The cluster's cached answers go with it, because the list it was on is now wrong.
    *
    * Invalidated on success only. Dropping the cache after a refusal would make the screen refetch and redraw
    * for no reason, and the group would still be there — which reads as the delete having half worked.
    */
  def deleteGroup(cluster: ClusterId, group: GroupId): EventStream[Either[ApiError, Unit]] =
    call(ConsumersApi.deleteGroup, (cluster, group)).map { outcome =>
      if outcome.isRight then invalidateCluster(cluster)
      outcome
    }

  /** Forget this group's committed offsets on one topic, and say which partitions were forgotten. */
  def deleteOffsets(
      cluster: ClusterId,
      group: GroupId,
      topic: TopicName
  ): EventStream[Either[ApiError, DeletedOffsetsDto]] =
    call(ConsumersApi.deleteOffsets, (cluster, group, topic)).map { outcome =>
      if outcome.isRight then invalidateCluster(cluster)
      outcome
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
