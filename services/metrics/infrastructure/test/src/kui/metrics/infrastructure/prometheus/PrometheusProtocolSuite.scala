package kui.metrics.infrastructure.prometheus

import java.time.Instant

import scala.concurrent.duration.DurationInt

import munit.FunSuite

final class PrometheusProtocolSuite extends FunSuite {

  private val queryId =
    QueryId.from("consumer.total-lag.v1").fold(problem => fail(problem.toString), identity)
  private val query =
    CompiledPromQuery
      .compile(queryId, "sum(kafka_consumergroup_group_lag)")
      .fold(problem => fail(problem.toString), identity)

  test("query ids are stable bounded names rather than arbitrary telemetry values") {
    assertEquals(QueryId.from("consumer.total-lag.v1").map(_.value), Right("consumer.total-lag.v1"))
    assertEquals(QueryId.from("Consumer Total Lag"), Left(PrometheusProtocolProblem.InvalidQueryId))
    assertEquals(QueryId.from(""), Left(PrometheusProtocolProblem.InvalidQueryId))
    assertEquals(QueryId.from("a" * 129), Left(PrometheusProtocolProblem.InvalidQueryId))
  }

  test("compiled queries retain a stable id and digest without exposing their expression") {
    val same = CompiledPromQuery.compile(queryId, "sum(kafka_consumergroup_group_lag)")
    val other = CompiledPromQuery.compile(queryId, "max(kafka_consumergroup_group_lag)")

    assertEquals(query.id, queryId)
    assertEquals(same.map(_.expressionDigest), Right(query.expressionDigest))
    assertNotEquals(other.map(_.expressionDigest), Right(query.expressionDigest))
    assert(!query.toString.contains("kafka_consumergroup"), query.toString)
    assert(query.toString.contains("consumer.total-lag.v1"), query.toString)
  }

  test("an empty or excessive compiled expression is rejected without echoing it") {
    assertEquals(
      CompiledPromQuery.compile(queryId, "   "),
      Left(PrometheusProtocolProblem.EmptyExpression)
    )
    assertEquals(
      CompiledPromQuery.compile(queryId, "x" * (CompiledPromQuery.MaxExpressionBytes + 1)),
      Left(PrometheusProtocolProblem.ExpressionTooLarge(CompiledPromQuery.MaxExpressionBytes))
    )
  }

  test("Prometheus timestamps round-trip exact positive and negative fractional seconds") {
    val positive = PrometheusTimestamp.fromUnixSeconds(BigDecimal("1725624000.123456789"))
    val negative = PrometheusTimestamp.fromUnixSeconds(BigDecimal("-0.1"))

    assertEquals(positive.map(_.instant), Right(Instant.parse("2024-09-06T12:00:00.123456789Z")))
    assertEquals(positive.map(_.unixSeconds), Right(BigDecimal("1725624000.123456789")))
    assertEquals(negative.map(_.instant), Right(Instant.parse("1969-12-31T23:59:59.900Z")))
    assertEquals(negative.map(_.unixSeconds), Right(BigDecimal("-0.1")))
  }

  test("timestamps outside Instant or finer than nanoseconds are refused") {
    assertEquals(
      PrometheusTimestamp.fromUnixSeconds(BigDecimal("0.0000000001")),
      Left(PrometheusProtocolProblem.TimestampPrecision)
    )
    assertEquals(
      PrometheusTimestamp.fromUnixSeconds(BigDecimal("999999999999999999999999")),
      Left(PrometheusProtocolProblem.TimestampOutOfRange)
    )
    assertEquals(
      PrometheusTimestamp.fromUnixSeconds(BigDecimal("1e100000")),
      Left(PrometheusProtocolProblem.TimestampOutOfRange)
    )
    assertEquals(
      PrometheusTimestamp.fromUnixSeconds(BigDecimal("-1e100000")),
      Left(PrometheusProtocolProblem.TimestampOutOfRange)
    )
    assertEquals(
      PrometheusTimestamp.fromUnixSeconds(BigDecimal("1e-100000")),
      Left(PrometheusProtocolProblem.TimestampPrecision)
    )
  }

  test("sample values preserve finite zero and each Prometheus non-finite spelling") {
    assertEquals(SampleValue.parse("0"), Right(finite(0.0)))
    assertEquals(SampleValue.parse("-1.25e3"), Right(finite(-1250.0)))
    assertEquals(SampleValue.parse("NaN"), Right(SampleValue.NonFinite(NonFiniteKind.NaN)))
    assertEquals(SampleValue.parse("Inf"), Right(SampleValue.NonFinite(NonFiniteKind.PositiveInfinity)))
    assertEquals(SampleValue.parse("+Inf"), Right(SampleValue.NonFinite(NonFiniteKind.PositiveInfinity)))
    assertEquals(SampleValue.parse("-Inf"), Right(SampleValue.NonFinite(NonFiniteKind.NegativeInfinity)))
  }

