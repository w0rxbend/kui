package kui.topic.api

import java.time.Instant

import munit.FunSuite

import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}
import kui.kernel.ClusterId
import kui.topic.application.TopicCapability

/** What the topic service may say about itself, which is two of the four words the wire has.
  *
  * The file's own header spends two paragraphs on it — "`available` or `degraded`, never `unavailable`. A
  * service that is answering this request at all is reachable by definition… a Kafka cluster the topic
  * service cannot reach must not dim the Topics entry in the sidebar" — and nothing asserted the arm that
  * carries it. Mapping `TopicCapability.Unavailable` to `not_configured` left `./mill services.topic.__.test`
  * at 287/287 green, and ADR-032 then hides the whole Topics row for a cluster that has merely never been
  * scraped.
  */
final class TopicCapabilityMappingSuite extends FunSuite {

  private val At: Instant = Instant.parse("2026-09-06T10:00:00Z")

  /** The `degraded` discriminator, read off the enum for the reason the production code reads it off. */
  private val Degraded: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  test("a cluster that has never been scraped is degraded, and not unavailable or not-configured") {
    val never = TopicCapability.Unavailable("nothing has ever been scraped", At)

    assertEquals(TopicCapabilityMapping.statusOf(never), Degraded)
    assertNotEquals(TopicCapabilityMapping.statusOf(never), CapabilityState.NotConfigured.status)
    assertEquals(TopicCapabilityMapping.reasonOf(never), Some("nothing has ever been scraped"))
  }

  test("a cluster whose scrapes are failing is degraded and carries the reason") {
    val failing = TopicCapability.Degraded("the broker is not answering", At, Some(At))

    assertEquals(TopicCapabilityMapping.statusOf(failing), Degraded)
    assertEquals(TopicCapabilityMapping.reasonOf(failing), Some("the broker is not answering"))
  }

  test("a cluster that answered is available and has nothing wrong to say") {
    val healthy = TopicCapability.Available(At)

    assertEquals(TopicCapabilityMapping.statusOf(healthy), CapabilityState.Available.status)
    assertEquals(TopicCapabilityMapping.reasonOf(healthy), None)
  }

  test("every cluster in the report is configured, whatever its scrape is doing") {
    // A cluster that is not configured is *absent* from the map. Reporting one as unconfigured is how a
    // row an operator did configure disappears from the sidebar.
    val rows = TopicCapabilityMapping.toWire(
      List(
        ClusterId.unsafe("prod") -> TopicCapability.Available(At),
        ClusterId.unsafe("staging") -> TopicCapability.Unavailable("never scraped", At)
      )
    )

    assertEquals(rows.service, TopicApi.Id)
    assertEquals(rows.clusters.size, 2)
    assert(rows.clusters.values.forall(_.configured), "every cluster in this report came from the profiles")
  }
}
