package kui.ui.kernel.component

import com.raquo.laminar.api.L.{seqToModifier, svg as s, SvgElement}
import com.raquo.laminar.codecs.StringAsIsCodec

import kui.ui.kernel.css.KernelCss

/** The icon set, drawn inline as SVG.
  *
  * ## Why inline SVG and not an icon font or a sprite sheet
  *
  * An icon font renders glyphs, so a screen reader may read one out as a private-use character, and a failed
  * font load leaves an empty box where the icon should be. A sprite sheet is one more network request that
  * has to arrive before the interface looks finished. Inline SVG is markup: it costs no extra request, it
  * inherits `currentColor` so an icon is automatically the right colour in both themes, and it is invisible
  * to assistive technology unless the caller asks otherwise.
  *
  * Every icon is a 24×24 stroked outline on the same grid, so they sit together without individual nudging,
  * and each is sized in `em` so it matches the text next to it at any font size.
  *
  * ## Why these are methods and not values
  *
  * A Laminar element is a real DOM node, and a DOM node can only be in one place at a time: putting the same
  * `val` in two buttons would move it out of the first. Each of these builds a fresh element per call.
  *
  * ## Accessibility
  *
  * Icons are decoration: they carry `aria-hidden="true"`, and the meaning is carried by the text or the
  * `aria-label` of whatever contains them. An icon that is the *only* content of a control needs that control
  * to be labelled, not the icon.
  */
object Icon {

  private val ariaHidden = s.svgAttr("aria-hidden", StringAsIsCodec, None)
  private val focusable = s.svgAttr("focusable", StringAsIsCodec, None)

  /** The shared skeleton: right size, inherits the text colour, hidden from screen readers. */
  private def icon(shapes: SvgElement*): SvgElement =
    s.svg(
      s.cls := KernelCss.Icon,
      s.viewBox := "0 0 24 24",
      s.width := "1em",
      s.height := "1em",
      s.fill := "none",
      s.stroke := "currentColor",
      s.strokeWidth := "2",
      s.strokeLineCap := "round",
      s.strokeLineJoin := "round",
      ariaHidden := "true",
      focusable := "false",
      shapes
    )

  private def draw(commands: String): SvgElement = s.path(s.d := commands)

  private def circle(centreX: String, centreY: String, radius: String): SvgElement =
    s.circle(s.cx := centreX, s.cy := centreY, s.r := radius)

  def chevronDown: SvgElement = icon(draw("M6 9l6 6 6-6"))
  def chevronUp: SvgElement = icon(draw("M18 15l-6-6-6 6"))
  def chevronLeft: SvgElement = icon(draw("M15 18l-6-6 6-6"))
  def chevronRight: SvgElement = icon(draw("M9 18l6-6-6-6"))

  def close: SvgElement = icon(draw("M18 6L6 18"), draw("M6 6l12 12"))
  def check: SvgElement = icon(draw("M20 6L9 17l-5-5"))
  def plus: SvgElement = icon(draw("M12 5v14"), draw("M5 12h14"))

  def warning: SvgElement =
    icon(
      draw("M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"),
      draw("M12 9v4"),
      draw("M12 17h.01")
    )

  def info: SvgElement = icon(circle("12", "12", "10"), draw("M12 16v-4"), draw("M12 8h.01"))

  def refresh: SvgElement =
    icon(
      draw("M23 4v6h-6"),
      draw("M1 20v-6h6"),
      draw("M20.5 9a9 9 0 0 0-14.9-3.4L1 10"),
      draw("M3.5 15a9 9 0 0 0 14.9 3.4L23 14")
    )

  def external: SvgElement =
    icon(
      draw("M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"),
      draw("M15 3h6v6"),
      draw("M10 14L21 3")
    )

  def search: SvgElement = icon(circle("11", "11", "8"), draw("M21 21l-4.35-4.35"))

  def menu: SvgElement = icon(draw("M3 12h18"), draw("M3 6h18"), draw("M3 18h18"))

  def sun: SvgElement =
    icon(
      circle("12", "12", "5"),
      draw("M12 1v2"),
      draw("M12 21v2"),
      draw("M4.2 4.2l1.4 1.4"),
      draw("M18.4 18.4l1.4 1.4"),
      draw("M1 12h2"),
      draw("M21 12h2"),
      draw("M4.2 19.8l1.4-1.4"),
      draw("M18.4 5.6l1.4-1.4")
    )

  def moon: SvgElement = icon(draw("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"))

  def copy: SvgElement =
    icon(
      draw("M9 9h13v13H9z"),
      draw("M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
    )

  def dot: SvgElement = icon(s.circle(s.cx := "12", s.cy := "12", s.r := "4", s.fill := "currentColor"))

  /** The busy indicator. Its rotation lives in CSS, so the reset's `prefers-reduced-motion` rule can stop it
    * for users who asked for that.
    */
  def spinner: SvgElement =
    icon(s.circle(s.cx := "12", s.cy := "12", s.r := "9", s.opacity := "0.25"), draw("M21 12a9 9 0 0 0-9-9"))
      .amend(s.cls := KernelCss.Spinner)

  /** Every icon by name, as thunks. Used by the gallery page and by `A11ySuite`, which asserts that every one
    * of them is hidden from assistive technology.
    */
  def all: List[(String, () => SvgElement)] = List(
    "chevron-down" -> (() => chevronDown),
    "chevron-up" -> (() => chevronUp),
    "chevron-left" -> (() => chevronLeft),
    "chevron-right" -> (() => chevronRight),
    "close" -> (() => close),
    "check" -> (() => check),
    "plus" -> (() => plus),
    "warning" -> (() => warning),
    "info" -> (() => info),
    "refresh" -> (() => refresh),
    "external" -> (() => external),
    "search" -> (() => search),
    "menu" -> (() => menu),
    "sun" -> (() => sun),
    "moon" -> (() => moon),
    "copy" -> (() => copy),
    "dot" -> (() => dot),
    "spinner" -> (() => spinner)
  )
}
