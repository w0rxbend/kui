package kui.ui.consumers.detail

import com.raquo.laminar.api.L.*

import kui.contracts.consumer.{PartitionDto, TopicSubscriptionDto}
import kui.ui.consumers.{ConsumersCss, Messages, Numbers}
import kui.ui.kernel.component.*

/** One subscribed topic: every partition this group reads, what it has committed, where the log ends, and how
  * far behind it is.
  *
  * ## The bar is scaled within the topic
  *
  * Against the worst partition of *this* topic, not against the group's total and not against another topic's
  * worst. The question a per-partition table answers is "is the lag spread evenly, or is one partition
  * stuck", and only a scale local to the topic answers it: scaled globally, a topic that is entirely healthy
  * beside one that is badly behind would draw no bars at all and look unmeasured rather than fine.
  *
  * ## Every missing number says why
  *
  * `committed`, `end` and `lag` are each optional on the wire, and each absence means something different: no
  * commit ever made, a committed offset past the end of the log, a committed offset older than anything still
  * retained. Those come back as `anomalies`, and the row shows the anomaly's own sentence rather than
  * inventing a zero — a lag of zero means "caught up", which is precisely what a partition with no commit has
  * not done.
  */
object LagTable {

  def apply(topic: TopicSubscriptionDto): HtmlElement = {
    val worst: Long = topic.partitions.flatMap(_.lag).maxOption.getOrElse(0L)

    div(
      cls := ConsumersCss.Section,
      dataAttr("testid") := s"group-topic-${topic.topic.value}",
      div(
        cls := ConsumersCss.TopicHeading,
        h3(cls := ConsumersCss.TopicName, topic.topic.value),
        span(
          cls := ConsumersCss.SummaryValue,
          dataAttr("testid") := s"group-topic-${topic.topic.value}-lag",
          topic.lag.fold(DataTable.missing)(Numbers.grouped)
        ),
        Option.when(topic.excludedPartitions > 0)(
          span(cls := ConsumersCss.Note, Messages.excluded(topic.excludedPartitions))
        )
      ),
      DataTable[PartitionDto](
        columns = columns(worst),
        rows = Val(topic.partitions.sortBy(_.partition)),
        rowKey = _.partition.toString,
        testId = Some(s"group-topic-${topic.topic.value}-table")
      )
    )
  }

  private def columns(worst: Long): List[Column[PartitionDto]] =
    List(
      Column[PartitionDto](
        id = "partition",
        header = Messages.ColumnPartition,
        render = row => row.partition.toString,
        align = ColumnAlign.Numeric,
        width = Some("7rem")
      ),
      Column[PartitionDto](
        id = "committed",
        header = Messages.ColumnCommitted,
        render = row => row.committed.fold(DataTable.missing)(Numbers.grouped),
        align = ColumnAlign.Numeric
      ),
      Column[PartitionDto](
        id = "end",
        header = Messages.ColumnEnd,
        render = row => row.end.fold(DataTable.missing)(Numbers.grouped),
        align = ColumnAlign.Numeric
      ),
      Column[PartitionDto](
        id = "lag",
        header = Messages.ColumnLag,
        render = row => lagCell(row, worst),
        align = ColumnAlign.Numeric,
        width = Some("14rem")
      ),
      Column[PartitionDto](
        id = "member",
        header = Messages.ColumnMember,
        render = row =>
          // The host, because that is what an operator acts on, with the member id on the title for when they
          // need to match it against a broker log line.
          row.host match {
            case Some(host) => span(host, row.memberId.map(id => title := id))
            case None => span(DataTable.missing)
          }
      )
    )

  /** The lag, its bar, and — when there is no lag — the anomaly that explains the absence. */
  private def lagCell(row: PartitionDto, worst: Long): Modifier[HtmlElement] =
    row.lag match {
      case Some(lag) =>
        MagnitudeBar(
          value = Val(Numbers.grouped(lag)),
          fraction = Val(Numbers.fraction(lag, worst)),
          inline = true,
          testId = Some(s"partition-${row.partition}-lag")
        )
      case None =>
        span(
          dataAttr("testid") := s"partition-${row.partition}-lag",
          DataTable.missing,
          // The anomalies carry their own sentences, written once in `libs/kernel` so that the server's logs
          // and this cell say the same thing about the same condition.
          row.anomalies.map(anomaly =>
            span(
              cls := ConsumersCss.Anomaly,
              title := anomaly.description,
              anomaly.wire.toLowerCase.replace('_', ' ')
            )
          )
        )
    }
}
