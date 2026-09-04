package kui.topic.application

import kui.kernel.search.SearchMode
import kui.kernel.{Page, TopicName}
import kui.topic.domain.{TopicSnapshot, TopicSummary}

/** The one place a topic list is produced.
  *
  * ==The order is the contract==
  *
  *   1. `visible` — RBAC (M6). A user must not be able to learn that a topic exists from a page count.
  *   1. internal — the `showInternal` toggle.
  *   1. `q` / `mode` — the name search.
  *   1. `sort`.
  *   1. `Page.of` — which counts exactly what it is handed.
  *
  * Every filter runs before the total is taken, and that is the whole difference from the implementation this
  * product is modelled on. Its page count is computed from the list *before* the internal-topic filter
  * (`research/kafbat/api-analysis.md` §3.3, `TopicsController.java:213-220`), so it overstates the number of
  * pages whenever internal topics are hidden — which is the default. The user sees "page 3 of 40", clicks
  * through to page 34 and finds nothing there, and there is no error anywhere to explain it.
  *
  * Getting the order right once is not enough, because the bug is reintroduced by any later edit that moves a
  * filter below the count. `ListTopicsSuite` asserts the equivalence as a property over generated snapshots
  * and generated queries, so such an edit fails a test rather than a user.
  *
  * This is pure computation over a snapshot that is already in memory. It performs no I/O, and it neither
  * knows nor cares whether that snapshot is stale: staleness is carried by the `SnapshotCell` the snapshot
  * came out of and rendered at the edge. The separation is deliberate — a list must remain computable from an
  * old snapshot, or a cluster outage would empty the screen instead of ageing it.
  */
object ListTopics {

  def apply(snapshot: TopicSnapshot, query: TopicListQuery): Page[TopicSummary] = {
    // 1 and 2. Both filters, before anything counts anything.
    val permitted = snapshot.topics.iterator
      .filter(row => query.visible(row.name))
      .filter(row => query.showInternal || !row.isInternal)
      .toVector

    // 3. The search, applied to what survived the filters and not to the whole snapshot. Both orders give
    // the same rows; only this one gives a count that matches them, because the count is taken once, at the
    // end. It also means the index is never consulted about a topic the caller may not see.
    val matched = query.term match {
      case None => permitted
      case Some(term) => search(snapshot, permitted, term, query.mode)
    }

    // 4. The sort. Relevance order — the order `search` returned — is honoured only when the request named
    // no sort field, so that an explicit `sort` is never silently ignored in `fts` mode.
    val ordered = (query.sort, query.term) match {
      case (Some(sort), _) => matched.sorted(using TopicOrdering.of(sort))
      case (None, Some(_)) if query.mode == SearchMode.Fts => matched
      case (None, _) => matched.sorted(using TopicOrdering.byName)
    }

    // 5. The page, cut from a list that every filter has already been applied to.
    Page.of(ordered.toList, query.page)
  }

  /** Matches `term` against the snapshot's index and keeps the rows that survived the filters.
    *
    * The index is built over every name in the snapshot, including ones this query has filtered out, because
    * it belongs to the snapshot rather than to the request — building a per-request index over ten thousand
    * names would rebuild it on every keystroke. Restricting the result afterwards is what makes that safe:
    * the index proposes, the filtered set disposes, and a topic the caller may not see cannot come back
    * through the search box.
    *
    * The order the index returned is preserved, which is what makes relevance ranking available to step 4.
    */
  private def search(
      snapshot: TopicSnapshot,
      permitted: Vector[TopicSummary],
      term: String,
      mode: SearchMode
  ): Vector[TopicSummary] = {
    val survivors: Map[String, TopicSummary] = permitted.iterator.map(row => row.name.value -> row).toMap

    snapshot.index.search(term, mode).iterator.flatMap(survivors.get).toVector
  }

  /** Whether one row would survive this query's filters, exposed so that a suite can state the expected set
    * independently of the pipeline that produces it — the property that closes the reference product's
    * page-count defect is only worth anything if the two sides are computed differently.
    */
  def matches(snapshot: TopicSnapshot, query: TopicListQuery, name: TopicName): Boolean =
    query.visible(name) &&
      (query.showInternal || !snapshot.get(name).exists(_.isInternal)) &&
      query.term.forall(term => snapshot.index.search(term, query.mode).contains(name.value))
}
