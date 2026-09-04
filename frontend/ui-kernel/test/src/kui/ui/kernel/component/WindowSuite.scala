package kui.ui.kernel.component

import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The window arithmetic, which is where every virtualizer's bugs live.
  *
  * Property-heavy on purpose. Each of these failures shows on a screen as a flickering row, a blank strip or
  * a scrollbar that lies, and none of them shows as an exception — so an example-based suite would have to
  * guess the exact scroll position that breaks, which is the one thing a generator is better at than a
  * person.
  */
final class WindowSuite extends ScalaCheckSuite {

  private val positions = Gen.choose(-5_000, 2_000_000)
  private val viewports = Gen.choose(0, 2_000)
  private val rowHeights = Gen.choose(1, 200)
  private val overscans = Gen.choose(0, 20)
  private val totals = Gen.choose(0, 100_000)

  private val inputs: Gen[(Int, Int, Int, Int, Int)] =
    for {
      top <- positions
      viewport <- viewports
      rowHeight <- rowHeights
      overscan <- overscans
      total <- totals
    } yield (top, viewport, rowHeight, overscan, total)

  property("theSliceNeverExceedsTheTotal") {
    forAll(inputs) { (top, viewport, rowHeight, overscan, total) =>
      val cut = Window.slice(top, viewport, rowHeight, overscan, total)
      cut.firstIndex >= 0 && cut.count >= 0 && cut.endIndex <= total
    }
  }

  property("theSliceCoversTheViewport") {
    // The assertion that catches an off-by-one showing as a blank strip: every pixel of the viewport, at the
    // scroll position the container can actually reach, is covered by a row that was rendered.
    forAll(inputs) { (top, viewport, rowHeight, overscan, total) =>
      val cut = Window.slice(top, viewport, rowHeight, overscan, total)
      if cut.isEmpty then true
      else {
        val maxScrollTop = math.max(0, total * rowHeight - viewport)
        val clamped = math.min(math.max(0, top), maxScrollTop)
        val renderedTop = cut.offsetPx
        val renderedBottom = cut.offsetPx + cut.count * rowHeight
        val visibleBottom = math.min(clamped + viewport, total * rowHeight)
        renderedTop <= clamped && renderedBottom >= visibleBottom
      }
    }
  }

  property("scrollingToTheEndDoesNotOverrun") {
    forAll(inputs) { (_, viewport, rowHeight, overscan, total) =>
      val cut = Window.slice(Int.MaxValue, viewport, rowHeight, overscan, total)
      cut.endIndex <= total && (total == 0 || viewport == 0 || cut.endIndex == total)
    }
  }

  property("totalHeightIsRowsTimesHeight") {
    // The scrollbar's length, which is what makes the scrollbar honest about how much list there is.
    forAll(inputs) { (top, viewport, rowHeight, overscan, total) =>
      Window.slice(top, viewport, rowHeight, overscan, total).totalHeightPx == rowHeight * total
    }
  }

  property("theOffsetKeepsRowsAlignedToTheirIndex") {
    forAll(inputs) { (top, viewport, rowHeight, overscan, total) =>
      val cut = Window.slice(top, viewport, rowHeight, overscan, total)
      cut.offsetPx == cut.firstIndex * rowHeight
    }
  }

  test("aShortListRendersEveryRow") {
    // Five rows in a viewport that holds fifteen: there is nothing to window, and windowing anyway would
    // leave the last rows out of a table that visibly has room for them.
    val cut = Window.slice(0, 600, 40, 3, 5)
    assertEquals(cut.firstIndex, 0)
    assertEquals(cut.count, 5)
    assertEquals(cut.offsetPx, 0)
    assertEquals(cut.totalHeightPx, 200)
  }

  test("anEmptyListIsAZeroSlice") {
    assertEquals(Window.slice(0, 600, 40, 3, 0), Window.Slice(0, 0, 0, 0))
  }

  test("aViewportWithNoHeightRendersNothingAndDoesNotDivideByZero") {
    // A table on a hidden tab. Its scroller is zero pixels tall and nothing can be seen in it.
    val cut = Window.slice(0, 0, 40, 3, 10_000)
    assertEquals(cut.count, 0)
    assertEquals(cut.totalHeightPx, 400_000)
  }

  test("aZeroRowHeightIsNothingRatherThanAnArithmeticException") {
    assertEquals(Window.slice(100, 600, 0, 3, 10_000), Window.Slice(0, 0, 0, 0))
  }

  test("tenThousandRowsRenderEighteenAtBothEnds") {
    // The acceptance criterion from the task spec: fifteen rows fit a 600px viewport at 40px each, plus the
    // three rows of overscan on the side there is room for one.
    assertEquals(Window.slice(0, 600, 40, 3, 10_000).count, 18)
    assertEquals(Window.slice(400_000, 600, 40, 3, 10_000).count, 18)
  }

  test("aScrollPositionPastTheEndShowsTheLastScreenful") {
    // A list that shrank under a scrolled viewport. "The last screenful" is the honest answer; "nothing" would
    // be a blank area under a scrollbar claiming there is content there.
    val cut = Window.slice(999_999, 600, 40, 3, 100)
    assertEquals(cut.endIndex, 100)
    assert(cut.count >= 15, s"expected at least a viewport's worth, got ${cut.count}")
  }
}

/** The one assertion that does not need ScalaCheck, kept apart so it is obvious it is an example. */
final class WindowExampleSuite extends FunSuite {

  test("theWindowMovesWithTheScrollPosition") {
    val first = Window.slice(0, 400, 40, 2, 1_000)
    val later = Window.slice(4_000, 400, 40, 2, 1_000)
    assertEquals(first.firstIndex, 0)
    assertEquals(later.firstIndex, 98)
    assertEquals(later.offsetPx, 3_920)
  }
}
