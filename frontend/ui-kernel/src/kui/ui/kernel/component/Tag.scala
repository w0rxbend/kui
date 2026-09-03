package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A small coloured label: a topic's cleanup policy, a broker's role, a consumer group's state, an applied
  * filter.
  *
  * ## Colour is never the only signal
  *
  * A tag always carries text. The tone tints it, and the optional dot adds a second, non-colour cue, but
  * nothing here depends on the user being able to distinguish red from green — around one man in twelve
  * cannot.
  *
  * ## When it is a status, say so
  *
  * `live = true` renders `role="status"`, which makes a screen reader announce the tag when its text changes
  * without moving focus. Use it for something that changes on its own (a consumer group going from `Stable`
  * to `Rebalancing`), and leave it off for a static label — a page full of announcing tags announces nothing
  * useful.
  *
  * @param onRemove
  *   when given, the tag gains a real `<button>` to dismiss it. Used for applied filters. The button is
  *   labelled, because "×" on its own is read out as "times".
  */
object Tag {

  def apply(
      label: Signal[String],
      tone: Tone = Tone.Neutral,
      dot: Boolean = false,
      live: Boolean = false,
      onRemove: Option[Observer[Unit]] = None,
      testId: Option[String] = None
  ): HtmlElement =
    span(
      cls := KernelCss.Tag,
      cls := toneClass(tone),
      Option.when(live)(role := "status"),
      Components.testIdAttr(testId),
      Option.when(dot)(span(cls := KernelCss.TagDot, aria.hidden := true)),
      text <-- label,
      onRemove.map { observer =>
        button(
          tpe := "button",
          cls := KernelCss.TagRemove,
          // The accessible name has to say what is being removed, not just "remove".
          aria.label <-- label.map(current => s"Remove $current"),
          Icon.close,
          onClick.mapTo(()) --> observer
        )
      }
    )

  private def toneClass(tone: Tone): String =
    tone match {
      case Tone.Neutral => KernelCss.TagNeutral
      case Tone.Info => KernelCss.TagInfo
      case Tone.Success => KernelCss.TagSuccess
      case Tone.Warning => KernelCss.TagWarning
      case Tone.Danger => KernelCss.TagDanger
    }
}
