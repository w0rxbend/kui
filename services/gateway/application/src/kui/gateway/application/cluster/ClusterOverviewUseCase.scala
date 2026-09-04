package kui.gateway.application.cluster

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.contract.ClusterEndpoints
import kui.consumer.contract.dto.GroupPageDto
import kui.consumer.contract.{ConsumerEndpoints, GroupListParams}
import kui.contracts.Section
import kui.contracts.capability.{CapabilityKey, CapabilityState, DegradedReason, ReasonCode}
import kui.contracts.cluster.ClusterRowDto
import kui.contracts.consumer.GroupSortField
import kui.contracts.paging.PageDto
import kui.contracts.topic.TopicRowDto
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, ReadinessSignal}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.contract.dto.{
  ClusterOverviewDto,
  ClusterOverviewRow,
  GroupTotalsDto,
  TopicMagnitudeDto,
  TopicTotalsDto
}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{CorrelationId, PageRequest, PageSize, PositiveInt, SortOrder}
import kui.security.Principal
import kui.topic.contract.{TopicEndpoints, TopicListParams}

/** The dashboard's answer, assembled from one upstream call and the gateway's own knowledge.
  *
  * ==One call for the list, then two per cluster, all in parallel==
  *
  * The cluster list is one call: the cluster service already aggregates every cluster from snapshots it
  * refreshes on a timer, so asking it per cluster would multiply calls without multiplying freshness.
  *
  * The topic and consumer totals cannot come from that call, because they belong to two other services, and
  * they are what turns this document from a list of names into a dashboard. So each row makes two more calls
  * — both served from those services' own timed snapshots, neither touching a broker on the request path —
  * and every one of them is issued in parallel with every other. That is what keeps the milestone's risk R-8
  * closed: the response is bounded by the slowest single call rather than by the sum of them, so a dead
  * cluster costs the dashboard one timeout and not one timeout per section per cluster.
  *
  * ==Every section fails on its own==
  *
  * The cluster list, each cluster's own summary, each cluster's topic totals and each cluster's group totals
  * are four independent statuses, and the dashboard renders all four separately. A consumer service that is
  * down leaves every other figure on the screen intact. This is the product's central argument and the
  * dashboard is where it is most visible, so nothing here is allowed to fold two failures into one.
  *
  * ==It never fails==
  *
  * Every path returns a document. An upstream that answered gives `Ok`; one that did not gives `Stale` with
  * the rows last seen and the time they were fetched, or `Unavailable` with the reason when there are none. A
  * dashboard that 500s because one of its inputs failed is the outage the section shape exists to prevent.
  *
  * The failure is still *reported* to the capability signals when it is a transport failure, exactly as a
  * proxied route reports one: an aggregation that swallowed it would leave the sidebar green while the page
  * in front of the user showed an outage, and nothing on the screen would explain the difference.
  */
trait ClusterOverviewUseCase[F[_]] {
  def overview(principal: Principal, correlationId: CorrelationId): F[ClusterOverviewDto]
}

object ClusterOverviewUseCase {

  /** How many topics one page of the topic list asks for, and therefore the largest cluster whose partition
    * total the dashboard can sum. It is `PageSize.Max`, which is the largest page the contract admits; a
    * cluster with more topics than this gets its topic count and no partition total, which is the honest
    * answer rather than a sum over the first five hundred.
    */
  val TopicSample: Int = PageSize.Max.value

  /** The same, for consumer groups. The group list clamps rather than refuses, and 200 is where it clamps. */
  val GroupSample: Int = 200

  def resource[F[_]: {Async, Parallel}](
      clusters: ServiceClient[F],
      registry: CapabilityRegistry[F],
      signals: CapabilitySignals[F],
      logger: StructuredLogger[F],
      topics: Option[ServiceClient[F]] = None,
      groups: Option[ServiceClient[F]] = None
  ): Resource[F, ClusterOverviewUseCase[F]] =
    Resource
      .eval(LastKnown.of[F, List[ClusterRowDto]])
      .map(cache => new Impl[F](clusters, registry, signals, logger, cache, topics, groups))

  /** Why the outer section is not `Ok`, classified by failure *case* rather than by error code: "could not
    * connect" and "the breaker is open" share a code and mean different things on a screen.
    */
  def reasonOf(error: KuiError): ReasonCode = error match {
    case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
    case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
    case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
    case _ => ReasonCode.UpstreamUnavailable
  }

  /** What a row says when the registry has no entry for it yet.
    *
    * Never missing: "not asked yet" and "not deployed" mean different things to whoever is looking, and a row
    * with no status at all forces the browser to invent one. A row whose own summary arrived is available; a
    * row still being scraped is degraded with a reason that says so.
    */
  def defaultState(row: ClusterRowDto): CapabilityState =
    row.summary match {
      case Section.Ok(_, _) => CapabilityState.Available
      case _ =>
        CapabilityState.Degraded(
          DegradedReason(ReasonCode.Starting, "this cluster has not been scraped yet", None, None)
        )
    }

