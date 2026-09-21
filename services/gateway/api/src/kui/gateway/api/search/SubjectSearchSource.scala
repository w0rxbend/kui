package kui.gateway.api.search

import cats.effect.kernel.Async
import cats.syntax.all.*

import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.application.search.SearchSource
import kui.gateway.contract.SearchQuery
import kui.gateway.contract.dto.{SearchResultsDto, SubjectHitDto}
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ServiceId, SortOrder}
import kui.schema.contract.{SchemaEndpoints, SubjectListParams}

/** The subjects half of a search, over the subject list's own `q`.
  *
  * Filtered in the schema service for the same reason the groups are filtered in the consumer service: the
  * registry has no search of its own, so somebody has to hold the whole subject list and match against it,
  * and the service that already holds it is the cheaper place.
  *
  * A cluster with no registry configured answers `KUI-UNSUPPORTED`, which arrives here as a `Left` and puts
  * `schema` in the answer's `partial` list. That is the intended reading: subject results for that cluster
  * genuinely cannot be produced, and a browser that was handed an empty list instead would show "no subjects
  * match" for a registry nobody ever configured.
  */
object SubjectSearchSource {

  def apply[F[_]: Async](schemas: ServiceClient[F]): SearchSource[F] =
    new SearchSource[F] {

      val service: ServiceId = schemas.service

      def find(
          cluster: ClusterId,
          query: SearchQuery,
          context: CallContext
      ): F[Either[KuiError, SearchResultsDto]] =
        schemas
          .call(SchemaEndpoints.subjects, (cluster, params(query)))(context)
          .map(
            _.map(page =>
              SearchResultsDto(Nil, Nil, page.items.map(row => SubjectHitDto(cluster, row.subject)))
            )
          )
    }

  /** One page the size of the limit.
    *
    * The page size matters more here than anywhere else in the fold: this endpoint enriches each row it
    * returns with per-subject registry calls, so asking for ten rows is ten enrichments and asking for the
    * contract's maximum would be five hundred. A search must never be the most expensive request in the
    * product.
    */
  private def params(query: SearchQuery): SubjectListParams =
    SubjectListParams(q = Some(query.q), direction = SortOrder.Asc, page = 1, pageSize = query.limit)
}
