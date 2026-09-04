package kui.ui.messages.produce

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.message.contract.{ProduceResultDto, ProducedRecordDto}
import kui.ui.kernel.api.ApiClient
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.messages.{Messages, MessagesCss, SerdeChoices}

/** The publish form: a drawer that writes a record to a topic.
  *
  * ## A drawer, not an expanding row
  *
  * The design puts detail *in place* on a row and composition *in a drawer*, and the split is by role rather
  * than by taste. Reading a record is something you do to one row among many, and moving it into a modal
  * would hide the list you are comparing it against. Writing one is a composition task with a dozen fields, a
  * focus trap and a submit button — it needs its own surface, and it needs Escape to mean "abandon this"
  * rather than "close that record".
  *
  * ## What it does when it succeeds
  *
  * It says where the record landed — partition and offset, per copy — and leaves the form filled in. Both
  * halves matter. An operator who has just published needs the offset in order to go and look at the record,
  * and a form that cleared itself after a successful send would throw away a value somebody may be about to
  * publish again with one field changed, which is the ordinary way this screen gets used while testing a
  * consumer. Kafbat's produce form keeps its contents for the same reason.
  *
  * ## Failures stay in the drawer
  *
  * A refusal — a read-only cluster, a partition that does not exist, a value the serde could not encode — is
  * shown beside the form with the user's text still in it. Closing the drawer and raising a toast would lose
  * the record they had composed, which on a long JSON payload is the difference between a corrected mistake
  * and a retyped document.
  */
object ProduceDrawer {

