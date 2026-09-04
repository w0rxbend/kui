package kui.ui.kernel.component

/** The arithmetic behind a virtualized list, extracted so it is testable without a DOM.
  *
  * ## Why this is a separate object
  *
  * Every off-by-one in a virtualizer lives in this function, and every one of them shows up on screen as a
  * flickering row, a blank strip at the bottom of a fast scroll, or a scrollbar that lies about how much list
  * there is — never as an exception. None of those can be caught by looking at a screenshot, and all of them
  * can be caught by a property test over five integers. So the five integers are the whole interface: no DOM
  * node, no `Signal`, no component.
  *
  * ## The model
  *
  * Rows all have the same height. The list is therefore `total * rowHeight` pixels tall, and which rows are
  * on screen is division. That is the trade this component makes deliberately: a virtualizer that *measured*
  * its rows could show rows of different heights, but every scroll event would then have to read layout back
  * out of the browser, and its cost would depend on what is in the rows. A fixed height makes the cost
  * constant and, more importantly, predictable — a virtualizer that is fast in a test and slow on the one
  * cluster that matters is not a virtualizer.
  */
object Window {

  /** Which rows to render, where to put them, and how tall to pretend the list is.
    *
    * @param firstIndex
    *   the index, in the whole list, of the first row to render.
    * @param count
    *   how many rows to render from there. Never more than `total`.
    * @param offsetPx
    *   how far down the full list the first rendered row starts: `firstIndex * rowHeight`. The rendered rows
    *   are pushed down by this much so each one sits exactly where it would have been if every row were in
    *   the document.
    * @param totalHeightPx
    *   the height the scroll container is told the content has, which is what makes the scrollbar honest: it
    *   is the length of the whole list, not of the window.
    */
  final case class Slice(firstIndex: Int, count: Int, offsetPx: Int, totalHeightPx: Int) {

    /** One past the last rendered index — the exclusive end, which is what every loop actually wants. */
    def endIndex: Int = firstIndex + count

    def isEmpty: Boolean = count <= 0
  }

  object Slice {

    /** Nothing to render: an empty list, a zero row height, or a viewport with no height because its tab is
      * hidden. Rendering nothing is correct in all three, and none of them divides by zero.
      */
    val nothing: Slice = Slice(0, 0, 0, 0)

    given CanEqual[Slice, Slice] = CanEqual.derived
  }

  /** The rows to render for a given scroll position.
    *
    * `scrollTop` is **clamped** to the furthest the container can actually be scrolled before anything else
    * is computed. A browser will not scroll past the end, but a list that shrinks under a scrolled viewport
    * leaves the container holding a scroll position that no longer exists, and the honest answer to "which
    * rows are visible at a position past the end" is "the last screenful" — not "none", which would render a
    * blank area under a scrollbar that says there is content there.
    *
    * @param overscan
    *   extra rows rendered above and below the viewport, so that a fast scroll reveals a row that is already
    *   in the document instead of a gap that is filled a frame later.
    */
  def slice(scrollTop: Int, viewportHeight: Int, rowHeight: Int, overscan: Int, total: Int): Slice =
    if rowHeight <= 0 || total <= 0 || viewportHeight <= 0 then
      Slice.nothing.copy(totalHeightPx = contentHeight(rowHeight, total))
    else {
      val safeOverscan = overscan.max(0)
      val totalHeightPx = contentHeight(rowHeight, total)
      val maxScrollTop = (totalHeightPx - viewportHeight).max(0)
      val top = scrollTop.max(0).min(maxScrollTop)

      val firstVisible = top / rowHeight
      // Exclusive, and rounded *up*: a viewport whose bottom edge falls inside a row still shows part of
      // that row, and leaving it out is the off-by-one that renders a blank strip along the bottom.
      val endVisible = ceilDiv(top + viewportHeight, rowHeight)

      val firstIndex = (firstVisible - safeOverscan).max(0)
      val endIndex = (endVisible + safeOverscan).min(total)

      Slice(
        firstIndex = firstIndex,
        count = (endIndex - firstIndex).max(0),
        offsetPx = firstIndex * rowHeight,
        totalHeightPx = totalHeightPx
      )
    }

  /** The full list's height, in 64-bit arithmetic and then clamped.
    *
    * Ten million rows at forty pixels is four hundred million, which fits; a hundred million rows does not,
    * and an overflowed height is negative, which a browser reads as "no content at all". Clamping gives a
    * scrollbar that is merely wrong about how far past the end of the world the list goes.
    */
  private def contentHeight(rowHeight: Int, total: Int): Int =
    if rowHeight <= 0 || total <= 0 then 0
    else math.min(rowHeight.toLong * total.toLong, Int.MaxValue.toLong).toInt

  private def ceilDiv(numerator: Int, denominator: Int): Int =
    (numerator + denominator - 1) / denominator
}
