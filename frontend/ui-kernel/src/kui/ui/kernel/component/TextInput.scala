package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A labelled single-line text field with optional help text and an error message.
  *
  * ## The accessibility contract, and why it needs code
  *
  * A visible label is not the same thing as an accessible name. A screen reader announces the text next to a
  * field only if the two are connected — either by nesting the input inside the `<label>`, or by `for`/`id`.
  * This component does the latter, which means it has to invent an id, and that id has to be unique in the
  * whole document. It cannot be derived from the label text, because two "Name" fields on one screen are
  * perfectly ordinary; so `Components.nextId` mints one per instance.
  *
  * The hint and the error are attached with `aria-describedby`, so they are read out after the label rather
  * than being purely visual. `aria-invalid` is set only while there is an error, which is what makes a screen
  * reader say "invalid entry" at the right moment and not before the user has typed anything.
  *
  * ## Ownership
  *
  * The component owns no state. `value` is the caller's `Var`, and the binding is two-way: typing writes to
  * the `Var`, and writing to the `Var` from anywhere else (a form reset, a value arriving from the server)
  * updates what is on screen. `controlled` is what makes that safe — without it the DOM and the `Var` drift
  * apart as soon as both change in the same tick.
  *
  * @param error
  *   `Some(message)` while the field is invalid. A `Signal` because validation runs as the user types, not
  *   only on submit.
  */
object TextInput {

  def apply(
      value: Var[String],
      label: String,
      placeholder: String = "",
      hint: Option[String] = None,
      error: Signal[Option[String]] = Val(None),
      disabled: Signal[Boolean] = Val(false),
      testId: Option[String] = None
  ): HtmlElement = {
    val inputId = Components.nextId("kui-input")
    val hintId = s"$inputId-hint"
    val errorId = s"$inputId-error"

    // `aria-describedby` takes a space-separated list of ids, and must name only elements that are
    // actually in the document: pointing at an absent error message makes some screen readers
    // announce nothing at all rather than announcing the hint.
    val describedBy = error.map { currentError =>
      val ids = hint.map(_ => hintId).toList ++ currentError.map(_ => errorId).toList
      if ids.isEmpty then None else Some(ids.mkString(" "))
    }

    div(
      cls := KernelCss.Field,
      cls(KernelCss.FieldInvalid) <-- error.map(_.isDefined),
      L.label(cls := KernelCss.FieldLabel, forId := inputId, label),
      input(
        idAttr := inputId,
        cls := KernelCss.FieldControl,
        tpe := "text",
        L.placeholder := placeholder,
        L.disabled <-- disabled,
        // `aria-invalid` is a string enumeration, not a boolean flag; the absent case is "false".
        aria.invalid <-- error.map(current => if current.isDefined then "true" else "false"),
        aria.describedBy <-- describedBy.map(_.getOrElse("")),
        Components.testIdAttr(testId),
        controlled(L.value <-- value.signal, onInput.mapToValue --> value)
      ),
      hint.map(text => div(idAttr := hintId, cls := KernelCss.FieldHint, text)),
      child.maybe <-- error.map(
        _.map(message => div(idAttr := errorId, cls := KernelCss.FieldError, role := "alert", message))
      )
    )
  }
}
