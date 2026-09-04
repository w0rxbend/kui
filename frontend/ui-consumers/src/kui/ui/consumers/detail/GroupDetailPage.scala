package kui.ui.consumers.detail

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.consumer.contract.dto.GroupDetailDto
import kui.contracts.consumer.AssignmentFreshness
import kui.kernel.{ClusterId, GroupId}
import kui.ui.consumers.reset.ResetWizard
import kui.ui.consumers.{ConsumersCss, ConsumersQueries, GroupStateChip, Messages, Numbers}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.query.QueryState
import kui.ui.kernel.time.Timestamps

/** One consumer group: who is in it, what each member holds, and how far behind each partition is.
  *
  * ## One document, one moment
  *
  * Everything on this screen comes from a single answer, and that is a correctness property rather than an
  * optimisation. Lag is a subtraction between a committed offset and an end offset, so pairing a fresh commit
  * with a stale end offset produces a lag that never existed — a negative one, or a spike that resolves
  * itself the next time anybody looks. The service reads both in the same snapshot pass and sends them
  * together; assembling this page from four calls would undo that.
  *
  * ## What the bars are for
  *
  * A group with sixty partitions is a table nobody reads. The magnitude bar beside each partition's lag is
  * scaled against the worst partition *in that topic*, so the answer to "is this spread evenly or is one
  * partition stuck" is visible before a digit is read — and one stuck partition is the overwhelmingly common
  * shape of a consumer-group problem.
  *
  * ## An empty group is not an error
  *
  * A group id that does not exist answers 200 with an empty group in state `DEAD`, because the Kafka port
  * fabricates one for a describe of a group that is not there. So a stale bookmark lands on an empty group
  * page that says `Dead` rather than on an error, which is the truthful answer: KUI genuinely does not know
  * whether the group was deleted a minute ago or never existed.
  *
  * @param backHref
  *   where the breadcrumb goes. A real `href`, so the browser's own gestures work on it.
  */
object GroupDetailPage {