  /** Renders the drawer and the state behind it.
    *
    * @param draft
    *   the form's contents. `None` means the drawer is closed. It is a `Var` owned by the page rather than by
    *   this object so that "Publish" and "Republish this record" are the same drawer opened with different
    *   contents, instead of two drawers that can both be open at once.
    * @param onProduced
    *   told about every record that landed, so the page can say so somewhere the drawer is not covering.
    */
  def apply(
      cluster: ClusterId,
      draft: Var[Option[ProduceDraft]],
      api: ApiClient,
      onProduced: Observer[List[ProducedRecordDto]] = Observer.empty
  ): HtmlElement = {
    val problem: Var[Option[String]] = Var(None)
    val result: Var[Option[ProduceResultDto]] = Var(None)
    val sending: Var[Boolean] = Var(false)
    val sent = new EventBus[ProduceDraft]

    val open: Var[Boolean] = Var(false)

    // The drawer's own `open` flag and the draft are kept in step in one place, so that a page can open the
    // form simply by putting a draft in the `Var` and cannot leave the two disagreeing.
    val binding = draft.signal --> Observer[Option[ProduceDraft]] { current =>
      open.set(current.isDefined)
      if current.isEmpty then {
        problem.set(None)
        result.set(None)
      }
    }

    /** One field of the form, read out of the draft. `""` while the drawer is closed, which nothing sees. */
    def text(of: ProduceDraft => String): Signal[String] = draft.signal.map(_.fold("")(of))

    def field(name: String, control: HtmlElement): HtmlElement =
      div(
        cls := MessagesCss.ControlGroup,
        L.label(cls := MessagesCss.ControlLabel, name, control)
      )

    def update(change: ProduceDraft => ProduceDraft): Unit =
      draft.update(_.map(change))

    def body(): HtmlElement =
      div(
        cls := MessagesCss.Form,
        dataAttr("testid") := "produce-form",
        field(
          Messages.ProduceTopicLabel,
          input(
            tpe := "text",
            cls := KernelCss.FieldControl,
            dataAttr("testid") := "produce-topic",
            L.value <-- text(_.topic),
            onInput.mapToValue --> { raw => update(_.copy(topic = raw)) }
          )
        ),
        field(
          Messages.ProducePartitionLabel,
          input(
            tpe := "text",
            cls := KernelCss.FieldControl,
            L.placeholder := Messages.ProducePartitionPlaceholder,
            dataAttr("testid") := "produce-partition",
            L.value <-- text(_.partition),
            onInput.mapToValue --> { raw => update(_.copy(partition = raw)) }
          )
        ),
        // The key and its "no key" switch are one group, because they answer one question and the answer
        // is not readable from either control alone.
        absentable(
          Messages.ProduceKeyLabel,
          Messages.ProduceNoKeyLabel,
          testId = "key",
          absent = draft.signal.map(_.forall(!_.hasKey)),
          text = text(_.key),
          onAbsent = present => update(_.copy(hasKey = !present)),
          onText = raw => update(_.copy(key = raw))
        ),
        absentable(
          Messages.ProduceValueLabel,
          Messages.ProduceTombstoneLabel,
          testId = "value",
          absent = draft.signal.map(_.exists(_.isTombstone)),
          text = text(_.value),
          onAbsent = absent => update(_.copy(isTombstone = absent)),
          onText = raw => update(_.copy(value = raw))
        ),
        // The two serde choices sit between the payloads and the headers, next to the fields they act on
        // rather than at the bottom with the count: "Value as JSON" is a statement about the value box
        // above it, and a picker three controls away from what it changes gets set by nobody.
        serdePicker(
          Messages.ProduceKeySerdeLabel,
          testId = "produce-key-serde",
          chosen = text(_.keySerde),
          onChosen = raw => update(_.copy(keySerde = raw))
        ),
        serdePicker(
          Messages.ProduceValueSerdeLabel,
          testId = "produce-value-serde",
          chosen = text(_.valueSerde),
          onChosen = raw => update(_.copy(valueSerde = raw))
        ),
        headerRows(draft, update),
        field(
          Messages.ProduceCountLabel,
          input(
            tpe := "text",
            cls := KernelCss.FieldControl,
            dataAttr("testid") := "produce-count",
            title := Messages.ProduceCountHint,
            L.value <-- text(_.count),
            onInput.mapToValue --> { raw => update(_.copy(count = raw)) }
          )
        ),
        div(
          cls := MessagesCss.FormActions,
          Button(
            label = Val(Messages.Publish),
            onClick = Observer[Unit](_ => draft.now().foreach(sent.writer.onNext)),
            variant = ButtonVariant.Primary,
            loading = sending.signal,
            testId = Some("produce-submit")
          )
        ),
        child.maybe <-- problem.signal.map(
          _.map(message =>
            p(cls := MessagesCss.Error, role := "alert", dataAttr("testid") := "produce-error", message)
          )
        ),
        child.maybe <-- result.signal.map(
          _.map(answer =>
            div(
              cls := MessagesCss.FormResult,
              role := "status",
              dataAttr("testid") := "produce-result",
              p(Messages.published(answer.records.length)),
              ul(answer.records.map(record => li(where(record))))
            )
          )
        ),
        // The request itself. It hangs off the form element, so a drawer that is closed mid-flight stops
        // updating anything — the record may still land, and nothing here pretends otherwise, but no
        // detached element is written to.
        sent.events.flatMapSwitch(publish(cluster, api, problem, sending)) --> Observer[
          Either[String, ProduceResultDto]
        ] {
          case Left(message) =>
            sending.set(false)
            result.set(None)
            problem.set(Some(message))
          case Right(answer) =>
            sending.set(false)
            problem.set(None)
            result.set(Some(answer))
            onProduced.onNext(answer.records)
        }
      )

    div(
      binding,
      Drawer(
        open = open,
        title = Val(Messages.Publish),
        body = () => body(),
        width = "34rem",
        onClose = Observer[Unit](_ => draft.set(None)),
        testId = Some("produce-drawer")
      )
    )
  }

  // -----------------------------------------------------------------------------------------------

  /** Sends one draft, refusing it locally first so that a mistyped partition never reaches the network.
    *
    * The two failures are deliberately the same shape on the way back: whichever side refused, the drawer
    * shows one sentence beside a form that still holds what the user typed.
    */
  private def publish(
      cluster: ClusterId,
      api: ApiClient,
      problem: Var[Option[String]],
      sending: Var[Boolean]
  )(draft: ProduceDraft): EventStream[Either[String, ProduceResultDto]] =
    draft.request match {
      case Left(message) => EventStream.fromValue(Left(message))
      case Right((topic, request)) =>
        sending.set(true)
        problem.set(None)
        api
          .call(ProduceApi.produce, (cluster, topic, request))
          .map(_.left.map(_.userMessage))
    }

  /** One serde choice, as a menu.
    *
    * The same list the browse bar offers, from [[kui.ui.messages.SerdeChoices]], because the mistake this
    * form can make that a topic does not recover from is publishing with a serde the reader cannot read back
    * — and two lists maintained apart is how that becomes possible.
    *
    * `controlled`, so that a draft arriving from "Republish this record" — which fills the menu with the
    * serde that record was decoded with — cannot leave the DOM and the draft disagreeing.
    */
  private def serdePicker(
      label: String,
      testId: String,
      chosen: Signal[String],
      onChosen: String => Unit
  ): HtmlElement =
    div(
      cls := MessagesCss.ControlGroup,
      L.label(
        cls := MessagesCss.ControlLabel,
        label,
        select(
          cls := KernelCss.FieldControl,
          dataAttr("testid") := testId,
          SerdeChoices.options.map((value, name) => option(L.value := value, name)),
          controlled(L.value <-- chosen, onChange.mapToValue --> { raw => onChosen(raw) })
        )
      )
    )

