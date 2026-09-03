package kui.gateway.application.cluster

import java.time.Instant

import cats.effect.kernel.{Async, Clock, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.Section
import kui.contracts.capability.{CapabilityKey, CapabilityState, DegradedReason, ReasonCode}
import kui.contracts.cluster.ClusterRowDto
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, ReadinessSignal}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.contract.dto.{ClusterOverviewDto, ClusterOverviewRow}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.CorrelationId
import kui.security.Principal

/** The dashboard's answer, assembled from one upstream call and the gateway's own knowledge.
  *
  * ==One call, not one per cluster==
  *
  * The cluster service already aggregates every cluster from snapshots it refreshes on a timer, so fanning
  * out per cluster from here would multiply calls without multiplying freshness — and it would reintroduce
  * exactly the failure the milestone's risk register calls R-8: a dashboard that serialises per-cluster calls
  * and stalls on the dead one. With a single call, the response is bounded by that call's own configured
  * timeout, which is a property of the shape rather than of the timing.
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

  def resource[F[_]: Async](
      clusters: ServiceClient[F],
      registry: CapabilityRegistry[F],
      signals: CapabilitySignals[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterOverviewUseCase[F]] =
    Resource
      .eval(LastKnown.of[F, List[ClusterRowDto]])
      .map(cache => new Impl[F](clusters, registry, signals, logger, cache))

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

  final private class Impl[F[_]: Async](
      clusters: ServiceClient[F],
      registry: CapabilityRegistry[F],
      signals: CapabilitySignals[F],
      logger: StructuredLogger[F],
      cache: LastKnown[F, List[ClusterRowDto]]
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
        decorated <- decorate(section)
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

    /** Adds each row's capability state, read from the in-memory registry with no I/O.
      *
      * The order is the cluster service's, which is configuration order, and the gateway does not re-sort:
      * sorting is the table's job, and two components sorting by different rules is how a row appears to move
      * when nothing has changed.
      */
    private def decorate(
        section: Section[List[ClusterRowDto]]
    ): F[Section[List[ClusterOverviewRow]]] =
      section match {
        case Section.Ok(rows, at) => rowsOf(rows).map(Section.Ok(_, at))
        case Section.Stale(rows, at, reason) => rowsOf(rows).map(Section.Stale(_, at, reason))
        case Section.Unavailable(reason, message, since) =>
          Async[F].pure(Section.Unavailable(reason, message, since))
        case Section.Forbidden => Async[F].pure(Section.Forbidden)
        case Section.NotConfigured => Async[F].pure(Section.NotConfigured)
      }

    private def rowsOf(rows: List[ClusterRowDto]): F[List[ClusterOverviewRow]] =
      rows.traverse(row =>
        registry
          .state(CapabilityKey(clusters.service, Some(row.id)))
          .map {
            // The registry answers `NotConfigured` for a key it has never been told about, which is not
            // what a configured cluster with no entry yet means. That case falls back to the row's own
            // section, which is the freshest thing the gateway has about it.
            case CapabilityState.NotConfigured => defaultState(row)
            case known => known
          }
          .map(ClusterOverviewRow(row, _))
      )
  }
}
