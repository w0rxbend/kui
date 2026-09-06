package kui.gateway.api.search

import cats.effect.kernel.Async
import cats.syntax.all.*

import kui.consumer.contract.{ConsumerEndpoints, GroupListParams}
import kui.contracts.consumer.GroupSortField
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.application.search.SearchSource
import kui.gateway.contract.SearchQuery
import kui.gateway.contract.dto.{GroupHitDto, SearchResultsDto}
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ServiceId, SortOrder}

/** The consumer-groups half of a search, over the group list's own `q`.
  *
  * The filtering happens in the consumer service and not here. Its list endpoint already takes a substring
  * query and answers from the 30-second group snapshot, so asking it with `q` costs one request and returns
  * the page already narrowed; fetching every group and filtering at the gateway would move a cluster's whole
  * group list across the network on every keystroke to do the same thing worse.
  *
  * `pageSize` is the caller's `limit`, which is what bounds the fan-out: the answer is one page and there is
  * no second request for a second page. A search shows the first few matches and offers the list screen for
  * the rest.
  */
object GroupSearchSource {

  def apply[F[_]: Async](consumers: ServiceClient[F]): SearchSource[F] =
    new SearchSource[F] {

      val service: ServiceId = consumers.service

      def find(
          cluster: ClusterId,
          query: SearchQuery,
          context: CallContext
      ): F[Either[KuiError, SearchResultsDto]] =
        consumers
          .call(ConsumerEndpoints.list, (cluster, params(query)))(context)
          .map(_.flatMap { response =>
            SearchSections
              .data(service, "consumer groups", response.groups)
              .map(page =>
                SearchResultsDto(Nil, page.items.map(row => GroupHitDto(cluster, row.groupId)), Nil)
              )
          })
    }

  /** Every group state, sorted the list's own default way, one page the size of the limit.
    *
    * No state filter: a search for `orders` must find the dead group somebody is looking for precisely
    * *because* it is dead, and a default that hid empty groups would make the field silently useless for the
    * case it is most often opened for.
    */
  private def params(query: SearchQuery): GroupListParams =
    GroupListParams(
      states = Set.empty,
      q = Some(query.q),
      sort = GroupSortField.Default,
      direction = SortOrder.Asc,
      page = 1,
      pageSize = query.limit
    )
}
