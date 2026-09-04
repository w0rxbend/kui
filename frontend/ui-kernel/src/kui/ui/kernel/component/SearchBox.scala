package kui.ui.kernel.component

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.kernel.search.SearchMode
import kui.ui.kernel.css.KernelCss

/** A debounced search field, with a clear button and an optional plain/full-text toggle.
  *
  * ## Why the debounce, and why 300 ms
  *
  * Without one, a ten-character query is ten requests, nine of whose answers are thrown away — and on a list
  * that is re-sorted per query, nine repaints the user sees flicker past. The reference product waits 500 ms,
  * which is tuned for a backend that scans on every keystroke; KUI answers from an in-memory snapshot, so the
  * round trip is a few milliseconds and 300 ms still collapses a burst of typing into one request while
  * feeling immediate.
  *
  * ## The empty string is a value
  *
  * Clearing the box fires `onQuery("")`. It is tempting to skip the call — there is nothing to search for —
  * but then the previous filter stays applied and the user, looking at an empty box and a filtered list,
  * reads the clear button as broken. "No filter" is a request, not the absence of one.
  *
  * @param value
  *   what the field shows. The caller owns it, so that a query arriving from the URL (a pasted link, the Back
  *   button) puts text in the box without this component knowing where it came from
  * @param onQuery
  *   fired after the debounce, with the trimmed query
  * @param mode
  *   when present, renders the plain/full-text toggle beside the field. The reference product shows this
  *   toggle only when the cluster advertises the capability; KUI's index is always available, so it is always
  *   shown, and the tooltip explains the difference in one sentence
  */
object SearchBox {

  /** What the toggle says about each mode, in one sentence each. */
  val PlainHint: String = "Matches names that contain what you typed"
  val FtsHint: String = "Also matches near-misses, so a typo still finds the name"

  def apply(
      value: Signal[String],
      onQuery: String => Unit,
      placeholder: String,
      debounce: FiniteDuration = 300.millis,
      mode: Option[(Signal[SearchMode], SearchMode => Unit)] = None,
      testId: Option[String] = None
  ): HtmlElement = {
    val fieldId = Components.nextId("kui-search")
    val typed = new EventBus[String]

    div(
      cls := KernelCss.Search,
      Components.testIdAttr(testId),
      L.label(cls := KernelCss.VisuallyHidden, forId := fieldId, placeholder),
      div(
        cls := KernelCss.SearchField,
        L.span(cls := KernelCss.SearchIcon, Icon.search),
        input(
          idAttr := fieldId,
          cls := KernelCss.SearchInput,
          tpe := "search",
          L.placeholder := placeholder,
          Components.testIdAttr(testId.map(_ + "-input")),
          // `controlled` keeps the DOM and the caller's signal from drifting apart when both change in
          // the same tick — a query arriving from the URL while the user is still typing.
          controlled(L.value <-- value, onInput.mapToValue --> typed.writer),
          // The debounce lives on the *outgoing* edge only. What the field shows updates on every
          // keystroke; what the caller is told waits for the typing to stop.
          typed.events.debounce(debounce.toMillis.toInt).map(_.trim) --> { query => onQuery(query) }
        ),
        // Rendered only when there is something to clear: a permanently visible cross on an empty box
        // is a control that does nothing, which teaches a user to ignore it.
        child.maybe <-- value.map { current =>
          Option.when(current.nonEmpty)(
            button(
              tpe := "button",
              cls := KernelCss.SearchClear,
              aria.label := "Clear search",
              Components.testIdAttr(testId.map(_ + "-clear")),
              Icon.close,
              // Not debounced: a click is already one event, and waiting 300 ms to clear a box the
              // user just emptied on purpose feels broken.
              onClick --> { _ => onQuery("") }
            )
          )
        }
      ),
      mode.map(modeToggle)
    )
  }

  private def modeToggle(binding: (Signal[SearchMode], SearchMode => Unit)): HtmlElement = {
    val (current, onMode) = binding

    def option(value: SearchMode, label: String, hint: String): HtmlElement =
      button(
        tpe := "button",
        cls := KernelCss.SearchMode,
        cls(KernelCss.SearchModeSelected) <-- current.map(_ == value),
        // A toggle button group: `aria-pressed` is what tells a screen reader which of the two is on.
        aria.pressed <-- current.map(selected => if selected == value then "true" else "false"),
        L.title := hint,
        dataAttr("mode") := value.wire,
        label,
        onClick --> { _ => onMode(value) }
      )

    div(
      cls := KernelCss.SearchModes,
      role := "group",
      aria.label := "Search mode",
      option(SearchMode.Plain, "Exact", PlainHint),
      option(SearchMode.Fts, "Fuzzy", FtsHint)
    )
  }
}
