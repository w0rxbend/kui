package kui.ui.consumers.reset

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.consumer.contract.dto.{PlannedPartitionDto, ResetPlanDto, ResetPlanRequest}
import kui.contracts.consumer.TopicSubscriptionDto
import kui.kernel.group.ResetTarget
import kui.ui.consumers.{ConsumersCss, Messages, Numbers}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.time.Timestamps

/** Where the wizard is. One value, so that the screen cannot be in two of these at once.
  *
  * The states are deliberately not booleans on the side. "A plan is being fetched" and "a plan is on screen"
  * and "the plan is being applied" all disable the same buttons and show different things, and three
  * independent flags is how a screen ends up offering Apply while a plan is still being computed.
  */
enum ResetStep {
  case Composing
  case Planning

  /** A plan is on screen and has not been applied. `Apply` sends `plan.token` and nothing else. */
  case Planned(plan: ResetPlanDto)

  case Applying(plan: ResetPlanDto)

  /** The write happened. `receipt` is what the server says it wrote, not what the browser asked for. */
  case Applied(receipt: ResetPlanDto)
}

object ResetStep {
  given CanEqual[ResetStep, ResetStep] = CanEqual.derived
}

/** The offset-reset wizard: choose, preview, confirm, receipt (ADR-045).
  *
  * ## Why a preview is not a nicety
  *
  * The operator types "reset to 09:00". What that means in offsets depends on what exists at 09:00 on each of
  * twelve partitions, on whether retention has moved past that point, and on KIP-122's rule that a timestamp
  * with no matching record resolves to the *end* of the partition — which is the opposite of what the person
  * expects and is how a reset intended to replay a morning's traffic instead skips it entirely.
  *
  * Only the broker knows those numbers. So the first request resolves them and changes nothing, the screen
  * shows what each partition would move from and to, and the second request writes exactly that.
  *
  * ## The confirmation is the plan token, and there is no second one
  *
  * The apply endpoint takes a token and nothing else. The Apply button therefore cannot exist before a plan
  * does — not because a rule on this screen disables it, but because there is nothing to send. There is no
  * "type the group name to confirm" here either: that guards against clicking the wrong row, and the failure
  * this flow is about is the one where the operator is confident and the arithmetic is the surprise.
  *
  * A token expires after five minutes. When it does, the server refuses the apply and the wizard goes back to
  * the plan step rather than quietly re-planning: a re-plan would compute different offsets from the ones on
  * screen, which is the whole thing being prevented.
  *
  * ## A plan that changes nothing offers nothing to confirm
  *
  * `noOp` means every partition is already where the reset would put it. The wizard says so and shows no
  * Apply button, because a confirmation dialogue for an operation that changes nothing teaches operators to
  * click through confirmation dialogues.
  *
  * ## Why the topic list arrives as a `Signal`
  *
  * The wizard holds the only copy of where the operator has got to — which step they are on, and, after an
  * apply, the receipt saying what was written. That state lives in this element, so the element has to
  * outlive every redraw of the page around it.
  *
  * It did not. The group detail page rebuilt its whole body from each new snapshot of the group, and an apply
  * *causes* a new snapshot: the committed offsets it just wrote are what changed. So the receipt was set on
  * an element that was replaced in the same breath, and the wizard blinked back to its closed state the
  * instant the write succeeded. The reset itself was correct — the offsets moved, the lag on screen changed —
  * but the operator never saw the confirmation of what had been written, which is the one record they have of
  * a destructive action.
  *
  * Taking the topic list as a `Signal` is what lets the page build this element once and let the data flow
  * through it, instead of rebuilding it whenever the data changes.
  *
  * @param topics
  *   the topics this group holds offsets on, with their partitions, as they currently stand. The topic list
  *   is the group's own rather than the cluster's: resetting a group on a topic it does not consume writes
  *   offsets for a subscription that does not exist.
  * @param plan
  *   and `apply`, passed in, so the whole flow can be driven by a suite with no server.
  */
object ResetWizard {

