package kui.topic.application

import kui.kernel.{Sort, SortOrder}
import kui.topic.domain.{TopicSortField, TopicSummary}

/** The orderings, one per sort field.
  *
  * Two rules apply to all of them, and they are the reason this is a named object rather than six inline
  * lambdas at the one call site that needs them today:
  *
  *   - **Missing values sort last in both directions.** A topic whose message count could not be computed
  *     must not float to the top when the user sorts descending by count. The rows with data are what the
  *     user asked to see, and a screenful of em dashes above them is a list that has answered a different
  *     question. This is the rule that is easy to get right in one direction and wrong in the other, which is
  *     why the suite tests each direction separately.
  *   - **The tiebreak is always the name, ascending.** Without it, two topics of equal size swap places
  *     between two identical requests, and page 2 of a "stable" list is not stable — the same row can appear
  *     on two pages and another row on none.
  */
object TopicOrdering {

  def of(sort: Sort[TopicSortField]): Ordering[TopicSummary] = {
    val primary = sort.field match {
      case TopicSortField.Name => present(sort.order)(_.name.value)
      case TopicSortField.Partitions => present(sort.order)(_.partitionCount)
      case TopicSortField.ReplicationFactor => optional(sort.order)(_.replicationFactor)
      case TopicSortField.OutOfSyncReplicas => present(sort.order)(_.outOfSyncReplicas)
      case TopicSortField.Size => optional(sort.order)(_.sizeBytes)
      case TopicSortField.MessageCount => optional(sort.order)(_.messageCount)
    }

    primary.orElse(byName)
  }

  /** The order a list takes when the request named no sort field. */
  val byName: Ordering[TopicSummary] = Ordering.by((summary: TopicSummary) => summary.name.value)

  private def present[A](order: SortOrder)(key: TopicSummary => A)(using
      ordering: Ordering[A]
  ): Ordering[TopicSummary] =
    (left, right) => direction(order, ordering.compare(key(left), key(right)))

  /** An ordering over a field that can be absent, with absence pinned to the end regardless of direction.
    *
    * The comparison is written out rather than expressed as `Ordering.Option`, because `Ordering.Option` puts
    * `None` *first* and reversing it for a descending sort would move the absent rows to the top — precisely
    * the behaviour this exists to prevent.
    */
  private def optional[A](order: SortOrder)(key: TopicSummary => Option[A])(using
      ordering: Ordering[A]
  ): Ordering[TopicSummary] =
    (left, right) =>
      (key(left), key(right)) match {
        case (None, None) => 0
        case (None, Some(_)) => 1
        case (Some(_), None) => -1
        case (Some(a), Some(b)) => direction(order, ordering.compare(a, b))
      }

  private def direction(order: SortOrder, comparison: Int): Int = order match {
    case SortOrder.Asc => comparison
    case SortOrder.Desc => -comparison
  }
}
