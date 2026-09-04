package kui.ui.messages.track

import java.time.Instant

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.message.contract.{TrackHitDto, TrackMatchDto, TrackResultDto}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.{Button, ButtonVariant, EmptyState}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.time.Timestamps
import kui.ui.messages.MessagesCss

/** Following one business event across several topics (ET-003).
  *
  * ## The question this screen exists to answer
  *
  * "Where did order 4711 go?" A support engineer has an identifier and six topics, and no console consumer
  * can answer it: the records are in different topics, written at different times, and the answer is the list
  * of them in time order. Kouncil's second defining feature, and the reason its users keep it.
  *
  * ## Why nothing happens until Search is pressed
  *
  * A track is a bounded read of every named topic across the whole window, and there is no index behind it. A
  * screen that searched as the user typed would start a multi-topic scan per keystroke on a cluster somebody
  * is already investigating an incident on. So the form is filled in, the window is chosen, and Search reads.
  *
  * ## Why the scanned count is on screen even when there are no hits
  *
  * Because "nothing matched" and "nothing was read" are the same screen without it and mean opposite things.
  * The first says the value is not in those topics in that window; the second says the window was empty and
  * the user should widen it before concluding anything. That distinction is the difference between a support
  * engineer closing a ticket correctly and closing it wrongly.
  */
object TrackPage {

