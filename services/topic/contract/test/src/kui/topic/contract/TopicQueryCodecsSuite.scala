package kui.topic.contract

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import sttp.tapir.{Codec as TapirCodec, CodecFormat, DecodeResult}

import kui.contracts.KernelDecodeFailure
import kui.kernel.search.SearchMode
import kui.kernel.{PageRequest, PageSize, PositiveInt, Sort, SortOrder}

/** That the topic list refuses what it does not understand instead of guessing.
  *
  * Every assertion here is about one rule: a parameter that cannot be read is a 400 naming the parameter,
  * never a default quietly substituted for it. The reference product does the opposite — it resets an
  * out-of-range `page` to 1 and an unrecognised `mode` to substring matching — and the result is a screen
  * that answers a question nobody asked, with nothing anywhere saying so
  * (`research/kafbat/api-analysis.md` §3.3).
  */
final class TopicQueryCodecsSuite extends ScalaCheckSuite {

  import TopicQueryCodecs.given

  private val modeCodec = summon[TapirCodec[String, SearchMode, CodecFormat.TextPlain]]
  private val sortCodec = summon[TapirCodec[String, Sort[TopicSortField], CodecFormat.TextPlain]]

  /** The field a decode failure names, which is what the browser puts beside the offending input. */
  private def refusedField(result: DecodeResult[?]): Option[String] = result match {
    case DecodeResult.Error(_, failure: KernelDecodeFailure) => Some(failure.error.fieldName)
    case _ => None
  }

  test("sortParsesFieldColonDirection") {
    val table = List(
      "name:asc" -> Sort(TopicSortField.Name, SortOrder.Asc),
      "name:desc" -> Sort(TopicSortField.Name, SortOrder.Desc),
      "partitions:asc" -> Sort(TopicSortField.Partitions, SortOrder.Asc),
      "replicationFactor:desc" -> Sort(TopicSortField.ReplicationFactor, SortOrder.Desc),
      "outOfSyncReplicas:desc" -> Sort(TopicSortField.OutOfSyncReplicas, SortOrder.Desc),
      "size:desc" -> Sort(TopicSortField.Size, SortOrder.Desc),
      "messageCount:asc" -> Sort(TopicSortField.MessageCount, SortOrder.Asc)
    )

    table.foreach { case (raw, expected) =>
      assertEquals(sortCodec.decode(raw), DecodeResult.Value(expected), raw)
      // And back out again, so a client that echoes the sort it was given sends the same string.
      assertEquals(sortCodec.encode(expected), raw)
    }
  }

  test("everySortFieldHasAWireSpellingAndReadsBack") {
    // A field missing from `all` is a field the query string silently refuses, which looks exactly like a
    // broken sort from the outside.
    assertEquals(TopicSortField.all.size, TopicSortField.values.length)
    TopicSortField.values.foreach { field =>
      assertEquals(TopicSortField.fromWire(field.wire), Some(field))
    }
    assertEquals(TopicSortField.all.map(_.wire).distinct.size, TopicSortField.all.size)
  }

  test("anUnknownSortFieldIsADecodeFailure") {
    // Not a default. A silently ignored sort is how a user concludes that sorting is broken.
    assertEquals(refusedField(sortCodec.decode("nonsense:asc")), Some("sort"))
    assertEquals(refusedField(sortCodec.decode("name:sideways")), Some("sort"))
    // A bare field name is refused too: "size" meaning "biggest first" would otherwise silently give the
    // smallest topics.
    assertEquals(refusedField(sortCodec.decode("size")), Some("sort"))
    assertEquals(refusedField(sortCodec.decode("")), Some("sort"))
  }

  test("anUnknownModeIsADecodeFailure") {
    assertEquals(modeCodec.decode("plain"), DecodeResult.Value(SearchMode.Plain))
    assertEquals(modeCodec.decode("fts"), DecodeResult.Value(SearchMode.Fts))
    assertEquals(refusedField(modeCodec.decode("true")), Some("mode"))
    assertEquals(refusedField(modeCodec.decode("FTS")), Some("mode"))
  }

