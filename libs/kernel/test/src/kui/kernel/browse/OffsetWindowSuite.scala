package kui.kernel.browse

import kui.kernel.{Offset, OffsetRange}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The window arithmetic the backward browse walker is built out of.
  *
  * Backward browsing is the one place in M3 that can quietly read a whole partition, and the reason it does
  * not is `tail` and `dropTail`: the walker takes the last `n` offsets, reads them forwards, and repeats on
  * what is left. If those two ever disagreed about where the boundary is, the walker would either skip
  * records or read the same ones twice, and both look like "the browser is showing the wrong page".
  *
  * So the tiling property is asserted directly, over arbitrary ranges and arbitrary window sizes, rather than
  * being argued in a comment.
  */
final class OffsetWindowSuite extends ScalaCheckSuite {

  private val windowSize: Gen[Long] = Gen.chooseNum(0L, 6000L)

  property("tailAndDropTailTileTheRangeExactly") {
    forAll(BrowseGenerators.offsetRange, windowSize) { (range, n) =>
      val head = range.dropTail(n)
      val last = range.tail(n)

      assertEquals(head.until, last.from, "the two windows are adjacent")
      assertEquals(head.size + last.size, range.size, "their sizes sum to the whole")
      assert(head.from.value >= range.from.value, "the head does not escape below the range")
      assert(last.until.value <= range.until.value, "the tail does not escape above the range")
    }
  }

  property("tailClampsAtFrom") {
    forAll(BrowseGenerators.offsetRange) { range =>
      val more = range.size + 1L
      assertEquals(range.tail(more), range, "asking for more than the range holds gives the whole range")
      assert(range.tail(more).from.value >= 0L, "and never a negative offset")
    }
  }

  property("tailOfNothingIsEmptyAtTheEnd") {
    forAll(BrowseGenerators.offsetRange) { range =>
      assertEquals(range.tail(0L).size, 0L)
      assertEquals(range.tail(0L).from, range.until)
      assertEquals(range.dropTail(0L), range)
    }
  }

  property("clampToIsIdempotent") {
    forAll(BrowseGenerators.offsetRange, BrowseGenerators.offsetRange) { (range, bounds) =>
      val once = range.clampTo(bounds)
      assertEquals(once.clampTo(bounds), once)
    }
  }

  property("clampToNeverEscapesItsBounds") {
    forAll(BrowseGenerators.offsetRange, BrowseGenerators.offsetRange) { (range, bounds) =>
      val clamped = range.clampTo(bounds)
      assert(clamped.size >= 0L)
      if !clamped.isEmpty then {
        assert(clamped.from.value >= bounds.from.value)
        assert(clamped.until.value <= bounds.until.value)
        assert(clamped.from.value >= range.from.value)
        assert(clamped.until.value <= range.until.value)
      }
    }
  }

  test("clampingADisjointRangeIsEmptyRatherThanInverted") {
    // A bookmark below the log start after retention moved: the answer is "nothing there", not a range that
    // runs backwards, and certainly not a seek to a negative offset.
    val bookmark = OffsetRange.from(o(0), o(10)).toOption.get
    val log = OffsetRange.from(o(900), o(1000)).toOption.get

    assert(bookmark.clampTo(log).isEmpty)
    assertEquals(bookmark.clampTo(log).size, 0L)
  }

  test("halfOpenEverywhere") {
    val range = OffsetRange.from(o(10), o(13)).toOption.get
    assertEquals(range.size, 3L)
    assert(range.contains(o(10)))
    assert(!range.contains(o(13)))
  }

  test("theWalkerPrimitiveOnAHundredOffsets") {
    val range = OffsetRange.from(o(0), o(100)).toOption.get
    assertEquals(range.dropTail(30).until, range.tail(30).from)
    assertEquals(range.tail(30), OffsetRange.from(o(70), o(100)).toOption.get)
    assertEquals(range.dropTail(30), OffsetRange.from(o(0), o(70)).toOption.get)
  }

  private def o(value: Long): Offset = Offset.unsafe(value)
}
