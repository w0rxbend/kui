package kui.message.domain

import java.time.Instant

import munit.FunSuite

import kui.kernel.serde.{PayloadKind, SerdeName}
import kui.kernel.{Offset, PartitionId}

/** What a track means, over a field the record does not carry.
  *
  * `PreparedMatch.matches`'s own comment states the rule: a record whose searched field is absent is **not**
  * a hit for a positive operator and **is** a hit for a negative one, *"which is what 'this record does not
  * contain X' has to mean if it is to be usable for finding the messages that are missing something"*. That
  * is the whole feature — an operator tracking a missing correlation header — and it was asserted by nothing:
  * rewriting `!subject.exists(_.contains(value))` as `subject.exists(!_.contains(value))` left
  * `./mill libs.__.test + services.*` at 2633/2633 and made every record without the header a miss.
  */
final class TrackMatchingSuite extends FunSuite {

  private def decoded(text: String): Decoded =
    Decoded(text, PayloadKind.Text, SerdeName.unsafe("string"), Map.empty)

  private def record(headers: List[RenderedHeader]): DecodedRecord =
    DecodedRecord(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(1L),
      timestamp = Instant.parse("2026-02-01T10:00:00Z"),
      timestampType = TimestampType.CreateTime,
      key = decoded("order-1"),
      value = decoded("{\"total\":12}"),
      headers = headers,
      keySize = 7,
      valueSize = 12,
      headersSize = headers.map(header => header.key.length + header.value.length).sum,
      decodeErrors = Nil
    )

  private def matches(operator: MatchOperator, source: MatchSource, value: String)(
      of: DecodedRecord
  ): Boolean =
    PreparedMatch.of(TrackMatch(source, operator, value)).matches(of)

  private val carries = record(List(RenderedHeader("correlation-id", "abc-123")))
  private val missing = record(List(RenderedHeader("trace-id", "abc-123")))

  test("a record missing the header is a hit for NOT_CONTAINS, which is the point of tracking one") {
    assert(matches(MatchOperator.NotContains, MatchSource.Header("correlation-id"), "abc")(missing))
  }

  test("a record missing the header is a hit for NOT_EQUALS too") {
    assert(matches(MatchOperator.NotEquals, MatchSource.Header("correlation-id"), "abc-123")(missing))
  }

  test("a record missing the header is not a hit for CONTAINS or EQUALS") {
    // The complement, and what makes the two above more than "everything matches".
    assert(!matches(MatchOperator.Contains, MatchSource.Header("correlation-id"), "abc")(missing))
    assert(!matches(MatchOperator.Equals, MatchSource.Header("correlation-id"), "abc-123")(missing))
  }

  test("a record that carries the header is judged on its value and not on its presence") {
    assert(matches(MatchOperator.Contains, MatchSource.Header("correlation-id"), "abc")(carries))
    assert(!matches(MatchOperator.NotContains, MatchSource.Header("correlation-id"), "abc")(carries))
    assert(matches(MatchOperator.NotContains, MatchSource.Header("correlation-id"), "zzz")(carries))
  }

  test("a regex that cannot be applied answers false rather than failing the scan at its millionth record") {
    // `TrackQuery.of` refuses an invalid pattern, so this cannot arrive from a request; the guard exists
    // so that a matcher can never throw a scan away, and it is cheap to state.
    assert(!matches(MatchOperator.Regex, MatchSource.Header("correlation-id"), "abc")(missing))
    assert(matches(MatchOperator.Regex, MatchSource.Header("correlation-id"), "abc-\\d+")(carries))
  }

  test("key and value are always present, so a negative operator over them reads the text") {
    assert(matches(MatchOperator.NotContains, MatchSource.Value, "refund")(carries))
    assert(!matches(MatchOperator.NotContains, MatchSource.Key, "order")(carries))
  }
}