  final private class Impl[F[_]: {Async, Parallel}](
      clusters: ServiceClient[F],
      registry: CapabilityRegistry[F],
      signals: CapabilitySignals[F],
      logger: StructuredLogger[F],
      cache: LastKnown[F, List[ClusterRowDto]],
      topics: Option[ServiceClient[F]],
      groups: Option[ServiceClient[F]]
  ) extends ClusterOverviewUseCase[F] {

    private val context: Map[String, String] =
      Map("operation" -> "kui.gateway.clusters.overview", "upstream" -> clusters.service.value)

    def overview(principal: Principal, correlationId: CorrelationId): F[ClusterOverviewDto] =
      for {
        answer <- clusters.call(ClusterEndpoints.listClusters, ())(
          CallContext(principal, correlationId, None)
        )
        now <- Clock[F].realTimeInstant
        section <- answer match {
          case Right(response) =>
            cache
              .put(response.items, now)
              .as(Section.Ok(response.items, now): Section[List[ClusterRowDto]])

          case Left(error) => degraded(error, now)
        }
        decorated <- decorate(section, principal, correlationId, now)
      } yield ClusterOverviewDto(decorated, now)

    /** The upstream failed. Serve what is known, say why, and tell the registry. */
    private def degraded(error: KuiError, now: Instant): F[Section[List[ClusterRowDto]]] =
      for {
        _ <- report(error, now)
        cached <- cache.get
        _ <- logger.warn(context ++ Map("reason" -> reasonOf(error).wire))(
          s"the cluster list could not be fetched: ${error.message}"
        )
      } yield cached match {
        case Some((rows, fetchedAt)) => Section.Stale(rows, fetchedAt, reasonOf(error))
        case None => Section.Unavailable(reasonOf(error), error.message, Some(now))
      }

    /** Only a transport failure dims a capability (ADR-039 §6).
      *
      * A cluster that is unreachable is *not* one of these: it is the cluster service answering correctly
      * about a broker it cannot reach, and dimming the service's capability for it would take the whole
      * cluster feature away from every other cluster in the deployment.
      */
    private def report(error: KuiError, now: Instant): F[Unit] = error match {
      case infrastructure: InfrastructureError =>
        signals.updateService(clusters.service)(
          _.copy(readiness =
            Some(ReadinessSignal.NotReady(reasonOf(infrastructure), infrastructure.message, now))
          )
        )
      case _ => Async[F].unit
    }

    /** Adds each row's capability state, its topic totals and its group totals.
      *
      * The order is the cluster service's, which is configuration order, and the gateway does not re-sort:
      * sorting is the table's job, and two components sorting by different rules is how a row appears to move
      * when nothing has changed.
      */
    private def decorate(
        section: Section[List[ClusterRowDto]],
        principal: Principal,
        correlationId: CorrelationId,
        now: Instant
    ): F[Section[List[ClusterOverviewRow]]] = {
      def rows(list: List[ClusterRowDto]): F[List[ClusterOverviewRow]] =
        rowsOf(list, principal, correlationId, now)

      section match {
        case Section.Ok(list, at) => rows(list).map(Section.Ok(_, at))
        case Section.Stale(list, at, reason) => rows(list).map(Section.Stale(_, at, reason))
        case Section.Unavailable(reason, message, since) =>
          Async[F].pure(Section.Unavailable(reason, message, since))
        case Section.Forbidden => Async[F].pure(Section.Forbidden)
        case Section.NotConfigured => Async[F].pure(Section.NotConfigured)
      }
    }

    /** Every row, with its capability, its topic totals and its group totals.
      *
      * `parTraverse` and not `traverse`, over rows *and* over the two calls each row makes. The dashboard's
      * standing risk is that it serialises per-cluster work and stalls on the dead cluster (M1 risk R-8); in
      * parallel the whole response is bounded by the slowest single call rather than by their sum, and each
      * client already bounds that with its own timeout and circuit breaker.
      *
      * Neither of the two calls can fail this method. Each becomes its own `Section`, so a consumer service
      * that is down costs the dashboard its consumer panel and nothing else — which is the argument the
      * dashboard exists to make.
      */
    private def rowsOf(
        rows: List[ClusterRowDto],
        principal: Principal,
        correlationId: CorrelationId,
        now: Instant
    ): F[List[ClusterOverviewRow]] =
      rows.parTraverse { row =>
        val context = CallContext(principal, correlationId, Some(row.id))
        (
          capabilityOf(row),
          topicTotals(row, context, now),
          groupTotals(row, context, now)
        ).parMapN(ClusterOverviewRow.apply(row, _, _, _))
      }

    private def capabilityOf(row: ClusterRowDto): F[CapabilityState] =
      registry
        .state(CapabilityKey(clusters.service, Some(row.id)))
        .map {
          // The registry answers `NotConfigured` for a key it has never been told about, which is not
          // what a configured cluster with no entry yet means. That case falls back to the row's own
          // section, which is the freshest thing the gateway has about it.
          case CapabilityState.NotConfigured => defaultState(row)
          case known => known
        }

    /** One cluster's topic and partition totals.
      *
      * Internal topics are counted. The dashboard is a view of what the broker is holding, and a partition
      * total that silently excluded `__consumer_offsets`'s fifty partitions would not add up against the
      * broker's own figures — which is exactly the check an operator makes with this screen open.
      */
    private def topicTotals(
        row: ClusterRowDto,
        context: CallContext,
        now: Instant
    ): F[Section[TopicTotalsDto]] =
      topics.fold(Async[F].pure(Section.NotConfigured: Section[TopicTotalsDto])) { client =>
        client
          .call(
            TopicEndpoints.listTopics,
            (
              row.id,
              TopicListParams.Default.copy(
                showInternal = true,
                page = PageRequest(PositiveInt.One, PageSize.unsafe(TopicSample))
              )
            )
          )(context)
          .map {
            case Left(error) => unavailable[TopicTotalsDto](error, now)
            // The topic service answers with its own `Section` inside a 200 when it is serving a stale or
            // failed snapshot, and that inner state is the truth about this cluster. It is carried through
            // rather than flattened to `Ok`: a dashboard that relabelled a stale topic count as current
            // would be the consumer-group screen's defect repeated on a bigger screen.
            case Right(response) => totalsOf(response.topics)
          }
      }

    private def totalsOf(section: Section[PageDto[TopicRowDto]]): Section[TopicTotalsDto] =
      section match {
        case Section.Ok(page, at) => Section.Ok(topicTotalsOf(page), at)
        case Section.Stale(page, at, reason) => Section.Stale(topicTotalsOf(page), at, reason)
        case Section.Unavailable(reason, message, since) => Section.Unavailable(reason, message, since)
        case Section.Forbidden => Section.Forbidden
        case Section.NotConfigured => Section.NotConfigured
      }

    private def topicTotalsOf(page: PageDto[TopicRowDto]): TopicTotalsDto =
      TopicTotalsDto.of(
        page.items.map(topic => TopicMagnitudeDto(topic.name, topic.partitionCount)),
        // A page that counted nothing is reported as holding exactly what arrived, which makes the
        // partition total present when the page is the whole list and absent when it may not be.
        page.page.totalItems.getOrElse(page.items.size.toLong)
      )

    /** One cluster's consumer groups, by state.
      *
      * The consumer service's own freshness is carried through rather than flattened, exactly as the topic
      * totals are: lag read before a broker died and lag read now look identical as numbers, and a dashboard
      * that relabelled the first as current would be showing an operator a cluster keeping up at the moment
      * it stopped.
      */
    private def groupTotals(
        row: ClusterRowDto,
        context: CallContext,
        now: Instant
    ): F[Section[GroupTotalsDto]] =
      groups.fold(Async[F].pure(Section.NotConfigured: Section[GroupTotalsDto])) { client =>
        client
          .call(
            ConsumerEndpoints.list,
            (
              row.id,
              GroupListParams(
                states = Set.empty,
                q = None,
                sort = GroupSortField.Default,
                direction = SortOrder.Asc,
                page = 1,
                pageSize = GroupSample
              )
            )
          )(context)
          .map {
            case Left(error) => unavailable[GroupTotalsDto](error, now)
            // The consumer service's own freshness is carried through rather than replaced with `now`.
            // These totals are computed from rows that service may already have marked as coming from
            // before the cluster stopped answering, and a total derived from stale rows is stale — the
            // dashboard must not launder it into a current figure by restamping it.
            case Right(response) => groupTotalsIn(response.groups)
          }
      }

    private def groupTotalsIn(section: Section[GroupPageDto]): Section[GroupTotalsDto] =
      section match {
        case Section.Ok(page, at) => Section.Ok(groupTotalsOf(page), at)
        case Section.Stale(page, at, reason) => Section.Stale(groupTotalsOf(page), at, reason)
        case Section.Unavailable(reason, message, since) => Section.Unavailable(reason, message, since)
        case Section.Forbidden => Section.Forbidden
        case Section.NotConfigured => Section.NotConfigured
      }

    private def groupTotalsOf(page: GroupPageDto): GroupTotalsDto =
      GroupTotalsDto.of(
        page.items.map(_.state),
        page.items.map(_.totalLag),
        page.page.totalItems.getOrElse(page.items.size.toLong)
      )

    private def unavailable[A](error: KuiError, now: Instant): Section[A] =
      Section.Unavailable(reasonOf(error), error.message, Some(now))
  }
}
