package kui.ui.topics.admin

import com.raquo.laminar.api.L.*

import kui.kernel.TopicName
import kui.topic.contract.dto.{CreateTopicRequest, CreatedTopicDto}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState
import kui.ui.topics.{Messages, TopicsCss}

/** The button that opens "New topic", and the form behind it.
  *
  * ==What is deliberately left blank==
  *
  * Partitions and replication factor start empty, and an empty field is sent as *absent* rather than as a
  * one. Kafka then applies the broker's own `num.partitions` and `default.replication.factor`, which on any
  * cluster an operator has configured is what they meant. A form that prefilled `1` would create
  * single-replica topics on a three-broker cluster and would look, on screen, exactly as if the operator had
  * chosen that. The dialog says what an empty field means rather than making the reader infer it, and the
  * receipt afterwards reports what the broker actually made.
  *
  * ==Why configuration is free-form==
  *
  * Kafka's set of topic-level settings changes with every release. A dropdown of the settings KUI knows about
  * would be a list that goes stale and starts refusing settings the broker accepts, so the entries are typed
  * and an unknown key is refused *by the broker*, with the broker's own refusal shown. The two most-used keys
  * are offered as placeholder text, which helps without constraining.
  *
  * @param create
  *   passed in rather than reached for, so the whole flow can be driven by a suite with no server.
  */
object CreateTopicDialog {

  /** One configuration entry being typed. A stable `id` because Laminar needs a key that survives a row above
    * it being removed; using the key itself would make two blank rows the same row.
    */
  final case class Entry(id: Int, key: Var[String], value: Var[String])

  def apply(
      create: CreateTopicRequest => EventStream[Either[ApiError, CreatedTopicDto]],
      onCreated: TopicName => Unit,
      permitted: Signal[Boolean] = Val(true)
  ): HtmlElement = {
    val open: Var[Boolean] = Var(false)
    val name: Var[String] = Var("")
    val partitions: Var[String] = Var("")
    val replication: Var[String] = Var("")
    val entries: Var[List[Entry]] = Var(Nil)
    val nextId: Var[Int] = Var(0)
    val busy: Var[Boolean] = Var(false)

    /** The composed request, waiting to be sent.
      *
      * The click handler has to be synchronous — it reads the form's `Var`s — while the call has to be a
      * stream bound to an element, so that it is cancelled when the page goes away. This `Var` is the join
      * between the two, and it is created per dialog rather than held on the object: a value on the object
      * would be shared by every instance of the feature and two tabs would submit each other's forms.
      */
    val pending: Var[Option[CreateTopicRequest]] = Var(None)

    /** The last thing that went wrong, whether the form refused it or the cluster did.
      *
      * One place, because from the operator's side "I filled this in wrongly" and "the cluster refused" are
      * the same question — what do I do now — and two places to look for the answer is one too many.
      */
    val problem: Var[Option[String]] = Var(None)

    def reset(): Unit = {
      name.set("")
      partitions.set("")
      replication.set("")
      entries.set(Nil)
      problem.set(None)
      busy.set(false)
    }

    def addEntry(): Unit = {
      val id = nextId.now()
      nextId.set(id + 1)
      entries.update(_ :+ Entry(id, Var(""), Var("")))
    }

    /** What the form holds, as the request — or the sentence saying why it is not one yet.
      *
      * The checks here are the ones the browser can make truthfully: a name Kafka's own rules reject, and a
      * count that is not a positive number. Everything else — a replication factor larger than the cluster, a
      * configuration key this broker does not know — is the broker's to refuse, and guessing at it here would
      * mean refusing things a newer Kafka accepts.
      */
    def compose(): Either[String, CreateTopicRequest] =
      for {
        topic <- TopicName.from(name.now().trim).left.map(_ => Messages.CreateNameInvalid)
        count <- optionalPositive(partitions.now(), Messages.CreatePartitionsInvalid)
        factor <- optionalPositive(replication.now(), Messages.CreateReplicationInvalid)
        config <- configOf(entries.now())
      } yield CreateTopicRequest(topic, count, factor, config)

    val submit: Observer[Unit] = Observer[Unit] { _ =>
      compose() match {
        case Left(message) => problem.set(Some(message))
        case Right(request) =>
          problem.set(None)
          busy.set(true)
          pending.set(Some(request))
      }
    }

    div(
      cls := TopicsCss.CreateWrapper,
      // Gated, because it was not, and a `viewer` with `TOPIC: [VIEW, MESSAGES_READ]` was offered a
      // "New topic" button that opened a form, accepted a name, and ended in `KUI-FORBIDDEN` — the exact
      // shape of control this product keeps promising not to ship. Every other write on the topic screens
      // already went through this wrapper; this one had been missed.
      //
      // Disabled with the reason on it rather than removed, which is ADR-032's choice throughout: a control
      // that is simply absent leaves a user unable to tell "I may not" from "KUI cannot".
      ActionPermissionWrapper(
        action = Button(
          label = Val(Messages.CreateTopic),
          onClick = Observer[Unit] { _ =>
            reset()
            open.set(true)
          },
          variant = ButtonVariant.Primary,
          icon = Some(() => Icon.plus),
          testId = Some("topic-create-open")
        ),
        // Not gated on the topic service's health here: the list screen this sits on already tells the
        // user when the service is unavailable, and the create call itself reports its own failure.
        capability = Val(FeatureState.Ready),
        permitted = permitted,
        testId = Some("topic-create-gate")
      ),
      Dialog(
        open = open,
        title = Val(Messages.CreateTopicTitle),
        body = () =>
          div(
            cls := TopicsCss.Form,
            p(cls := TopicsCss.FormHint, Messages.CreateHint),
            TextInput(
              value = name,
              label = Messages.CreateNameLabel,
              placeholder = "orders.v1",
              hint = Some(Messages.CreateNameHint),
              testId = Some("topic-create-name")
            ),
            TextInput(
              value = partitions,
              label = Messages.CreatePartitionsLabel,
              placeholder = Messages.CreateBrokerDefault,
              hint = Some(Messages.CreatePartitionsHint),
              testId = Some("topic-create-partitions")
            ),
            TextInput(
              value = replication,
              label = Messages.CreateReplicationLabel,
              placeholder = Messages.CreateBrokerDefault,
              hint = Some(Messages.CreateReplicationHint),
              testId = Some("topic-create-replication")
            ),
            div(
              cls := TopicsCss.FormSection,
              h3(cls := TopicsCss.FormSectionTitle, Messages.CreateConfigTitle),
              p(cls := TopicsCss.FormHint, Messages.CreateConfigHint),
              div(
                cls := TopicsCss.ConfigRows,
                children <-- entries.signal.map(_.map(entryRow(entries)))
              ),
              Button(
                label = Val(Messages.CreateAddSetting),
                onClick = Observer[Unit](_ => addEntry()),
                testId = Some("topic-create-add-setting")
              )
            ),
            child.maybe <-- problem.signal.map(_.map(alert))
          ),
        actions = () =>
          List(
            Button(
              Val(Messages.Cancel),
              Observer[Unit](_ => open.set(false)),
              testId = Some("topic-create-cancel")
            ),
            Button(
              label = Val(Messages.CreateSubmit),
              onClick = submit,
              variant = ButtonVariant.Primary,
              loading = busy.signal,
              testId = Some("topic-create-submit")
            )
          ),
        size = Size.Md,
        // Not dismissible by a stray click: the form holds typed work, and a backdrop click that threw it
        // away would be indistinguishable from a cancel the operator meant.
        dismissible = false,
        testId = Some("topic-create-dialog")
      ),
      // The submission itself. It lives here, bound to the wrapper, so its subscription's lifetime is the
      // page's rather than the dialog's — a dialog that closed on success would otherwise cancel the very
      // request whose answer closes it.
      submissions(pending, create, busy, problem, open, onCreated)
    )
  }

