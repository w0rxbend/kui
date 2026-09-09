package kui.metrics.contract

import java.time.Instant

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.metrics.contract.dto.*

/** That each metrics response is exactly the document committed beside it, and that the shapes a real
  * exporter produces are among those documents rather than only the happy one.
  *
  * Cross-compiled: the same assertions run under Node, which is what makes "the browser decodes what the
  * service encodes" a fact rather than a hope. The browser's own half is
  * `frontend/packages/shell/src/overview/wire.golden.test.ts`, which reads these same files off disk and
  * runs them through the fetchers the screen uses — so the two sides are asserted against one artefact
  * instead of against two hand-written literals that agree with nothing but themselves.
  */
final class MetricsResponsesSuite extends FunSuite {

  private val at = Instant.parse("2026-09-06T12:00:00Z")
  private val from = Instant.parse("2026-09-06T11:45:00Z")

  private def assertGolden(name: String, document: String, encoded: Json): Unit =
    assertNoDiff(
      encoded.spaces2,
      parse(document).fold(failure => fail(s"$name is not JSON: ${failure.message}"), _.spaces2)
    )

  // -----------------------------------------------------------------------------------------------
  // The two series
  // -----------------------------------------------------------------------------------------------

  private val throughput = ThroughputResponse(
    Section.Ok(
      ThroughputSeriesDto(
        range = ThroughputRangeDto.Last24Hours,
        from = from,
        to = at,
        stepSeconds = 300L,
        buckets = List(
          ThroughputBucketDto(from, Some(124800.5), Some(249600.75), Some(1420.75)),
          ThroughputBucketDto(from.plusSeconds(300L), None, None, None),
          ThroughputBucketDto(from.plusSeconds(600L), Some(0.0), Some(0.0), Some(0.0))
        )
      ),
      at
    )
  )

  private val latency = LatencyResponse(
    Section.Ok(
      LatencySeriesDto(
        window = ThroughputRangeDto.Last24Hours,
        from = from,
        to = at,
        stepSeconds = 300L,
        buckets = List(
          LatencyBucketDto(from, Some(9.0), Some(502.0)),
          LatencyBucketDto(from.plusSeconds(300L), Some(7.5), None),
          LatencyBucketDto(from.plusSeconds(600L), None, None)
        )
      ),
      at
    )
  )

  test("a throughput series is exactly its golden document, gap and measured zero and all") {
    assertGolden("throughput-response.json", GoldenDocuments.throughputResponse, throughput.asJson)
    assertEquals(parse(GoldenDocuments.throughputResponse).flatMap(_.as[ThroughputResponse]), Right(throughput))
  }

  test("a null rate and a zero rate survive the round trip as different facts") {
    // The rule the whole screen rests on. If either becomes the other in transit, a cluster nobody measured
    // draws as an idle cluster, or an idle cluster draws as a gap — and both are readings an operator acts
    // on. Asserted through the document rather than on the case class, because the JSON is where they could
    // collapse.
    val decoded = parse(GoldenDocuments.throughputResponse)
      .flatMap(_.as[ThroughputResponse])
      .fold(failure => fail(failure.toString), identity)
    val buckets = decoded.throughput.toOption.map(_.buckets).getOrElse(fail("the golden section carries data"))

    assertEquals(buckets.map(_.bytesInPerSecond), List(Some(124800.5), None, Some(0.0)))
  }

  test("a latency series is exactly its golden document, including a half-measured step") {
    assertGolden("latency-response.json", GoldenDocuments.latencyResponse, latency.asJson)
    assertEquals(parse(GoldenDocuments.latencyResponse).flatMap(_.as[LatencyResponse]), Right(latency))
  }

  // -----------------------------------------------------------------------------------------------
  // The three point-in-time readings
  // -----------------------------------------------------------------------------------------------

  private val handlers = RequestHandlersDto(
    requestHandlerIdleRatio = Some(0.8912),
    networkProcessorIdleRatio = Some(0.7104),
    purgatory = List(PurgatoryQueueDto("Fetch", 481L), PurgatoryQueueDto("Produce", 0L))
  )

  test("a request-handlers document is exactly its golden, with ratios and queue lengths and no readings[]") {
    // The wire two packets guessed differently. The keys below are this endpoint's whole vocabulary: two
    // ratios and a `purgatory` list. There is no `readings` array, there never was, and a browser that
    // decoded one got an empty list and drew "the source served no readings" over a source that served
    // three.
    val response = RequestHandlersResponse(Section.Ok(handlers, at))

    assertGolden("request-handlers-response.json", GoldenDocuments.requestHandlersResponse, response.asJson)
    assertEquals(
      parse(GoldenDocuments.requestHandlersResponse).flatMap(_.as[RequestHandlersResponse]),
      Right(response)
    )
    assert(!GoldenDocuments.requestHandlersResponse.contains("readings"))
  }

