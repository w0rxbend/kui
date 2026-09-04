package kui.schema.domain

import kui.kernel.{Page, PageRequest, SortOrder, Subject}

/** What a subject list request asks for.
  *
  * Search is a case-insensitive substring match over the subject name and nothing else. It is not a regular
  * expression and not a fuzzy match, because a subject name is a machine-generated string an operator pastes
  * — `orders-value` — and the search that surprises them least is the one that finds exactly what they typed.
  */
final case class SubjectQuery(search: Option[String], order: SortOrder, page: PageRequest)

object SubjectQuery {

  val Default: SubjectQuery = SubjectQuery(None, SortOrder.Asc, PageRequest.Default)

  given CanEqual[SubjectQuery, SubjectQuery] = CanEqual.derived
}

/** Filtering, sorting and paging the registry's subject list, in one pure place.
  *
  * The registry returns every subject name in one call and offers no search, no sort and no paging of its
  * own, so somebody has to do it. Doing it here — a function over a list, with no effect anywhere near it —
  * is what lets the arithmetic be tested by calling it, and it is why the count on the screen cannot drift
  * from the rows: `Page.of` counts *after* filtering, which is the mistake the reference product makes and
  * the reason that helper exists.
  */
object SubjectCatalog {

  def page(subjects: List[Subject], query: SubjectQuery): Page[Subject] = {
    val matching = query.search.map(_.trim).filter(_.nonEmpty) match {
      case None => subjects
      case Some(needle) =>
        val lowered = needle.toLowerCase
        subjects.filter(_.value.toLowerCase.contains(lowered))
    }

    val sorted = query.order match {
      case SortOrder.Asc => matching.sortBy(_.value)
      case SortOrder.Desc => matching.sortBy(_.value).reverse
    }

    Page.of(sorted, query.page)
  }

  /** The subject a topic's keys or values are registered under, by the default `TopicNameStrategy`.
    *
    * `orders` becomes `orders-key` and `orders-value`. The other two Confluent strategies name the subject
    * after the record's own type, which is inside a payload nobody has decoded at this point, so they cannot
    * be applied to a topic name at all — a screen offering "the schema for this topic" under those strategies
    * has to be told the subject rather than deriving it.
    */
  def subjectFor(topic: String, target: SubjectTarget): Subject =
    Subject.unsafe(s"$topic-${target.suffix}")
}

/** Which half of a record a subject is about. */
enum SubjectTarget {
  case Key, Value

  def suffix: String = this match {
    case Key => "key"
    case Value => "value"
  }
}

object SubjectTarget {
  given CanEqual[SubjectTarget, SubjectTarget] = CanEqual.derived
}
