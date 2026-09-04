package kui.ui.topics.admin

import com.raquo.laminar.api.L.*

import kui.topic.contract.dto.{TopicConfigResponse, UpdateTopicConfigRequest}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.topics.{Messages, TopicsCss}

/** Change one of a topic's settings, or put it back to the broker's default.
  *
  * ==One setting at a time, deliberately==
  *
  * The reference product edits the whole dynamic set: it submits every key it is holding, and every key it is
  * *not* holding is deleted. That makes "change the retention" a request that can silently revert an override
  * somebody else set an hour ago. KUI's endpoint is a PATCH carrying a change — keys to set, keys to reset,
  * everything else untouched — and this dialog is the smallest honest thing that fits it.
  *
  * ==Reset is not "set it to the default's current value"==
  *
  * They differ later. A key that has been *reset* follows the broker's default if an operator changes that
  * default next month; a key set to the number that default happens to be today stays at that number for
  * ever. The checkbox says which one is happening, because the Settings table cannot show the difference
  * afterwards without the `source` column an operator would have to know to read.
  *
  * @param update
  *   passed in rather than reached for, so the flow can be driven by a suite with no server.
  */
object EditSettingDialog {

  def apply(
      open: Var[Boolean],
      /** The key being edited, and its current value where it has one. Set by the Settings table's Edit
        * button; `("", None)` is the "add a setting" case, where the key is typed.
        */
      editing: Var[(String, Option[String])],
      update: UpdateTopicConfigRequest => EventStream[Either[ApiError, TopicConfigResponse]]
  ): HtmlElement = {
    val key: Var[String] = Var("")
    val value: Var[String] = Var("")
    val reset: Var[Boolean] = Var(false)
    val busy: Var[Boolean] = Var(false)
    val problem: Var[Option[String]] = Var(None)
    val pending: Var[Option[UpdateTopicConfigRequest]] = Var(None)

    val resetId = Components.nextId("kui-topic-setting-reset")

    val submit: Observer[Unit] = Observer[Unit] { _ =>
      val name = key.now().trim

      if name.isEmpty then problem.set(Some(Messages.EditSettingNoKey))
      else {
        problem.set(None)
        busy.set(true)
        pending.set(
          Some(
            if reset.now() then UpdateTopicConfigRequest(Map.empty, List(name))
            else UpdateTopicConfigRequest(Map(name -> value.now()), Nil)
          )
        )
      }
    }

    div(
      cls := TopicsCss.EditWrapper,
      // The dialog is filled from whatever the table last asked to edit. Writing both fields from one
      // place is what stops the key and the value belonging to different rows.
      editing.signal --> { (name, current) =>
        key.set(name)
        value.set(current.getOrElse(""))
        reset.set(false)
        problem.set(None)
        busy.set(false)
      },
      Dialog(
        open = open,
        title = Val(Messages.EditSettingTitle),
        body = () =>
          div(
            cls := TopicsCss.Form,
            p(cls := TopicsCss.FormHint, Messages.EditSettingHint),
            TextInput(
              value = key,
              label = Messages.EditSettingKey,
              placeholder = "retention.ms",
              testId = Some("topic-setting-key")
            ),
            TextInput(
              value = value,
              label = Messages.EditSettingValue,
              placeholder = "604800000",
              disabled = reset.signal,
              testId = Some("topic-setting-value")
            ),
            label(
              cls := TopicsCss.Toggle,
              forId := resetId,
              input(
                idAttr := resetId,
                tpe := "checkbox",
                dataAttr("testid") := "topic-setting-reset",
                controlled(checked <-- reset.signal, onInput.mapToChecked --> reset.writer)
              ),
              span(Messages.EditSettingReset)
            ),
            child.maybe <-- problem.signal.map(
              _.map(message =>
                p(
                  cls := TopicsCss.FormError,
                  role := "alert",
                  dataAttr("testid") := "topic-setting-error",
                  message
                )
              )
            )
          ),
        actions = () =>
          List(
            Button(
              Val(Messages.Cancel),
              Observer[Unit](_ => open.set(false)),
              testId = Some("topic-setting-cancel")
            ),
            Button(
              label = Val(Messages.EditSettingSubmit),
              onClick = submit,
              variant = ButtonVariant.Primary,
              loading = busy.signal,
              testId = Some("topic-setting-submit")
            )
          ),
        size = Size.Sm,
        dismissible = false,
        testId = Some("topic-setting-dialog")
      ),
      pending.signal.changes.collect { case Some(request) => request }.flatMapSwitch(update) --> { outcome =>
        busy.set(false)
        pending.set(None)
        outcome match {
          case Right(_) => open.set(false)
          case Left(error) => problem.set(Some(error.userMessage))
        }
      }
    )
  }
}
