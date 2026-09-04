package kui.allinone

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import kui.cluster.api.CapabilityMapping
import kui.cluster.application.{
  CapabilityReport,
  CapabilityReportUseCase,
  ClusterCapabilityReport,
  SnapshotFreshness
}
import kui.cluster.domain.StoreHealth
import kui.contracts.capability.{CapabilityState, ServiceCapabilities}
import kui.gateway.application.capability.{CapabilityFold, CapabilityInputs, ReadinessSignal}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.ClusterId

/** The capability seam: what the cluster service says about a cluster, and what the gateway makes of it.
  *
  * ==Why this suite is in this module==
  *
  * It is the only module that compiles against both halves. `services/cluster/api` owns the mapping onto the
  * wire and `services/gateway/application` owns the fold that reads it, and neither may depend on the other —
  * that is ADR-041's layering, and `./mill checkArchitecture` enforces it. So a suite on either side can only
  * ever assert what its own half believes the other one does, which is exactly how the defect this suite
  * exists for survived: the cluster service reported a dead cluster as `available` and the gateway faithfully
  * folded `available` into `Available`, and every test on both sides passed.
  *
  * Nothing here is stubbed. The report is built by the cluster service's own use case, spelled onto the wire
  * by its own mapping, serialised and parsed as real JSON, and folded by the gateway's own function.
  */
final class CapabilitySeamSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-04T09:00:00Z")

  private val dead: ClusterId = ClusterId.unsafe("dead")
  private val alive: ClusterId = ClusterId.unsafe("prod-eu-1")

  private val noRoute: KuiError = InfrastructureError.Unreachable("the cluster", "no route")

  /** One cluster's entry, built the way `CapabilityReportUseCase` builds it. */
  private def entry(
      name: String,
      freshness: SnapshotFreshness,
      features: Set[String] = Set("CLUSTER_TOPOLOGY")
  ): ClusterCapabilityReport = {
    val (state, reachable) = CapabilityReportUseCase.stateOf(freshness, StoreHealth.Online)

    ClusterCapabilityReport(
      configured = true,
      name = Some(name),
      state = state,
      reachable = reachable,
      features = features,
      scrapedAt = freshness.scrapedAtOption
    )
  }

  /** The document the gateway actually receives: encoded to JSON and decoded back out of it. */
  private def overTheWire(report: CapabilityReport): ServiceCapabilities =
    decode[ServiceCapabilities](CapabilityMapping.toWire(report).asJson.noSpaces) match {
      case Right(parsed) => parsed
      case Left(failure) => fail(s"the capability document did not round-trip: $failure")
    }

  private def foldOf(reported: kui.contracts.capability.ClusterCapability): CapabilityState =
    CapabilityFold.fold(
      previous = None,
      inputs = CapabilityInputs.unknown.copy(
        readiness = Some(ReadinessSignal.Ready),
        serviceReport = Some(reported)
      ),
      now = at
    )

  private val report: CapabilityReport = CapabilityReport(
    Map(
      alive -> entry("Production EU (primary)", SnapshotFreshness.Fresh(at)),
      dead -> entry("Decommissioned", SnapshotFreshness.Unavailable(noRoute, at))
    ),
    StoreHealth.Online
  )

  test("aClusterTheServiceCannotReachDoesNotFoldToAvailable") {
    // The defect, at the seam it hid in. Before the fix the cluster service spelled this cluster
    // `available` on the wire and the gateway folded it to `Available`, so the sidebar and the cluster
    // switcher showed a dead cluster as healthy while its own dashboard row said `Unavailable`.
    val document = overTheWire(report)

    assertEquals(document.clusters(dead).status, "degraded", document.clusters(dead).toString)
    assert(
      foldOf(document.clusters(dead)) != CapabilityState.Available,
      s"the registry state a dead cluster ends up in: ${foldOf(document.clusters(dead))}"
    )
  }

  test("aHealthyClusterStillFoldsToAvailable") {
    // The other half, and the reason the fix is not "report everything as degraded": a working cluster
    // has to stay working, or the fix would simply move the lie.
    val document = overTheWire(report)

    assertEquals(document.clusters(alive).status, "available")
    assertEquals(foldOf(document.clusters(alive)), CapabilityState.Available)
  }

  test("theDeadClustersReasonSurvivesTheCrossing") {
    // A dimmed entry that cannot say why is what ADR-032 exists to prevent. The service's own message
    // has to reach the state the browser renders, through the wire and through the fold.
    val document = overTheWire(report)

    assertEquals(document.clusters(dead).reason, Some(noRoute.message))

    foldOf(document.clusters(dead)) match {
      case CapabilityState.Degraded(reason) => assertEquals(reason.message, noRoute.message)
      case other => fail(s"expected a degraded state carrying the reason, got $other")
    }
  }

  test("theOperatorsOwnNameForEachClusterSurvivesTheCrossing") {
    // What the cluster switcher renders. The shell reads names from the capability stream and from
    // nowhere else, so a name that does not cross this seam is a switcher showing slugs.
    val document = overTheWire(report)

    assertEquals(document.clusters(alive).name, Some("Production EU (primary)"))
    assertEquals(document.clusters(dead).name, Some("Decommissioned"))
  }

  test("oneDeadClusterDoesNotMakeTheServiceItselfLookBroken") {
    // DEVPLAN D4, restated at the seam. The service-wide key is folded from readiness and the circuit,
    // never from what a cluster reported, so the `Clusters` sidebar entry stays usable for everyone
    // whose clusters are fine. This is the invariant that used to be enforced in the cluster service's
    // per-cluster report, where it was true and in the wrong place.
    val serviceWide = kui.contracts.capability.ClusterCapability(
      configured = true,
      features = overTheWire(report).clusters.values.toList.flatMap(_.features).distinct.sorted,
      status = CapabilityFold.Status.Available
    )

    assertEquals(foldOf(serviceWide), CapabilityState.Available)
  }
}
