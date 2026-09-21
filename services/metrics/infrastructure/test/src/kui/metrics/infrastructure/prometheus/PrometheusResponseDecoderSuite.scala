package kui.metrics.infrastructure.prometheus

import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.Using

import io.circe.parser
import munit.FunSuite

import kui.config.{MetricsSourceSettings, SafeUrl}

final class PrometheusResponseDecoderSuite extends FunSuite {

  private val retrievedAt = Instant.parse("2026-09-20T12:00:00Z")
  private val query =
    for {
      id <- QueryId.from("decoder.test.v1")
      compiled <- CompiledPromQuery.compile(id, "vector(1)")
    } yield compiled

  private val defaultLimits = PrometheusDecodeLimits(
    maxSeries = 200,
    maxPointsPerSeries = 600,
    maxTotalSamples = 120000,
    maxLabelsPerSeries = 64,
    maxLabelBytesPerSeries = 16384
  )

  private def fixture(name: String): String =
    Using.resource(Option(getClass.getResourceAsStream(s"/prometheus/$name")).getOrElse {
      fail(s"the Prometheus fixture '$name' is not on the test classpath")
    })(stream => Source.fromInputStream(stream, "UTF-8").mkString)

  private def fixtureCase(name: String, field: String): String =
    parser
      .parse(fixture(name))
      .flatMap(_.hcursor.downField(field).focus.toRight(new IllegalArgumentException(field)))
      .fold(problem => fail(problem.toString), _.noSpaces)

  private def rangeQuery(from: Instant, to: Instant, maxPoints: Int): RangeQuery =
    RangeQuery
      .create(
        query.fold(problem => fail(problem.toString), identity),
        from,
        to,
        1.minute,
        maxPoints
      )
      .fold(problem => fail(problem.toString), identity)

  private val fixtureRange = rangeQuery(
    Instant.parse("2024-09-06T12:00:00Z"),
    Instant.parse("2024-09-06T12:04:00Z"),
    maxPoints = 5
  )

