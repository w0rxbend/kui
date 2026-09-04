package kui.ui.consumers.topic

import com.raquo.laminar.api.L.*

import kui.contracts.consumer.TopicConsumerRowDto
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{ClusterId, TopicName}
import kui.ui.consumers.{ConsumersCss, ConsumersQueries, GroupStateChip, Messages, Numbers}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.query.QueryState

/** The Consumers tab on the topic page: which groups read this topic, and how far behind each one is.
  *
  * ## This panel is the microfrontend mechanism doing its job
  *
  * The topic page is owned by `ui-topics`; this panel is owned by `ui-consumers`; and neither module names
  * the other. The topic page offers a slot (`FeatureSlots.TopicTabs`) and hands whatever fills it a
  * `PanelContext` carrying the cluster and the topic name. This feature registers a `PanelContribution`
  * against that slot. The two agree on one thing, the slot id, and it is a constant in the kernel — below
  * both of them — so a typo is a compile error rather than a tab that silently never appears.
  *
  * Nothing about that is theoretical any more: before this panel existed the slot was offered and nothing
  * filled it, which is a mechanism with no evidence behind it.
  *
  * ## Where the rows come from, and why not from this feature's own endpoint
  *
  * The consumer service does serve "the groups that read this topic" — `ConsumerEndpoints.forTopic` — but on
  * `/internal/v1`, which no browser may call. The gateway calls it while assembling the topic overview and
  * puts the answer in that document's `consumerGroups` section, and this panel reads the overview. The result
  * is that `ui-topics` never learns a single one of this service's routes (DEVPLAN §10 D13).
  *
  * ## Why the tab does not fetch until it is opened
  *
  * `GuestTabs` renders a panel through a thunk, so this function is not called until somebody selects the
  * tab. A topic page that built every guest panel up front would issue every guest's requests for a user who
  * looked at one of them.
  *
  * @param onOpen
  *   how clicking a group reaches its detail page. Passed in rather than reached for, so the panel can be
  *   driven by a suite with no router — and because navigation is the feature's, not the panel's.
  */
object TopicConsumersPanel {

  def apply(
      cluster: ClusterId,
      topic: TopicName,
      queries: ConsumersQueries,
      hrefFor: String => String,
      onOpen: String => Unit
  ): HtmlElement = {
    val state: Signal[QueryState[TopicOverviewDto]] = queries.topicOverview.state((cluster, topic))

    val view: Signal[Option[TopicConsumersView]] = state.map(_.lastGood.map(TopicConsumers.of))

    /** A failed request with nothing held from before. Distinct from a section that failed *inside* a
      * successful overview: this one means the topic page's own document could not be fetched.
      */
    val refusal: Signal[Option[ApiError]] =
      state.map(current =>
        if current.lastGood.isEmpty then current.outcome.flatMap(_.left.toOption) else None
      )

    val pending: Signal[Boolean] =
      state.map(current => current.lastGood.isEmpty && current.outcome.isEmpty)

    def retry(): Unit = queries.topicOverview.invalidateWhere((id, name) => id == cluster && name == topic)

    div(
      cls := ConsumersCss.Section,
      dataAttr("testid") := "topic-consumers",
      child.maybe <-- refusal.map(_.map(error => errorPanel(error, () => retry()))),
      child.maybe <-- Signal
        .combine(pending, refusal)
        .map((waiting, failed) =>
          Option.when(waiting && failed.isEmpty)(
            p(
              cls := ConsumersCss.Note,
              dataAttr("testid") := "topic-consumers-loading",
              Messages.TopicLoading
            )
          )
        ),
      child.maybe <-- view.map(_.map(body(hrefFor, onOpen, () => retry())))
    )
  }

  private def body(
      hrefFor: String => String,
      onOpen: String => Unit,
      retry: () => Unit
  )(view: TopicConsumersView): HtmlElement =
    view match {
      case TopicConsumersView.Absent =>
        p(
          cls := ConsumersCss.Note,
          dataAttr("testid") := "topic-consumers-absent",
          Messages.TopicNotConfigured
        )

      case TopicConsumersView.Unreadable(message) =>
        div(
          cls := ConsumersCss.Error,
          dataAttr("testid") := "topic-consumers-error",
          role := "alert",
          p(message),
          Button(
            label = Val(Messages.TryAgain),
            onClick = Observer[Unit](_ => retry()),
            variant = ButtonVariant.Primary,
            testId = Some("topic-consumers-retry")
          )
        )

      case TopicConsumersView.Rows(rows, stale) =>
        val largestLag = rows.flatMap(_.topicLag).maxOption.getOrElse(0L)

        div(
          Option.when(stale)(
            p(cls := ConsumersCss.Note, dataAttr("testid") := "topic-consumers-stale", Messages.TopicStale)
          ),
          DataTable[TopicConsumerRowDto](
            columns = columns(largestLag, hrefFor, onOpen),
            rows = Val(rows),
            rowKey = _.group.groupId.value,
            empty =
              () => EmptyState(Messages.TopicEmptyTitle, description = Some(Messages.TopicEmptyDescription)),
            testId = Some("topic-consumers-table")
          )
        )
    }

