package kui.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The page arithmetic, including the regression guard for the bug this design exists to avoid.
  */
final class PagingSuite extends ScalaCheckSuite {

  private def request(page: Int, size: Int): PageRequest =
    PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size))

  private val listAndSize: Gen[(List[Int], Int)] = for {
    items <- Gen.listOf(Gen.chooseNum(0, 1000))
    size  <- Gen.chooseNum(1, 50)
  } yield (items, size)

  property("every page of a list, concatenated, is the list again") {
    forAll(listAndSize) { pair =>
      val items    = pair._1
      val size     = pair._2
      val lastPage = math.max(1, math.ceil(items.size.toDouble / size).toInt)
      val paged    = (1 to lastPage).toList.flatMap(page => Page.of(items, request(page, size)).items)
      assertEquals(paged, items)
    }
  }

  property("no page is ever larger than the requested page size") {
    forAll(listAndSize) { pair =>
      val page = Page.of(pair._1, request(1, pair._2))
      assert(page.items.sizeIs <= pair._2)
    }
  }

  test("totalItems counts the filtered list, not the list before filtering") {
    val all      = (1 to 100).toList
    val filtered = all.filter(_ % 10 == 0)
    val page     = Page.of(filtered, request(1, 25))

    assertEquals(page.totalItems, Some(10L))
    assertEquals(page.items, filtered)
  }

  test("a page past the end of the list is empty rather than an error") {
    val page = Page.of((1 to 10).toList, request(99, 25))
    assertEquals(page.items, Nil)
    assertEquals(page.totalItems, Some(10L))
    assertEquals(page.page, 99)
  }

  test("an absurd page number is still empty, rather than overflowing back to the first page") {
    val page = Page.of((1 to 10).toList, request(Int.MaxValue, 500))
    assertEquals(page.items, Nil)
    assert(request(Int.MaxValue, 500).offset >= 0)
  }

  test("pages are numbered from one: page 1 is the first pageSize items") {
    val all = (1 to 10).toList
    assertEquals(Page.of(all, request(1, 3)).items, List(1, 2, 3))
    assertEquals(Page.of(all, request(2, 3)).items, List(4, 5, 6))
    assertEquals(Page.of(all, request(4, 3)).items, List(10))
  }

  test("map converts the items and keeps every piece of pagination metadata") {
    val page   = Page.of((1 to 10).toList, request(2, 3))
    val mapped = page.map(_.toString)

    assertEquals(mapped.items, List("4", "5", "6"))
    assertEquals(mapped.page, page.page)
    assertEquals(mapped.pageSize, page.pageSize)
    assertEquals(mapped.totalItems, page.totalItems)
    assertEquals(mapped.nextPageToken, page.nextPageToken)
  }

  test("an empty page still says which page was asked for") {
    val page = Page.empty[String](request(3, 50))
    assertEquals(page.items, Nil)
    assertEquals(page.page, 3)
    assertEquals(page.pageSize, 50)
    assertEquals(page.totalItems, Some(0L))
  }

  test("a page size of zero, a negative one, or one above the maximum is refused") {
    assertEquals(PageSize.from(0), Left(ValidationError.Range("pageSize", Some("1"), Some("500"), "0")))
    assertEquals(
      PageSize.from(-5),
      Left(ValidationError.Range("pageSize", Some("1"), Some("500"), "-5"))
    )
    assertEquals(
      PageSize.from(501),
      Left(ValidationError.Range("pageSize", Some("1"), Some("500"), "501"))
    )
    assertEquals(PageSize.from(1).map(_.value), Right(1))
    assertEquals(PageSize.from(500).map(_.value), Right(500))
  }

  test("the defaults are the ones ADR-026 fixes: page 1, 25 items, at most 500") {
    assertEquals(PageSize.Default.value, 25)
    assertEquals(PageSize.Max.value, 500)
    assertEquals(PageRequest.Default.page.value, 1)
    assertEquals(PageRequest.Default.pageSize.value, 25)
  }

  test("a page number below one is refused, and the error names the page, not the int") {
    assertEquals(
      PageRequest.from(0, 25),
      Left(ValidationError.Range("page", Some("1"), None, "0"))
    )
  }

  test("a page request refuses a bad page size too") {
    assert(PageRequest.from(1, 0).isLeft)
    assertEquals(PageRequest.from(2, 10).map(_.offset), Right(10))
  }

  test("a page token must be non-empty and no larger than 32 KiB") {
    assert(PageToken.from("").isLeft)
    assert(PageToken.from("x" * (32 * 1024 + 1)).isLeft)
    assertEquals(PageToken.from("abc").map(_.value), Right("abc"))
  }

  test("sort order has a stable lowercase wire form in both directions") {
    assertEquals(SortOrder.Asc.wire, "asc")
    assertEquals(SortOrder.Desc.wire, "desc")
    assertEquals(SortOrder.fromWire("asc"), Some(SortOrder.Asc))
    assertEquals(SortOrder.fromWire("desc"), Some(SortOrder.Desc))
    assertEquals(SortOrder.fromWire("ASC"), None)
  }
}