  private def params(page: Int, pageSize: Int): DecodeResult[TopicListParams] =
    TopicQueryCodecs.decodeParams(None, SearchMode.Plain, showInternal = false, sort = None, page, pageSize)

  test("pageSizeAboveTheMaximumIsADecodeFailure") {
    // ADR-026's 500. These lists are built in memory: a request for a million rows is not a big page, it
    // is an outage — so it is refused rather than clamped, which would answer a different question.
    assertEquals(refusedField(params(1, PageSize.Max.value + 1)), Some("pageSize"))
    assertEquals(refusedField(params(1, 1_000_000)), Some("pageSize"))
    assertEquals(refusedField(params(1, 0)), Some("pageSize"))

    assertEquals(
      params(1, PageSize.Max.value),
      DecodeResult.Value(TopicListParams.Default.copy(page = PageRequest(PositiveInt.One, PageSize.Max)))
    )
  }

  test("pageZeroOrNegativeIsADecodeFailure") {
    // The reference resets these to the default silently. Naming the problem is better than guessing what
    // was meant, and the field named is `page` and not `positiveInt`.
    assertEquals(refusedField(params(0, 25)), Some("page"))
    assertEquals(refusedField(params(-3, 25)), Some("page"))
  }

  test("defaultsAreTwentyFivePlainNotInternalAndNoSort") {
    assertEquals(params(1, PageSize.Default.value), DecodeResult.Value(TopicListParams.Default))

    assertEquals(TopicListParams.Default.mode, SearchMode.Plain)
    assertEquals(TopicListParams.Default.showInternal, false)
    assertEquals(TopicListParams.Default.sort, None)
    assertEquals(TopicListParams.Default.q, None)
    assertEquals(TopicListParams.Default.page.pageSize.value, 25)
    assertEquals(TopicListParams.Default.page.page.value, 1)
  }

  test("a blank search term is the same as none, so an emptied search box is not a search for nothing") {
    List("", "   ", "\t").foreach { blank =>
      val decoded =
        TopicQueryCodecs.decodeParams(Some(blank), SearchMode.Plain, false, None, 1, 25)
      assertEquals(decoded, DecodeResult.Value(TopicListParams.Default), blank)
    }

    assertEquals(
      TopicQueryCodecs.decodeParams(Some("  orders  "), SearchMode.Plain, false, None, 1, 25),
      DecodeResult.Value(TopicListParams.Default.copy(q = Some("orders")))
    )
  }

  property("every valid page and size is accepted and reaches the request unchanged") {
    forAll(Gen.choose(1, 100_000), Gen.choose(1, PageSize.Max.value)) { (page: Int, size: Int) =>
      params(page, size) match {
        case DecodeResult.Value(decoded) =>
          decoded.page.page.value == page && decoded.page.pageSize.value == size
        case _ => false
      }
    }
  }

  property("no page or size outside its bounds is ever accepted") {
    val outOfRange = Gen.oneOf(Gen.choose(Int.MinValue, 0), Gen.choose(PageSize.Max.value + 1, Int.MaxValue))

    forAll(outOfRange) { (bad: Int) =>
      // `page` has no upper bound, so only the non-positive half of the generator applies to it.
      val pageRefused = bad > 0 || refusedField(params(bad, 25)).contains("page")
      val sizeRefused = refusedField(params(1, bad)).contains("pageSize")
      pageRefused && sizeRefused
    }
  }

  property("a sort string round-trips through the codec for every field and direction") {
    val sorts = for {
      field <- Gen.oneOf(TopicSortField.all)
      order <- Gen.oneOf(SortOrder.Asc, SortOrder.Desc)
    } yield Sort(field, order)

    forAll(sorts) { (sort: Sort[TopicSortField]) =>
      sortCodec.decode(sortCodec.encode(sort)) == DecodeResult.Value(sort)
    }
  }
}