  def apply(
      cluster: ClusterId,
      group: GroupId,
      queries: ConsumersQueries,
      backHref: String,
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {

    val state: Signal[QueryState[GroupDetailDto]] = queries.group.state((cluster, group))

    val detail: Signal[Option[GroupDetailDto]] = state.map(_.lastGood)

    val stale: Signal[Option[StaleReason]] =
      state.map(current =>
        Option.when(current.isStale)(
          StaleReason.lastRequestFailed(
            current.outcome.flatMap(_.left.toOption).map(_.userMessage).getOrElse(Messages.StaleState)
          )
        )
      )

    /** The "as of" is the *group's own* `observedAt` and not the moment the browser received the bytes.
      *
      * Those are different times whenever anything is cached, and the one that makes a lag figure
      * interpretable is when the snapshot was taken — not when it was handed over.
      */
    val observedAt: Signal[Option[Instant]] = detail.map(_.map(_.observedAt))

    val refusal: Signal[Option[ApiError]] =
      state.map(current =>
        if current.lastGood.isEmpty then current.outcome.flatMap(_.left.toOption) else None
      )

    div(
      cls := ConsumersCss.Page,
      dataAttr("testid") := "page-consumers-detail",
      Breadcrumbs(
        Val(
          List(
            Crumb(Messages.BackToList, Some(backHref)),
            Crumb(group.value, None)
          )
        ),
        testId = Some("group-breadcrumbs")
      ),
      h1(group.value),
      child.maybe <-- refusal.map(
        _.map(error => errorPanel(error, () => queries.invalidateCluster(cluster)))
      ),
      child.maybe <-- detail.map(
        _.map(current =>
          StaleDataOverlay(
            content = body(current, zone, now),
            stale = stale,
            fetchedAt = observedAt,
            zone = zone,
            now = now,
            testId = Some("group-region")
          )
        )
      ),
      // A sibling of the snapshot-driven region rather than a child of it, so that a new snapshot redraws
      // the tables without taking the wizard — and the operator's place in it — down with them.
      wizard(cluster, group, queries, detail, zone, now)
    )
  }

  /** The offset-reset wizard, built once for the life of this screen.
    *
    * Once, and not once per snapshot, because the wizard holds the operator's place in it — which step they
    * are on, and after an apply the receipt of what was written. Building it inside the redraw that each new
    * group snapshot triggers destroyed that state at the worst possible moment: applying a reset is exactly
    * what makes the next snapshot differ, so the receipt was discarded in the same instant it arrived and
    * the drawer shut itself. Hence a `Signal` of the topics rather than a list.
    *
    * It renders nothing while the group holds no offsets on any topic: there is nothing to reset, and a form
    * whose topic list is empty is a control that can only refuse.
    *
    * There is no second confirmation around it and no disabled state for a group that has members. The
    * *server* refuses a reset of a live group — that check is made at plan time and again immediately before
    * the write, because the group can gain a member in between — and a screen that made its own copy of the
    * rule would be a second opinion about a safety property, which is how the two come to disagree.
    */
  private def wizard(
      cluster: ClusterId,
      group: GroupId,
      queries: ConsumersQueries,
      detail: Signal[Option[GroupDetailDto]],
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement = {
    val topics = detail.map(_.toList.flatMap(_.topics))

    div(
      // The wizard itself is never rebuilt; only whether it is shown depends on the snapshot. `display`
      // rather than `child.maybe` for the same reason the element is hoisted: taking it out of the DOM
      // would take its state with it.
      display <-- topics.map(current => if current.isEmpty then "none" else ""),
      ResetWizard(
        topics = topics,
        plan = request => queries.planReset(cluster, group, request),
        applyPlan = token => queries.applyReset(cluster, group, token),
        zone = zone,
        now = now
      )
    )
  }

  /** The summary strip, the members, and the per-topic assignment tables. The reset wizard is deliberately
    * not here: see [[wizard]] for why it is a sibling of this region instead of part of it.
    */
  private def body(
      group: GroupDetailDto,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    div(
      summary(group, zone, now),
      sectionTag(
        cls := ConsumersCss.Section,
        h2(cls := ConsumersCss.SectionHeading, Messages.MembersHeading),
        // The freshness note sits above the tables it qualifies, not below them, because it changes how
        // every number under it should be read.
        freshnessNote(group.assignments.status),
        MemberTable(group.members)
      ),
      sectionTag(
        cls := ConsumersCss.Section,
        h2(cls := ConsumersCss.SectionHeading, Messages.AssignmentsHeading),
        if group.topics.isEmpty then
          EmptyState(
            Messages.AssignmentsEmpty,
            description = Some(Messages.AssignmentsEmptyDescription),
            testId = Some("group-assignments-empty")
          )
        else div(group.topics.map(LagTable.apply))
      )
    )

  /** State, total lag, protocol, assignor, coordinator and "as of", on one strip.
    *
    * The total lag is the one figure an operator came here for, so it is beside the state chip rather than at
    * the bottom of a table of sixty partitions.
    */
  private def summary(group: GroupDetailDto, zone: Signal[String], now: () => Instant): HtmlElement =
    div(
      cls := ConsumersCss.Summary,
      dataAttr("testid") := "group-summary",
      item(Messages.ColumnState, GroupStateChip(Val(group.state), testId = Some("group-state"))),
      item(
        Messages.TotalLagLabel,
        span(
          dataAttr("testid") := "group-total-lag",
          // Never a zero for an unknown total: a group whose lag could not be computed has not caught up.
          group.totalLag.fold(DataTable.missing)(Numbers.grouped)
        ),
        // How many partitions the figure could not include, when any could not, because a total over fewer
        // partitions than the group holds is smaller than the truth.
        Option.when(group.excludedPartitions > 0)(Messages.excluded(group.excludedPartitions))
      ),
      item(Messages.ProtocolLabel, span(group.protocol.wire.toLowerCase)),
      item(
        Messages.AssignorLabel,
        // Kafka reports `""` for a group with no members, and translating that to an em dash would invent a
        // distinction the broker does not make — so the em dash is for the empty string, deliberately.
        span(Option(group.partitionAssignor).filter(_.nonEmpty).getOrElse(DataTable.missing))
      ),
      item(
        Messages.CoordinatorLabel,
        span(group.coordinatorId.fold(DataTable.missing)(_.toString))
      ),
      item(
        Messages.ObservedAtLabel,
        span(
          dataAttr("testid") := "group-observed-at",
          // The relative form is what a person reads; the absolute one, in their chosen zone, is on the
          // title for when "4 minutes ago" is not precise enough to correlate with a log line.
          Timestamps.relative(group.observedAt, now()),
          title <-- zone.map(zoneId => Timestamps.absolute(group.observedAt, zoneId))
        )
      )
    )

  private def item(label: String, value: HtmlElement, hint: Option[String] = None): HtmlElement =
    div(
      cls := ConsumersCss.SummaryItem,
      span(cls := ConsumersCss.SummaryLabel, label),
      span(cls := ConsumersCss.SummaryValue, value),
      hint.map(text => span(cls := ConsumersCss.Note, text))
    )

  /** What to say when the assignments below are not the current ones.
    *
    * During a rebalance Kafka reports no assignments at all. Drawing an empty members table then would tell
    * an operator that their consumers had stopped, which is the opposite of what is happening — so the last
    * ones seen are drawn, and this sentence says so.
    */
  private def freshnessNote(status: AssignmentFreshness): Option[HtmlElement] =
    status match {
      case AssignmentFreshness.Current => None
      case AssignmentFreshness.LastSeen =>
        Some(
          p(
            cls := ConsumersCss.Note,
            dataAttr("testid") := "group-assignments-note",
            Messages.AssignmentsLastSeen
          )
        )
      case AssignmentFreshness.Unknown =>
        Some(
          p(
            cls := ConsumersCss.Note,
            dataAttr("testid") := "group-assignments-note",
            Messages.AssignmentsUnknown
          )
        )
    }

  private def errorPanel(error: ApiError, retry: () => Unit): HtmlElement =
    div(
      cls := ConsumersCss.Error,
      dataAttr("testid") := "group-error",
      role := "alert",
      p(error.userMessage),
      Button(
        label = Val(Messages.TryAgain),
        onClick = Observer[Unit](_ => retry()),
        variant = ButtonVariant.Primary,
        testId = Some("group-retry")
      )
    )
}
