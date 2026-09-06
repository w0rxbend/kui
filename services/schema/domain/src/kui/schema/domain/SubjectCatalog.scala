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

/** One row of the subject list: the name, and the three facts its caption is built from.
  *
  * Every fact is separately optional, and every `None` means "nobody found out" rather than zero. The
  * registry answers the three from three different calls, so a row can be half-enriched — a version count
  * with no format is what a subject whose latest version was deleted mid-read looks like, and it is a better
  * row than none at all.
  *
  * The subject itself is never optional, because it came from the list and the list is the one call that
  * cannot have partially failed: [[SubjectCatalog.page]] cut this row out of an answer that arrived whole.
  */
final case class SubjectSummary(
    subject: Subject,
    format: Option[SchemaFormat],
    versionCount: Option[Int],
    compatibility: Option[SubjectCompatibility]
) {

  /** The row as it reads once the registry-wide level is known.
    *
    * A subject with no level of its own follows the global one, and on a healthy registry almost every
    * subject has none — so a row that named a level only for the pinned few would leave the caption blank
    * nearly everywhere. The rule is `CompatibilityReadUseCase.forSubject`'s, applied once per page instead of
    * once per row: the global level is the same for every subject on the page, and asking for it per row
    * would multiply the one call a page can share by the page size.
    *
    * `None` for the global level leaves the field absent rather than filling in the registry's documented
    * default, for the reason that use case gives — a default nobody observed is a guess on a screen an
    * operator uses to decide whether a breaking change is allowed.
    */
  def inheriting(global: Option[CompatibilityLevel]): SubjectSummary =
    if compatibility.isDefined then this
    else copy(compatibility = global.map(SubjectCompatibility.inherited))
}

object SubjectSummary {

  /** The name and nothing else: the row a failed enrichment leaves behind, and the row a subject that has
    * been deleted since the list was taken gets.
    *
    * It exists as a named constructor because it is an answer the service gives on purpose. A row that
    * vanished because a secondary call failed would tell an operator their subject is gone.
    */
  def bare(subject: Subject): SubjectSummary = SubjectSummary(subject, None, None, None)

  given CanEqual[SubjectSummary, SubjectSummary] = CanEqual.derived
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
