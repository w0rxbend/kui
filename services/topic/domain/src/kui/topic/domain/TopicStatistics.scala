package kui.topic.domain

/** The cluster-wide totals the topics list draws above its table.
  *
  * `SCREENS-V4.md` §4.6 makes the load-bearing point about this document explicitly: the statistics are
  * cluster-wide and **unaffected by the list's filter** — `TOTAL TOPICS` still reads 128 while the table
  * shows the three rows matching `orders.`. That is why it is a separate document folded from the whole
  * snapshot rather than a footer computed from a page: a total derived from the rows on screen would track
  * the search box, and the number an operator is reading it for is the one that does not.
  *
  * ==Each figure refuses on its own==
  *
  * A scrape that could not describe two of ten thousand topics still knows that ten thousand topics exist —
  * `listTopics` answered, and being unable to *describe* a topic is not being unable to see it. So the count
  * survives an incomplete scrape and the two sums do not: KUI does not know how many partitions those two
  * topics have, and a partition total that silently omitted them is the "number that looks measured and is
  * wrong in the direction that reassures" this milestone exists to keep off a screen. The size total refuses
  * for that reason *and* for its own, because `describeLogDirs` can fail on a cluster whose describe worked —
  * so a cluster can honestly show a partition total beside an em dash for storage.
  *
  * @param topicCount
  *   every topic the scrape learned of, the ones it could not describe included. Not an `Option`: it comes
  *   from the listing, and a snapshot cannot exist without one — the outage that would remove it removes the
  *   whole document instead, as an `unavailable` section
  * @param incompleteTopics
  *   how many of those the scrape could not describe. It is what lets the screen say *why* the two totals are
  *   absent, rather than showing two unexplained em dashes under a count of 128
  */
final case class TopicStatistics(
    topicCount: Int,
    partitionCount: Option[Long],
    sizeBytes: Option[Long],
    incompleteTopics: Int
)

object TopicStatistics {

  /** The totals of one scrape.
    *
    * The sums go through [[Aggregate.sumOrRefuse]], the same function the per-topic rows are built with. A
    * cluster-wide size that refused by a different rule from the per-topic one would let a list of rows each
    * showing a size sit under a total showing an em dash, or the reverse, and neither screen would look wrong
    * on its own.
    */
  def of(snapshot: TopicSnapshot): TopicStatistics = {
    val described = snapshot.incomplete.isEmpty

    TopicStatistics(
      topicCount = snapshot.names.size,
      partitionCount = Option.when(described)(snapshot.topics.map(_.partitionCount.toLong).sum),
      sizeBytes =
        if described then Aggregate.sumOrRefuse(snapshot.topics.map(_.sizeBytes)) else None,
      incompleteTopics = snapshot.incompleteCount
    )
  }

  given CanEqual[TopicStatistics, TopicStatistics] = CanEqual.derived
}
