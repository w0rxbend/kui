package kui.metrics.api

import cats.effect.IO
import munit.CatsEffectSuite

import kui.contracts.capability.CapabilityState
import kui.kernel.ClusterId
import kui.metrics.application.{ClusterSources, SourceProfile}
import kui.metrics.contract.MetricsEndpoints
import kui.metrics.domain.MetricsSourcePort

/** What the gateway is told about each cluster this service can and cannot measure.
  *
  * The row's *status* is the thing the browser branches on: `available` says a chart here is worth asking
  * for, and `not_configured` is what keeps a metrics card showing its written sentence instead of an error.
  * The row's *reason* is what a person reads, and it has to distinguish the two situations behind the one
  * refusal — otherwise an operator who configured an address is told nothing is configured and goes to
  * re-read their own YAML.
  */
final class MetricsCapabilitiesSuite extends CatsEffectSuite {

  private val local = ClusterId.unsafe("local")
  private val prod = ClusterId.unsafe("prod")

  final private class Sources(profiles: List[SourceProfile]) extends ClusterSources[IO] {
    def all: IO[List[SourceProfile]] = IO.pure(profiles)
    def profile(cluster: ClusterId): IO[Option[SourceProfile]] =
      IO.pure(profiles.find(_.cluster == cluster))
    def source(cluster: ClusterId): IO[Option[MetricsSourcePort[IO]]] = IO.pure(None)
  }

  /** A cluster naming a source this build cannot read: configured, and unmeasurable. */
  private def unreadable(cluster: ClusterId, name: String): SourceProfile =
    SourceProfile(cluster, name, hasSource = true, unreadableReason = Some(s"$name names kind: jmx"))

  test("every configured cluster appears, source or not") {
    val profiles = List(SourceProfile(local, "Local", false), SourceProfile(prod, "Prod", true))

    MetricsCapabilities
      .make[IO](new Sources(profiles))
      .report
      .map { report =>
        // A cluster missing from the report reads as "this service has never heard of it", which the
        // browser renders as a service being down rather than as a feature that is off.
        assertEquals(report.keySet, Set(local, prod))
        assertEquals(report(local).status, CapabilityState.NotConfigured.status)
      }
  }

  test("a cluster with a readable source is available and names the feature it can answer") {
    // The row that could not exist before the collector did. It is what tells the browser that this
    // cluster's throughput card is worth asking for, rather than one more card that will always answer a
    // sentence — and it is the assertion M7's old exit criterion could not make, because a service with
    // no adapter can produce every refusal and none of this.
    MetricsCapabilities
      .make[IO](new Sources(List(SourceProfile(prod, "Prod", hasSource = true))))
      .report
      .map { report =>
        assertEquals(report(prod).status, CapabilityState.Available.status)
        assert(report(prod).configured)
        // Every endpoint this service publishes, named exactly as the OpenAPI document names it. A
        // feature list shorter than the contract is a card the browser never asks for on a cluster that
        // can answer it, which looks exactly like a service that is down.
        assertEquals(report(prod).features, MetricsEndpoints.all.flatMap(_.info.name))
        assertEquals(report(prod).features.size, 5)
        assert(report(prod).features.contains(MetricsCapabilities.ThroughputFeature))
        // Nothing to explain: a row carrying a reason it does not need is a row an operator reads
        // looking for a problem that is not there.
        assertEquals(report(prod).reason, None)
      }
  }

  test("the reason distinguishes 'nothing configured' from 'a protocol this build cannot read'") {
    val profiles = List(SourceProfile(local, "Local", false), unreadable(prod, "Prod"))

    MetricsCapabilities
      .make[IO](new Sources(profiles))
      .report
      .map { report =>
        assert(report(local).reason.exists(_.contains("kui.metrics.sources")), report(local).reason)
        assert(report(prod).reason.exists(_.contains("kind: jmx")), report(prod).reason)
      }
  }

  test("a cluster whose source this build cannot read is not reported as available") {
    MetricsCapabilities
      .make[IO](new Sources(List(unreadable(prod, "Prod"))))
      .report
      .map { report =>
        // The one answer this service must never give about such a cluster: `available` would put a card
        // on a dashboard that then draws nothing, which is the state the whole design is written to
        // avoid. `configured` is the same question — "can KUI measure this" — and the answer is no.
        assertEquals(report(prod).status, CapabilityState.NotConfigured.status)
        assert(!report(prod).configured)
        assertEquals(report(prod).features, Nil)
      }
  }
}
