package kui.ui.messages.track

import java.time.Instant

import munit.FunSuite

import kui.contracts.message.DecodedPayloadDto
import kui.kernel.{Offset, PartitionId, TopicName}
import kui.message.contract.{MessageDto, TrackHitDto, TrackResultDto}

/** The two sentences the track screen says about a result, and the one rule about how a hit is shown.
  *
  * `summary` is where "no hits" stops being ambiguous, which is the single most important thing this screen
  * does with its answer: read a million records and match none, and read nothing at all, are the same word on
  * screen without it and mean opposite things.
  */
final class TrackPageSuite extends FunSuite {

  private def hit(topic: String, value: String): TrackHitDto =
    TrackHitDto(
      topic = TopicName.unsafe(topic),
      record = MessageDto(
        partition = PartitionId.unsafe(0),
        offset = Offset.unsafe(1L),
        timestamp = Instant.parse("2026-09-04T09:00:00Z"),
        timestampType = MessageDto.TimestampType.CreateTime,
        key = DecodedPayloadDto("k", DecodedPayloadDto.Kind.Text, "String", Map.empty),
        value = DecodedPayloadDto(value, DecodedPayloadDto.Kind.Text, "String", Map.empty),
        headers = Map.empty,
        keySize = 1,
        valueSize = value.length,
        headersSize = 0,
        deserializeErrors = Nil
      )
    )

  test("aScanThatReadNothingSaysSoRatherThanSayingNoHits") {
    // The user's next step is different: widen the window, rather than conclude the value is not there.
    val answer = TrackResultDto(hits = Nil, scanned = 0L, matched = 0L, truncated = false)

    assert(TrackPage.summary(Some(answer), running = false).contains("Nothing was read"))
  }

  test("aScanThatReadRecordsAndMatchedNoneSaysHowManyItRead") {
    val answer = TrackResultDto(hits = Nil, scanned = 12_000L, matched = 0L, truncated = false)

    assert(TrackPage.summary(Some(answer), running = false).contains("12000"))
  }

  test("aScanWithHitsSaysHowManyOfHowMuch") {
    val answer =
      TrackResultDto(hits = List(hit("orders.v1", "a")), scanned = 500L, matched = 1L, truncated = false)

    val line = TrackPage.summary(Some(answer), running = false)

    assert(line.contains("1 hit"), line)
    assert(line.contains("500"), line)
  }

  test("nothingIsSaidBeforeTheFirstSearch") {
    // An empty status line, not "no hits": nothing has been asked yet, and saying "no hits" would be an
    // answer to a question nobody put.
    assertEquals(TrackPage.summary(None, running = false), "")
  }

  test("aRunningSearchSaysSoRatherThanShowingTheLastAnswersCount") {
    val answer = TrackResultDto(hits = Nil, scanned = 10L, matched = 0L, truncated = false)

    assertEquals(TrackPage.summary(Some(answer), running = true), TrackMessages.Searching)
  }

  test("aLongPayloadIsClippedSoThatEveryRowIsTheSameHeight") {
    val long = "x" * 500

    assertEquals(TrackPage.preview(long).length, TrackMessages.PreviewLength + 1)
    assertEquals(TrackPage.preview("short"), "short")
  }
}
