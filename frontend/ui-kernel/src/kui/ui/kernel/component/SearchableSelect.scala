package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A dropdown over a list too long for a dropdown: type to narrow it, then pick.
  *
  * ## Why this exists next to `Select`
  *
  * `Select` wraps the browser's native control, and for a handful of options that is the right answer — the
  * platform already implements keyboard navigation, type-ahead and, on a phone, its own picker. It stops
  * being the right answer somewhere around a hundred entries. The timezone list has several hundred, and
  * scrolling several hundred rows to find `Europe/Warsaw` is not a thing anyone should be asked to do.
  *
  * So this is the exception, not the replacement: reach for `Select` unless the list is long enough that a
  * user would have to search it.
  *
  * ## The accessibility contract
  *
  * This is the ARIA combobox pattern, and every part of it is load-bearing:
  *
  *   - the text field is `role="combobox"`, with `aria-expanded` saying whether the list is open and
  *     `aria-controls` naming it, so a screen reader can describe what pressing Down will do;
  *   - the list is `role="listbox"` and each row `role="option"` with `aria-selected`;
  *   - the *focus stays in the text field* while the arrow keys move through the list, and which row is
  *     current is published as `aria-activedescendant`. Moving real focus into the list instead would mean
  *     the user could no longer type, which is the whole point of the control.
  *
  * Escape closes without changing the selection, and Enter takes the highlighted row. Both are expected by
  * anyone who has used a combobox anywhere else, and a control that swallows Escape is a control a keyboard
  * user cannot get out of.
  *
  * @param options
  *   the choices, each with the text shown and searched.
  * @param selected
  *   the current choice. Never cleared by this control: a list that must always have an answer is the only
  *   kind of list it is used for so far.
  * @param filter
  *   how a typed query is matched against an option. The default is a case-insensitive substring of the
  *   label, which is what makes `warsaw` and `+02:00` both find `UTC+02:00 Europe/Warsaw`.
  */
object SearchableSelect {

  def apply[A](
      options: Signal[List[(A, String)]],
      selected: Var[A],
      label: String,
      placeholder: String = "Type to search",
      filter: (String, String) => Boolean = defaultFilter,
      testId: Option[String] = None
  ): HtmlElement = {
    val inputId = Components.nextId("kui-combobox")
    val listId = s"$inputId-list"

    val query = Var("")
    val open = Var(false)
    val active = Var(0)

    val matching: Signal[List[(A, String)]] =
      options.combineWith(query.signal).map { (available, typed) =>
        if typed.isEmpty then available else available.filter((_, text) => filter(typed, text))
      }

    /** The row the keyboard is on, clamped: the list shrinks as the user types, and an index left pointing
      * past the end would make Enter do nothing for no visible reason.
      */
    val activeIndex: Signal[Int] =
      active.signal.combineWith(matching).map((wanted, rows) => wanted.max(0).min((rows.length - 1).max(0)))

    def choose(value: A): Unit = {
      selected.set(value)
      query.set("")
      open.set(false)
    }

    def move(by: Int): Unit = {
      open.set(true)
      active.update(_ + by)
    }

    div(
      cls := KernelCss.Combobox,
      L.label(cls := KernelCss.FieldLabel, forId := inputId, label),
      input(
        idAttr := inputId,
        cls := KernelCss.FieldControl,
        tpe := "text",
        role := "combobox",
        autoComplete := "off",
        aria.expanded <-- open.signal,
        aria.controls := listId,
        aria.activeDescendant <-- activeIndex.map(index => s"$listId-$index"),
        L.placeholder := placeholder,
        Components.testIdAttr(testId),
        // Closed, the field shows what is chosen; open, it shows what has been typed. Otherwise the
        // user has to delete the current value before they can search for another one.
        controlled(
          L.value <-- open.signal.combineWith(query.signal, selected.signal, options).map {
            (isOpen, typed, current, available) =>
              if isOpen then typed
              else available.collectFirst { case (`current`, text) => text }.getOrElse("")
          },
          onInput.mapToValue --> { typed =>
            query.set(typed)
            open.set(true)
            active.set(0)
          }
        ),
        onFocus.mapTo(true) --> open.writer,
        // Safe to close on blur because the rows take their `mousedown` with `preventDefault`, so
        // clicking one never moves focus out of the field in the first place.
        onBlur.mapTo(false) --> open.writer,
        onKeyDown.compose(_.withCurrentValueOf(matching, activeIndex)) --> { (event, rows, index) =>
          event.key match {
            case "ArrowDown" => event.preventDefault(); move(1)
            case "ArrowUp" => event.preventDefault(); move(-1)
            case "Escape" => open.set(false); query.set("")
            case "Enter" =>
              event.preventDefault()
              rows.lift(index).foreach((value, _) => choose(value))
            case _ => ()
          }
        }
      ),
      ul(
        idAttr := listId,
        cls := KernelCss.ComboboxList,
        role := "listbox",
        aria.label := label,
        L.hidden <-- open.signal.map(!_),
        children <-- matching.combineWith(selected.signal, activeIndex).map { (rows, current, index) =>
          rows.zipWithIndex.map { case ((value, text), position) =>
            li(
              idAttr := s"$listId-$position",
              cls := KernelCss.ComboboxOption,
              cls(KernelCss.ComboboxOptionActive) := position == index,
              role := "option",
              aria.selected := value == current,
              text,
              // `mousedown`, not `click`: the field's blur fires first on a click, and closing the
              // list on blur would remove the row before its click could land.
              onMouseDown.preventDefault --> { _ => choose(value) }
            )
          }
        }
      ),
      // Nothing matched. Said out loud, because an empty list under a search field is otherwise
      // indistinguishable from a control that has stopped working.
      div(
        cls := KernelCss.ComboboxEmpty,
        role := "status",
        L.hidden <-- open.signal.combineWith(matching).map((isOpen, rows) => !isOpen || rows.nonEmpty),
        "No match"
      )
    )
  }

  /** Case-insensitive substring of the visible label. */
  def defaultFilter(query: String, label: String): Boolean =
    label.toLowerCase.contains(query.toLowerCase)
}