  test("malformed and alternate non-finite sample spellings are refused") {
    List(
      "",
      " 1",
      "1 ",
      "Infinity",
      "-Infinity",
      "nan",
      "not-a-number",
      "1e-400",
      "1d",
      "0x1.0p0"
    ).foreach { raw =>
      assertEquals(SampleValue.parse(raw), Left(PrometheusProtocolProblem.InvalidSampleValue), raw)
    }
  }

  test("unchecked non-finite doubles cannot enter the finite sample type") {
    List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { value =>
      assertEquals(SampleValue.finite(value), Left(PrometheusProtocolProblem.InvalidSampleValue))
    }
    assertEquals(SampleValue.finite(-0.0), Right(finite(-0.0)))
  }

  test("an instant query captures one exact server-owned query and evaluation time") {
    val at = Instant.parse("2026-09-20T12:00:00.123456789Z")
    val input = InstantQuery(query, PrometheusTimestamp.fromInstant(at))

    assertEquals(input.query, query)
    assertEquals(input.at.instant, at)
  }

  test("an aligned range computes the inclusive expected point count before I/O") {
    val from = Instant.parse("2026-09-20T12:00:00Z")
    val to = Instant.parse("2026-09-20T12:05:00Z")
    val input = RangeQuery.create(query, from, to, 1.minute, maxPoints = 6)

    assertEquals(input.map(_.expectedPoints), Right(6))
    assertEquals(input.map(_.stepSeconds), Right("60"))
    assertEquals(input.map(_.from.instant), Right(from))
    assertEquals(input.map(_.to.instant), Right(to))
  }

  test("a one-instant range is one point") {
    val at = Instant.parse("2026-09-20T12:00:00Z")

    assertEquals(RangeQuery.create(query, at, at, 30.seconds, maxPoints = 1).map(_.expectedPoints), Right(1))
  }

  test("range validation rejects a non-positive point limit and step") {
    val from = Instant.parse("2026-09-20T12:00:00Z")
    val to = Instant.parse("2026-09-20T12:01:00Z")

    assertEquals(
      RangeQuery.create(query, from, to, 1.minute, maxPoints = 0),
      Left(PrometheusProtocolProblem.InvalidPointLimit)
    )
    assertEquals(
      RangeQuery.create(query, from, to, 0.seconds, maxPoints = 10),
      Left(PrometheusProtocolProblem.NonPositiveStep)
    )
    assertEquals(
      RangeQuery.create(query, from, to, (-1).second, maxPoints = 10),
      Left(PrometheusProtocolProblem.NonPositiveStep)
    )
  }

  test("range validation rejects reversed and epoch-unaligned bounds") {
    val aligned = Instant.parse("2026-09-20T12:00:00Z")

    assertEquals(
      RangeQuery.create(query, aligned.plusSeconds(60), aligned, 1.minute, maxPoints = 10),
      Left(PrometheusProtocolProblem.ReversedRange)
    )
    assertEquals(
      RangeQuery.create(query, aligned.plusSeconds(1), aligned.plusSeconds(61), 1.minute, maxPoints = 10),
      Left(PrometheusProtocolProblem.UnalignedRange(RangeBoundary.From))
    )
    assertEquals(
      RangeQuery.create(query, aligned, aligned.plusSeconds(61), 1.minute, maxPoints = 10),
      Left(PrometheusProtocolProblem.UnalignedRange(RangeBoundary.To))
    )
  }

  test("range validation rejects an excessive inclusive point count without arithmetic overflow") {
    val from = Instant.parse("2026-09-20T12:00:00Z")
    val to = from.plusSeconds(60)

    assertEquals(
      RangeQuery.create(query, from, to, 1.second, maxPoints = 60),
      Left(PrometheusProtocolProblem.TooManyPoints(expected = BigInt(61), maximum = 60))
    )
    assertEquals(
      RangeQuery.create(
        query,
        Instant.MIN,
        Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
        1.second,
        maxPoints = 2000
      ),
      Left(PrometheusProtocolProblem.TooManyPoints(expected = BigInt("63113904031622400"), maximum = 2000))
    )
  }

  test("typed results distinguish empty data, finite zero, diagnostics and stale fallback") {
    val at = PrometheusTimestamp.fromInstant(Instant.parse("2026-09-20T12:00:00Z"))
    val sample = QuerySample(at, finite(0.0))
    val result = InstantQueryResult(Vector(InstantSeries(MetricLabels(Map("group" -> "orders")), sample)))
    val retrievedAt = Instant.parse("2026-09-20T12:00:01Z")
    val servedAt = retrievedAt.plusSeconds(30)
    val answer = QueryAnswer(
      result,
      QueryFreshness.Stale(retrievedAt, servedAt),
      QueryDiagnostics(warningCount = 1, infoCount = 2)
    )

    assertEquals(InstantQueryResult(Vector.empty).series, Vector.empty)
    assertEquals(answer.result.series.head.sample.value, finite(0.0))
    assertEquals(answer.freshness, QueryFreshness.Stale(retrievedAt, servedAt))
    assertEquals(answer.diagnostics, QueryDiagnostics(warningCount = 1, infoCount = 2))
  }

  private def finite(value: Double): SampleValue.Finite =
    SampleValue.finite(value).fold(problem => fail(problem.toString), identity)
}
