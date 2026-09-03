package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** Where a figure sits relative to the limit its caller cares about.
  *
  * Three levels and not five: an operator scanning a column has to sort each cell into "fine", "look at this"
  * and "act on this" in the time it takes to scroll past, and a scale with more steps than that is read as a
  * gradient, which is to say as nothing.
  */
enum ThresholdLevel {

  /** Under the limit. Drawn exactly like every other quiet number in the table. */
  case Normal

  /** Past the limit. */
  case Warning

  /** Past the second limit, if the caller has one. */
  case Critical
}

object ThresholdLevel {

  /** The comparison, done once so that every screen does it the same way.
    *
    * Both bounds are *exclusive*: a value equal to `warnAbove` is still normal. That is the reading an
    * operator expects from "warn above 0" — zero out-of-sync replicas is the healthy case, not a borderline
    * one.
    *
    * There is deliberately no default for either bound. What counts as too much consumer lag, or too many
    * out-of-sync replicas, is a fact about Kafka and about this cluster; it comes from the researched
    * behaviour of the reference products and from the caller's own configuration, and a plausible-looking
    * number invented inside a styling component would be wrong everywhere and obvious nowhere.
    */
  def above(value: Double, warnAbove: Double, criticalAbove: Option[Double] = None): ThresholdLevel =
    if criticalAbove.exists(limit => value > limit) then Critical
    else if value > warnAbove then Warning
    else Normal
}

/** A figure that takes a colour only once it has crossed a limit.
  *
  * ## Why this is a component rather than a conditional class in each screen
  *
  * The design's rule is that a threshold column is *uncoloured* almost all of the time. Out-of-sync replica
  * counts are zero on a healthy cluster and consumer lag is near zero on a healthy consumer, so a column
  * drawn permanently in the warning colour is a column the operator learns to ignore within a week. Colouring
  * only the exception makes the exception the single coloured thing on a screen of two hundred rows, which is
  * the whole point.
  *
  * That rule is easy to state and easy to lose. Written into each screen separately, the third screen gets it
  * slightly wrong — colours the healthy case grey-but-not-quite, or drops the second cue — and nobody
  * notices, because each screen looks fine on its own.
  *
  * ## Colour is never the only signal
  *
  * A crossed threshold also grows a warning mark and a heavier weight, and a screen reader hears a word.
  * Around one man in twelve cannot separate these hues, and nobody at all can see a colour they have scrolled
  * past at speed.
  *
  * ## What it does not do
  *
  * It does not know any limits, and it does not format the number. `ThresholdLevel.above` will do the
  * comparison if the caller hands it the bounds; the bounds themselves belong to the screen.
  *
  * @param value
  *   the figure, already formatted.
  * @param level
  *   where that figure sits. A `Signal`, because lag moves on its own.
  * @param announcement
  *   what a screen reader hears when the level is not `Normal`. Overridable because "3" on its own says
  *   nothing about whether three is a problem, and only the caller knows what three *is*.
  */
object ThresholdValue {

  def apply(
      value: Signal[String],
      level: Signal[ThresholdLevel],
      announcement: ThresholdLevel => String = defaultAnnouncement,
      testId: Option[String] = None
  ): HtmlElement =
    span(
      cls := KernelCss.Threshold,
      cls(KernelCss.ThresholdOver) <-- level.map(_ == ThresholdLevel.Warning),
      cls(KernelCss.ThresholdCritical) <-- level.map(_ == ThresholdLevel.Critical),
      Components.testIdAttr(testId),
      // The mark is the non-colour half of the signal, and it appears only when there is something
      // to mark: a row of warning triangles beside every healthy zero would be the same mistake as
      // colouring every healthy zero.
      child.maybe <-- level.map(current =>
        Option.when(current != ThresholdLevel.Normal)(span(cls := KernelCss.ThresholdMark, Icon.warning))
      ),
      text <-- value,
      // `Icon` renders everything `aria-hidden`, so without this a screen reader hears the number and
      // nothing about it being over a limit.
      child.maybe <-- level.map(current =>
        Option.when(current != ThresholdLevel.Normal)(
          span(cls := KernelCss.VisuallyHidden, s" (${announcement(current)})")
        )
      )
    )

  /** Says which side of the limit the figure is on, and nothing about what the figure means — that part is
    * the caller's, because this component has never been told.
    */
  val defaultAnnouncement: ThresholdLevel => String = {
    case ThresholdLevel.Normal => ""
    case ThresholdLevel.Warning => "above the warning threshold"
    case ThresholdLevel.Critical => "above the critical threshold"
  }
}
