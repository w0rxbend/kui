package kui.topic.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.tapir.server.ServerEndpoint

import kui.cache.Snapshot
import kui.contracts.Section
import kui.http.principal.RbacGuard
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, TopicName}
import kui.security.PrincipalCodec
import kui.topic.application.*
import kui.topic.contract.dto.*
import kui.topic.contract.{TopicEndpoints, TopicListParams}
import kui.topic.domain.{TopicError, TopicSnapshot}

/** The five endpoints, bound to use cases.
  *
  * One rule shapes every route in this file, and it is the milestone's central promise written as code:
  *
  *   - a request that names something KUI has never heard of **fails**: an unknown cluster id is
  *     `404 KUI-CLUSTER-NOT-FOUND` and an unknown topic is `404 KUI-TOPIC-NOT-FOUND`;
  *   - a request that names something real which could not be read **succeeds**: 200, with the section that
  *     needed a broker marked `stale` or `unavailable` and carrying the reason.
  *
  * A cluster being down is a fact about one section of one answer, never a failure of the answer. The
  * alternative — a 5xx — would take the whole topic page down because a broker is slow, and would leave the
  * browser with nothing to render and nothing to say about why.
  *
  * ==This layer never builds a page==
  *
  * `Page` arrives from `ListTopics` already filtered, sorted and cut, and `TopicMapping.page` renames its
  * fields. Nothing here slices, sorts or counts. That is not style: the reference product this one is
  * modelled on computes its page count before applying its internal-topic filter, so the count disagrees with
  * the rows whenever the filter removes anything, and the only fix that stays fixed is for the arithmetic to
  * happen once.
  *
  * Nothing here decides an HTTP status either. `ErrorEnvelope.statusOf` is the single code-to-status table in
  * KUI and `TopicApi.Securing` is the only place it is consulted.
  */
object TopicRoutes {

  def apply[F[_]: Async](
      snapshots: TopicSnapshots[F],
      detail: TopicDetailUseCase[F],
      config: TopicConfigUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      guard: RbacGuard[F]
  ): List[ServerEndpoint[Any, F]] = {
    val secured = TopicApi.Securing[F](principals, rejections, logger, guard)

    List(
      listTopics(snapshots, secured),
      getTopic(detail, secured),
      topicConfig(config, secured),
      topicPartitions(detail, secured),
      refresh(snapshots, secured)
    )
  }

