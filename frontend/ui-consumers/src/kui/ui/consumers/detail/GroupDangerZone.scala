package kui.ui.consumers.detail

import com.raquo.laminar.api.L.*

import kui.consumer.contract.dto.DeletedOffsetsDto
import kui.contracts.consumer.TopicSubscriptionDto
import kui.kernel.{GroupId, TopicName}
import kui.ui.consumers.{ConsumersCss, Messages}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState

/** The two ways to throw a consumer group's state away, on the screen that shows that state.
  *
  * ==Both endpoints have answered since M4 and nothing has ever called them==
  *
  * `DELETE …/consumer-groups/{groupId}` and `DELETE …/consumer-groups/{groupId}/offsets?topic=…` were built,
  * tested, routed through the gateway and left with no control anywhere in the product. The reset wizard
  * beside this panel is the only thing an operator could ever do to a group from KUI, which meant that
  * cleaning up after a decommissioned service required a shell and `kafka-consumer-groups.sh`.
  *
  * ==Why no plan token, when a reset needs one==
  *
  * ADR-045's two-phase shape exists because the *result* of a reset is arithmetic nobody can do in their
  * head: what "09:00" means in offsets depends on retention and on KIP-122's end-of-partition rule. The
  * operator has to be shown the numbers before they agree to them.
  *
  * A delete has no numbers. The group is named in the path and the outcome is that it is gone. What that
  * needs is a confirmation the operator cannot dismiss by reflex, and that is a property of this screen — a
  * dialogue whose default focus is Cancel — rather than of a second request. Inventing a plan token here
  * would be ceremony with nothing in it, and ceremony with nothing in it is what teaches people to click
  * through the ceremony that matters.
  *
  * ==What is deliberately not checked here==
  *
  * Kafka refuses to delete a group that still has members, and this panel does not disable the button for
  * one. The rule is the server's, it is re-checked immediately before the write, and a consumer can join in
  * the second between the click and the request — so a copy of it on this screen would be a second opinion
  * about a safety property that is sometimes wrong. The refusal comes back as `KUI-GROUP-NOT-EMPTY` and is
  * rendered as the sentence it is, which is the one refusal an operator can act on directly by stopping their
  * consumers.
  *
  * ==Deleting offsets on one topic is the smaller instrument==
  *
  * It is offered per topic, on the topics the group actually holds offsets on, because that is the operation
  * for "this service stopped reading that topic six months ago" — where deleting the whole group would take
  * the eleven subscriptions that are still in use with it. The receipt names the partitions that were
  * forgotten, so "the group had none there" and "they are gone now" are different sentences on the screen; a
  * bare success could not tell them apart, and the difference decides whether the operator is finished.
  *
  * @param onDeleted
  *   what to do once the group is gone. The page it was on no longer describes anything, so the caller
  *   navigates away rather than leaving a screen about a group that does not exist.
  */
object GroupDangerZone {

