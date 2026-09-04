package kui.ui.topics.list

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.search.SearchMode
import kui.ui.kernel.component.*
import kui.ui.topics.{Messages, TopicsCss}

/** The bar above the table: search and its mode, the internal-topics switch, the count, and refresh.
  *
  * ## The count says "N topics", not "N of M"
  *
  * `totalItems` is counted by the server **after every filter, the internal-topic one included**, so it *is*
  * the number of topics the user is looking through. A second number invites the reader to work out the
  * difference, and the difference is exactly what the reference product gets wrong: it counts before applying
  * the internal filter, so its page count overstates and its last page is short with no explanation
  * (`research/kafbat/api-analysis.md` §3.3). One honest number cannot reproduce that.
  *
  * ## Refresh is disabled while the data is stale
  *
  * Not because refreshing would be harmful, but because the button's promise is "this will be current in a
  * moment" and while the upstream is failing that promise is false. The overlay beside it already says when
  * the rows were fetched, so the user is not left guessing why.
  */
object TopicListControls {

  def apply(
      query: Signal[String],
      onQuery: String => Unit,
      mode: Signal[SearchMode],
      onMode: SearchMode => Unit,
      showInternal: Signal[Boolean],
      onShowInternal: Boolean => Unit,
      total: Signal[Option[Long]],
      refreshEnabled: Signal[Boolean],
      onRefresh: () => Unit
  ): HtmlElement = {
    val internalId = Components.nextId("kui-topics-internal")

    div(
      cls := TopicsCss.Controls,
      SearchBox(
        value = query,
        onQuery = onQuery,
        placeholder = Messages.SearchPlaceholder,
        mode = Some((mode, onMode)),
        testId = Some("topics-search")
      ),
      L.label(
        cls := TopicsCss.Toggle,
        forId := internalId,
        input(
          idAttr := internalId,
          tpe := "checkbox",
          dataAttr("testid") := "topics-internal-toggle",
          // `controlled`, so a value arriving from the URL while the box is being clicked cannot leave the
          // DOM and the state disagreeing.
          controlled(checked <-- showInternal, onInput.mapToChecked --> { on => onShowInternal(on) })
        ),
        span(Messages.ShowInternal)
      ),
      span(
        cls := TopicsCss.Count,
        dataAttr("testid") := "topics-count",
        // `role="status"`: the count changes when the user types, and a change nobody announces is one a
        // screen-reader user has to go looking for.
        role := "status",
        text <-- total.map(Messages.topicCount)
      ),
      Button(
        label = Val(Messages.Refresh),
        onClick = Observer[Unit](_ => onRefresh()),
        variant = ButtonVariant.Secondary,
        icon = Some(() => Icon.refresh),
        disabled = refreshEnabled.map(!_),
        testId = Some("topics-refresh")
      )
    )
  }
}