  def apply(
      cluster: ClusterId,
      api: ApiClient,
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {

    val form: Var[TrackForm] = Var(TrackForm.initial(now()))
    val problem: Var[Option[String]] = Var(None)
    val result: Var[Option[TrackResultDto]] = Var(None)
    val running: Var[Boolean] = Var(false)

    val pressed = new EventBus[TrackForm]

    div(
      cls := MessagesCss.Page,
      dataAttr("testid") := "page-track",
      h1(TrackMessages.Title),
      p(cls := MessagesCss.Lead, TrackMessages.Lead),
      controls(form, pressed),
      child.maybe <-- problem.signal.map(
        _.map(text => p(cls := MessagesCss.Error, dataAttr("testid") := "track-problem", text))
      ),
      summaryLine(result.signal, running.signal),
      child <-- result.signal
        .combineWith(zone)
        .map((answer, zoneId) => answer.fold(emptyState)(hitsTable(_, zoneId))),
      // One search at a time. `flatMapSwitch` so that pressing Search again abandons the answer to the
      // older question rather than letting it arrive after the newer one and overwrite it.
      pressed.events.flatMapSwitch { filled =>
        TrackForm.query(filled) match {
          case Left(complaint) =>
            problem.set(Some(complaint))
            EventStream.empty
          case Right(query) =>
            problem.set(None)
            running.set(true)
            api.call(TrackApi.track, (cluster, query))
        }
      } --> Observer[Either[ApiError, TrackResultDto]] { answer =>
        running.set(false)
        answer match {
          case Right(found) =>
            problem.set(None)
            result.set(Some(found))
          // The previous result is left on screen beside the failure. Those hits were a real answer to a
          // real question, and clearing them to show an error would take away the evidence somebody may
          // have been reading.
          case Left(error) => problem.set(Some(error.userMessage))
        }
      }
    )
  }

  private def controls(form: Var[TrackForm], pressed: EventBus[TrackForm]): HtmlElement =
    div(
      cls := MessagesCss.Controls,
      dataAttr("testid") := "track-controls",
      field(
        TrackMessages.TopicsLabel,
        textBox(
          form.signal.map(_.topics),
          TrackMessages.TopicsPlaceholder,
          "track-topics",
          raw => form.update(_.copy(topics = raw))
        ).amend(title := TrackMessages.TopicsHint)
      ),
      field(
        TrackMessages.SourceLabel,
        select(
          cls := KernelCss.FieldControl,
          dataAttr("testid") := "track-source",
          List(
            TrackMatchDto.Source.Value -> TrackMessages.SourceValue,
            TrackMatchDto.Source.Key -> TrackMessages.SourceKey,
            TrackMatchDto.Source.Header -> TrackMessages.SourceHeader
          ).map((value, text) => option(L.value := value, text)),
          controlled(
            L.value <-- form.signal.map(_.source),
            onChange.mapToValue --> { raw => form.update(_.copy(source = raw)) }
          )
        )
      ),
      // Only for a header search. A header-name box beside a value search is a control that does nothing,
      // and a control that does nothing is what somebody fills in and then wonders about.
      child.maybe <-- form.signal.map(current =>
        Option.when(current.source == TrackMatchDto.Source.Header)(
          field(
            TrackMessages.HeaderLabel,
            textBox(
              form.signal.map(_.header),
              TrackMessages.HeaderPlaceholder,
              "track-header",
              raw => form.update(_.copy(header = raw))
            )
          )
        )
      ),
      field(
        TrackMessages.OperatorLabel,
        select(
          cls := KernelCss.FieldControl,
          dataAttr("testid") := "track-operator",
          List(
            TrackMatchDto.Operator.Contains -> TrackMessages.OperatorContains,
            TrackMatchDto.Operator.Equals -> TrackMessages.OperatorEquals,
            TrackMatchDto.Operator.Matches -> TrackMessages.OperatorMatches
          ).map((value, text) => option(L.value := value, text)),
          controlled(
            L.value <-- form.signal.map(_.operator),
            onChange.mapToValue --> { raw => form.update(_.copy(operator = raw)) }
          )
        )
      ),
      field(
        TrackMessages.ValueLabel,
        textBox(
          form.signal.map(_.value),
          TrackMessages.ValuePlaceholder,
          "track-value",
          raw => form.update(_.copy(value = raw))
        )
      ),
      field(
        TrackMessages.FromLabel,
        textBox(
          form.signal.map(_.from),
          TrackForm.render(Instant.EPOCH),
          "track-from",
          raw => form.update(_.copy(from = raw))
        ).amend(title := TrackMessages.WindowHint)
      ),
      field(
        TrackMessages.ToLabel,
        textBox(
          form.signal.map(_.to),
          TrackForm.render(Instant.EPOCH),
          "track-to",
          raw => form.update(_.copy(to = raw))
        ).amend(title := TrackMessages.WindowHint)
      ),
      Button(
        label = Val(TrackMessages.Search),
        onClick = Observer[Unit](_ => ()),
        variant = ButtonVariant.Primary,
        testId = Some("track-search")
      ).amend(onClick.compose(_.sample(form.signal)) --> pressed.writer)
    )

  /** What the last search came to, in one line. */
  private def summaryLine(result: Signal[Option[TrackResultDto]], running: Signal[Boolean]): HtmlElement =
    div(
      cls := MessagesCss.Status,
      dataAttr("testid") := "track-status",
      role := "status",
      child.text <-- result.combineWith(running).map((answer, isRunning) => summary(answer, isRunning)),
      child.maybe <-- result.map(
        _.filter(_.truncated).map(_ => span(cls := MessagesCss.StatusPhase, TrackMessages.Truncated))
      )
    )

  private[track] def summary(result: Option[TrackResultDto], running: Boolean): String =
    if running then TrackMessages.Searching
    else
      result match {
        case None => ""
        case Some(answer) if answer.hits.isEmpty => TrackMessages.noHits(answer.scanned)
        case Some(answer) => TrackMessages.found(answer.hits.size, answer.scanned)
      }

  private def emptyState: HtmlElement =
    EmptyState(title = TrackMessages.EmptyTitle, description = Some(TrackMessages.EmptyDescription))

  /** The hits, newest first, with the topic on every row.
    *
    * Sorted by timestamp across topics rather than grouped by topic, because the answer to "where did this
    * order go" is a sequence: created here, then paid there, then shipped there. Grouping by topic would put
    * the last step above the first as often as not.
    */
  private def hitsTable(result: TrackResultDto, zone: String): HtmlElement =
    if result.hits.isEmpty then
      EmptyState(title = TrackMessages.NoHitsTitle, description = Some(TrackMessages.noHits(result.scanned)))
    else
      table(
        cls := KernelCss.Table,
        cls := MessagesCss.Table,
        dataAttr("testid") := "track-results",
        thead(
          tr(
            th(TrackMessages.ColumnTopic),
            th(TrackMessages.ColumnPartition),
            th(TrackMessages.ColumnOffset),
            th(TrackMessages.ColumnTimestamp),
            th(TrackMessages.ColumnKey),
            th(TrackMessages.ColumnValue)
          )
        ),
        tbody(
          cls := KernelCss.TableBody,
          result.hits.sortBy(_.record.timestamp).map(row(_, zone))
        )
      )

  private def row(hit: TrackHitDto, zone: String): HtmlElement =
    tr(
      cls := MessagesCss.Row,
      dataAttr(
        "testid"
      ) := s"track-hit-${hit.topic.value}-${hit.record.partition.value}-${hit.record.offset.value}",
      td(hit.topic.value),
      td(hit.record.partition.value.toString),
      td(hit.record.offset.value.toString),
      td(Timestamps.absolute(hit.record.timestamp, zone)),
      td(cls := MessagesCss.Key, preview(hit.record.key.text)),
      td(cls := MessagesCss.Value, preview(hit.record.value.text))
    )

  /** One line of a payload. The whole document is a click away on the browse screen; a cell that rendered
    * twenty kilobytes would make one row taller than the viewport and push every other hit off the page.
    */
  private[track] def preview(text: String): String =
    if text.length <= TrackMessages.PreviewLength then text
    else text.take(TrackMessages.PreviewLength) + "…"

  private def field(name: String, control: HtmlElement): HtmlElement =
    div(cls := MessagesCss.ControlGroup, span(cls := MessagesCss.ControlLabel, name), control)

  /** A plain text box. `onChange` rather than `onInput` — and therefore not `controlled` — for the same
    * reason the browse controls use it: these fields describe a search that has not been run yet, and
    * reacting per keystroke buys nothing here and costs a re-render of the form on every character.
    */
  private def textBox(
      value: Signal[String],
      placeholder: String,
      testId: String,
      onEntered: String => Unit
  ): HtmlElement =
    input(
      tpe := "text",
      cls := KernelCss.FieldControl,
      L.placeholder := placeholder,
      dataAttr("testid") := testId,
      L.value <-- value,
      onChange.mapToValue --> { raw => onEntered(raw) }
    )
}
