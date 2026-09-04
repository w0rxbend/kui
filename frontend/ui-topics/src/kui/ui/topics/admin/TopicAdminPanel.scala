package kui.ui.topics.admin

import com.raquo.laminar.api.L.*

import kui.kernel.TopicName
import kui.message.contract.{PurgePlanDto, PurgeReceiptDto}
import kui.topic.contract.dto.{DeletionPlanDto, PartitionPlanDto, PlanWarningDto}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState
import kui.ui.topics.{Messages, TopicsCss}

/** Where a plan-confirmed change has got to. One value, so the panel cannot be in two of these at once.
  *
  * Three independent booleans — "planning", "planned", "applying" — is how a screen ends up offering Apply
  * while a plan is still being computed. The plan itself is *inside* the state, which is what makes the Apply
  * button unable to exist before a plan does: there is no token to send.
  */
enum ChangeStep[+A] {
  case Idle extends ChangeStep[Nothing]
  case Planning extends ChangeStep[Nothing]
  case Planned[A](plan: A) extends ChangeStep[A]
  case Applying[A](plan: A) extends ChangeStep[A]
  case Applied[A](receipt: A) extends ChangeStep[A]
}

object ChangeStep {
  given [A] => CanEqual[ChangeStep[A], ChangeStep[A]] = CanEqual.derived
}

/** The two changes to a topic that cannot be undone: growing it, and deleting it.
  *
  * ==Why they share a panel, and why it is at the bottom==
  *
  * Because they share a property that nothing else on the topic page has: after either one, there is no way
  * back. Grouping them is how an operator learns where the irreversible controls are, rather than meeting one
  * beside a refresh button.
  *
  * ==Each one is preview, then confirm, and the browser cannot skip the preview==
  *
  * Not because a rule on this screen disables the button, but because the server's apply endpoints take a
  * plan token and there is nothing else to send (ADR-045). The preview is worth the extra click for a
  * different reason in each case:
  *
  *   - growing a topic changes how Kafka routes *every future record*. Records are placed by
  *     `hash(key) % partitions`, so raising the count sends most keys to a different partition from the
  *     records already stored under them; per-key ordering, which is the only ordering Kafka offers, is
  *     broken across the change. The plan says so in this panel rather than only in the documentation.
  *   - deleting a topic can silently undo itself. A cluster with `auto.create.topics.enable` recreates the
  *     topic — with the broker's defaults and none of its configuration — the moment any client names it.
  *     KUI's own message browser was bitten by exactly this, so the plan reports what the cluster answered:
  *     on, off, or "KUI could not read it", which is shown as its own warning and never as "off".
  *
  * ==What happens after a delete==
  *
  * The panel keeps the receipt on screen and calls `onDeleted`. It does not re-read the topic: the topic is
  * gone, so a refresh would answer 404 and the operator's confirmation of what they destroyed would be
  * replaced by an error about a topic they know is missing.
  */
object TopicAdminPanel {

