package kui.contracts.health

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.GoldenDocuments

/** That the three health documents look the same on both platforms and stay the shape an operator
  * and an orchestrator were promised.
  */
final class HealthDtosSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val degraded = ReadinessReport(
    ready = false,
    checks = List(
      CheckResult.healthy("config"),
      CheckResult.failed("schema-registry", "connection refused"),
      CheckResult.timedOut("connect")
    ),
    at = at
  )

  test("a degraded readiness report matches the golden document, field for field") {
    assertEquals(
      degraded.asJson.spaces2,
      parse(GoldenDocuments.readinessReportDegraded).fold(failure => fail(failure.message), _.spaces2)
    )
  }

  test("every check is listed, not only the failing ones") {
    // An operator reading a 503 needs to know what was tried as well as what failed: one broken
    // upstream out of four is a different situation from the only check there is.
    val rendered = degraded.asJson.noSpaces
    List("config", "schema-registry", "connect").foreach(name => assert(rendered.contains(name), rendered))
  }

  test("ReadinessReport.of derives ready from the checks, so the two cannot disagree") {
    assertEquals(ReadinessReport.of(List(CheckResult.healthy("a")), at).ready, true)
    assertEquals(ReadinessReport.of(Nil, at).ready, true)
    assertEquals(ReadinessReport.of(degraded.checks, at).ready, false)
  }

  test("a timed-out check is distinguishable from one that answered no") {
    assertEquals(CheckResult.timedOut("connect").detail, Some("timeout"))
    assertEquals(CheckResult.failed("connect", "rebalancing").detail, Some("rebalancing"))
  }

  test("a liveness report carries a flag and a time and nothing else") {
    // Deliberately minimal: liveness answers one question, and anything else in the body invites
    // someone to make a restart decision depend on it.
    assertEquals(LivenessReport.at(at).asJson.asObject.map(_.keys.toList), Some(List("alive", "at")))
  }

  test("each document decodes back to what it was") {
    assertEquals(degraded.asJson.as[ReadinessReport], Right(degraded))
    assertEquals(LivenessReport.at(at).asJson.as[LivenessReport], Right(LivenessReport.at(at)))
    assertEquals(
      CheckResult.failed("a", "b").asJson.as[CheckResult],
      Right(CheckResult.failed("a", "b"))
    )
  }

  test("a report with no checks decodes, so an older service still answers a newer probe") {
    val minimal = parse("""{"ready":true,"at":"2026-09-03T10:11:12.000Z"}""")
      .flatMap(_.as[ReadinessReport])

    assertEquals(minimal, Right(ReadinessReport(ready = true, Nil, at)))
  }
}
