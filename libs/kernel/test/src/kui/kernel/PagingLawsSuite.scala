package kui.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAllNoShrink
import org.scalacheck.{Arbitrary, Gen}

/** The paging arithmetic as laws rather than as examples.
  *
  * `PagingSuite` next door already checks the everyday cases and the one regression this design exists to
  * prevent. This suite covers the other half: that there is **no** input, however absurd, for which `Page.of`
  * throws, returns a negative offset, or reports a total that disagrees with the number of pages a client
  * would have to walk to see everything.
  *
  * The generators are deliberately biased towards the shapes that break paging rather than towards the shapes
  * that are easy to generate. A uniformly random page number is almost never 1, almost never past the end and
  * essentially never near `Int.MaxValue` — and those are three of the four places this code can be wrong. The
  * fourth, filtering after paging instead of before it, cannot be seen from here: it lives in the caller, and
  * `ListLawsSuite` in the topic service is where it is stated.
  *
  * It runs on both platforms, unchanged. `Int.MaxValue` arithmetic is one of the few places where the JVM and
  * a browser can genuinely differ, because JavaScript has no 32-bit integer type underneath, and a law that
  * held only where it was written would be worth very little.
  */
final class PagingLawsSuite extends ScalaCheckSuite {

  /** Page numbers spread across the whole legal range, with the edges over-represented: one, the boundary
    * either side of a typical list, and the largest page a client can ask for at all.
    */
  private val pageNumbers: Gen[Int] =
    Gen.frequency(
      3 -> Gen.const(1),
      3 -> Gen.chooseNum(1, 40),
      2 -> Gen.chooseNum(1, Int.MaxValue),
      2 -> Gen.oneOf(Int.MaxValue, Int.MaxValue - 1, Int.MaxValue / 2)
    )

  private val pageSizes: Gen[Int] =
    Gen.frequency(
      2 -> Gen.const(1),
      3 -> Gen.chooseNum(1, PageSize.Max.value),
      2 -> Gen.const(PageSize.Max.value)
    )

  private val lists: Gen[List[Int]] =
    Gen.frequency(
      2 -> Gen.const(Nil),
      6 -> Gen.listOf(Arbitrary.arbitrary[Int]),
      2 -> Gen.chooseNum(0, 600).map(size => List.fill(size)(0))
    )

  private val requests: Gen[PageRequest] =
    for {
      page <- pageNumbers
      size <- pageSizes
    } yield PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size))

  property("pageOfIsTotalOverEveryInput") {
    // "Total" in the mathematical sense: defined for every input. A bookmark to page two billion of a list
    // that has since been emptied is a request a browser really does make, and the answer is an empty page,
    // never an exception and never the first page pretending to be the last.
    forAllNoShrink(lists, requests) { (items, request) =>
      val page = Page.of(items, request)
      assertEquals(page.page, request.page.value)
      assertEquals(page.pageSize, request.pageSize.value)
      assertEquals(page.totalItems, Some(items.size.toLong))
      assert(page.items.sizeIs <= request.pageSize.value)
      assert(items.containsSlice(page.items))
    }
  }

  property("offsetNeverGoesNegative") {
    // The multiplication behind an offset is done in 64 bits and clamped for exactly this reason: an
    // overflowed offset is negative, and `slice` reads a negative start as "begin at the beginning", so an
    // absurd page number would quietly have returned page one instead of nothing.
    forAllNoShrink(requests) { request =>
      assert(request.offset >= 0, s"offset was ${request.offset} for $request")
    }
  }

  property("pageCountAndTotalItemsAgree") {
    // The number of pages a client has to walk to see every item, worked out from `totalItems` alone, is the
    // number of pages that actually have something on them. This is the arithmetic the reference product
    // gets wrong whenever a filter removes anything, and stating it as a law is what stops it coming back.
    forAllNoShrink(lists, pageSizes) { (items, size) =>
      val total = Page.of(items, PageRequest(PositiveInt.One, PageSize.unsafe(size))).totalItems
        .getOrElse(fail("a list built in memory always knows its total"))
      val pageCount = math.max(1L, (total + size - 1L) / size)

      val nonEmpty = (1L to pageCount).count { number =>
        val request = PageRequest(PositiveInt.unsafe(number.toInt), PageSize.unsafe(size))
        Page.of(items, request).items.nonEmpty
      }
      val expected = if items.isEmpty then 0 else pageCount.toInt

      assertEquals(nonEmpty, expected)
      // And one page past the count is empty, with the total unchanged: the client can tell "you have run
      // off the end" from "there is nothing here at all" without a second request.
      val past = Page.of(items, PageRequest(PositiveInt.unsafe((pageCount + 1L).toInt), PageSize.unsafe(size)))
      assert(past.items.isEmpty)
      assertEquals(past.totalItems, Some(total))
    }
  }

  property("everyPageIsContiguousAndDisjoint") {
    // Walking the pages of a list yields each item exactly once, in order. A page that repeated an item or
    // skipped one would still pass every per-page assertion above.
    val smallLists = Gen.listOf(Gen.chooseNum(0, 1000)).map(_.take(400))
    forAllNoShrink(smallLists, Gen.chooseNum(1, 50)) { (items, size) =>
      val pageCount = math.max(1, (items.size + size - 1) / size)
      val walked = (1 to pageCount).toList
        .flatMap(number => Page.of(items, PageRequest(PositiveInt.unsafe(number), PageSize.unsafe(size))).items)
      assertEquals(walked, items)
    }
  }
}