  def apply(
      topic: TopicName,
      partitionCount: Signal[Option[Int]],
      planPartitions: Int => EventStream[Either[ApiError, PartitionPlanDto]],
      applyPartitions: String => EventStream[Either[ApiError, PartitionPlanDto]],
      planDeletion: () => EventStream[Either[ApiError, DeletionPlanDto]],
      applyDeletion: String => EventStream[Either[ApiError, DeletionPlanDto]],
      planPurge: () => EventStream[Either[ApiError, PurgePlanDto]],
      applyPurge: String => EventStream[Either[ApiError, PurgeReceiptDto]],
      onDeleted: () => Unit,
      /** Whether this user may delete this topic — E4's worked example of RBAC gating, and the shape every
        * other write control in the product should copy.
        *
        * The decision is made by `Rbac.decide` in `libs/security-core`, from the permission list `/auth/me`
        * returned, which means the interface's answer and the server's come from one function rather than
        * from two implementations that can disagree. `Val(true)` by default so that a caller which has not
        * been converted yet behaves exactly as it did before; the topic detail page passes the real signal.
        */
      deletePermitted: Signal[Boolean] = Val(true),
      /** Whether the topic service can act at all. Merged with `deletePermitted` into one tooltip by
        * `ActionPermissionWrapper`, so a user who both lacks permission and has a service down is told both,
        * rather than fixing one and discovering the other.
        */
      deleteCapability: Signal[FeatureState] = Val(FeatureState.Ready),
      /** Whether this user may change this topic — `TOPIC.EDIT`, which is what raising the partition count
        * needs. Gated for the same reason the delete is: growing a topic cannot be undone, and finding that
        * out from a 403 after composing the change is the experience E4 exists to remove.
        */
      editPermitted: Signal[Boolean] = Val(true),
      /** Whether this user may empty this topic — `TOPIC.MESSAGES_DELETE`, which is a different permission
        * from deleting the topic itself. An operator may well be trusted to purge a queue and not to remove
        * it, so the two controls are gated separately rather than on one "may administer" flag.
        */
      purgePermitted: Signal[Boolean] = Val(true)
  ): HtmlElement = {

    /** Whether the topic has been deleted from under this panel.
      *
      * Once it has, the partition controls above are hidden rather than left on screen. They were still
      * offering to grow a topic that no longer exists — the first thing a person notices when they use this —
      * and a control whose only outcome is a 404 is worse than no control. The receipt stays, because for an
      * irreversible action it is the operator's only record of what happened.
      */
    val gone: Var[Boolean] = Var(false)

    div(
      cls := TopicsCss.Danger,
      dataAttr("testid") := "topic-danger-zone",
      h2(cls := TopicsCss.DangerTitle, Messages.DangerTitle),
      p(
        cls := TopicsCss.FormHint,
        child.text <-- gone.signal.map(if _ then Messages.DangerGone else Messages.DangerHint)
      ),
      partitionsSection(partitionCount, planPartitions, applyPartitions, editPermitted, deleteCapability)
        .amend(cls(TopicsCss.Hidden) <-- gone.signal),
      purgeSection(topic, planPurge, applyPurge, purgePermitted, deleteCapability)
        .amend(cls(TopicsCss.Hidden) <-- gone.signal),
      deleteSection(
        topic,
        planDeletion,
        applyDeletion,
        () => {
          gone.set(true)
          onDeleted()
        },
        deletePermitted,
        deleteCapability
      )
    )
  }

  // ------------------------------------------------------------------------------------ partitions

