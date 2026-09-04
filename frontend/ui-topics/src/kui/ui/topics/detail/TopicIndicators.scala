package kui.ui.topics.detail

import com.raquo.laminar.api.L.*

import kui.contracts.topic.TopicDetailDto
import kui.ui.kernel.component.{Bytes, DataTable, Tone}
import kui.ui.topics.{Messages, TopicsCss}

/** The strip above the tabs: what the topic is made of, as nine figures.
  *
  * One pure function from the document to the values it shows, for the same reason the list has a row model:
  * the threshold rules become test rows instead of a rendering to inspect, and `TopicIndicatorsSuite` never
  * mounts anything.
  *
  * ## The em dash rule, again, and in the same place as the table
  *
  * A topic whose message count is unknown shows an em dash here *and* on every partition row. Summing the
  * partitions that did answer would produce a number smaller than the truth and present it as the truth,
  * which is exactly the wrongness `libs/kafka/PORT-INVARIANTS.md` §1 refuses on the server — reintroduced in
  * the browser, where nothing would catch it.
  */
object TopicIndicators {

  /** One figure, its label, and whether it is saying something is wrong. */
  final case class Indicator(label: String, value: String, tone: Tone = Tone.Neutral)

  object Indicator {
    given CanEqual[Indicator, Indicator] = CanEqual.derived
  }

  def of(detail: TopicDetailDto): List[Indicator] = {
    val row = detail.row
    val inSyncReplicas = detail.partitions.map(_.replicas.count(_.inSync)).sum
    val replicas = detail.partitions.map(_.replicas.size).sum

    List(
      Indicator(Messages.IndicatorPartitions, row.partitionCount.toString),
      Indicator(
        Messages.IndicatorReplicationFactor,
        row.replicationFactor.fold(DataTable.missing)(_.toString)
      ),
      // Zero is the normal state and is drawn like every other quiet number. Colouring a healthy zero
      // teaches the eye to ignore the colour, which is the one thing the colour has to do.
      Indicator(
        Messages.IndicatorOutOfSync,
        row.outOfSyncReplicas.toString,
        if row.outOfSyncReplicas > 0 then Tone.Warning else Tone.Neutral
      ),
      Indicator(
        Messages.IndicatorOfflinePartitions,
        row.offlinePartitions.toString,
        if row.offlinePartitions > 0 then Tone.Danger else Tone.Neutral
      ),
      // "82 of 84". Fewer in sync than there are replicas is the thing an operator is looking for.
      //
      // Both halves are summed from the partition list, so an *empty* list on a topic that has partitions
      // means the figure is unknown, not zero. That case is not hypothetical: it is what a topic page looks
      // like while the cluster is unreachable, when the last scrape's row survives and the partition
      // assignment does not. It used to read "0 of 0", which on the one screen an operator opens during an
      // outage says every replica is out of sync - the most alarming statement the strip can make, made from
      // no data at all, two lines above a table that correctly says the partitions are not available.
      Indicator(
        Messages.IndicatorInSyncReplicas,
        if detail.partitions.isEmpty && row.partitionCount > 0 then DataTable.missing
        else Messages.nOfM(inSyncReplicas, replicas),
        if replicas > 0 && inSyncReplicas < replicas then Tone.Warning else Tone.Neutral
      ),
      Indicator(Messages.IndicatorType, if row.internal then Messages.TypeInternal else Messages.TypeNormal),
      Indicator(Messages.IndicatorSize, Bytes.format(row.sizeBytes)),
      Indicator(Messages.IndicatorSegments, detail.segmentCount.fold(DataTable.missing)(_.toString)),
      Indicator(Messages.IndicatorCleanupPolicy, detail.cleanupPolicy.getOrElse(DataTable.missing)),
      Indicator(Messages.IndicatorMessages, row.messageCount.fold(DataTable.missing)(_.toString))
    )
  }

  /** The strip. A definition list, because that is what it is: nine labelled values. */
  def apply(indicators: Signal[List[Indicator]]): HtmlElement =
    dl(
      cls := TopicsCss.Indicators,
      dataAttr("testid") := "topic-indicators",
      children <-- indicators.split(_.label)((_, _, indicator) =>
        div(
          cls := TopicsCss.Indicator,
          dt(cls := TopicsCss.IndicatorLabel, text <-- indicator.map(_.label)),
          dd(
            cls := TopicsCss.IndicatorValue,
            cls(TopicsCss.IndicatorWarning) <-- indicator.map(_.tone == Tone.Warning),
            cls(TopicsCss.IndicatorDanger) <-- indicator.map(_.tone == Tone.Danger),
            dataAttr("testid") <-- indicator.map(current => s"topic-indicator-${slug(current.label)}"),
            text <-- indicator.map(_.value)
          )
        )
      )
    )

  /** A label as a `data-testid` suffix: lower case, words joined by hyphens. */
  private[detail] def slug(label: String): String =
    label.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
}
