package kui.ui.consumers

import com.raquo.laminar.api.L.*

import kui.kernel.group.GroupState
import kui.ui.kernel.component.{Tag, Tone}
import kui.ui.kernel.css.KernelCss

/** A group's state as a filled chip, which the design draws and which is the one thing an operator reads
  * first on this screen.
  *
  * ## Why the tone is a function and not a table in the stylesheet
  *
  * Because the mapping is a *judgement about Kafka*, not a colour choice. `PREPARING_REBALANCE` is amber
  * because a rebalance is normal and brief but means the numbers beside it are about to change;
  * `COMPLETING_REBALANCE` is the same for the same reason. `EMPTY` is not a failure — a group with committed
  * offsets and no members is what every batch job looks like between runs — so it is neutral rather than red,
  * and an operator who saw red there would go looking for a problem that is not there. `DEAD` is red because
  * the group is being removed. `UNKNOWN` is amber because the broker did not answer, which is a fact about
  * KUI's view rather than about the group, and pretending it is fine would be a lie.
  *
  * The chip always carries the state's own text, so nothing here depends on the reader being able to
  * distinguish red from amber — around one man in twelve cannot — and it renders `role="status"` because a
  * group's state changes on its own while somebody is looking at it.
  */
object GroupStateChip {

  def apply(state: Signal[GroupState], testId: Option[String] = None): HtmlElement =
    Tag(
      label = state.map(label),
      tone = Tone.Neutral,
      dot = true,
      live = true,
      testId = testId
    ).amend(cls <-- state.map(toneClassOf))

  /** What the chip says. The wire spelling with its underscore turned into a space, because
    * `PREPARING_REBALANCE` is a protocol constant and "Preparing rebalance" is a sentence fragment a person
    * reads without decoding.
    */
  def label(state: GroupState): String = {
    val words = state.wire.split('_').toList
    words match {
      case head :: tail => (head.toLowerCase.capitalize :: tail.map(_.toLowerCase)).mkString(" ")
      case Nil => state.wire
    }
  }

  /** The tone, as the kernel's own modifier class.
    *
    * `Tag` takes a `Tone` up front and this chip's tone depends on a signal, so the neutral tag is amended
    * with the tone class that matches the current state rather than the whole tag being rebuilt on every
    * change — a rebuilt chip loses the `role="status"` announcement it exists to make.
    */
  private[consumers] def toneOf(state: GroupState): Tone =
    state match {
      case GroupState.Stable => Tone.Success
      case GroupState.Empty => Tone.Neutral
      case GroupState.Dead => Tone.Danger
      case GroupState.PreparingRebalance => Tone.Warning
      case GroupState.CompletingRebalance => Tone.Warning
      case GroupState.Unknown => Tone.Warning
    }

  private def toneClassOf(state: GroupState): String =
    toneOf(state) match {
      case Tone.Neutral => KernelCss.TagNeutral
      case Tone.Info => KernelCss.TagInfo
      case Tone.Success => KernelCss.TagSuccess
      case Tone.Warning => KernelCss.TagWarning
      case Tone.Danger => KernelCss.TagDanger
    }
}
