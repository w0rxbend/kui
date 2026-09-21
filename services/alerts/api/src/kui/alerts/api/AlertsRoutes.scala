package kui.alerts.api

import java.time.Instant

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint

import kui.alerts.application.{AlertStore, AlertUseCases}
import kui.alerts.contract.dto.*
import kui.alerts.contract.{AlertsEndpoints, AlertsStreamEndpoint}
import kui.contracts.Section
import kui.http.sse.{Sse, SseConfig, SseEvent}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.observability.Telemetry
import kui.security.Principal

/** The two JSON routes, bound to use cases.
  *
  * ==One rule shapes both, and it is the metrics service's==
  *
  * A request naming a cluster KUI has never heard of **fails** with `404 KUI-CLUSTER-NOT-FOUND`, because the
  * caller followed a link to something that does not exist. Everything else **succeeds**, carrying sections
  * that say what happened. The alerts card sits on a dashboard beside cards that work, and a 4xx would make a
  * feed behaving exactly as designed indistinguishable from a broken one.
  *
  * ==Nothing here decides anything==
  *
  * The permission is `SecuredRoutes`', the read-only refusal and the audit record are `MutationGuard`'s, the
  * "already closed" refusal is the store's, and the choice of section per rule is `AlertsMapping`'s. This
  * module renames fields and turns one instant into a `fetchedAt`.
  */
object AlertsRoutes {

  def apply[F[_]: Async](
      alerts: AlertUseCases[F],
      secured: AlertsApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(eventsRoute(alerts, secured), acknowledgeRoute(alerts, secured))

  /** `GET /internal/v1/clusters/{clusterId}/alerts/events`. */
  private def eventsRoute[F[_]: Async](
      alerts: AlertUseCases[F],
      secured: AlertsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(AlertsEndpoints.events) { principal => (cluster, limit, markRead) =>
      for {
        now <- Clock[F].realTimeInstant
        answer <- alerts.feed(principal, cluster, limit, markRead)
      } yield answer.map(feed => AlertFeedResponse(Section.Ok(AlertsMapping.feed(feed, now), now)))
    }

  /** `POST /internal/v1/clusters/{clusterId}/alerts/events/{eventId}/acknowledgement`.
    *
    * Bodiless, so it is verified in Tapir's *security* stage: an unauthenticated caller is refused before
    * this service parses a byte of what they sent, and `SecuredRoutes.withBody`'s one-stage-later
    * reconstruction is not needed because there are no bytes to reconstruct (ADR-020 Amendment 1).
    */
  private def acknowledgeRoute[F[_]: Async](
      alerts: AlertUseCases[F],
      secured: AlertsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(AlertsEndpoints.acknowledge) { principal => (_, cluster, rawEventId) =>
      AlertsMapping.eventId(rawEventId) match {
        case Left(invalid) => invalid.asLeft[AcknowledgementDto].pure[F]
        case Right(event) =>
          alerts.acknowledge(principal, cluster, event).map(_.map(AlertsMapping.acknowledged))
      }
    }

  /** `GET /internal/v1/clusters/{clusterId}/alerts/stream`, as a route.
    *
    * It is built separately from [[apply]] because Tapir's `ServerEndpoint` is invariant in its capability
    * parameter once a streaming body is involved, which is the same reason `SecuredRoutes` has a `stream`
    * method beside its `apply`.
    */
  def stream[F[_]: Async](
      store: AlertStore[F],
      secured: AlertsApi.Securing[F],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      config: SseConfig
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    List(
      secured.stream(AlertsStreamEndpoint.endpoint[F]) { principal => _ => cluster =>
        Sse
          .encode(
            Sse.stream(
              changes[F](store, cluster, principal),
              config,
              AlertsStreamEndpoint.EventName,
              telemetry,
              logger
            )
          )
          .asRight[KuiError]
          .pure[F]
      }
    )

  /** This cluster's changes, as frames.
    *
    * The store publishes a cluster id whenever its feed moved; the count is read back here rather than
    * carried through the publication, so a subscriber that connects mid-burst gets the count as it *is*
    * rather than as it was at whichever notification it happened to catch.
    *
    * The read is made with the subscriber's own principal and `markRead = None`. Reading a stream is not
    * reading the feed: a bell that cleared itself because its own stream delivered a frame would be a bell
    * that never lit up.
    */
  def changes[F[_]: Async](
      store: AlertStore[F],
      cluster: ClusterId,
      principal: Principal
  ): Stream[F, SseEvent] =
    store.changes
      .filter(_ == cluster)
      .evalMap(changed =>
        for {
          now <- Clock[F].realTimeInstant
          feed <- store.feed(changed, principal, 0, None)
        } yield frame(changed, feed.openCount, now)
      )

  private def frame(cluster: ClusterId, openCount: Int, at: Instant): SseEvent =
    SseEvent.data(AlertsStreamEndpoint.EventName, AlertChangeDto(cluster.value, openCount, at).asJson)
}
