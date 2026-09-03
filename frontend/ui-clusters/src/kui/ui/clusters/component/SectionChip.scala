package kui.ui.clusters.component

import com.raquo.laminar.api.L.*

import kui.ui.clusters.dashboard.RowStatus
import kui.ui.kernel.component.{Tag, Tone}

/** One cluster's state, as a filled chip.
  *
  * ## Filled, not a dot
  *
  * The design draws status as a chip with a container colour and its paired text colour, never as a coloured
  * dot beside plain text. A dot carries the state in colour alone, and about one man in twelve cannot tell
  * the two most important ones apart; the chip carries the word, and the colour merely reinforces it.
  *
  * ## The reason, verbatim
  *
  * `Unavailable: connection refused` — the state word, then the message the server actually sent, unedited
  * and untruncated. ADR-032 is explicit that the reason is rendered as received: an operator whose cluster is
  * down needs the string they can search for or paste into a message, not a friendlier paraphrase of it. CSS
  * may ellipsise a long one; the text node may not.
  */
object SectionChip {

  def apply(status: Signal[RowStatus], testId: Option[String] = None): HtmlElement =
    Tag(
      label = status.map(label),
      // A cluster's health changes on its own, so the chip announces itself when it changes. It is one of
      // the few places in the product where `live` is right.
      live = true,
      testId = testId,
      tone = Tone.Neutral
    ).amend(cls <-- status.map(toneClass))

  /** `Unavailable: connection refused`; `Online`; `Degraded: cluster too slow to answer`. */
  def label(status: RowStatus): String =
    status match {
      case RowStatus.Online => "Online"
      case RowStatus.Degraded(reason) => s"Degraded: $reason"
      case RowStatus.Unavailable(reason, _) => s"Unavailable: $reason"
      case RowStatus.Forbidden => "Forbidden"
    }

  /** The tone class, applied after `Tag`'s own so that it wins on equal specificity.
    *
    * `Tag` takes its tone as a constructor argument and this component takes a `Signal`, because a row's
    * state changes while the row is on screen. Amending the class is how a signal reaches a component whose
    * parameter is not one; the alternative would be rebuilding the chip on every change, which would move
    * focus off it mid-announcement.
    */
  private def toneClass(status: RowStatus): String =
    status match {
      case RowStatus.Online => kui.ui.kernel.css.KernelCss.TagSuccess
      case RowStatus.Degraded(_) => kui.ui.kernel.css.KernelCss.TagWarning
      case RowStatus.Unavailable(_, _) => kui.ui.kernel.css.KernelCss.TagDanger
      case RowStatus.Forbidden => kui.ui.kernel.css.KernelCss.TagNeutral
    }
}
