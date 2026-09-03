package kui.contracts.capability

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.GoldenDocuments
import kui.kernel.{ClusterId, ServiceId}

/** The capability contract: the strings a browser switches on, and what happens when it meets a
  * value from a newer gateway.
  */
final class CapabilityDtosSuite extends FunSuite {

  private val at        = Instant.parse("2026-09-03T10:11:12Z")
  private val laterOn   = Instant.parse("2026-09-03T10:11:13Z")
  private val prettyJson = io.circe.Printer.spaces2

  private val key = CapabilityKey(ServiceId.unsafe("cluster"), None)

  test("available and not-configured encode as nothing but their status") {
    assertEquals(CapabilityState.Available.asJson.noSpaces, """{"status":"available"}""")
    assertEquals(CapabilityState.NotConfigured.asJson.noSpaces, """{"status":"not_configured"}""")
  }

  test("degraded carries a reason a client can pace itself with") {
    val degraded = CapabilityState.Degraded(
      DegradedReason(ReasonCode.UpstreamTimeout, "slow", Some(30000L), Some(1200L))
    )
    assertEquals(
      degraded.asJson.noSpaces,
      """{"status":"degraded","reason":{"code":"UPSTREAM_TIMEOUT","message":"slow","suggestedPollIntervalMs":30000,"p95Ms":1200}}"""
    )
    assertEquals(decode[CapabilityState](degraded.asJson.noSpaces), Right(degraded))
  }

  test("unavailable says why, in words, and since when") {
    val state =
      CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "readiness probe failed", at)
    assertEquals(
      state.asJson.noSpaces,
      """{"status":"unavailable","reason":"UPSTREAM_UNAVAILABLE","message":"readiness probe failed","since":"2026-09-03T10:11:12.000Z"}"""
    )
    assertEquals(decode[CapabilityState](state.asJson.noSpaces), Right(state))
  }

  /** The literal table. It exists to fail loudly when someone renames a wire string, because the
    * browser and the metric labels are both built on these exact five words.
    */
  test("the discriminator and reason strings are the ones the contract fixes") {
    assertEquals(CapabilityState.Available.status, "available")
    assertEquals(CapabilityState.NotConfigured.status, "not_configured")
    assertEquals(
      CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status,
      "degraded"
    )
    assertEquals(CapabilityState.Unavailable(ReasonCode.Unknown, "", at).status, "unavailable")

    assertEquals(
      ReasonCode.values.map(_.wire).toList,
      List(
        "UPSTREAM_UNAVAILABLE",
        "UPSTREAM_TIMEOUT",
        "CIRCUIT_OPEN",
        "UPSTREAM_AUTH",
        "NOT_CONFIGURED",
        "FORBIDDEN",
        "STARTING",
        "UNKNOWN"
      )
    )
  }

  test("a reason code from a newer gateway decodes as Unknown rather than failing") {
    assertEquals(decode[ReasonCode](""""QUOTA_EXCEEDED""""), Right(ReasonCode.Unknown))
    assertEquals(decode[ReasonCode](""""UPSTREAM_TIMEOUT""""), Right(ReasonCode.UpstreamTimeout))
  }

  test("a status this build does not know is a decode failure, not a silent Available") {
    assert(decode[CapabilityState]("""{"status":"rebooting"}""").isLeft)
  }

  test("a change encodes to the golden document, byte for byte") {
    val change = CapabilityChange(
      CapabilityEntry(
        key,
        CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "readiness probe failed", at),
        laterOn
      ),
      previous = Some(CapabilityState.Available)
    )

    assertEquals(prettyJson.print(change.asJson), GoldenDocuments.capabilityChangeUnavailable)
    assertEquals(decode[CapabilityChange](GoldenDocuments.capabilityChangeUnavailable), Right(change))
  }

  test("a snapshot encodes to the golden document and round-trips") {
    val snapshot = CapabilitySnapshot(
      List(
        CapabilityEntry(key, CapabilityState.Available, at),
        CapabilityEntry(
          CapabilityKey(ServiceId.unsafe("schema"), Some(ClusterId.unsafe("prod-eu"))),
          CapabilityState.NotConfigured,
          at
        )
      ),
      generatedAt = laterOn
    )

    assertEquals(prettyJson.print(snapshot.asJson), GoldenDocuments.capabilitiesSnapshot)
    assertEquals(decode[CapabilitySnapshot](GoldenDocuments.capabilitiesSnapshot), Right(snapshot))
  }

  test("a service's own capabilities document is the one ARCHITECTURE.md §6 prints") {
    val expected = ServiceCapabilities(
      ServiceId.unsafe("schema"),
      Map(
        ClusterId.unsafe("prod-eu") -> ClusterCapability(true, List("SCHEMA_REGISTRY"), "available"),
        ClusterId.unsafe("staging") -> ClusterCapability(false, Nil, "not_configured")
      )
    )

    assertEquals(decode[ServiceCapabilities](GoldenDocuments.serviceCapabilities), Right(expected))
    assertEquals(
      decode[ServiceCapabilities](
        """{"service":"schema","clusters":{
          |  "prod-eu": {"configured": true,  "features": ["SCHEMA_REGISTRY"], "status": "available"},
          |  "staging": {"configured": false, "features": [], "status": "not_configured"}}}""".stripMargin
      ),
      Right(expected)
    )
  }

  test("a cluster id that is not a legal slug is not accepted as a map key") {
    assert(
      decode[ServiceCapabilities]("""{"service":"schema","clusters":{"Not A Slug":{"configured":true,"features":[],"status":"available"}}}""").isLeft
    )
  }

  test("a capability key without a cluster keeps the field, as null") {
    assertEquals(key.asJson.noSpaces, """{"service":"cluster","cluster":null}""")
    assertEquals(decode[CapabilityKey](key.asJson.noSpaces), Right(key))
  }
}
