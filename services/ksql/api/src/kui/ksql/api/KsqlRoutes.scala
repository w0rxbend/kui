package kui.ksql.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint

import kui.contracts.sse.{DoneReason, SseEventName}
import kui.http.principal.SecuredRoutes
import kui.http.sse.{Sse, SseConfig, SseEvent}
import kui.kernel.CorrelationId
import kui.kernel.error.KuiError
import kui.ksql.application.KsqlUseCases
import kui.ksql.contract.dto.*
import kui.ksql.contract.{KsqlEndpoints, KsqlStreamEndpoint}
import kui.ksql.domain.QueryFrame
import kui.observability.{Correlation, Telemetry}

/** The three JSON routes and the one stream, bound to use cases.
  *
  * ==One rule shapes the read and the opposite rule shapes the writes==
  *
  * A read naming a cluster KUI has never heard of **fails** with `404 KUI-CLUSTER-NOT-FOUND`, because the
  * caller followed a link to something that does not exist. Everything else about a read **succeeds**,
  * carrying a section that says what happened — a ksqlDB that is down or starting is a panel on a working
  * screen, and a 4xx would make a server behaving exactly as designed indistinguishable from a broken KUI.
  *
  * A statement is the other way round. One that did not run must not answer 200 with a document saying so:
  * the browser would show a success toast that is true about the response and false about the cluster.
  *
  * ==Nothing here decides anything==
  *
  * The permission is `SecuredRoutes`', the read-only refusal and the audit record are `MutationGuard`'s, the
  * plan token is `KsqlPlanToken`'s, the classification of a statement is the domain's, and the choice of
  * section is `KsqlMapping`'s. This module turns one instant into a `fetchedAt` and one frame into an SSE
  * event.
  */
object KsqlRoutes {

  def apply[F[_]: Async](
      ksql: KsqlUseCases[F],
      secured: KsqlApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(objectsRoute(ksql, secured), planRoute(ksql, secured), executeRoute(ksql, secured))

  /** `GET /internal/v1/clusters/{clusterId}/ksql/objects`. */
  private def objectsRoute[F[_]: Async](
      ksql: KsqlUseCases[F],
      secured: KsqlApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(KsqlEndpoints.objects) { principal => cluster =>
      for {
        now <- Clock[F].realTimeInstant
        answer <- ksql.objects(principal, cluster)
      } yield answer.map(KsqlMapping.objects(_, now))
    }

  /** `POST /internal/v1/clusters/{clusterId}/ksql/statements/plan`.
    *
    * Bound with `withBody`, so the signed request digest covers the bytes that were sent (ADR-020 Amendment
    * 1). That matters more here than on most bodies: the body *is* the statement, and a digest that covered
    * only the request line would leave the one field worth substituting outside it.
    */
  private def planRoute[F[_]: Async](
      ksql: KsqlUseCases[F],
      secured: KsqlApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(KsqlEndpoints.plan)(input => SecuredRoutes.bodyBytes(input._3)) {
      principal => (_, cluster, request) =>
        ksql.plan(principal, cluster, request.statement).map(_.map(KsqlMapping.plan))
    }

  /** `POST /internal/v1/clusters/{clusterId}/ksql/statements`. */
  private def executeRoute[F[_]: Async](
      ksql: KsqlUseCases[F],
      secured: KsqlApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(KsqlEndpoints.execute)(input => SecuredRoutes.bodyBytes(input._3)) {
      principal => (_, cluster, request) =>
        ksql
          .execute(principal, cluster, request.statement, request.token)
          .map(_.map(KsqlMapping.result))
    }

  /** `GET /internal/v1/clusters/{clusterId}/ksql/stream?statement=…`, as a route.
    *
    * It is built separately from [[apply]] because Tapir's `ServerEndpoint` is invariant in its capability
    * parameter once a streaming body is involved, which is the same reason `SecuredRoutes` has a `stream`
    * method beside its `apply`.
    */
  def stream[F[_]: Async](
      ksql: KsqlUseCases[F],
      secured: KsqlApi.Securing[F],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      config: SseConfig
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    List(
      secured.stream(KsqlStreamEndpoint.endpoint[F]) { principal => ctx => (cluster, statement) =>
        for {
          // The correlation id the caller sent, or a new one. A failure that happens *after* the response
          // headers have gone is rendered into the stream as an `error` frame (ADR-035), and an envelope
          // has to carry an id or the line in the log and the line on the screen cannot be joined.
          correlationId <- ctx.correlationId.fold(Correlation.newRandom[F])(_.pure[F])
          answer <- ksql.stream(principal, cluster, statement)
        } yield answer.map { rows =>
          Sse.encode(
            Sse.stream(frames(rows, correlationId), config, KsqlApi.StreamName, telemetry, logger)
          )
        }
      }
    )

  /** The use case's frames, as the events ADR-035 fixes.
    *
    * Four things happen here and each is a rule rather than a rendering:
    *
    *   - a header becomes `phase`, the shared name every KUI stream uses for "here is what is coming". It
    *     arrives as soon as ksqlDB accepts the query, which is what makes an idle push query a stream that
    *     has visibly started rather than a socket that has said nothing;
    *   - a row becomes `row`, whose name is `SseEventName.Row` and is compared to the golden document rather
    *     than mirrored by eye;
    *   - a failure becomes ADR-035's `error`, which is the ordinary envelope, and it is terminal;
    *   - **the stream always ends with exactly one terminal event.** A push query that stops because its
    *     budget expired ends `budget`; one ksqlDB itself finished ends `exhausted`. A stream that simply
    *     stopped would reach the browser as "the query finished, apparently", and for a push query over a
    *     live topic that is the most misleading thing the screen could say.
    */
  def frames[F[_]: Async](
      rows: Stream[F, Either[KuiError, QueryFrame]],
      correlationId: CorrelationId
  ): Stream[F, SseEvent] = {
    val body = rows.evalMap {
      case Right(QueryFrame.Header(columns)) =>
        SseEvent.data(SseEventName.Phase, KsqlMapping.header(columns).asJson).pure[F]
      case Right(QueryFrame.Row(row)) =>
        SseEvent.data(SseEventName.Row, KsqlMapping.row(row).asJson).pure[F]
      case Right(QueryFrame.Ended(exhausted)) =>
        SseEvent
          .done(if exhausted then DoneReason.Exhausted else DoneReason.Budget, None)
          .pure[F]
      case Left(error) => KsqlApi.errorEvent[F](error, correlationId)
    }

    // Appended rather than emitted by the source, because the source ends in three ways — the budget
    // expiring, the client disconnecting, and ksqlDB finishing the query — and only the third produces a
    // frame of its own. `Sse.atMostOneTerminal` drops this one when an `error` or an `exhausted` `done`
    // came first, so the two paths cannot both terminate the stream.
    body ++ Stream.emit(SseEvent.done(DoneReason.Budget, None))
  }
}
