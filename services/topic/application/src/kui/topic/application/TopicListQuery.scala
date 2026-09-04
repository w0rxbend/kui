package kui.topic.application

import kui.kernel.search.SearchMode
import kui.kernel.{PageRequest, Sort, SortOrder, TopicName}
import kui.topic.domain.TopicSortField

/** Everything a list request asks for, already validated.
  *
  * It is a value and not six parameters because the ordering rule the pipeline enforces is a property of the
  * *query*, and a function taking six arguments in the right order is a function somebody will eventually
  * call in the wrong one — two of these are booleans and three of them are optional.
  *
  * @param sort
  *   `None` means the request named no sort field, which is **not** the same as naming `name:asc`. In `fts`
  *   mode the difference decides whether the list comes back in relevance order or alphabetically; see
  *   [[ListTopics]]. The edge is what tells the two apart, because only the edge can see whether the
  *   parameter was present in the query string.
  * @param visible
  *   M6's RBAC filter. M2 passes [[TopicListQuery.EverythingVisible]]. It exists now, unused, so that the
  *   ordering rule — filter before you page — is enforced by a property test before there is anything to get
  *   wrong, rather than being retrofitted into a pipeline that has meanwhile grown two more filters
  */
final case class TopicListQuery(
    q: Option[String],
    mode: SearchMode,
    showInternal: Boolean,
    sort: Option[Sort[TopicSortField]],
    page: PageRequest,
    visible: TopicName => Boolean
) {

  /** The search term, or `None` when it is absent or blank. A blank `q` is not a filter that matches nothing;
    * it is the search box the user has just emptied, and it must give the whole list back.
    */
  def term: Option[String] = q.map(_.trim).filter(_.nonEmpty)
}

object TopicListQuery {

  /** The visibility predicate M2 passes: everything. Named rather than written as `_ => true` at the call
    * site, so that a search for the place RBAC plugs in finds one result.
    */
  val EverythingVisible: TopicName => Boolean = _ => true

  val DefaultSort: Sort[TopicSortField] = Sort(TopicSortField.Name, SortOrder.Asc)

  /** The list a screen asks for when the user has done nothing yet: no search, internal topics hidden, first
    * page, default size, no sort named.
    */
  def default: TopicListQuery =
    TopicListQuery(
      q = None,
      mode = SearchMode.Default,
      showInternal = false,
      sort = None,
      page = PageRequest.Default,
      visible = EverythingVisible
    )
}
