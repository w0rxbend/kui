package kui.contracts.paging

import io.circe.parser.parse
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.contracts.topic.TopicGoldenDocuments
import kui.kernel.{Page, PageRequest, PageSize, PageToken, PositiveInt}

/** The page arithmetic on the wire, and the one number this shape exists to make impossible to get wrong.
  *
  * Cross-compiled: it runs unchanged under Node, which is the only way to be sure the browser reads what the
  * server writes rather than something that merely looks similar.
  */
final class PageDtoSuite extends ScalaCheckSuite {

  private val emptyPage: PageDto[String] =
    PageDto(Nil, PageInfo(page = 1, pageSize = 25, totalItems = Some(0L), nextPageToken = None))

  property("pageCountIsDerivedFromTotalItems") {
    forAll(Gen.chooseNum(0L, 1000000L), Gen.chooseNum(1, 500)) { (total: Long, size: Int) =>
      val expected = math.max(1, math.ceil(total.toDouble / size.toDouble).toInt)
      assertEquals(PageInfo(1, size, Some(total), None).pageCount, Some(expected))
    }
  }

  property("anExactMultipleDoesNotGainASpareEmptyPage") {
    forAll(Gen.chooseNum(1, 200), Gen.chooseNum(1, 500)) { (pages: Int, size: Int) =>
      assertEquals(PageInfo(1, size, Some(pages.toLong * size.toLong), None).pageCount, Some(pages))
    }
  }

  test("anEmptyListIsPageOneOfOne") {
    // A pageCount of 0 makes a client render "Page 1 of 0", which reads as broken rather than as empty.
    assertEquals(PageInfo(1, 25, Some(0L), None).pageCount, Some(1))
  }

  test("pageCountIsAbsentWhenTotalItemsIs") {
    // A cursor-paged response of M3 counts nothing, so it can say nothing about how many pages there are.
    assertEquals(PageInfo(1, 25, None, None).pageCount, None)
  }

  test("pageCountIsNeverDividedByZero") {
    // Not reachable through `PageSize`, which is 1..500 — but `PageInfo` is a wire record and decodes
    // whatever a producer sent, so the arithmetic has to survive a zero rather than throw at render time.
    assertEquals(PageInfo(1, 0, Some(10L), None).pageCount, Some(1))
  }

  property("ofPreservesPaginationAndMapsItems") {
    forAll(Gen.listOf(Gen.chooseNum(0, 1000)), Gen.chooseNum(1, 10), Gen.chooseNum(1, 50)) {
      (items: List[Int], page: Int, size: Int) =>
        val kernelPage = Page.of(items, PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size)))
        val dto = PageDto.of(kernelPage)(_.toString)
        assertEquals(dto.items, kernelPage.items.map(_.toString))
        assertEquals(dto.page.page, kernelPage.page)
        assertEquals(dto.page.pageSize, kernelPage.pageSize)
        assertEquals(dto.page.totalItems, kernelPage.totalItems)
        assertEquals(dto.page.nextPageToken, kernelPage.nextPageToken)
    }
  }

  test("ofCarriesACursorThroughUntouched") {
    val token = PageToken.unsafe("eyJ2IjoxfQ.c2ln")
    val kernelPage = Page(List(1), page = 1, pageSize = 25, totalItems = None, nextPageToken = Some(token))
    val dto = PageDto.of(kernelPage)(identity)
    assertEquals(dto.page.nextPageToken, Some(token))
    assertEquals(dto.page.pageCount, None)
  }

  test("goldenRoundTrip: the committed empty page is exactly what the encoder writes") {
    val normalised = parse(TopicGoldenDocuments.page)
      .fold(failure => fail(s"the golden document is not JSON: ${failure.message}"), _.spaces2)
    assertNoDiff(emptyPage.asJson.spaces2, normalised)
  }

  test("goldenRoundTrip: the committed empty page decodes back to the value it was written from") {
    assertEquals(parse(TopicGoldenDocuments.page).flatMap(_.as[PageDto[String]]), Right(emptyPage))
  }

  test("theCompactFormIsTheDocumentTheTaskSpecNames") {
    // Asserted on the compact text as well as on the pretty one, because the acceptance criterion of
    // TOP-019 is written that way and the field order is part of what it fixes.
    assertEquals(
      emptyPage.asJson.noSpaces,
      """{"items":[],"page":{"page":1,"pageSize":25,"totalItems":0,"pageCount":1,"nextPageToken":null}}"""
    )
  }

  test("pageCountIsWrittenButNeverBelieved") {
    // A producer that sent a pageCount disagreeing with its own totalItems must not be taken at its word:
    // the decoder ignores the field and the accessor recomputes it. This is the reference product's bug
    // (its count is computed before its filter runs) made unrepresentable.
    val lying = """{"items":[],"page":{"page":1,"pageSize":25,"totalItems":100,"pageCount":99,"nextPageToken":null}}"""
    val decoded = parse(lying).flatMap(_.as[PageDto[String]])
    assertEquals(decoded.map(_.page.pageCount), Right(Some(4)))
  }
}