  private def partitionsSection(
      partitionCount: Signal[Option[Int]],
      plan: Int => EventStream[Either[ApiError, PartitionPlanDto]],
      applyPlan: String => EventStream[Either[ApiError, PartitionPlanDto]],
      permitted: Signal[Boolean],
      capability: Signal[FeatureState]
  ): HtmlElement = {
    val target: Var[String] = Var("")
    val step: Var[ChangeStep[PartitionPlanDto]] = Var(ChangeStep.Idle)
    val problem: Var[Option[String]] = Var(None)
    val planning: Var[Option[Int]] = Var(None)
    val applying: Var[Option[String]] = Var(None)

    div(
      cls := TopicsCss.DangerSection,
      dataAttr("testid") := "topic-partitions-section",
      h3(cls := TopicsCss.DangerSectionTitle, Messages.AddPartitionsTitle),
      p(
        cls := TopicsCss.FormHint,
        child.text <-- partitionCount.map(_.fold(Messages.AddPartitionsUnknown)(Messages.addPartitionsNow))
      ),
      div(
        cls := TopicsCss.DangerControls,
        TextInput(
          value = target,
          label = Messages.AddPartitionsLabel,
          placeholder = "12",
          testId = Some("topic-partitions-target")
        ),
        // Both halves are gated, the preview as well as the confirmation: composing a change the server
        // will refuse is the failure mode, and it happens at the first click, not the last.
        ActionPermissionWrapper(
          action = Button(
            label = Val(Messages.PreviewPartitions),
            onClick = Observer[Unit] { _ =>
              target.now().trim.toIntOption.filter(_ > 0) match {
                case None => problem.set(Some(Messages.AddPartitionsInvalid))
                case Some(count) =>
                  problem.set(None)
                  step.set(ChangeStep.Planning)
                  planning.set(Some(count))
              }
            },
            disabled = step.signal.map(isBusy),
            testId = Some("topic-partitions-plan")
          ),
          capability = capability,
          permitted = permitted,
          testId = Some("topic-partitions-plan-gate")
        )
      ),
      // The plan, the receipt and the failure, each rendered from the one state value.
      child.maybe <-- step.signal.map {
        case ChangeStep.Planned(plan) =>
          Some(
            div(
              cls := TopicsCss.Plan,
              dataAttr("testid") := "topic-partitions-plan-result",
              p(cls := TopicsCss.PlanSummary, Messages.partitionPlan(plan.current, plan.target)),
              warnings(plan.warnings),
              ActionPermissionWrapper(
                action = Button(
                  label = Val(Messages.AddPartitionsConfirm),
                  onClick = Observer[Unit] { _ =>
                    step.set(ChangeStep.Applying(plan))
                    // The token and nothing else. There is no path here that sends the number again.
                    plan.token.foreach(token => applying.set(Some(token)))
                  },
                  variant = ButtonVariant.Danger,
                  testId = Some("topic-partitions-apply")
                ),
                capability = capability,
                permitted = permitted,
                testId = Some("topic-partitions-apply-gate")
              )
            )
          )

        case ChangeStep.Applied(receipt) =>
          Some(
            p(
              cls := TopicsCss.Receipt,
              role := "status",
              dataAttr("testid") := "topic-partitions-receipt",
              Messages.partitionsApplied(receipt.current, receipt.target)
            )
          )

        case _ => None
      },
      child.maybe <-- problem.signal.map(_.map(alert("topic-partitions-error"))),
      planning.signal.changes.collect { case Some(count) => count }.flatMapSwitch(plan) --> { outcome =>
        planning.set(None)
        outcome match {
          case Right(answer) => step.set(ChangeStep.Planned(answer))
          case Left(error) =>
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      },
      applying.signal.changes.collect { case Some(token) => token }.flatMapSwitch(applyPlan) --> { outcome =>
        applying.set(None)
        outcome match {
          case Right(receipt) =>
            target.set("")
            step.set(ChangeStep.Applied(receipt))
          case Left(error) =>
            // Back to Idle rather than back to the plan: the token has been spent or has expired, and
            // offering the same button again would ask the operator to confirm a plan the server will
            // no longer accept.
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      }
    )
  }

  // ----------------------------------------------------------------------------------------- purge

  /** Emptying the topic: the operation ADR-045 was written for.
    *
    * It is beside the delete rather than on the message browser because that is where a person looks for it —
    * "empty this topic" and "delete this topic" are neighbours in an operator's head, whatever they are in
    * the service map.
    *
    * A plan over a topic that is already empty offers no confirmation at all. A confirmation dialogue for an
    * operation that changes nothing is how operators learn to click through confirmation dialogues.
    */
  private def purgeSection(
      topic: TopicName,
      plan: () => EventStream[Either[ApiError, PurgePlanDto]],
      applyPlan: String => EventStream[Either[ApiError, PurgeReceiptDto]],
      permitted: Signal[Boolean],
      capability: Signal[FeatureState]
  ): HtmlElement = {
    val step: Var[ChangeStep[PurgePlanDto]] = Var(ChangeStep.Idle)
    val receipt: Var[Option[PurgeReceiptDto]] = Var(None)
    val problem: Var[Option[String]] = Var(None)
    val planning: Var[Boolean] = Var(false)
    val applying: Var[Option[String]] = Var(None)
    val confirming: Var[Boolean] = Var(false)

    div(
      cls := TopicsCss.DangerSection,
      dataAttr("testid") := "topic-purge-section",
      h3(cls := TopicsCss.DangerSectionTitle, Messages.PurgeTitle),
      p(cls := TopicsCss.FormHint, Messages.PurgeHint),
      ActionPermissionWrapper(
        action = Button(
          label = Val(Messages.PreviewPurge),
          onClick = Observer[Unit] { _ =>
            problem.set(None)
            receipt.set(None)
            step.set(ChangeStep.Planning)
            planning.set(true)
          },
          disabled = step.signal.map(isBusy),
          testId = Some("topic-purge-plan")
        ),
        capability = capability,
        permitted = permitted,
        testId = Some("topic-purge-plan-gate")
      ),
      child.maybe <-- step.signal.map {
        case ChangeStep.Planned(plan) =>
          Some(
            div(
              cls := TopicsCss.Plan,
              dataAttr("testid") := "topic-purge-plan-result",
              p(
                cls := TopicsCss.PlanSummary,
                Messages.purgePlan(topic.value, plan.records, plan.partitions.count(_.records > 0L))
              ),
              warnings(plan.warnings.map(warning => PlanWarningDto(warning.code, warning.message))),
              // Nothing to confirm when there is nothing to delete.
              child.maybe <-- Val(
                Option.when(!plan.isNoOp)(
                  ActionPermissionWrapper(
                    action = Button(
                      label = Val(Messages.PurgeConfirm),
                      onClick = Observer[Unit](_ => confirming.set(true)),
                      variant = ButtonVariant.Danger,
                      testId = Some("topic-purge-apply")
                    ),
                    capability = capability,
                    permitted = permitted,
                    testId = Some("topic-purge-apply-gate")
                  )
                )
              )
            )
          )

        case _ => None
      },
      child.maybe <-- receipt.signal.map(
        _.map(answer =>
          p(
            cls := TopicsCss.Receipt,
            role := "status",
            dataAttr("testid") := "topic-purge-receipt",
            Messages.purged(topic.value, answer.plan.records, answer.result.failed.size)
          )
        )
      ),
      child.maybe <-- problem.signal.map(_.map(alert("topic-purge-error"))),
      ConfirmDialog(
        open = confirming,
        title = Val(Messages.PurgeConfirmTitle),
        message = Val(Messages.purgeConfirmMessage(topic.value)),
        onConfirm = Observer[Unit] { _ =>
          step.now() match {
            case ChangeStep.Planned(plan) =>
              step.set(ChangeStep.Applying(plan))
              plan.token.foreach(token => applying.set(Some(token)))
            case _ => ()
          }
        },
        confirmLabel = Messages.PurgeConfirm,
        testId = Some("topic-purge-confirm")
      ),
      planning.signal.changes.filter(identity).flatMapSwitch(_ => plan()) --> { outcome =>
        planning.set(false)
        outcome match {
          case Right(answer) => step.set(ChangeStep.Planned(answer))
          case Left(error) =>
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      },
      applying.signal.changes.collect { case Some(token) => token }.flatMapSwitch(applyPlan) --> { outcome =>
        applying.set(None)
        outcome match {
          case Right(answer) =>
            receipt.set(Some(answer))
            step.set(ChangeStep.Idle)
          case Left(error) =>
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      }
    )
  }

  // ---------------------------------------------------------------------------------------- delete

  private def deleteSection(
      topic: TopicName,
      plan: () => EventStream[Either[ApiError, DeletionPlanDto]],
      applyPlan: String => EventStream[Either[ApiError, DeletionPlanDto]],
      onDeleted: () => Unit,
      permitted: Signal[Boolean],
      capability: Signal[FeatureState]
  ): HtmlElement = {
    val step: Var[ChangeStep[DeletionPlanDto]] = Var(ChangeStep.Idle)
    val problem: Var[Option[String]] = Var(None)
    val planning: Var[Boolean] = Var(false)
    val applying: Var[Option[String]] = Var(None)
    val confirming: Var[Boolean] = Var(false)

    div(
      cls := TopicsCss.DangerSection,
      dataAttr("testid") := "topic-delete-section",
      h3(cls := TopicsCss.DangerSectionTitle, Messages.DeleteTitle),
      p(cls := TopicsCss.FormHint, Messages.DeleteHint),
      // Both halves of the delete are gated, not just the last one. Planning a delete the user may not
      // perform renders a page of counts and warnings that ends in a refusal, which is a worse experience
      // than a disabled button that says why.
      ActionPermissionWrapper(
        action = Button(
          label = Val(Messages.PreviewDelete),
          onClick = Observer[Unit] { _ =>
            problem.set(None)
            step.set(ChangeStep.Planning)
            planning.set(true)
          },
          disabled = step.signal.map(isBusy),
          testId = Some("topic-delete-plan")
        ),
        capability = capability,
        permitted = permitted,
        testId = Some("topic-delete-plan-gate")
      ).amend(cls(TopicsCss.Hidden) <-- step.signal.map(isApplied)),
      child.maybe <-- step.signal.map {
        case ChangeStep.Planned(plan) =>
          Some(
            div(
              cls := TopicsCss.Plan,
              dataAttr("testid") := "topic-delete-plan-result",
              p(
                cls := TopicsCss.PlanSummary,
                Messages.deletionPlan(topic.value, plan.partitions, plan.records)
              ),
              warnings(plan.warnings),
              ActionPermissionWrapper(
                action = Button(
                  label = Val(Messages.DeleteConfirm),
                  onClick = Observer[Unit](_ => confirming.set(true)),
                  variant = ButtonVariant.Danger,
                  testId = Some("topic-delete-apply")
                ),
                capability = capability,
                permitted = permitted,
                testId = Some("topic-delete-apply-gate")
              )
            )
          )

        case ChangeStep.Applied(receipt) =>
          Some(
            p(
              cls := TopicsCss.Receipt,
              role := "status",
              dataAttr("testid") := "topic-delete-receipt",
              Messages.deleted(topic.value, receipt.records)
            )
          )

        case _ => None
      },
      child.maybe <-- problem.signal.map(_.map(alert("topic-delete-error"))),
      // A second, typed-out confirmation on top of the plan. It guards a different failure from the plan's:
      // the plan answers "what will this do", and this answers "did you mean this row".
      ConfirmDialog(
        open = confirming,
        title = Val(Messages.DeleteConfirmTitle),
        message = Val(Messages.deleteConfirmMessage(topic.value)),
        onConfirm = Observer[Unit] { _ =>
          step.now() match {
            case ChangeStep.Planned(plan) =>
              step.set(ChangeStep.Applying(plan))
              plan.token.foreach(token => applying.set(Some(token)))
            case _ => ()
          }
        },
        confirmLabel = Messages.DeleteConfirm,
        testId = Some("topic-delete-confirm")
      ),
      planning.signal.changes.filter(identity).flatMapSwitch(_ => plan()) --> { outcome =>
        planning.set(false)
        outcome match {
          case Right(answer) => step.set(ChangeStep.Planned(answer))
          case Left(error) =>
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      },
      applying.signal.changes.collect { case Some(token) => token }.flatMapSwitch(applyPlan) --> { outcome =>
        applying.set(None)
        outcome match {
          case Right(receipt) =>
            step.set(ChangeStep.Applied(receipt))
            onDeleted()
          case Left(error) =>
            step.set(ChangeStep.Idle)
            problem.set(Some(error.userMessage))
        }
      }
    )
  }

  // -------------------------------------------------------------------------------------- plumbing

  /** The server's own sentences, rendered whole and never paraphrased.
    *
    * They are computed on the server precisely so that an API user and a browser user are warned about the
    * same thing (ADR-045), and a friendlier rewording here would break that on the screen where it matters.
    */
  private def warnings(all: List[PlanWarningDto]): HtmlElement =
    ul(
      cls := TopicsCss.PlanWarnings,
      all.map(warning =>
        li(
          cls := TopicsCss.PlanWarning,
          dataAttr("testid") := s"topic-plan-warning-${warning.code.toLowerCase.replace('_', '-')}",
          warning.message
        )
      )
    )

  private def alert(testId: String)(message: String): HtmlElement =
    p(cls := TopicsCss.FormError, role := "alert", dataAttr("testid") := testId, message)

  private def isApplied[A](step: ChangeStep[A]): Boolean = step match {
    case ChangeStep.Applied(_) => true
    case ChangeStep.Idle | ChangeStep.Planning | ChangeStep.Planned(_) | ChangeStep.Applying(_) => false
  }

  private def isBusy[A](step: ChangeStep[A]): Boolean = step match {
    case ChangeStep.Planning | ChangeStep.Applying(_) => true
    case ChangeStep.Idle | ChangeStep.Planned(_) | ChangeStep.Applied(_) => false
  }
}
