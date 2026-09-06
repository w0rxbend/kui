package kui.gateway.application.search

import cats.Parallel
import cats.effect.kernel.Async
import cats.syntax.all.*

import kui.cluster.contract.ClusterEndpoints
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.contract.SearchQuery
import kui.gateway.contract.dto.{SearchAnswerDto, SearchResultsDto}
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, CorrelationId, ServiceId}
import kui.security.Principal

/** One kind of thing a search can find, on one cluster, from the service that owns it.
  *
  * The port is here and its three implementations are not, and the reason is a module edge rather than a
  * preference: this layer sees the cluster, topic and consumer contracts, and the schema service's contract
  * reaches only as far as `services/gateway/api`. Three sources in one place beats two in this layer and one
  * in another, so all three live beside the route (`kui.gateway.api.search`) and the fold names only this
  * trait — which is the seam that makes the fold testable with a counting fake anyway.
  *
  * A source fills the one field of [[SearchResultsDto]] it owns and leaves the other two empty. It never
  * throws: a failure is a `Left`, because the fold has to be able to tell a service that answered nothing
  * from one that could not be asked, and an exception would collapse both into a 500.
  */
trait SearchSource[F[_]] {

  /** Which service this source calls, for the `partial` list. */
  def service: ServiceId

  def find(
      cluster: ClusterId,
      query: SearchQuery,
      context: CallContext
  ): F[Either[KuiError, SearchResultsDto]]
}

/** Cross-entity search: one query, every configured cluster, three services, one document.
  *
  * ==It never fails because a service did not answer==
  *
  * The only failure this endpoint has is a malformed query, which the contract's own validators refuse before
  * any of this runs. Everything else is an answer: a service that is not routed, one that is down and one
  * that refused the caller all contribute their id to `partial` and nothing to the results, and the other two
  * services still return theirs. A search that 500s because one of three upstreams was missing would take the
  * whole field away over a deployment choice — and on the distributed stack, which routes no schema service
  * at all, it would take it away permanently.
  *
  * ==The fan-out is bounded by clusters times services==
  *
  * One request per service per cluster and no more. Each of the three list endpoints already matches on its
  * own side — the consumer and schema services take a `q`, and `topics/names` answers the whole name index in
  * one unpaged call, which is what that endpoint was built for — so nothing here asks about a result it has
  * just been told about. A search that cost a request per topic would be the slowest thing in the product and
  * would be issued on every keystroke.
  */
trait SearchUseCase[F[_]] {
  def search(query: SearchQuery, principal: Principal, correlationId: CorrelationId): F[SearchAnswerDto]
}

object SearchUseCase {

  /** The services a search folds over, in the order the answer's three lists appear.
    *
    * The list is the whole definition of what `partial` can name: a service in it that this deployment has no
    * client for is reported as unaskable rather than silently contributing an empty list, and the composition
    * root builds its sources by walking this list, so what is searched and what can be reported missing
    * cannot drift apart.
    */
  val TopicService: ServiceId = ServiceId.unsafe("topic")
  val ConsumerService: ServiceId = ServiceId.unsafe("consumer")
  val SchemaService: ServiceId = ServiceId.unsafe("schema")

  val Services: List[ServiceId] = List(TopicService, ConsumerService, SchemaService)

  /** @param clusters
    *   the cluster service's client. Required rather than optional, exactly as the dashboard's aggregation
    *   requires it: without it the gateway does not know which clusters exist, so there is nothing to search
    *   and the composition root serves no route at all rather than an endpoint that answers an empty document
    *   to every query.
    * @param sources
    *   one per service this deployment can actually ask. A service in [[Services]] with no source here is
    *   what `partial` reports.
    */
  def of[F[_]: {Async, Parallel}](
      clusters: ServiceClient[F],
      sources: List[SearchSource[F]]
  ): SearchUseCase[F] = new Impl[F](clusters, sources)

  final private class Impl[F[_]: {Async, Parallel}](
      clusters: ServiceClient[F],
      sources: List[SearchSource[F]]
  ) extends SearchUseCase[F] {

    /** Every covered service this deployment holds no client for. Computed once: it is a property of the
      * wiring and cannot change between requests.
      */
    private val unrouted: List[ServiceId] =
      Services.filterNot(service => sources.exists(_.service == service))

    def search(
        query: SearchQuery,
        principal: Principal,
        correlationId: CorrelationId
    ): F[SearchAnswerDto] =
      clusters.call(ClusterEndpoints.listClusters, ())(CallContext(principal, correlationId, None)).flatMap {
        // The cluster list is what says which clusters exist, so losing it is not one missing category —
        // it is every service unasked. Reporting all four in `partial` is the honest answer; reporting an
        // empty result set with an empty `partial` would tell the browser the product holds nothing.
        case Left(_) =>
          Async[F].pure(SearchAnswerDto(SearchResultsDto.Empty, partialOf(clusters.service :: Services)))

        case Right(response) => fanOut(response.items.map(_.id), query, principal, correlationId)
      }

    /** One call per cluster per source, all at once.
      *
      * `parSequence` and not `sequence`: the response is then bounded by the slowest single call rather than
      * by the sum of them, and each client already bounds that with its own timeout and circuit breaker. This
      * is the dashboard's risk R-8 in a smaller shape — a dead cluster must cost the search one timeout, not
      * one timeout per service per cluster.
      */
    private def fanOut(
        ids: List[ClusterId],
        query: SearchQuery,
        principal: Principal,
        correlationId: CorrelationId
    ): F[SearchAnswerDto] = {
      val calls = for {
        cluster <- ids
        source <- sources
      } yield source
        .find(cluster, query, CallContext(principal, correlationId, Some(cluster)))
        .map(source.service -> _)

      calls.parSequence.map { outcomes =>
        val failed = outcomes.collect { case (service, Left(_)) => service }
        val found = outcomes
          .collect { case (_, Right(results)) => results }
          .foldLeft(SearchResultsDto.Empty)(SearchResultsDto.combine)

        SearchAnswerDto(SearchResultsDto.take(found, query.limit), partialOf(unrouted ++ failed))
      }
    }

    /** The `partial` list: distinct and sorted, so two identical requests produce identical bytes.
      *
      * One cluster's failure is enough to name the service. A service that answered for four clusters and
      * timed out on the fifth has given an incomplete answer, and saying so is the point of the field.
      */
    private def partialOf(services: List[ServiceId]): List[ServiceId] =
      services.distinct.sortBy(_.value)
  }
}