  private val epochRange = rangeQuery(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), maxPoints = 2)

  test("a strict success vector preserves labels, exact sample timestamps and real zero") {
    val answer = PrometheusResponseDecoder
      .decodeInstant(fixture("success.json"), retrievedAt, defaultLimits)
      .fold(problem => fail(problem.toString), identity)

    assertEquals(answer.result.series.size, 2)
    assertEquals(
      answer.result.series.head.labels,
      MetricLabels(Map("cluster_name" -> "production", "group" -> "orders"))
    )
    assertEquals(answer.result.series.head.sample.at.unixSeconds, BigDecimal("1725624000.123456789"))
    assertEquals(answer.result.series.head.sample.value, finite(0.0))
    assertEquals(answer.result.series(1).sample.value, finite(1250.5))
    assertEquals(answer.freshness, QueryFreshness.Fresh(retrievedAt))
  }

  test("warning and info text is reduced to bounded counts") {
    val answer = PrometheusResponseDecoder
      .decodeInstant(fixture("success.json"), retrievedAt, defaultLimits)
      .fold(problem => fail(problem.toString), identity)

    assertEquals(answer.diagnostics, QueryDiagnostics(warningCount = 2, infoCount = 1))
    assert(!answer.toString.contains("sensitive warning"), answer.toString)

    val warnings = List.fill(PrometheusResponseDecoder.MaxDiagnosticCount + 20)("do not retain me")
    val body =
      s"""{"status":"success","data":{"resultType":"vector","result":[]},"warnings":[${warnings
          .map(value => s"\"$value\"")
          .mkString(",")}],"infos":[${warnings.map(value => s"\"$value\"").mkString(",")}]}"""
    val bounded = PrometheusResponseDecoder
      .decodeInstant(body, retrievedAt, defaultLimits)
      .fold(problem => fail(problem.toString), identity)

    assertEquals(
      bounded.diagnostics,
      QueryDiagnostics(
        warningCount = PrometheusResponseDecoder.MaxDiagnosticCount,
        infoCount = PrometheusResponseDecoder.MaxDiagnosticCount
      )
    )
    assert(!bounded.toString.contains("do not retain me"), bounded.toString)
  }

  test("a success matrix preserves ordered points and every legal non-finite kind") {
    val answer = PrometheusResponseDecoder
      .decodeRange(fixture("nonfinite.json"), retrievedAt, fixtureRange, defaultLimits)
      .fold(problem => fail(problem.toString), identity)
    val samples = answer.result.series.head.samples

    assertEquals(
      samples.map(_.at.unixSeconds),
      Vector(
        BigDecimal("1725624000"),
        BigDecimal("1725624060.000000001"),
        BigDecimal("1725624120"),
        BigDecimal("1725624180"),
        BigDecimal("1725624240")
      )
    )
    assertEquals(
      samples.map(_.value),
      Vector(
        finite(0.0),
        SampleValue.NonFinite(NonFiniteKind.NaN),
        SampleValue.NonFinite(NonFiniteKind.PositiveInfinity),
        SampleValue.NonFinite(NonFiniteKind.PositiveInfinity),
        SampleValue.NonFinite(NonFiniteKind.NegativeInfinity)
      )
    )
    assertEquals(answer.diagnostics, QueryDiagnostics.Empty)
  }

  test("empty vector and matrix successes are data, not failures or invented zero") {
    val vector = """{"status":"success","data":{"resultType":"vector","result":[]}}"""
    val matrix = """{"status":"success","data":{"resultType":"matrix","result":[]}}"""

    assertEquals(
      PrometheusResponseDecoder.decodeInstant(vector, retrievedAt, defaultLimits).map(_.result),
      Right(InstantQueryResult(Vector.empty))
    )
    assertEquals(
      PrometheusResponseDecoder.decodeRange(matrix, retrievedAt, epochRange, defaultLimits).map(_.result),
      Right(RangeQueryResult(Vector.empty))
    )
  }

  test("unknown nonessential envelope, data and series fields stay forward-compatible") {
    assert(
      PrometheusResponseDecoder
        .decodeInstant(fixture("success.json"), retrievedAt, defaultLimits)
        .isRight
    )
    assert(
      PrometheusResponseDecoder
        .decodeRange(fixture("nonfinite.json"), retrievedAt, fixtureRange, defaultLimits)
        .isRight
    )
  }

  test("only a success envelope with the requested result type is accepted") {
    val error = """{"status":"error","errorType":"bad_data","error":"canary body"}"""
    val matrix = """{"status":"success","data":{"resultType":"matrix","result":[]}}"""
    val vector = """{"status":"success","data":{"resultType":"vector","result":[]}}"""

    assertEquals(
      PrometheusResponseDecoder.decodeInstant(error, retrievedAt, defaultLimits),
      Left(PrometheusDecodeProblem.UpstreamError)
    )
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(matrix, retrievedAt, defaultLimits),
      Left(PrometheusDecodeProblem.MismatchedResultType)
    )
    assertEquals(
      PrometheusResponseDecoder.decodeRange(vector, retrievedAt, epochRange, defaultLimits),
      Left(PrometheusDecodeProblem.MismatchedResultType)
    )
  }

  test("error envelopes and malformed envelopes map to stable failures without retaining upstream canaries") {
    val cases = List(
      "errorEnvelope" -> PrometheusDecodeProblem.UpstreamError,
      "unknownStatus" -> PrometheusDecodeProblem.InvalidEnvelope,
      "malformedEnvelope" -> PrometheusDecodeProblem.InvalidEnvelope
    )

    cases.foreach { case (field, expected) =>
      val result = PrometheusResponseDecoder.decodeInstant(
        fixtureCase("errors.json", field),
        retrievedAt,
        defaultLimits
      )
      assertEquals(result, Left(expected), field)
      assert(!result.toString.contains("CANARY"), result.toString)
      assert(!result.toString.contains("super-secret"), result.toString)
      assert(!result.toString.contains("private_metric"), result.toString)
    }
  }

  test("duplicate envelope, data and metric-label keys are rejected before last-key-wins parsing") {
    val cases = List(
      """{"status":"error","status":"success","data":{"resultType":"vector","result":[]}}""",
      """{"status":"success","data":{},"data":{"resultType":"vector","result":[]}}""",
      """{"status":"success","data":{"resultType":"matrix","resultType":"vector","result":[]}}""",
      """{"status":"success","data":{"resultType":"vector","result":[],"result":[]}}""",
      """{"status":"success","data":{"resultType":"vector","result":[{
        |"metric":{"a":"1","\u0061":"2"},"value":[0,"1"]}]}}""".stripMargin
    )

    cases.foreach(body =>
      assertEquals(
        PrometheusResponseDecoder.decodeInstant(body, retrievedAt, defaultLimits),
        Left(PrometheusDecodeProblem.DuplicateObjectKey),
        body
      )
    )
  }

  test("malformed tuples, timestamps and unquoted values map to InvalidSample") {
    List("malformedTuple", "malformedTimestamp", "unquotedValue").foreach { field =>
      assertEquals(
        PrometheusResponseDecoder.decodeInstant(
          fixtureCase("errors.json", field),
          retrievedAt,
          defaultLimits
        ),
        Left(PrometheusDecodeProblem.InvalidSample),
        field
      )
    }
  }

  test("compact extreme timestamp exponents are rejected without exponent-sized expansion") {
    List("1e100000", "-1e100000", "1e-100000").foreach { timestamp =>
      val body =
        s"""{"status":"success","data":{"resultType":"vector","result":[{
           |"metric":{},"value":[$timestamp,"1"]}]}}""".stripMargin
      assertEquals(
        PrometheusResponseDecoder.decodeInstant(body, retrievedAt, defaultLimits),
        Left(PrometheusDecodeProblem.InvalidSample),
        timestamp
      )
    }
  }

  test("malformed envelope, diagnostic, series and sample shapes have stable categories") {
    val cases = List(
      "not-json" -> PrometheusDecodeProblem.InvalidJson,
      "[]" -> PrometheusDecodeProblem.InvalidEnvelope,
      "{}" -> PrometheusDecodeProblem.InvalidEnvelope,
      """{"status":1}""" -> PrometheusDecodeProblem.InvalidEnvelope,
      """{"status":"success","data":[]}""" -> PrometheusDecodeProblem.InvalidEnvelope,
      """{"status":"success","data":{"resultType":"vector","result":[]},"warnings":"CANARY"}""" ->
        PrometheusDecodeProblem.InvalidDiagnostics,
      """{"status":"success","data":{"resultType":"vector","result":[null]}}""" ->
        PrometheusDecodeProblem.InvalidSeries,
      """{"status":"success","data":{"resultType":"vector","result":[{
        |"metric":{"a":1},"value":[0,"1"]}]}}""".stripMargin ->
        PrometheusDecodeProblem.InvalidSeries,
      """{"status":"success","data":{"resultType":"vector","result":[{
        |"metric":{},"value":[0,"CANARY_VALUE"]}]}}""".stripMargin ->
        PrometheusDecodeProblem.InvalidSample
    )

    cases.foreach { case (body, expected) =>
      val result = PrometheusResponseDecoder.decodeInstant(body, retrievedAt, defaultLimits)
      assertEquals(result, Left(expected), body)
      assert(!result.toString.contains("CANARY"), result.toString)
    }

    val malformedRange =
      """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":"CANARY"}]}}"""
    val rangeResult =
      PrometheusResponseDecoder.decodeRange(malformedRange, retrievedAt, epochRange, defaultLimits)
    assertEquals(rangeResult, Left(PrometheusDecodeProblem.InvalidSeries))
    assert(!rangeResult.toString.contains("CANARY"), rangeResult.toString)
  }

  test(
    "scalar, string and unknown result types are unsupported while vector-matrix mismatches are distinct"
  ) {
    List("scalar", "string", "unknown").foreach { field =>
      val result = PrometheusResponseDecoder.decodeInstant(
        fixtureCase("unsupported.json", field),
        retrievedAt,
        defaultLimits
      )
      assertEquals(result, Left(PrometheusDecodeProblem.UnsupportedResultType), field)
      assert(!result.toString.contains("CANARY"), result.toString)
    }

    assertEquals(
      PrometheusResponseDecoder.decodeInstant(
        fixtureCase("unsupported.json", "matrix"),
        retrievedAt,
        defaultLimits
      ),
      Left(PrometheusDecodeProblem.MismatchedResultType)
    )
    assertEquals(
      PrometheusResponseDecoder.decodeRange(
        fixtureCase("unsupported.json", "vector"),
        retrievedAt,
        epochRange,
        defaultLimits
      ),
      Left(PrometheusDecodeProblem.MismatchedResultType)
    )
  }

  test("native instant and range histograms have one explicit unsupported mapping") {
    val instant = PrometheusResponseDecoder.decodeInstant(
      fixtureCase("unsupported.json", "instantHistogram"),
      retrievedAt,
      defaultLimits
    )
    val range = PrometheusResponseDecoder.decodeRange(
      fixtureCase("unsupported.json", "rangeHistogram"),
      retrievedAt,
      epochRange,
      defaultLimits
    )

    assertEquals(instant, Left(PrometheusDecodeProblem.NativeHistogramUnsupported))
    assertEquals(range, Left(PrometheusDecodeProblem.NativeHistogramUnsupported))
    assert(!instant.toString.contains("CANARY_HISTOGRAM"), instant.toString)
    assert(!range.toString.contains("CANARY_HISTOGRAM"), range.toString)
  }

  test("series and total-sample bounds accept the limit and reject one over") {
    val twoSeries = fixtureCase("limits.json", "instantAtSeriesLimit")
    val threeSeries = fixtureCase("limits.json", "instantOverSeriesLimit")
    val strict = defaultLimits.copy(maxSeries = 2, maxTotalSamples = 2)

    assert(PrometheusResponseDecoder.decodeInstant(twoSeries, retrievedAt, strict).isRight)
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(threeSeries, retrievedAt, strict),
      Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.Series))
    )

    val matrix = fixtureCase("limits.json", "rangeAtLimits")
    val fourSamples = strict.copy(maxPointsPerSeries = 2, maxTotalSamples = 4)
    assert(PrometheusResponseDecoder.decodeRange(matrix, retrievedAt, epochRange, fourSamples).isRight)
    assertEquals(
      PrometheusResponseDecoder.decodeRange(
        matrix,
        retrievedAt,
        epochRange,
        fourSamples.copy(maxTotalSamples = 3)
      ),
      Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.TotalSamples))
    )
  }

  test("point bounds accept the limit and reject one over before decoding samples") {
    val twoPoints = fixtureCase("limits.json", "rangeAtLimits")
    val threePoints = fixtureCase("limits.json", "rangeOverPoints")
    val strict = defaultLimits.copy(maxSeries = 2, maxPointsPerSeries = 2, maxTotalSamples = 4)

    assert(PrometheusResponseDecoder.decodeRange(twoPoints, retrievedAt, epochRange, strict).isRight)
    assertEquals(
      PrometheusResponseDecoder.decodeRange(threePoints, retrievedAt, epochRange, strict),
      Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.PointsPerSeries))
    )
  }

  test("label count and UTF-8 byte bounds accept the limit and reject one over") {
    val twoLabels = fixtureCase("limits.json", "instantAtSeriesLimit")
    val threeLabels = fixtureCase("limits.json", "instantOverLabelCount")
    val countLimits = defaultLimits.copy(maxLabelsPerSeries = 2)

    assert(PrometheusResponseDecoder.decodeInstant(twoLabels, retrievedAt, countLimits).isRight)
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(threeLabels, retrievedAt, countLimits),
      Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelsPerSeries))
    )

    val utf8 =
      """{"status":"success","data":{"resultType":"vector","result":[{
        |"metric":{"é":"é"},"value":[0,"1"]}]}}""".stripMargin
    assert(
      PrometheusResponseDecoder
        .decodeInstant(utf8, retrievedAt, defaultLimits.copy(maxLabelBytesPerSeries = 4))
        .isRight
    )
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(
        utf8,
        retrievedAt,
        defaultLimits.copy(maxLabelBytesPerSeries = 3)
      ),
      Left(PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelBytesPerSeries))
    )
  }

  test("duplicate canonical label sets are refused for instant and range answers") {
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(
        fixtureCase("limits.json", "duplicateInstantSeries"),
        retrievedAt,
        defaultLimits
      ),
      Left(PrometheusDecodeProblem.DuplicateSeries)
    )
    assertEquals(
      PrometheusResponseDecoder.decodeRange(
        fixtureCase("limits.json", "duplicateRangeSeries"),
        retrievedAt,
        epochRange,
        defaultLimits
      ),
      Left(PrometheusDecodeProblem.DuplicateSeries)
    )

    val reordered =
      """{"status":"success","data":{"resultType":"vector","result":[
        |{"metric":{"a":"1","b":"2"},"value":[0,"1"]},
        |{"metric":{"b":"2","a":"1"},"value":[1,"2"]}]}}""".stripMargin
    assertEquals(
      PrometheusResponseDecoder.decodeInstant(reordered, retrievedAt, defaultLimits),
      Left(PrometheusDecodeProblem.DuplicateSeries)
    )
  }

  test("range points must be strictly increasing and inside the requested inclusive window") {
    val widerRange = rangeQuery(Instant.EPOCH, Instant.EPOCH.plusSeconds(120), maxPoints = 3)

    assertEquals(
      PrometheusResponseDecoder.decodeRange(
        fixtureCase("limits.json", "nonMonotonic"),
        retrievedAt,
        widerRange,
        defaultLimits
      ),
      Left(PrometheusDecodeProblem.NonMonotonicSamples)
    )
    List("beforeWindow", "afterWindow").foreach { field =>
      assertEquals(
        PrometheusResponseDecoder.decodeRange(
          fixtureCase("limits.json", field),
          retrievedAt,
          epochRange,
          defaultLimits
        ),
        Left(PrometheusDecodeProblem.SampleOutsideRange),
        field
      )
    }
  }

  test("invalid decoder limits fail before inspecting an upstream body") {
    val invalid = defaultLimits.copy(maxSeries = 0)
    val body = "CANARY_NOT_JSON"
    val result = PrometheusResponseDecoder.decodeInstant(body, retrievedAt, invalid)

    assertEquals(result, Left(PrometheusDecodeProblem.InvalidLimits))
    assert(!result.toString.contains("CANARY_NOT_JSON"), result.toString)
  }

  test("decoder limits derive safely from minimum, default and maximum validated source budgets") {
    val base = MetricsSourceSettings(SafeUrl.unsafe("https://prometheus.example/base"))
    val settings = List(
      base.copy(
        maxSeriesPerQuery = MetricsSourceSettings.MinSeriesPerQuery,
        maxPointsPerSeries = MetricsSourceSettings.MinPointsPerSeries
      ),
      base,
      base.copy(
        maxSeriesPerQuery = MetricsSourceSettings.MaxSeriesPerQuery,
        maxPointsPerSeries = MetricsSourceSettings.MaxPointsPerSeries
      )
    )

    assertEquals(
      settings
        .map(PrometheusDecodeLimits.fromSettings)
        .map(limits => (limits.maxSeries, limits.maxPointsPerSeries, limits.maxTotalSamples)),
      List(
        (1, 60, 60),
        (200, 600, 120000),
        (1000, 2000, 2000000)
      )
    )
    assert(settings.map(PrometheusDecodeLimits.fromSettings).forall(_.maxLabelsPerSeries == 64))
    assert(settings.map(PrometheusDecodeLimits.fromSettings).forall(_.maxLabelBytesPerSeries == 16384))
  }

  private def finite(value: Double): SampleValue.Finite =
    SampleValue.finite(value).fold(problem => fail(problem.toString), identity)
}