  /** A label, its "there is none" switch, and the text box the switch disables.
    *
    * The box is disabled rather than hidden while the switch is on, so that the text somebody typed is still
    * there when they change their mind — and so that the layout does not jump under the pointer they are
    * about to click with.
    */
  private def absentable(
      label: String,
      absentLabel: String,
      testId: String,
      absent: Signal[Boolean],
      text: Signal[String],
      onAbsent: Boolean => Unit,
      onText: String => Unit
  ): HtmlElement =
    div(
      cls := MessagesCss.ControlGroup,
      div(
        cls := MessagesCss.FormRow,
        span(cls := MessagesCss.ControlLabel, label),
        L.label(
          cls := MessagesCss.ControlLabel,
          input(
            tpe := "checkbox",
            dataAttr("testid") := s"produce-$testId-absent",
            controlled(
              checked <-- absent,
              onInput.mapToChecked --> { on => onAbsent(on) }
            )
          ),
          absentLabel
        )
      ),
      textArea(
        cls := KernelCss.FieldControl,
        cls := MessagesCss.FormText,
        dataAttr("testid") := s"produce-$testId",
        rows := 6,
        L.disabled <-- absent,
        L.value <-- text,
        onInput.mapToValue --> { raw => onText(raw) }
      )
    )

  /** The headers editor: a name and a value per row, and a button that adds another.
    *
    * Rows are addressed by index, which is safe here because nothing reorders them: adding appends and
    * removing drops one. A keyed list would be more code for a control whose ordinary size is zero rows.
    */
  private def headerRows(
      draft: Var[Option[ProduceDraft]],
      update: (ProduceDraft => ProduceDraft) => Unit
  ): HtmlElement =
    div(
      cls := MessagesCss.ControlGroup,
      span(cls := MessagesCss.ControlLabel, Messages.ProduceHeadersLabel),
      div(
        cls := MessagesCss.FormHeaders,
        dataAttr("testid") := "produce-headers",
        children <-- draft.signal.map(_.fold(List.empty[(String, String)])(_.headers)).map { rows =>
          rows.zipWithIndex.map { case ((name, value), index) =>
            div(
              cls := MessagesCss.FormRow,
              input(
                tpe := "text",
                cls := KernelCss.FieldControl,
                L.placeholder := Messages.ProduceHeaderNamePlaceholder,
                dataAttr("testid") := s"produce-header-$index-name",
                L.value := name,
                onInput.mapToValue --> { raw =>
                  update(current => current.copy(headers = replace(current.headers, index, raw -> _._2)))
                }
              ),
              input(
                tpe := "text",
                cls := KernelCss.FieldControl,
                L.placeholder := Messages.ProduceHeaderValuePlaceholder,
                dataAttr("testid") := s"produce-header-$index-value",
                L.value := value,
                onInput.mapToValue --> { raw =>
                  update(current => current.copy(headers = replace(current.headers, index, _._1 -> raw)))
                }
              ),
              Button(
                label = Val(Messages.ProduceRemoveHeader),
                onClick = Observer[Unit](_ =>
                  update(current =>
                    current.copy(headers = current.headers.zipWithIndex.filterNot(_._2 == index).map(_._1))
                  )
                ),
                variant = ButtonVariant.Ghost,
                size = Size.Sm,
                testId = Some(s"produce-header-$index-remove")
              )
            )
          }
        }
      ),
      Button(
        label = Val(Messages.ProduceAddHeader),
        onClick =
          Observer[Unit](_ => update(current => current.copy(headers = current.headers :+ ("" -> "")))),
        variant = ButtonVariant.Secondary,
        size = Size.Sm,
        testId = Some("produce-add-header")
      )
    )

  /** One row of the header list, rewritten. */
  private def replace(
      rows: List[(String, String)],
      index: Int,
      change: ((String, String)) => (String, String)
  ): List[(String, String)] =
    rows.zipWithIndex.map((row, position) => if position == index then change(row) else row)

  /** Where one record landed, as a sentence rather than three numbers. */
  private[messages] def where(record: ProducedRecordDto): String =
    Messages.landedAt(record.partition.value, record.offset.value)
}
