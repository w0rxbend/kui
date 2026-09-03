package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.cluster.LogDirDto
import kui.kernel.{BrokerId, ClusterId}
import kui.ui.clusters.component.Bytes
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.*

/** Where this broker's data actually sits, and how much room is left.
  *
  * One card per directory, because a directory is the unit Kafka reports errors against: a broker with a
  * failed disk answers with three good directories and one error, and this page has to show exactly that.
  */
object BrokerLogDirsTab {

  def apply(
      cluster: ClusterId,
      broker: BrokerId,
      queries: ClustersQueries,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement = {
    // `lastGood`, not the current outcome: a failed refetch leaves the disks the user is looking at on the
    // screen, under the overlay, rather than emptying the tab.
    val section: Signal[Option[Section[List[LogDirDto]]]] =
      queries.logDirs.state((cluster, Some(broker))).map(_.lastGood.map(_.logDirs))

    val views: Signal[List[LogDirView]] =
      section.map(
        _.flatMap(_.toOption).map(LogDirView.forBroker(_, broker)).map(LogDirView.of).getOrElse(Nil)
      )

    TabBody(
      section = section,
      unavailableTestId = "broker-logdirs-unavailable",
      unavailableMessage = Messages.logDirsUnavailable,
      forbiddenMessage = Messages.LogDirsForbidden,
      emptyTitle = Messages.LogDirsEmptyTitle,
      emptyDescription = Messages.LogDirsEmptyDescription,
      isEmpty = views.map(_.isEmpty),
      body = div(
        cls := ClustersCss.LogDirs,
        children <-- views.map(_.zipWithIndex.map(card))
      ),
      overlayTestId = "broker-logdirs-region",
      zone = zone,
      now = now
    )
  }

  private def card(view: LogDirView, index: Int): HtmlElement =
    Card(
      header = Some(
        div(
          cls := ClustersCss.LogDirHeader,
          // Monospaced and selectable: this is going into an `ssh` command in a moment.
          code(cls := ClustersCss.LogDirPath, view.path),
          view.error.map(_ => Tag(Val(Messages.LogDirOffline), tone = Tone.Danger))
        )
      ),
      body = div(
        dataAttr("testid") := s"broker-logdir-$index",
        view.error match {
          // In place of the figures, never instead of the whole page: the other directories are fine and
          // their numbers are what the operator came for.
          case Some(message) =>
            p(cls := ClustersCss.Error, role := "alert", Messages.logDirFailed(message))
          case None => figures(view)
        }
      )
    )

  private def figures(view: LogDirView): HtmlElement =
    div(
      cls := ClustersCss.LogDirFigures,
      // The bar is the answer to "is this disk about to fill up", which is the question that brings
      // somebody to this tab in the first place.
      view.usedFraction match {
        case Some(fraction) =>
          MagnitudeBar(
            value = Val(s"${Bytes.format(view.usedBytes)} ${Messages.ofDisk(Bytes.format(view.totalBytes))}"),
            fraction = Val(fraction),
            label = Some(Val(Messages.LogDirUsed))
          )
        case None =>
          // An unmeasured disk is not an empty one, so no bar is drawn at all rather than an empty track.
          div(cls := ClustersCss.LogDirFigure, span(Messages.LogDirUsed), span(DataTable.missing))
      },
      figure(Messages.LogDirTopics, view.topicCount.toString),
      figure(Messages.LogDirPartitions, view.partitionCount.toString),
      figure(Messages.LogDirFree, Bytes.format(view.usableBytes))
    )

  private def figure(label: String, value: String): HtmlElement =
    div(cls := ClustersCss.LogDirFigure, span(cls := ClustersCss.SummaryLabel, label), span(value))
}
