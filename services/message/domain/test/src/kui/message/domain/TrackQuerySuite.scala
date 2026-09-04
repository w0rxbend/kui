package kui.message.domain

import java.time.{Duration, Instant}

import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, TopicName}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** A track query is the one request in M3 that can ask a broker for a month of several topics at once, so
  * every bound on it is checked here rather than trusted to the use case.
  */
final class TrackQuerySuite extends ScalaCheckSuite {

  private val cluster = ClusterId.unsafe("local")
  private val orders = TopicName.unsafe("orders")
  private val payments = TopicName.unsafe("payments")
  private val t0 = Instant.parse("2026-09-01T00:00:00Z")

  private def build(
      topics: List[TopicName] = List(orders, payments),
      from: Instant = t0,
      until: Instant = t0.plusSeconds(3600),
      matcher: TrackMatch = TrackMatch(MatchSource.Header("orderId"), MatchOperator.Equals, "4471"),
      limit: Option[Int] = None,
      maxWindow: Duration = TrackQuery.DefaultMaxWindow
  ) =
    TrackQuery.of(
      cluster = cluster,
      topics = topics,
      from = from,
      until = until,
      matcher = matcher,
      limit = limit,
      isolation = None,
      correlationKey = None,
      maxWindow = maxWindow
    )

  test("windowIsMandatoryAndOrdered") {
    assert(build(until = t0.minusSeconds(1)).isLeft, "an end before the start")
    assert(build(until = t0).isLeft, "a zero-width window scans nothing and is a caller mistake")
    assert(build(until = t0.plusSeconds(1)).isRight)
  }

  test("windowWidthIsBounded") {
    assert(build(until = t0.plus(Duration.ofDays(8))).isLeft)
    assert(build(until = t0.plus(Duration.ofDays(7))).isRight)
    // and the ceiling is configuration, not a constant
    assert(build(until = t0.plusSeconds(120), maxWindow = Duration.ofSeconds(60)).isLeft)
  }

  test("matchSourceIsExplicit") {
    // There is no constructor that defaults the source (DEVPLAN §10 D12). The reference product sends an
    // empty string for "the value", which turns a header search with a forgotten name into a value search
    // that returns plausible, wrong results instead of failing.
    assert(build(matcher = TrackMatch(MatchSource.Header(""), MatchOperator.Equals, "x")).isLeft)
    assert(build(matcher = TrackMatch(MatchSource.Value, MatchOperator.Contains, "x")).isRight)
    assert(build(matcher = TrackMatch(MatchSource.Key, MatchOperator.Contains, "x")).isRight)
  }

  test("regexIsValidatedAtConstruction") {
    // An invalid pattern is a mistake in the request. Left to the scan it would fail on every one of a
    // million records and be reported as a search that found nothing.
    assert(build(matcher = TrackMatch(MatchSource.Value, MatchOperator.Regex, "order-([0-9]+")).isLeft)
    assert(build(matcher = TrackMatch(MatchSource.Value, MatchOperator.Regex, "order-([0-9]+)")).isRight)
  }

  test("atLeastOneTopicIsRequired") {
    assert(build(topics = Nil).isLeft)
    assertEquals(build(topics = List(orders, orders)).map(_.topics.length), Right(1), "duplicates collapse")
  }

  property("theLimitIsAlwaysInsideItsCap") {
    forAll(Gen.option(Gen.chooseNum(Int.MinValue, Int.MaxValue))) { asked =>
      build(limit = asked) match {
        case Right(query) =>
          assert(query.limit >= 1 && query.limit <= TrackQuery.DefaultLimit, s"limit was ${query.limit}")
        case Left(error) => fail(s"a limit should never be refused, got $error")
      }
    }
  }

  property("everyRejectionIsAValidationErrorNamingAField") {
    val rejections = Gen.oneOf(
      build(topics = Nil),
      build(until = t0.minusSeconds(1)),
      build(until = t0.plus(Duration.ofDays(30))),
      build(matcher = TrackMatch(MatchSource.Value, MatchOperator.Regex, "(("))
    )

    forAll(rejections) { attempt =>
      attempt match {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.Validation)
          assert(error.details.forall(_.field.exists(_.nonEmpty)), s"a detail names no field: ${error.details}")
        case Right(query) => fail(s"expected a rejection, got $query")
      }
    }
  }

  property("aWellFormedQueryIsAcceptedWhateverTheMatcher") {
    forAll(MessageGenerators.plainMatch) { matcher =>
      assert(build(matcher = matcher).isRight, s"refused $matcher")
    }
  }

  test("theOperatorWireSetIsExactlyTheDocumentedSet") {
    assertEquals(
      MatchOperator.All.map(_.wire).sorted,
      List("CONTAINS", "EQUALS", "NOT_CONTAINS", "NOT_EQUALS", "REGEX")
    )
  }
}