  def apply(
      group: GroupId,
      topics: Signal[List[TopicSubscriptionDto]],
      deleteGroup: () => EventStream[Either[ApiError, Unit]],
      deleteOffsets: TopicName => EventStream[Either[ApiError, DeletedOffsetsDto]],
      onDeleted: () => Unit,
      permitted: Signal[Boolean] = Val(true),
      capability: Signal[FeatureState] = Val(FeatureState.Ready)
  ): HtmlElement = {

    /** Set to the group id when the confirmed delete should run. A `Var` of an intent rather than a direct
      * call, because the request has to be started from inside the element's own subscription: a stream begun
      * in a click handler has no owner and is never cancelled when the screen goes away.
      */
    val deleting: Var[Boolean] = Var(false)

    /** The topic whose offsets a confirmed delete should forget, for the same reason. */
    val forgetting: Var[Option[TopicName]] = Var(None)

    val confirmingGroup: Var[Boolean] = Var(false)

    /** Which topic the open dialogue is about, and whether it is open, as two values.
      *
      * `ConfirmDialog` owns the open flag — it closes itself on both buttons and on Escape — so the topic has
      * to be held beside it rather than encoded in it. Reading the topic out of an `Option` that the dialogue
      * could clear underneath us is how a confirmation ends up applying to nothing.
      */
    val confirmingTopicOpen: Var[Boolean] = Var(false)
    val confirmingTopic: Var[Option[TopicName]] = Var(None)

    val problem: Var[Option[String]] = Var(None)
    val receipt: Var[Option[DeletedOffsetsDto]] = Var(None)

    sectionTag(
      cls := ConsumersCss.DangerSection,
      dataAttr("testid") := "group-danger-zone",
      h2(cls := ConsumersCss.SectionHeading, Messages.DangerHeading),
      p(cls := ConsumersCss.Note, Messages.DangerDescription),

      // ---------------------------------------------------------------------------- per-topic offsets
      div(
        cls := ConsumersCss.DangerAction,
        dataAttr("testid") := "group-forget-offsets",
        h3(cls := ConsumersCss.DangerActionHeading, Messages.ForgetOffsetsHeading),
        p(cls := ConsumersCss.Note, Messages.ForgetOffsetsDescription),
        child <-- topics.map { subscriptions =>
          if subscriptions.isEmpty then
            p(
              cls := ConsumersCss.Note,
              dataAttr("testid") := "group-forget-offsets-none",
              Messages.ForgetOffsetsNone
            )
          else
            ul(
              cls := ConsumersCss.DangerList,
              subscriptions.map(subscription =>
                li(
                  span(cls := ConsumersCss.TopicName, subscription.topic.value),
                  ActionPermissionWrapper(
                    action = Button(
                      label = Val(Messages.ForgetOffsets),
                      onClick = Observer[Unit] { _ =>
                        problem.set(None)
                        receipt.set(None)
                        confirmingTopic.set(Some(subscription.topic))
                        confirmingTopicOpen.set(true)
                      },
                      variant = ButtonVariant.Danger,
                      testId = Some(s"group-forget-offsets-${subscription.topic.value}")
                    ),
                    capability = capability,
                    permitted = permitted
                  )
                )
              )
            )
        }
      ),

      // ---------------------------------------------------------------------------- the whole group
      div(
        cls := ConsumersCss.DangerAction,
        dataAttr("testid") := "group-delete",
        h3(cls := ConsumersCss.DangerActionHeading, Messages.DeleteGroupHeading),
        p(cls := ConsumersCss.Note, Messages.DeleteGroupDescription),
        ActionPermissionWrapper(
          action = Button(
            label = Val(Messages.DeleteGroup),
            onClick = Observer[Unit] { _ =>
              problem.set(None)
              receipt.set(None)
              confirmingGroup.set(true)
            },
            variant = ButtonVariant.Danger,
            testId = Some("group-delete-button")
          ),
          capability = capability,
          permitted = permitted
        )
      ),
      child.maybe <-- receipt.signal.map(
        _.map(answer =>
          p(
            cls := ConsumersCss.Receipt,
            role := "status",
            dataAttr("testid") := "group-forget-offsets-receipt",
            Messages.forgotOffsets(answer.topic.value, answer.partitions.size)
          )
        )
      ),
      child.maybe <-- problem.signal.map(
        _.map(message =>
          p(
            cls := ConsumersCss.Error,
            role := "alert",
            dataAttr("testid") := "group-danger-error",
            message
          )
        )
      ),
      ConfirmDialog(
        open = confirmingGroup,
        title = Val(Messages.DeleteGroupConfirmTitle),
        message = Val(Messages.deleteGroupConfirmMessage(group.value)),
        onConfirm = Observer[Unit](_ => deleting.set(true)),
        confirmLabel = Messages.DeleteGroup,
        testId = Some("group-delete-confirm")
      ),
      // One dialogue for every topic rather than one per row: the topic being forgotten is in the message,
      // and a dialogue per subscription would be sixty hidden dialogues on a group with sixty topics.
      ConfirmDialog(
        open = confirmingTopicOpen,
        title = Val(Messages.ForgetOffsetsConfirmTitle),
        message =
          confirmingTopic.signal.map(_.fold("")(topic => Messages.forgetOffsetsConfirmMessage(topic.value))),
        onConfirm = Observer[Unit](_ => forgetting.set(confirmingTopic.now())),
        confirmLabel = Messages.ForgetOffsets,
        testId = Some("group-forget-offsets-confirm")
      ),
      deleting.signal.changes.filter(identity).flatMapSwitch(_ => deleteGroup()) --> { outcome =>
        deleting.set(false)
        outcome match {
          case Right(()) => onDeleted()
          case Left(error) => problem.set(Some(error.userMessage))
        }
      },
      forgetting.signal.changes.collect { case Some(topic) => topic }.flatMapSwitch(deleteOffsets) -->
        { outcome =>
          forgetting.set(None)
          confirmingTopic.set(None)
          outcome match {
            case Right(answer) => receipt.set(Some(answer))
            case Left(error) => problem.set(Some(error.userMessage))
          }
        }
    )
  }
}