  /** The stream that turns a composed request into a call, and its answer into a closed dialog.
    *
    * Bound to the wrapper rather than to the dialog, so that closing the dialog on success does not cancel
    * the very request whose answer closes it.
    */
  private def submissions(
      pending: Var[Option[CreateTopicRequest]],
      create: CreateTopicRequest => EventStream[Either[ApiError, CreatedTopicDto]],
      busy: Var[Boolean],
      problem: Var[Option[String]],
      open: Var[Boolean],
      onCreated: TopicName => Unit
  ): Modifier[HtmlElement] =
    pending.signal.changes.collect { case Some(request) => request }.flatMapSwitch(create) --> { outcome =>
      busy.set(false)
      pending.set(None)
      outcome match {
        case Right(created) =>
          open.set(false)
          onCreated(created.name)
        case Left(error) => problem.set(Some(error.userMessage))
      }
    }

  private def entryRow(entries: Var[List[Entry]])(entry: Entry): HtmlElement =
    div(
      cls := TopicsCss.ConfigRow,
      TextInput(
        value = entry.key,
        label = Messages.CreateSettingKey,
        placeholder = "retention.ms",
        testId = Some(s"topic-create-setting-key-${entry.id}")
      ),
      TextInput(
        value = entry.value,
        label = Messages.CreateSettingValue,
        placeholder = "604800000",
        testId = Some(s"topic-create-setting-value-${entry.id}")
      ),
      Button(
        label = Val(Messages.Remove),
        onClick = Observer[Unit](_ => entries.update(_.filterNot(_.id == entry.id))),
        testId = Some(s"topic-create-setting-remove-${entry.id}")
      )
    )

  private def alert(message: String): HtmlElement =
    p(cls := TopicsCss.FormError, role := "alert", dataAttr("testid") := "topic-create-error", message)

  /** An empty field is absent — the broker's default — and anything else has to be a positive number.
    *
    * The distinction is the whole reason this is not `toIntOption.getOrElse(1)`: "leave it to the broker" and
    * "one" are different requests, and only one of them is what an empty field means.
    */
  private[admin] def optionalPositive(raw: String, message: String): Either[String, Option[Int]] =
    raw.trim match {
      case "" => Right(None)
      case text => text.toIntOption.filter(_ > 0).map(Some(_)).toRight(message)
    }

  /** The typed entries as a configuration map, or the sentence saying which one is not usable.
    *
    * A blank key with a value is a refusal rather than a dropped row: an operator who typed a value and
    * forgot its key must be told, not have their setting silently discarded. A wholly blank row is ignored,
    * because that is what an added-and-not-used row is.
    */
  private[admin] def configOf(entries: List[Entry]): Either[String, Map[String, String]] = {
    val filled = entries.map(entry => entry.key.now().trim -> entry.value.now().trim)

    if filled.exists((key, value) => key.isEmpty && value.nonEmpty) then Left(Messages.CreateSettingNoKey)
    else Right(filled.filter((key, _) => key.nonEmpty).toMap)
  }
}