  test("an hour-old reading is a stale section carrying its figures and the instant they were taken") {
    // The fourth section state, which this service is the reason for. Everything in `data` is true; none of
    // it is current, and `fetchedAt` is an hour before the reply rather than the moment of it.
    val response =
      RequestHandlersResponse(
        Section.Stale(handlers.copy(purgatory = Nil), at.minusSeconds(3600L), ReasonCode.UpstreamUnavailable)
      )

    assertGolden(
      "request-handlers-response-stale.json",
      GoldenDocuments.requestHandlersResponseStale,
      response.asJson
    )
    assertEquals(
      parse(GoldenDocuments.requestHandlersResponseStale).flatMap(_.as[RequestHandlersResponse]),
      Right(response)
    )
  }

  test("a ratio the exporter named and did not measure is null and never zero") {
    // Zero is "the pool was saturated", null is "nobody looked". A card drawn from the first when the second
    // is true reports an incident that is not happening.
    val response =
      RequestHandlersResponse(Section.Ok(RequestHandlersDto(None, Some(0.7104), Nil), at))

    assertGolden("request-handlers-one-absent.json", GoldenDocuments.requestHandlersOneAbsent, response.asJson)
    assertEquals(
      parse(GoldenDocuments.requestHandlersOneAbsent).flatMap(_.as[RequestHandlersResponse]),
      Right(response)
    )
  }

  test("a top-producers document is exactly its golden, by topic and with the excluded count") {
    // The other wire the browser read wrongly: `topics` of `{topic, bytesInPerSecond}` under a `measuredBy`
    // that names what the rows are, not `entries` of `{clientId, bytesPerSecond}`.
    val response = TopProducersResponse(
      Section.Ok(
        TopProducersDto(
          measuredBy = TopProducersDto.ByTopic,
          topics = List(
            TopicProducerDto("orders.payments", 5400000.0),
            TopicProducerDto("analytics.clicks", 3100000.0),
            TopicProducerDto("audit.trail", 0.0)
          ),
          internalTopicsExcluded = 2
        ),
        at
      )
    )

    assertGolden("top-producers-response.json", GoldenDocuments.topProducersResponse, response.asJson)
    assertEquals(
      parse(GoldenDocuments.topProducersResponse).flatMap(_.as[TopProducersResponse]),
      Right(response)
    )
    assert(!GoldenDocuments.topProducersResponse.contains("clientId"))
  }

  test("a served family with no topic line is an ok section with an empty list, not a refusal") {
    val response =
      TopProducersResponse(Section.Ok(TopProducersDto(TopProducersDto.ByTopic, Nil, 0), at))

    assertGolden("top-producers-empty.json", GoldenDocuments.topProducersEmpty, response.asJson)
    assertEquals(parse(GoldenDocuments.topProducersEmpty).flatMap(_.as[TopProducersResponse]), Right(response))
  }

  test("a deployment with no metrics source answers not_configured and carries no data at all") {
    val response = TopProducersResponse(Section.NotConfigured)

    assertGolden(
      "top-producers-not-configured.json",
      GoldenDocuments.topProducersNotConfigured,
      response.asJson
    )
    assertEquals(
      parse(GoldenDocuments.topProducersNotConfigured).flatMap(_.as[TopProducersResponse]),
      Right(response)
    )
    assertEquals(response.producers.toOption, None)
  }

  test("a record size is a mean and the two rates it came from, with no percentile in the document") {
    val response = RecordSizeResponse(Section.Ok(RecordSizeDto(Some(128.0), Some(1024.0), Some(8.0)), at))

    assertGolden("record-size-response.json", GoldenDocuments.recordSizeResponse, response.asJson)
    assertEquals(parse(GoldenDocuments.recordSizeResponse).flatMap(_.as[RecordSizeResponse]), Right(response))
    // ADR-052's third refusal, asserted on the artefact a browser reads rather than on the type.
    assert(!GoldenDocuments.recordSizeResponse.contains("p50"))
    assert(!GoldenDocuments.recordSizeResponse.contains("bucket"))
  }

  test("a family the exporter does not publish is unavailable with the sentence that names the whitelist") {
    val response = RecordSizeResponse(
      Section.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "the metrics exporter answered and 3 reading(s) of it carry no bytes-in and records-in rate to divide",
        Some(at)
      )
    )

    assertGolden("record-size-unavailable.json", GoldenDocuments.recordSizeUnavailable, response.asJson)
    assertEquals(parse(GoldenDocuments.recordSizeUnavailable).flatMap(_.as[RecordSizeResponse]), Right(response))
  }

  test("every golden document names the section key its endpoint actually answers under") {
    // The mismatch that made this module necessary was invisible because the *top-level* key matched on both
    // sides. So the keys are asserted, in one place, against the documents rather than against the DTOs.
    assert(GoldenDocuments.throughputResponse.contains("\"throughput\""))
    assert(GoldenDocuments.latencyResponse.contains("\"latency\""))
    assert(GoldenDocuments.requestHandlersResponse.contains("\"requestHandlers\""))
    assert(GoldenDocuments.topProducersResponse.contains("\"producers\""))
    assert(GoldenDocuments.recordSizeResponse.contains("\"recordSize\""))
  }
}
