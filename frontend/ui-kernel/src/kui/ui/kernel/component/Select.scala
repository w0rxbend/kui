package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A labelled dropdown over a list of typed options.
  *
  * ## Why a native `<select>`
  *
  * A custom dropdown has to reimplement keyboard navigation, type-ahead, screen-reader announcements, touch
  * behaviour and — the hard one — rendering a list that escapes its scrolling ancestor. The browser already
  * does all of that correctly, and on a phone it opens the platform's own picker. KUI uses the native control
  * in M0 and gains a searchable combobox in M2, where the topic filters actually need one.
  *
  * ## How typed options survive the DOM
  *
  * A `<select>` deals in strings, and KUI wants `Select[TopicName]` rather than `Select[String]`. So each
  * option is rendered with its *position* as the DOM value, and the position is mapped back to the real value
  * on change. Positions, not `toString`: two distinct values are allowed to print the same way, and using the
  * label as a key would silently merge them.
  *
  * @param options
  *   the choices, each with the text to show. A `Signal`, because the list usually arrives from the server
  *   and changes with the selected cluster.
  * @param selected
  *   the current choice. `None` renders the placeholder row, which is how a required field starts out with
  *   nothing chosen rather than silently defaulting to the first option.
  */
object Select {

  def apply[A](
      options: Signal[List[(A, String)]],
      selected: Var[Option[A]],
      label: String,
      placeholder: String = "",
      disabled: Signal[Boolean] = Val(false),
      testId: Option[String] = None
  ): HtmlElement = {
    val selectId = Components.nextId("kui-select")

    /** The DOM value for a choice: its index, or `""` for "nothing chosen". */
    def positionOf(available: List[(A, String)], choice: Option[A]): String =
      choice.map(value => available.indexWhere(_._1 == value)).filter(_ >= 0).fold("")(_.toString)

    div(
      cls := KernelCss.Field,
      L.label(cls := KernelCss.FieldLabel, forId := selectId, label),
      select(
        idAttr := selectId,
        cls := KernelCss.FieldControl,
        L.disabled <-- disabled,
        Components.testIdAttr(testId),
        // The placeholder row is always present, so that clearing the selection has somewhere to go.
        option(L.value := "", placeholder),
        children <-- options.map(_.zipWithIndex.map { case ((_, text), position) =>
          option(L.value := position.toString, text)
        }),
        // Re-derived whenever either the selection or the option list changes: an option list that
        // is replaced while something is selected must not leave the browser showing a stale row.
        L.value <-- options.combineWith(selected.signal).map(positionOf),
        onChange.mapToValue.compose(_.withCurrentValueOf(options)) --> { (position, available) =>
          selected.set(position.toIntOption.flatMap(available.lift).map(_._1))
        }
      )
    )
  }
}
