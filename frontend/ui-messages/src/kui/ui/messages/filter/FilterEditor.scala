package kui.ui.messages.filter

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.message.contract.{BrowseAddress, FilterRegistrationDto}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.{Button, ButtonVariant}
import kui.ui.messages.{Messages, MessagesCss}

/** Where a smart filter is written (MS-007).
  *
  * ## Why the expression is registered before the browse
  *
  * The browse endpoint takes an *id*, because a CEL program can be a paragraph and a browse is a `GET` whose
  * parameters end up in a link people send each other. Registering is also the only cheap way to find out
  * that an expression is wrong: it compiles the program and answers with the position of the first error, so
  * `record.value.staus == 'PAID'` is an underlined line here rather than a scan over a million records that
  * matches none of them and reads as missing data.
  *
  * So Apply does two things in order: register, and only on success write the id and the source into the URL.
  * A filter that did not compile changes nothing — the browse that is running keeps running and the URL keeps
  * whatever filter it had, which is what makes an editing mistake free.
  *
  * ## Why the source goes into the URL beside the id
  *
  * Two reasons, and both matter. The service is allowed to be several replicas, and one that has never seen
  * this id compiles the carried source rather than refusing a filter its neighbour registered a second ago.
  * And a person opening a shared link sees the expression in this box instead of sixteen hexadecimal
  * characters that mean nothing to them.
  */
object FilterEditor {

  def apply(
      cluster: ClusterId,
      api: ApiClient,
      applied: Signal[Option[String]],
      rewrite: Map[String, Option[String]] => Unit
  ): HtmlElement = {

    /** What is in the box. It starts as whatever the URL says and is edited freely afterwards: an expression
      * being typed is not yet a filter, and rewriting the URL per keystroke would stop the running browse
      * once per character.
      */
    val draft: Var[String] = Var("")

    /** The last registration failure, cleared the moment another attempt starts. */
    val problem: Var[Option[String]] = Var(None)

    val pressed = new EventBus[String]

    div(
      cls := MessagesCss.Filter,
      dataAttr("testid") := "messages-filter",
      // Seeded from the URL, and re-seeded whenever the URL's filter changes under it — which is what
      // happens when somebody opens a shared link or presses Back.
      applied --> Observer[Option[String]](source => draft.set(source.getOrElse(""))),
      detailsTag(
        // Open when there is a filter, so that a link carrying one does not hide the reason the table has
        // three rows in it. It is set through the raw `open` attribute rather than a typed prop because
        // Laminar's `details` has no keyword for it.
        htmlAttr("open", com.raquo.laminar.codecs.BooleanAsAttrPresenceCodec) <-- applied.map(_.isDefined),
        summaryTag(
          dataAttr("testid") := "messages-filter-summary",
          child.text <-- applied.map(_.fold(Messages.SmartFilterLabel)(_ => Messages.SmartFilterActive))
        ),
        div(
          cls := MessagesCss.FilterBody,
          textArea(
            cls := MessagesCss.FilterInput,
            dataAttr("testid") := "messages-filter-source",
            rows := 3,
            placeholder := Messages.SmartFilterPlaceholder,
            // A filter expression is code. Spell-checking it underlines every field name in red.
            spellCheck := false,
            autoComplete := "off",
            L.autoCapitalize := "off",
            controlled(value <-- draft.signal, onInput.mapToValue --> draft.writer)
          ),
          p(cls := MessagesCss.FilterHint, Messages.SmartFilterHint),
          div(
            cls := MessagesCss.FilterActions,
            Button(
              label = Val(Messages.SmartFilterApply),
              onClick = Observer[Unit](_ => ()),
              variant = ButtonVariant.Primary,
              testId = Some("messages-filter-apply")
            ).amend(onClick.compose(_.sample(draft.signal)) --> pressed.writer),
            // Offered only when there is something to clear, because a Clear beside an empty box is a
            // control that can only ever do nothing.
            child.maybe <-- applied.map(
              _.map(_ =>
                Button(
                  label = Val(Messages.SmartFilterClear),
                  onClick = Observer[Unit](_ => clear(draft, problem, rewrite)),
                  variant = ButtonVariant.Secondary,
                  testId = Some("messages-filter-clear")
                )
              )
            )
          ),
          child.maybe <-- problem.signal.map(
            _.map(text =>
              p(cls := MessagesCss.FilterError, dataAttr("testid") := "messages-filter-error", text)
            )
          )
        )
      ),
      // The registration itself. `flatMapSwitch`, so a second Apply while the first is in flight replaces
      // it rather than racing it — the answer to the older expression must not overwrite the newer one.
      pressed.events.flatMapSwitch(source => register(cluster, api, source)) --> Observer[Outcome] {
        case Outcome.Cleared =>
          problem.set(None)
          clear(draft, problem, rewrite)
        case Outcome.Registered(id, source) =>
          problem.set(None)
          rewrite(
            Map(
              BrowseAddress.FilterIdParam -> Some(id),
              BrowseAddress.FilterSourceParam -> Some(source)
            )
          )
        case Outcome.Refused(message) => problem.set(Some(message))
      }
    )
  }

  /** What one Apply came to. An empty box is a `Cleared` rather than a registration of the empty string,
    * which the server would rightly refuse and which is plainly not what the user meant.
    */
  private enum Outcome {
    case Cleared
    case Registered(id: String, source: String)
    case Refused(message: String)
  }

  private def register(cluster: ClusterId, api: ApiClient, source: String): EventStream[Outcome] = {
    val trimmed = source.trim

    if trimmed.isEmpty then EventStream.fromValue(Outcome.Cleared)
    else
      api
        .call(FilterApi.register, (cluster, FilterRegistrationDto(trimmed, None)))
        .map {
          case Right(answer) => Outcome.Registered(answer.id, trimmed)
          case Left(error) => Outcome.Refused(describe(error))
        }
  }

  /** The failure, as one line the editor can show.
    *
    * A compile failure carries the line and column in its details, and that is the half a person acts on — so
    * it is appended rather than dropped in favour of the summary sentence, which for a compile error says
    * only that the filter could not be compiled.
    */
  private[messages] def describe(error: ApiError): String =
    error match {
      case ApiError.Envelope(_, message, details, _, _) =>
        val positions = details.flatMap(_.restrictions)
        if positions.isEmpty then message else s"$message — ${positions.mkString("; ")}"
      case other => other.userMessage
    }

  private def clear(
      draft: Var[String],
      problem: Var[Option[String]],
      rewrite: Map[String, Option[String]] => Unit
  ): Unit = {
    draft.set("")
    problem.set(None)
    rewrite(Map(BrowseAddress.FilterIdParam -> None, BrowseAddress.FilterSourceParam -> None))
  }
}
