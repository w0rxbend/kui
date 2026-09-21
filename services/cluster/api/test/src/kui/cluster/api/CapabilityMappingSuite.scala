package kui.cluster.api

import java.time.Instant

import munit.FunSuite

import kui.cluster.application.{
  CapabilityReport,
  CapabilityState as ApplicationCapabilityState,
  ClusterCapabilityReport,
  ClusterService
}
import kui.contracts.capability.CapabilityState
import kui.kernel.ClusterId

/** The worked example every other service's mapping is copied from, asserted for the first time.
  *
  * `CapabilityMapping` is called by `ClusterRoutes` and by no suite: deleting the `if !report.configured`
  * short-circuit — so a cluster this deployment was never told about is published as `available` or
  * `degraded` — left `./mill libs.__.test + services.*` at 2633/2633. ADR-032 is explicit that
  * `not_configured` is **not** a failure and must not be rendered as one, and a `degraded` in its place is a
  * dimmed menu item with a reason attached where the honest answer is "this deployment has no such cluster".
  */
final class CapabilityMappingSuite extends FunSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")

  private def report(
      configured: Boolean,
      state: ApplicationCapabilityState
  ): ClusterCapabilityReport =
    ClusterCapabilityReport(
      configured = configured,
      name = Some("Local"),
      state = state,
      reachable = true,
      features = Set("log-dirs"),
      scrapedAt = Some(Instant.parse("2026-02-01T10:00:00Z"))
    )

  test("a cluster this deployment was never told about is not_configured and never degraded") {
    // The state is `Degraded` here on purpose: `configured` decides the answer, and a mapping that read
    // the state first would publish a failure for a cluster that simply does not exist here.
    val status = CapabilityMapping.statusOf(
      report(configured = false, ApplicationCapabilityState.Degraded("the first scrape has not finished"))
    )

    assertEquals(status, CapabilityState.NotConfigured.status)
    assertEquals(status, "not_configured")
  }

  test("an unconfigured cluster that would otherwise be available is still not_configured") {
    assertEquals(
      CapabilityMapping.statusOf(report(configured = false, ApplicationCapabilityState.Available)),
      CapabilityState.NotConfigured.status
    )
  }

  test("a configured cluster reports what the use case said, which is where degraded comes from") {
    assertEquals(
      CapabilityMapping.statusOf(report(configured = true, ApplicationCapabilityState.Available)),
      CapabilityState.Available.status
    )
    assertEquals(
      CapabilityMapping.statusOf(
        report(configured = true, ApplicationCapabilityState.Degraded("the metadata store is unreachable"))
      ),
      "degraded"
    )
  }

  test("the reason travels with the degraded entry and with no other") {
    assertEquals(
      CapabilityMapping.reasonOf(
        report(configured = true, ApplicationCapabilityState.Degraded("the metadata store is unreachable"))
      ),
      Some("the metadata store is unreachable")
    )
    assertEquals(
      CapabilityMapping.reasonOf(report(configured = true, ApplicationCapabilityState.Available)),
      None
    )
  }

  test("the document names this service and carries every cluster it was given") {
    val wire = CapabilityMapping.toWire(
      CapabilityReport(
        clusters = Map(
          cluster -> report(configured = true, ApplicationCapabilityState.Available),
          ClusterId.unsafe("staging") ->
            report(configured = false, ApplicationCapabilityState.Available)
        )
      )
    )

    assertEquals(wire.service, ClusterService.Id)
    assertEquals(wire.clusters.get(cluster).map(_.status), Some("available"))
    assertEquals(wire.clusters.get(ClusterId.unsafe("staging")).map(_.status), Some("not_configured"))
    // `features` is a set on one side and a list on the other, and the conversion is the mapping's.
    assertEquals(wire.clusters.get(cluster).map(_.features), Some(List("log-dirs")))
  }
}