  def apply(
      topics: Signal[List[TopicSubscriptionDto]],
      plan: ResetPlanRequest => EventStream[Either[ApiError, ResetPlanDto]],
      applyPlan: String => EventStream[Either[ApiError, ResetPlanDto]],
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {
    val open: Var[Boolean] = Var(false)
    val step: Var[ResetStep] = Var(ResetStep.Composing)

    /** The latest topic list, held so that the click handlers below can read it synchronously.
      *
      * A `Signal` cannot be sampled without an owner, and the "preview" handler needs the partitions of the
      * chosen topic at the moment of the click. This `Var` is fed from the incoming signal and is the one
      * place that conversion happens.
      */
    val known: Var[List[TopicSubscriptionDto]] = Var(Nil)

    val form: Var[ResetForm] = Var(ResetForm.Empty)

    /** The last thing that went wrong, whether it was the form's own refusal or the server's.
      *
      * One place, because from the operator's side "I filled this in wrongly" and "the cluster refused" are
      * the same question — what do I do now — and two separate places to look for the answer is one too many.
      */
    val problem: Var[Option[String]] = Var(None)

    val partitions: Signal[List[Int]] =
      form.signal.map(_.topic).combineWith(known.signal).map(partitionsOf)

    val requests: EventBus[ResetPlanRequest] = new EventBus[ResetPlanRequest]
    val applications: EventBus[String] = new EventBus[String]

    def compose(): Unit = {
      step.set(ResetStep.Composing)
      problem.set(None)
    }

    def askForAPlan(current: ResetForm, available: List[Int]): Unit =
      ResetForm.requestOf(current, available) match {
        case Left(message) => problem.set(Some(message))
        case Right(request) =>
          problem.set(None)
          step.set(ResetStep.Planning)
          requests.writer.onNext(request)
      }

    div(
      cls := ConsumersCss.Section,
      dataAttr("testid") := "group-reset",

      // The incoming list, kept where the handlers can read it, and used once to seed the topic the form
      // starts on. Seeded rather than bound: after the operator has chosen a topic, a later snapshot of the
      // group must not move them off it.
      topics --> Observer[List[TopicSubscriptionDto]] { current =>
        known.set(current)
        if form.now().topic.isEmpty then
          current.headOption.foreach(first => form.update(_.copy(topic = first.topic.value)))
      },
      h2(cls := ConsumersCss.SectionHeading, Messages.ResetHeading),

      // --- The two requests, and where their answers go ------------------------------------------

      requests.events.flatMapSwitch(plan) --> Observer[Either[ApiError, ResetPlanDto]] {
        case Right(planned) => step.set(ResetStep.Planned(planned))
        case Left(error) =>
          // Back to the form, with the reason. Staying on a "planning" spinner that will never finish is
          // the one outcome an operator cannot act on.
          step.set(ResetStep.Composing)
          problem.set(Some(error.userMessage))
      },
      applications.events.flatMapSwitch(applyPlan) --> Observer[Either[ApiError, ResetPlanDto]] {
        case Right(receipt) =>
          problem.set(None)
          step.set(ResetStep.Applied(receipt))
        case Left(error) =>
          // Back to the *plan*, not to the form: the plan is still what the operator read, and if the token
          // has expired the honest next step is to ask for a new one rather than to re-plan silently.
          problem.set(Some(error.userMessage))
          step.update {
            case ResetStep.Applying(planned) => ResetStep.Planned(planned)
            case other => other
          }
      },

      // --- The screen ----------------------------------------------------------------------------

      child <-- open.signal.map(isOpen =>
        if isOpen then
          Button(
            label = Val(Messages.ResetClose),
            onClick = Observer[Unit] { _ =>
              open.set(false)
              compose()
            },
            variant = ButtonVariant.Ghost,
            testId = Some("group-reset-close")
          )
        else
          Button(
            label = Val(Messages.ResetOpen),
            onClick = Observer[Unit](_ => open.set(true)),
            variant = ButtonVariant.Secondary,
            testId = Some("group-reset-open")
          )
      ),
      child.maybe <-- problem.signal.map(
        _.map(message =>
          p(cls := ConsumersCss.Error, dataAttr("testid") := "group-reset-problem", role := "alert", message)
        )
      ),
      child.maybe <-- open.signal
        .combineWith(step.signal)
        .map((isOpen, current) =>
          Option.when(isOpen)(
            current match {
              case ResetStep.Composing =>
                composer(
                  known.signal,
                  form,
                  partitions,
                  busy = false,
                  onPreview = () => askForAPlan(form.now(), partitionsOf(form.now().topic, known.now()))
                )

              case ResetStep.Planning =>
                composer(known.signal, form, partitions, busy = true, onPreview = () => ())

              case ResetStep.Planned(planned) =>
                preview(
                  planned,
                  zone,
                  now,
                  busy = false,
                  onApply = () => {
                    step.set(ResetStep.Applying(planned))
                    applications.writer.onNext(planned.token)
                  },
                  onBack = () => compose()
                )

              case ResetStep.Applying(planned) =>
                preview(planned, zone, now, busy = true, onApply = () => (), onBack = () => ())

              case ResetStep.Applied(receipt) => this.receipt(receipt, () => compose())
            }
          )
        )
    )
  }

  private def partitionsOf(topic: String, topics: List[TopicSubscriptionDto]): List[Int] =
    topics.find(_.topic.value == topic).toList.flatMap(_.partitions.map(_.partition)).sorted

  /** Step one: what the operator is asking for. */
  private def composer(
      topics: Signal[List[TopicSubscriptionDto]],
      form: Var[ResetForm],
      partitions: Signal[List[Int]],
      busy: Boolean,
      onPreview: () => Unit
  ): HtmlElement = {
    val topic: Var[Option[String]] = Var(Some(form.now().topic).filter(_.nonEmpty))
    val target: Var[Option[ResetTarget]] = Var(Some(form.now().target))
    val offset: Var[String] = Var(form.now().offset)
    val timestamp: Var[String] = Var(form.now().timestamp)
    val shiftBy: Var[String] = Var(form.now().shiftBy)
    val duration: Var[String] = Var(form.now().durationMinutes)

    def parameterIs(name: String): Signal[Boolean] =
      target.signal.map(_.flatMap(ResetForm.parameterOf).contains(name))

    div(
      cls := ConsumersCss.ResetForm,
      dataAttr("testid") := "group-reset-form",
      p(cls := ConsumersCss.Note, Messages.ResetIntro),

      // Every control writes straight into the one form value, so there is a single place the request is
      // built from and no chance of the screen and the request disagreeing about what was chosen.
      topic.signal --> Observer[Option[String]](chosen => form.update(_.copy(topic = chosen.getOrElse("")))),
      target.signal --> Observer[Option[ResetTarget]](chosen =>
        form.update(_.copy(target = chosen.getOrElse(ResetTarget.Earliest)))
      ),
      offset.signal --> Observer[String](raw => form.update(_.copy(offset = raw))),
      timestamp.signal --> Observer[String](raw => form.update(_.copy(timestamp = raw))),
      shiftBy.signal --> Observer[String](raw => form.update(_.copy(shiftBy = raw))),
      duration.signal --> Observer[String](raw => form.update(_.copy(durationMinutes = raw))),

      Select[String](
        options = topics.map(_.map(one => one.topic.value -> one.topic.value)),
        selected = topic,
        label = Messages.ResetTopicLabel,
        disabled = Val(busy),
        testId = Some("group-reset-topic")
      ),
      Select[ResetTarget](
        // The label is the target's own sentence — "The beginning of each partition" — not its wire name.
        // `EARLIEST` is a word about the protocol; this is a control a person reads.
        options = Val(ResetTarget.All.map(one => one -> one.label)),
        selected = target,
        label = Messages.ResetTargetLabel,
        disabled = Val(busy),
        testId = Some("group-reset-target")
      ),
      child.maybe <-- parameterIs("offset").map(
        Option.when(_)(
          TextInput(
            value = offset,
            label = Messages.ResetOffsetLabel,
            hint = Some(Messages.ResetOffsetHint),
            disabled = Val(busy),
            testId = Some("group-reset-offset")
          )
        )
      ),
      child.maybe <-- parameterIs("timestamp").map(
        Option.when(_)(
          TextInput(
            value = timestamp,
            label = Messages.ResetTimestampLabel,
            hint = Some(Messages.ResetTimestampHint),
            disabled = Val(busy),
            testId = Some("group-reset-timestamp")
          ).amend(
            // A real date-and-time control rather than free text, so the browser does the parsing and an
            // operator on a phone gets a picker. Set on the mounted element because `TextInput` builds the
            // `<input>` itself, and giving it a `type` parameter for one caller would be a worse trade.
            onMountCallback(context =>
              Option(context.thisNode.ref.querySelector("input"))
                .foreach(_.setAttribute("type", "datetime-local"))
            )
          )
        )
      ),
      child.maybe <-- parameterIs("shiftBy").map(
        Option.when(_)(
          TextInput(
            value = shiftBy,
            label = Messages.ResetShiftLabel,
            hint = Some(Messages.ResetShiftHint),
            disabled = Val(busy),
            testId = Some("group-reset-shift")
          )
        )
      ),
      child.maybe <-- parameterIs("durationMinutes").map(
        Option.when(_)(
          TextInput(
            value = duration,
            label = Messages.ResetDurationLabel,
            hint = Some(Messages.ResetDurationHint),
            disabled = Val(busy),
            testId = Some("group-reset-duration")
          )
        )
      ),
      p(
        cls := ConsumersCss.Note,
        dataAttr("testid") := "group-reset-scope",
        text <-- partitions.map(scopeOf)
      ),
      Button(
        label = Val(if busy then Messages.ResetPlanning else Messages.ResetPreview),
        onClick = Observer[Unit](_ => onPreview()),
        variant = ButtonVariant.Primary,
        disabled = Val(busy),
        testId = Some("group-reset-preview")
      )
    )
  }

  private def scopeOf(partitions: List[Int]): String =
    if partitions.isEmpty then Messages.NoPartitions
    else if partitions.size == 1 then "1 partition will be moved."
    else s"${partitions.size} partitions will be moved."

  /** Step two: the numbers, and the one button that writes them. */
  private def preview(
      plan: ResetPlanDto,
      zone: Signal[String],
      now: () => Instant,
      busy: Boolean,
      onApply: () => Unit,
      onBack: () => Unit
  ): HtmlElement =
    div(
      cls := ConsumersCss.ResetPlan,
      dataAttr("testid") := "group-reset-plan",
      h3(cls := ConsumersCss.SectionHeading, Messages.ResetPlanHeading),

      // Warnings above the table, because they change how every number under them should be read. Clamping
      // is the case they exist for: an operator who asked for offset 9 000 000 on a partition that holds
      // four hundred records has to see what will actually be written.
      warnings(plan),
      partitionTable(plan, "group-reset-plan-table"),
      p(
        cls := ConsumersCss.Note,
        dataAttr("testid") := "group-reset-expiry",
        child.text <-- zone.map(where => Messages.resetExpires(Timestamps.absolute(plan.expiresAt, where)))
      ),
      if plan.noOp then
        p(cls := ConsumersCss.Note, dataAttr("testid") := "group-reset-noop", Messages.ResetNoOp)
      else
        Button(
          label = Val(if busy then Messages.ResetApplying else Messages.ResetApply),
          onClick = Observer[Unit](_ => onApply()),
          variant = ButtonVariant.Danger,
          disabled = Val(busy),
          testId = Some("group-reset-apply")
        )
      ,
      Button(
        label = Val(Messages.ResetStartAgain),
        onClick = Observer[Unit](_ => onBack()),
        variant = ButtonVariant.Ghost,
        disabled = Val(busy),
        testId = Some("group-reset-back")
      ),
      // Referenced so the clock is a parameter of this component rather than something it reaches for; the
      // expiry above is the only thing on this panel that is relative to now.
      onMountCallback(_ => now(): Unit)
    )

  /** Step three: what was actually written. */
  private def receipt(applied: ResetPlanDto, onDone: () => Unit): HtmlElement =
    div(
      cls := ConsumersCss.ResetPlan,
      dataAttr("testid") := "group-reset-receipt",
      h3(cls := ConsumersCss.SectionHeading, Messages.ResetReceiptHeading),
      p(cls := ConsumersCss.Note, Messages.ResetApplied),
      partitionTable(applied, "group-reset-receipt-table"),
      Button(
        label = Val(Messages.ResetDone),
        onClick = Observer[Unit](_ => onDone()),
        variant = ButtonVariant.Secondary,
        testId = Some("group-reset-done")
      )
    )

  private def warnings(plan: ResetPlanDto): HtmlElement =
    ul(
      cls := ConsumersCss.ResetWarnings,
      dataAttr("testid") := "group-reset-warnings",
      plan.warnings.map(one => li(dataAttr("kind") := one.kind, one.message))
    )

  /** The four columns that make a reset readable: which partition, where it is, where it goes, by how much.
    *
    * The same table draws the plan and the receipt, because they are the same document — the apply endpoint
    * answers with what it wrote — and two tables would be two chances for "what we said we would do" and
    * "what we did" to be rendered differently.
    */
  private def partitionTable(plan: ResetPlanDto, testId: String): HtmlElement =
    DataTable[PlannedPartitionDto](
      columns = List(
        Column[PlannedPartitionDto](
          id = "partition",
          header = Messages.ResetColumnPartition,
          render = row => row.partition.toString,
          align = ColumnAlign.Numeric
        ),
        Column[PlannedPartitionDto](
          id = "from",
          header = Messages.ResetColumnFrom,
          // An em dash and never a zero. A partition this group has never committed on is not a partition
          // whose consumer sits at the beginning of the log, and rendering `0` would say exactly that.
          render = row =>
            row.current match {
              case Some(current) => span(Numbers.grouped(current))
              case None => span(title := Messages.ResetNoCurrent, DataTable.missing)
            },
          align = ColumnAlign.Numeric
        ),
        Column[PlannedPartitionDto](
          id = "to",
          header = Messages.ResetColumnTo,
          render = row => Numbers.grouped(row.proposed),
          align = ColumnAlign.Numeric
        ),
        Column[PlannedPartitionDto](
          id = "delta",
          header = Messages.ResetColumnChange,
          // Signed, because "this rewinds 4 200 records" and "this skips 4 200 records" are the two
          // different things an operator is deciding between, and the sign is the whole difference.
          render = row =>
            row.delta match {
              case Some(delta) if delta > 0L => span(s"+${Numbers.grouped(delta)}")
              case Some(delta) => span(Numbers.grouped(delta))
              case None => span(DataTable.missing)
            },
          align = ColumnAlign.Numeric
        )
      ),
      rows = Val(plan.partitions.sortBy(_.partition)),
      rowKey = _.partition.toString,
      empty = () => EmptyState(Messages.NoPartitions),
      testId = Some(testId)
    )
}
