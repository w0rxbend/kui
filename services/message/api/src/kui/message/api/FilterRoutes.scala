package kui.message.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.http.principal.SecuredRoutes
import kui.message.application.FilterUseCase
import kui.message.contract.{FilterEndpoints, FilterIdDto, FilterTestResultDto, MessageDto}
import kui.message.domain.ports.{FilterSample, FilterVerdict}

/** The two filter routes: register an expression, and try one against a record (MS-007).
  *
  * ==Both carry a body, so both are bound with `withBody`==
  *
  * ADR-020 Amendment 1: a signed principal on a request that carries a body is bound to that body's digest,
  * reconstructed from the decoded input through the contract's own codec. Binding to the request line alone —
  * which a plain `secured(...)` would do — would refuse every call with a 401 naming nothing, and would make
  * an intercepted token replayable with any expression its holder liked. `libs/http`'s `SecuredRoutes` is the
  * one mechanism for this; there is deliberately no second one here.
  *
  * ==Why there is no read-only refusal and no audit record==
  *
  * Because neither call changes a cluster. Registering compiles a string in this process; testing evaluates a
  * program against a record the caller sent. `MutationGuard` exists for calls that touch Kafka, and putting
  * these through it would record an audit entry saying somebody changed something when nobody did.
  */
object FilterRoutes {

  def apply[F[_]: Async](
      filters: FilterUseCase[F],
      secured: MessageApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(registerRoute(filters, secured), testRoute(filters, secured))

  private def registerRoute[F[_]: Async](
      filters: FilterUseCase[F],
      secured: MessageApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(FilterEndpoints.register)((_, request) => SecuredRoutes.bodyBytes(request)) {
      _ => (cluster, request) =>
        // The name is accepted and not yet stored. Saved filters are a list this service does not have
        // (MS-007's second half), and refusing the field would make every client that sends one fail on
        // the day the list arrives. Registering is idempotent, so re-registering under a name later costs
        // nothing.
        filters.register(cluster, request.source).map(_.map(FilterIdDto.apply))
    }

  private def testRoute[F[_]: Async](
      filters: FilterUseCase[F],
      secured: MessageApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(FilterEndpoints.test)((_, request) => SecuredRoutes.bodyBytes(request)) {
      _ => (cluster, request) =>
        filters.test(cluster, request.source, sample(request.record)).map(_.map(result))
    }

  /** The record the caller sent, as the filter sees it.
    *
    * The decoded text of both halves, exactly as it is on the caller's screen, because a person writing
    * `record.value.status` means the document they can see rather than the bytes underneath it.
    */
  private def sample(record: MessageDto): FilterSample =
    FilterSample(
      partition = record.partition.value,
      offset = record.offset.value,
      timestampMs = record.timestamp.toEpochMilli,
      keyAsText = record.key.text,
      valueAsText = record.value.text,
      headers = record.headers
    )

  /** The verdict, as the wire's two fields.
    *
    * `matched` is false whenever `error` is set, so a client that reads only `matched` is wrong in the safe
    * direction: it shows the user nothing rather than showing them a record the filter never approved.
    */
  private def result(verdict: FilterVerdict): FilterTestResultDto =
    verdict match {
      case FilterVerdict.Matched => FilterTestResultDto(matched = true, error = None)
      case FilterVerdict.DidNotMatch => FilterTestResultDto(matched = false, error = None)
      case FilterVerdict.Failed(reason) => FilterTestResultDto(matched = false, error = Some(reason))
    }
}
