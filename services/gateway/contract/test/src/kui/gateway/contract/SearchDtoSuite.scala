package kui.gateway.contract

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.gateway.contract.dto.{GroupHitDto, SearchAnswerDto, SearchResultsDto, TopicHitDto}
import kui.kernel.{ClusterId, GroupId, ServiceId, TopicName}

/** That the search answer is exactly the document committed beside it, on both platforms.
  *
  * Cross-compiled for the reason every gateway aggregation's suite is: this document is assembled by the
  * gateway and decoded by the browser, and the browser's half of an aggregation is the half that has twice
  * gone unrun until a screen rendered the wrong thing.
  *
  * The case that earns its place is the empty `subjects` beside a `partial` naming `schema`. That pair is
  * the endpoint's entire argument — "we could not ask" is not "nothing matched" — and it is the one thing a
  * later change could quietly drop by omitting an empty list from the encoder.
  */
final class SearchDtoSuite extends FunSuite {

  private val cluster = ClusterId.unsafe("prod-eu")

  private val answer = SearchAnswerDto(
    SearchResultsDto(
      topics = List(TopicHitDto(cluster, TopicName.unsafe("orders.v1"))),
      groups = List(GroupHitDto(cluster, GroupId.unsafe("orders-consumer"))),
      subjects = Nil
    ),
    partial = List(ServiceId.unsafe("schema"))
  )

  test("theGoldenDocumentDecodesOnBothPlatforms") {
    assertNoDiff(
      answer.asJson.spaces2,
      parse(GoldenDocuments.search).fold(failure => fail(failure.message), _.spaces2)
    )
    assertEquals(parse(GoldenDocuments.search).flatMap(_.as[SearchAnswerDto]), Right(answer))
  }

  test("aCategoryThatCouldNotBeAskedIsAnEmptyListAndNotAMissingKey") {
    // Encoded, not asserted on the value: a `subjects` key that disappeared when the list was empty would
    // leave the browser unable to tell an unasked service from a service with nothing to say, which is the
    // distinction `partial` exists to draw.
    val keys = answer.results.asJson.asObject.map(_.keys.toList).getOrElse(Nil)

    assertEquals(keys.sorted, List("groups", "subjects", "topics"))
    assertEquals(answer.results.subjects, Nil)
    assertEquals(answer.partial.map(_.value), List("schema"))
  }

  test("everyServiceAnsweredIsAnEmptyPartialList") {
    // The ordinary answer. It is a list and not a null, so a client can write `partial.length` without
    // guarding it on every render.
    val complete = SearchAnswerDto(SearchResultsDto.Empty, Nil)

    assertEquals(
      complete.asJson.noSpaces,
      """{"results":{"topics":[],"groups":[],"subjects":[]},"partial":[]}"""
    )
  }
}
