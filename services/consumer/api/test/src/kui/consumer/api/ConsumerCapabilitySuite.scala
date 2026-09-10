package kui.consumer.api

import java.time.Instant

import munit.FunSuite

import cats.effect.IO

import kui.cache.SnapshotStatus
import kui.consumer.application.{ClusterProfileSource, GroupSnapshots, SnapshotFreshness}
import kui.consumer.domain.ClusterProfileView
import kui.contracts.Section
import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, InfrastructureError, KuiError}

/** What the consumer service is allowed to say about itself, and how a stale list is marked.
  *
  * Three rules, each argued for in a paragraph and each ungated. Measured one mutation at a time against
  * `./mill services.consumer.__.test`, which stayed at 185/185 green for every one:
  *
  *   - `ConsumerCapabilities.starting` reporting `configured = false`, so a cluster the snapshot registry
  *     has not caught up with reads as one the operator never configured — the row disappears instead of
  *     saying it is starting;
  *   - `capabilityOf`'s offline arm reporting `available`, so a cluster whose scrape is failing is drawn
  *     healthy;
  *   - `ConsumerReasons` classifying a refusal as `UPSTREAM_UNAVAILABLE`, which per ADR-039 §6 takes the
  *     whole service out of its healthy state because a caller was not permitted to read one thing.
  *
  * And `ConsumerSections`' stale arm, which mapped to `Section.Ok` with 185/185 still green.
  */
final class ConsumerCapabilitySuite extends FunSuite {

  private val At: Instant = Instant.parse("2026-09-06T10:00:00Z")

  private val Configured: ClusterId = ClusterId.unsafe("prod")

  private val Broken: KuiError = InfrastructureError.Unreachable("kafka", "the coordinator is not answering")

  /** The `degraded` discriminator, read off the enum for the reason the production code reads it off. */
  private val Degraded: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  test("a cluster whose first pass has not finished is configured, degraded and says why") {
    val starting = ConsumerCapabilities.capabilityOf(hasValue = false, SnapshotStatus.Initializing)

    // `configured = false` is what `not_configured` is made of at the gateway, and ADR-032 hides a
    // not-configured row entirely — so a cluster that is merely slow would vanish from the sidebar.
    assert(starting.configured, "a cluster that came from the profile source is configured")
    assertEquals(starting.status, Degraded)
    assertEquals(starting.reason, Some(ConsumerCapabilities.StartingMessage))
  }

  test("a configured cluster the registry has no cell for is reported as starting, not left out") {
    /*
     * `ConsumerCapabilities.make` is where the two sources meet, and its `case None => starting` arm was
     * reachable by no case at all: every assertion in this file called `capabilityOf` directly. Replacing
     * that arm with a healthy capability left `./mill services.consumer.__.test` at 201/201 green. The arm
     * is what happens in the seconds after a restart and every time a cluster is added at run time -- the
     * profile source already has it, the snapshot registry has not built its cell yet -- and the two wrong
     * answers are opposite: "available" draws a healthy row over a service that cannot answer a single
     * query, and dropping the entry makes the row vanish from the sidebar as `not_configured`.
     */
    val capabilities = ConsumerCapabilities.make[IO](
      profiles = new ClusterProfileSource[IO] {
        def profileOf(cluster: ClusterId) =
          IO.pure(Right(ClusterProfileView(cluster, "Production", readOnly = false)))
        def all = IO.pure(List(ClusterProfileView(Configured, "Production", readOnly = false)))
        def changes = fs2.Stream.empty
      },
      // The registry that has not caught up: it holds no cell for the cluster the profile source names.
      snapshots = new GroupSnapshots[IO] {
        def of(cluster: ClusterId) = IO.pure(None)
        def all = IO.pure(Nil)
        def previousOf(cluster: ClusterId) = IO.pure(None)
        def requestRefresh(cluster: ClusterId) = IO.pure(false)
        def invalidate(cluster: ClusterId, reason: String) = IO.unit
      }
    )

    capabilities.report
      .map { reported =>
        val entry = reported.get(Configured).getOrElse(fail("the configured cluster must be in the map"))

        assertEquals(reported.keySet, Set(Configured))
        assert(entry.configured, "a cluster the profile source named is configured")
        assertEquals(entry.status, Degraded)
        assertEquals(entry.reason, Some(ConsumerCapabilities.StartingMessage))
      }
      .unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  }

  test("a cluster whose scrape is failing is degraded and never available") {
    val failing = ConsumerCapabilities.capabilityOf(hasValue = true, SnapshotStatus.Offline(Broken, At))

    assertEquals(failing.status, Degraded)
    assertEquals(failing.reason, Some(Broken.message))
    assert(failing.configured)
  }

  test("a cluster that answered is available and carries no reason") {
    val healthy = ConsumerCapabilities.capabilityOf(hasValue = true, SnapshotStatus.Online)

    assertEquals(healthy.status, CapabilityState.Available.status)
    assertEquals(healthy.reason, None)
  }

  test("a refusal is Forbidden and must not read as a cluster that is not answering") {
    // ADR-039 §6: an `ApplicationError` never dims a capability. Classifying a refusal as an upstream
    // failure is how one user's missing permission greys the Consumers entry for everybody.
    assertEquals(ConsumerReasons.of(ApplicationError.Forbidden("no")), ReasonCode.Forbidden)
    assertEquals(ConsumerReasons.of(ApplicationError.Unsupported("no store")), ReasonCode.NotConfigured)
    assertEquals(ConsumerReasons.of(Broken), ReasonCode.UpstreamUnavailable)
  }

  test("a stale group list is marked stale on the wire, with its rows and the reason it stopped") {
    ConsumerSections.of(SnapshotFreshness.Stale(At, Broken), List("orders")) match {
      case Section.Stale(data, fetchedAt, reason) =>
        assertEquals(data, List("orders"))
        assertEquals(fetchedAt, At)
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
      case other => fail(s"a stale list must not be published as current: $other")
    }
  }

  test("a list that was never loaded drops its rows rather than publishing a fabricated empty page") {
    ConsumerSections.of(SnapshotFreshness.Unavailable(Broken), List("orders")) match {
      case Section.Unavailable(reason, message, since) =>
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
        assertEquals(message, Broken.message)
        assertEquals(since, None)
      case other => fail(s"an unloaded list must not send rows: $other")
    }
  }

  test("a fresh list is published as current") {
    assertEquals(ConsumerSections.of(SnapshotFreshness.Fresh(At), List("orders")).status, "ok")
  }
}