  /** One page of a cluster's topics, cut out of the snapshot.
    *
    * It never fails for a configured cluster. A cluster that has never been scraped answers `unavailable`,
    * **not** an empty page: an empty list from a cluster that has ten thousand topics is a lie that looks
    * like data, and it is the exact failure M1's dashboard produced.
    *
    * `incompleteTopics` is reported from whatever snapshot is being shown, including a stale one, because it
    * describes the rows on the screen rather than the health of the scrape.
    */
  private def listTopics[F[_]: Async](
      snapshots: TopicSnapshots[F],
      secured: TopicApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(TopicEndpoints.listTopics) { _ => (cluster, params) =>
      withSnapshot(snapshots, cluster) { snapshot =>
        Clock[F].realTimeInstant.map { now =>
          val query = queryOf(params)
          val section =
            TopicSections.of(snapshot, now)(topics => TopicMapping.page(ListTopics(topics, query)))

          TopicsResponse(section, incompleteTopics = snapshot.value.map(_.incomplete.size).getOrElse(0))
        }
      }
    }

  /** One topic, with the head of its partition table. */
  private def getTopic[F[_]: Async](
      detail: TopicDetailUseCase[F],
      secured: TopicApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(TopicEndpoints.getTopic) { _ => (cluster, topic) =>
      detailOf(detail, cluster, topic) { (fresh, now) =>
        // The cap and its flag are decided together, by the mapping, so that they cannot disagree.
        val truncated = fresh.get.partitions.sizeIs > TopicDetailResponse.EmbeddedPartitionLimit
        TopicDetailResponse(
          TopicSections.ofFresh(fresh, now)(value => TopicMapping.detail(value)._1),
          partitionsTruncated = truncated
        )
      }
    }

  /** Every partition of one topic — the whole table, which is what the detail document's cap sends a caller
    * here for.
    */
  private def topicPartitions[F[_]: Async](
      detail: TopicDetailUseCase[F],
      secured: TopicApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(TopicEndpoints.topicPartitions) { _ => (cluster, topic) =>
      detailOf(detail, cluster, topic) { (fresh, now) =>
        PartitionsResponse(TopicSections.ofFresh(fresh, now)(TopicMapping.partitions))
      }
    }

  /** The Settings tab.
    *
    * A caller who may see the topic but not its configuration gets a 200 with a `not_permitted` view, not a
    * 403. A 403 would take the partitions they *are* entitled to see down with the tab they are not.
    */
  private def topicConfig[F[_]: Async](
      config: TopicConfigUseCase[F],
      secured: TopicApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(TopicEndpoints.topicConfig) { _ => (cluster, topic) =>
      for {
        answer <- config.config(cluster, topic)
        now <- Clock[F].realTimeInstant
      } yield answer.bimap(
        TopicErrors.toKui,
        view => TopicConfigResponse(Section.Ok(TopicMapping.configView(view), now))
      )
    }

  /** Asks for this cluster's topics to be read now, and answers immediately.
    *
    * 202, and it does not await the scrape. A refresh that blocked would take the admin client's timeout on
    * exactly the cluster a user pressed the button about — the one that is already not answering — and would
    * hold a request open for a minute to tell them what they already knew.
    */
  private def refresh[F[_]: Async](
      snapshots: TopicSnapshots[F],
      secured: TopicApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(TopicEndpoints.refresh) { _ => cluster =>
      for {
        started <- snapshots.requestRefresh(cluster)
        now <- Clock[F].realTimeInstant
      } yield
        if started then RefreshAcceptedDto(cluster, now).asRight[KuiError]
        else TopicErrors.toKui(TopicError.ClusterNotFound(cluster)).asLeft[RefreshAcceptedDto]
    }

  // -----------------------------------------------------------------------------------------------

  /** The wire query as the use case's, which is where the two `TopicSortField` enums meet. */
  private def queryOf(params: TopicListParams): TopicListQuery =
    TopicListQuery(
      q = params.q,
      mode = params.mode,
      showInternal = params.showInternal,
      sort = params.sort.map(TopicMapping.sort),
      page = params.page,
      // M6 replaces this with the caller's own permission filter. It is a parameter now, and not a `filter`
      // applied after paging, because "filter before you page" is the ordering the total depends on.
      visible = TopicListQuery.EverythingVisible
    )

  /** Runs `body` against a configured cluster's snapshot, or answers 404.
    *
    * An unknown cluster is a 404 and never an empty list. "This cluster has no topics" and "KUI has never
    * heard of this cluster" are different statements, and the first one is alarming.
    */
  private def withSnapshot[F[_]: Async, A](
      snapshots: TopicSnapshots[F],
      cluster: ClusterId
  )(body: Snapshot[TopicSnapshot] => F[A]): F[Either[KuiError, A]] =
    snapshots.of(cluster).flatMap {
      case None =>
        TopicErrors.toKui(TopicError.ClusterNotFound(cluster)).asLeft[A].pure[F]
      case Some(cell) =>
        cell.get.flatMap(body).map(_.asRight[KuiError])
    }

  /** The shared shape of the three per-topic reads: resolve, read, stamp. */
  private def detailOf[F[_]: Async, A](
      detail: TopicDetailUseCase[F],
      cluster: ClusterId,
      topic: TopicName
  )(render: (Fresh[kui.topic.domain.TopicDetail], java.time.Instant) => A): F[Either[KuiError, A]] =
    for {
      answer <- detail.detail(cluster, topic)
      now <- Clock[F].realTimeInstant
    } yield answer.bimap(TopicErrors.toKui, fresh => render(fresh, now))
}
