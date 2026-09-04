package kui.ui.topics.list

import kui.contracts.paging.PageDto
import kui.contracts.topic.TopicRowDto
import kui.ui.topics.Messages

/** One row of the topic list, already reduced to what the table draws.
  *
  * ## Why this is a pure value with no `Signal` in it
  *
  * Every rendering rule on this screen — when a message count is an em dash, what the chip beside it says,
  * where a favourite sits — is decided here, by a total function from the response. That makes each rule one
  * test row instead of a rendering to squint at, and it is the single highest-value convention in this
  * frontend: `TopicRowSuite` is a table of inputs and expected values, and nothing in it mounts a DOM.
  *
  * @param offlinePartitions
  *   how many of this topic's partitions have no leader. Kept even though no column shows it, because it is
  *   what decides *why* the message count is missing, and a row that could not say why would have to leave
  *   the reader to guess between "empty" and "unreadable".
  */
final case class TopicRow(
    name: String,
    internal: Boolean,
    partitions: Int,
    replicationFactor: Option[Int],
    outOfSync: Int,
    offlinePartitions: Int,
    messages: Option[Long],
    sizeBytes: Option[Long],
    favourite: Boolean
) {

  /** Why the message count is missing, when it is; `None` when it is present.
    *
    * This is the most important method on this screen. A topic whose count could not be computed must render
    * an em dash and a chip that says why — never `0`, which reads as "this topic is empty" and is the worst
    * thing this table can say to somebody looking for a message that is actually there.
    *
    * There are two absences and they get two different sentences, because they call for two different
    * actions. Partitions with no leader means the count *cannot* be computed without lying: the offsets of
    * the partitions that did answer would add up to a number smaller than the truth, and a number that is
    * confidently wrong is worse than no number (`libs/kafka/PORT-INVARIANTS.md` §1). No offline partitions
    * and still no count means the broker did not report offsets — a permissions or a version problem, and
    * nothing to do with the health of the topic.
    */
  def missingCountReason: Option[String] =
    if messages.isDefined then None
    else if offlinePartitions > 0 then Some(Messages.countOfflinePartitions(offlinePartitions))
    else Some(Messages.CountNotReported)
}

object TopicRow {

  /** The rows of one page, in the server's order, with the favourites marked.
    *
    * Total: every row of the page becomes exactly one row here. Nothing is dropped, because the server has
    * already applied every filter and its `totalItems` counts what it kept — dropping a row here would make
    * the count on screen disagree with the rows under it, which is the reference product's bug wearing a
    * different hat.
    */
  def of(page: PageDto[TopicRowDto], favourites: Set[String]): List[TopicRow] =
    page.items.map { dto =>
      TopicRow(
        name = dto.name.value,
        internal = dto.internal,
        partitions = dto.partitionCount,
        replicationFactor = dto.replicationFactor,
        outOfSync = dto.outOfSyncReplicas,
        offlinePartitions = dto.offlinePartitions,
        messages = dto.messageCount,
        sizeBytes = dto.sizeBytes,
        favourite = favourites.contains(dto.name.value)
      )
    }

  /** Favourites first, then the server's order — **within the page only**.
    *
    * Not across the whole list, and that limitation is deliberate rather than a shortcut (DEVPLAN §10 D9).
    * Pinning globally would mean the server had to know each user's favourites in order to page correctly,
    * and a page boundary that depended on who was looking would make a shared link show two people different
    * rows. Within a page it is a purely local reordering that changes nothing about paging or totals.
    *
    * Stable: `partition` preserves the relative order inside each group, so the non-favourites come out in
    * exactly the order the server sorted them into.
    */
  def pin(rows: List[TopicRow]): List[TopicRow] = {
    val (favourites, rest) = rows.partition(_.favourite)
    favourites ++ rest
  }
}
