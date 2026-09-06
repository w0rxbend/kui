package kui.gateway.api.search

import cats.effect.kernel.Async
import cats.syntax.all.*

import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.application.search.SearchSource
import kui.gateway.contract.SearchQuery
import kui.gateway.contract.dto.{SearchResultsDto, TopicHitDto}
import kui.kernel.error.KuiError
import kui.kernel.search.{NameIndex, SearchMode}
import kui.kernel.{ClusterId, ServiceId, TopicName}
import kui.topic.contract.TopicEndpoints

/** The topics half of a search, over the topic service's names index.
  *
  * `GET …/topics/names` is the endpoint this uses and the reason it exists: it answers every topic name on a
  * cluster, unpaged, in one call, so a search never becomes a request per topic and never has to page through
  * a list to find out whether a name is on the fourth page.
  *
  * The matching is `NameIndex` and not `String.contains`, so "matches" means the same thing here as it does
  * in the consumer service's own list and in the browser (ADR-038). It matters more than it looks: the
  * index's case folding is per code point rather than per locale, and a gateway in a Turkish locale would
  * otherwise disagree with the service it is folding over about whether `ORDERS` matches `orders`.
  */
object TopicSearchSource {

  def apply[F[_]: Async](topics: ServiceClient[F]): SearchSource[F] =
    new SearchSource[F] {

      val service: ServiceId = topics.service

      def find(
          cluster: ClusterId,
          query: SearchQuery,
          context: CallContext
      ): F[Either[KuiError, SearchResultsDto]] =
        topics
          .call(TopicEndpoints.topicNames, cluster)(context)
          .map(_.flatMap { response =>
            SearchSections
              .data(service, "topic names", response.names)
              .map(names => SearchResultsDto(hits(cluster, names, query), Nil, Nil))
          })
    }

  /** The matching names, in the index's own order, capped at what the caller asked for.
    *
    * The index is built over the raw strings, so the typed names are recovered through a lookup rather than
    * re-parsed: a `TopicName` that arrived from the topic service has already been validated once, and
    * validating it a second time here would only be a way to lose it.
    */
  private def hits(cluster: ClusterId, names: List[TopicName], query: SearchQuery): List[TopicHitDto] = {
    val byValue = names.map(name => name.value -> name).toMap

    NameIndex
      .of(names.map(_.value))
      .search(query.q, SearchMode.Plain)
      .take(query.limit)
      .flatMap(byValue.get)
      .map(TopicHitDto(cluster, _))
  }
}
