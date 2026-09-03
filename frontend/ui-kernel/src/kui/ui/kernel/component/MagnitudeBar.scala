package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A figure with a proportional bar beside it.
  *
  * ## What it is for
  *
  * Almost every list in KUI is a list of quantities: topic sizes, partition counts, message rates, consumer
  * lag, connector task counts. Digits are slow to compare — the eye has to parse "112.9 GB" and "48.2 GB"
  * before it can tell you which is larger — and a column of them gives up no shape at all. A bar drawn to the
  * same scale down the column answers "which is the big one" before a single digit is read.
  *
  * The figure is always shown as well. The bar is deliberately redundant: it says *relative* size and nothing
  * else, and a reader who needs the actual number must not have to hover anything to get it.
  *
  * ## Why this is a kernel component and not a piece of one screen
  *
  * Three future screens draw it — topics by size, topics by throughput, consumer groups by lag — and a fourth
  * will. Copied into each of them, the three copies would disagree within a milestone about the track colour,
  * the bar height and, worse, about whether the bar is announced to a screen reader. The accessibility
  * decision in particular has to be made once: the bar carries `aria-hidden`, because it encodes exactly the
  * number printed next to it, and a screen reader that reads both says the same quantity twice.
  *
  * ## What it does not do
  *
  * It does not decide the scale. `fraction` is the caller's: only the caller knows what the bar is relative
  * *to* — the largest row on this page, the cluster total, a configured quota — and guessing here would
  * silently make two tables incomparable. Values outside 0…1 are clamped rather than rejected, because a bar
  * that overflows its track is a rendering bug and a clamped one is merely "full".
  *
  * @param value
  *   the figure, already formatted by the caller. Formatting bytes, rates and durations is a product
  *   decision, not a styling one.
  * @param fraction
  *   how much of the track to fill, 0…1.
  * @param label
  *   an optional name shown above the bar, for the stacked "top five" form. Omitted inside a table cell,
  *   where the row already says what the bar belongs to.
  * @param inline
  *   draws the figure and the bar on one line, for use in a table cell.
  * @param accent
  *   fills with the second accent rather than the primary one. For the case where two of these are shown side
  *   by side and have to be told apart.
  */
object MagnitudeBar {

  def apply(
      value: Signal[String],
      fraction: Signal[Double],
      label: Option[Signal[String]] = None,
      inline: Boolean = false,
      accent: Boolean = false,
      testId: Option[String] = None
  ): HtmlElement = {
    val width = fraction.map(percentage)

    val figure = span(cls := KernelCss.MagnitudeValue, text <-- value)

    div(
      cls := KernelCss.Magnitude,
      Option.when(inline)(cls := KernelCss.MagnitudeInline),
      Option.when(accent)(cls := KernelCss.MagnitudeAccent),
      Components.testIdAttr(testId),
      // Stacked, the name and the figure share a line above the bar; inline, the figure sits beside
      // the track and there is no name to show.
      label match {
        case Some(name) =>
          div(cls := KernelCss.MagnitudeRow, span(cls := KernelCss.MagnitudeLabel, text <-- name), figure)
        case None => figure
      },
      div(
        cls := KernelCss.MagnitudeTrack,
        // The bar restates the figure beside it, so announcing it would say the same quantity twice.
        aria.hidden := true,
        div(cls := KernelCss.MagnitudeFill, styleAttr <-- width.map(percent => s"width: $percent"))
      )
    )
  }

  /** A fraction as a CSS width.
    *
    * Clamped, so a caller whose denominator was stale or zero gets a full or empty bar rather than one that
    * paints outside its own track. `NaN` — which is what `0.0 / 0.0` produces, and every comparison with it
    * is false — is treated as empty rather than being allowed through by the clamping, which would otherwise
    * pass it straight into the stylesheet.
    *
    * One decimal place: a hundredth of a percent is well under a pixel on any bar this component draws, and
    * rounding to whole percents makes the smallest rows in a list all render as nothing.
    */
  private def percentage(fraction: Double): String = {
    val clamped = if fraction.isNaN then 0.0 else fraction.max(0.0).min(1.0)
    s"${Math.round(clamped * 1000.0) / 10.0}%"
  }
}
