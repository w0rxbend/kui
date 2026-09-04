package kui.topic.domain

import java.time.Instant

import kui.kernel.TopicName
import kui.kernel.search.NameIndex

/** Every topic of one cluster, at one instant, with the index the list searches.
  *
  * ==Why the index is part of the value==
  *
  * A search index built per request over ten thousand names would be rebuilt for every keystroke of every
  * user. Built here, it is built once per scrape and thrown away with the snapshot it belongs to, which is
  * the whole of ADR-038's "in memory first" position. Making it a field rather than something the list
  * pipeline constructs also removes the way that design goes wrong: an index built from a different list from
  * the rows would make search quietly return topics the list cannot show, or hide topics it can. The
  * constructor is private and [[TopicSnapshot.of]] builds both halves from one input, so they cannot drift.
  *
  * @param incomplete
  *   topics the scrape could not read, with the reason, so the list can say "9 998 of 10 000 topics; 2 could
  *   not be read" instead of quietly showing fewer. It explains, it does not remove: a topic here is still in
  *   `topics` if the scrape managed a row for it at all.
  */
final case class TopicSnapshot private (
    topics: Vector[TopicSummary],
    index: NameIndex,
    scrapedAt: Instant,
    incomplete: Map[TopicName, String]
) {

  /** The rows by name. A `lazy val` because the detail path looks a topic up by name on every request and the
    * list path never does, so neither one should pay for the other.
    */
  lazy val byName: Map[TopicName, TopicSummary] = topics.map(row => row.name -> row).toMap

  def get(name: TopicName): Option[TopicSummary] = byName.get(name)

  def size: Int = topics.size

  /** How many topics the scrape could not fully read. Rendered beside the total, never subtracted from it. */
  def incompleteCount: Int = incomplete.size
}

object TopicSnapshot {

  /** Builds a snapshot, and the index over exactly the names it holds.
    *
    * `topics` is a `Vector` and not a `List` because the list pipeline slices it by index for every page of
    * every request over a ten-thousand-element collection, and a `List` makes that a walk from the head each
    * time for no reason at all.
    *
    * The order given here is the order the index resolves ties in, so a caller that hands over an already
    * sorted vector gets deterministic search results and a stable later sort over them.
    */
  def of(
      topics: Vector[TopicSummary],
      scrapedAt: Instant,
      incomplete: Map[TopicName, String] = Map.empty
  ): TopicSnapshot =
    new TopicSnapshot(
      topics = topics,
      index = NameIndex.of(topics.map(_.name.value).toList),
      scrapedAt = scrapedAt,
      incomplete = incomplete
    )

  /** An empty snapshot, for a cluster whose first scrape has not produced anything yet. */
  def empty(scrapedAt: Instant): TopicSnapshot = of(Vector.empty, scrapedAt)
}