  /** Four columns, and no more.
    *
    * The group list already shows a group's members, its topic count and its total lag. What this tab adds,
    * and the only reason it exists rather than being a link to a filtered group list, is the *topic* lag —
    * this group's lag on this topic alone, which is not its total lag whenever it consumes more than one.
    * Repeating the rest of the group list here would make two screens that disagree the moment one of them
    * changes.
    */
  private def columns(
      largestLag: Long,
      hrefFor: String => String,
      onOpen: String => Unit
  ): List[Column[TopicConsumerRowDto]] =
    List(
      Column[TopicConsumerRowDto](
        id = "group",
        header = Messages.ColumnGroup,
        render = row => nameCell(row, hrefFor, onOpen)
      ),
      Column[TopicConsumerRowDto](
        id = "state",
        header = Messages.ColumnState,
        render = row =>
          GroupStateChip(
            Val(row.group.state),
            testId = Some(s"topic-consumer-${row.group.groupId.value}-state")
          ),
        width = Some("11rem")
      ),
      Column[TopicConsumerRowDto](
        id = "partitions",
        header = Messages.ColumnPartitions,
        render = row => Numbers.grouped(row.partitions.toLong),
        align = ColumnAlign.Numeric
      ),
      Column[TopicConsumerRowDto](
        id = "topicLag",
        header = Messages.ColumnTopicLag,
        render = row => lagCell(row, largestLag),
        align = ColumnAlign.Numeric,
        width = Some("14rem")
      )
    )

  /** The group id as a real link, plus the "dormant" badge.
    *
    * Dormant — committed offsets on this topic and no members — is a badge and not a warning, because a batch
    * job between runs looks exactly like this and colouring it red would train operators to ignore red.
    */
  private def nameCell(
      row: TopicConsumerRowDto,
      hrefFor: String => String,
      onOpen: String => Unit
  ): Modifier[HtmlElement] =
    div(
      cls := ConsumersCss.GroupCell,
      a(
        cls := ConsumersCss.GroupLink,
        href := hrefFor(row.group.groupId.value),
        dataAttr("testid") := s"topic-consumer-${row.group.groupId.value}-link",
        row.group.groupId.value,
        // A modified click is the user asking their browser for a new tab, and swallowing it would break
        // the one gesture that makes a table of links useful.
        onClick
          .filter(event => !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey)
          .preventDefault --> { _ => onOpen(row.group.groupId.value) }
      ),
      Option.when(row.dormant)(
        Tag(label = Val(Messages.DormantChip), tone = Tone.Neutral)
          .amend(title := Messages.DormantExplanation)
      )
    )

  /** The topic lag and its bar, or an em dash saying the figure is not known.
    *
    * Never a zero for an unknown lag, for the same reason the group list never renders one: a group whose lag
    * could not be computed is not a group that has caught up, and the wire carries `null` rather than `0`
    * precisely so the two cannot look the same.
    */
  private def lagCell(row: TopicConsumerRowDto, largestLag: Long): Modifier[HtmlElement] =
    row.topicLag match {
      case Some(lag) =>
        MagnitudeBar(
          value = Val(Numbers.grouped(lag)),
          fraction = Val(Numbers.fraction(lag, largestLag)),
          inline = true,
          testId = Some(s"topic-consumer-${row.group.groupId.value}-lag")
        )
      case None =>
        span(
          dataAttr("testid") := s"topic-consumer-${row.group.groupId.value}-lag",
          title := Messages.LagUnknown,
          DataTable.missing
        )
    }

  private def errorPanel(error: ApiError, retry: () => Unit): HtmlElement =
    div(
      cls := ConsumersCss.Error,
      dataAttr("testid") := "topic-consumers-error",
      role := "alert",
      p(error.userMessage),
      Button(
        label = Val(Messages.TryAgain),
        onClick = Observer[Unit](_ => retry()),
        variant = ButtonVariant.Primary,
        testId = Some("topic-consumers-retry")
      )
    )
}
