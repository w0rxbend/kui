package kui.ui.topics.detail

import com.raquo.laminar.api.L.*

import kui.contracts.topic.{PartitionDto, ReplicaDto}
import kui.ui.kernel.component.*
import kui.ui.topics.{Messages, TopicsCss}

/** One row per partition: who leads it, who holds it, where its offsets are and how big it is.
  *
  * ## Windowed, because two thousand partitions is ordinary
  *
  * This is `VirtualizedTable`'s second caller, and the one that justifies it beyond the topic list: a topic
  * with two thousand partitions is a normal thing to open, and every one of its rows in the document would
  * make the page stutter for the whole time it is open.
  *
  * ## A partition with no leader says so
  *
  * Kafka reports a leaderless partition as node `-1`. Rendering that is worse than useless — it looks like a
  * broker id — so the contract turns it into `None` and this cell says "offline". The same partition also has
  * no message count, and it must not be shown as `0`: the row would then read as an empty partition on a
  * healthy topic, which is the opposite of what is happening.
  */
object PartitionTable {

  def apply(
      partitions: Signal[List[PartitionDto]],
      viewportHeight: Var[Int] = Var(0)
  ): HtmlElement =
    VirtualizedTable[PartitionDto](
      // Ordered by id, always. A partition table is read by looking for a number, and a table that arrived
      // in whatever order the broker answered in would make that a search rather than a lookup.
      rows = partitions.map(_.sortBy(_.partition.value)),
      columns = columns,
      rowKey = _.partition.value.toString,
      emptyState = () => EmptyState(Messages.NoPartitionsTitle, description = Some(Messages.NoPartitions)),
      viewportHeight = viewportHeight,
      testId = Some("partition-table")
    )

  private val columns: List[Column[PartitionDto]] = List(
    Column[PartitionDto](
      id = "partition",
      header = Messages.ColumnPartition,
      render = row =>
        span(dataAttr("testid") := s"partition-row-${row.partition.value}", row.partition.value.toString),
      align = ColumnAlign.Numeric
    ),
    Column[PartitionDto](
      id = "leader",
      header = Messages.ColumnLeader,
      render = row => leaderCell(row),
      align = ColumnAlign.Numeric
    ),
    Column[PartitionDto](
      id = "replicas",
      header = Messages.ColumnReplicas,
      render = row => replicaChips(row)
    ),
    Column[PartitionDto](
      id = "earliest",
      header = Messages.ColumnFirstOffset,
      render = row => row.earliestOffset.fold(DataTable.missing)(_.toString),
      align = ColumnAlign.Numeric
    ),
    Column[PartitionDto](
      id = "latest",
      header = Messages.ColumnNextOffset,
      render = row => row.latestOffset.fold(DataTable.missing)(_.toString),
      align = ColumnAlign.Numeric
    ),
    Column[PartitionDto](
      id = "messages",
      header = Messages.ColumnMessages,
      render = row =>
        span(
          dataAttr("testid") := s"partition-row-${row.partition.value}-messages",
          row.messageCount.fold(DataTable.missing)(_.toString)
        ),
      align = ColumnAlign.Numeric
    ),
    Column[PartitionDto](
      id = "size",
      header = Messages.ColumnSize,
      render = row => Bytes.format(row.sizeBytes),
      align = ColumnAlign.Numeric
    )
  )

  /** The broker leading this partition, or the word "offline" — never Kafka's node id `-1`. */
  private def leaderCell(row: PartitionDto): Modifier[HtmlElement] =
    row.leader match {
      case Some(broker) => span(broker.value.toString)
      case None =>
        Tag(
          label = Val(Messages.Offline),
          tone = Tone.Danger,
          testId = Some(s"partition-row-${row.partition.value}-offline")
        )
    }

  /** Every replica as a chip: the leader marked, and anything out of sync marked differently.
    *
    * Both marks are words as well as colours. A chip that carried "out of sync" in its colour alone would be
    * invisible to about one man in twelve, and this is a column where the whole point is spotting the odd one
    * out.
    */
  private def replicaChips(row: PartitionDto): Modifier[HtmlElement] =
    div(
      cls := TopicsCss.Replicas,
      row.replicas.map { replica =>
        Tag(
          label = Val(replicaLabel(replica)),
          tone = toneOf(replica),
          testId = Some(s"partition-row-${row.partition.value}-replica-${replica.broker.value}")
        )
      }
    )

  private[detail] def replicaLabel(replica: ReplicaDto): String =
    if replica.leader then Messages.replicaLeader(replica.broker.value)
    else if !replica.inSync then Messages.replicaOutOfSync(replica.broker.value)
    else replica.broker.value.toString

  private def toneOf(replica: ReplicaDto): Tone =
    if !replica.inSync then Tone.Warning
    else if replica.leader then Tone.Info
    else Tone.Neutral
}
