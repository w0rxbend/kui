package kui.metrics.api

import cats.effect.IO
import munit.CatsEffectSuite

import kui.contracts.capability.CapabilityState
import kui.kernel.ClusterId
import kui.metrics.application.{ClusterSources, SourceProfile}
import kui.metrics.domain.MetricsSourcePort

/** What the gateway is told about a service that measures nothing.
  *
  * The row's *status* is the thing the browser branches on, and `not_configured` is what keeps a metrics
  * card showing its written sentence instead of an error. The row's *reason* is what a person reads, and it
  * has to distinguish the two situations behind the one status — otherwise an operator who configured an
  * address is told nothing is configured and goes to re-read their own YAML.
  */
final class MetricsCapabilitiesSuite extends CatsEffectSuite {

  private val local = ClusterId.unsafe("local")
  private val prod = ClusterId.unsafe("prod")

  private final class Sources(profiles: List[SourceProfile]) extends ClusterSources[IO] {
    def all: IO[List[SourceProfile]] = IO.pure(profiles)
    def profile(cluster: ClusterId): IO[Option[SourceProfile]] =
      IO.pure(profiles.find(_.cluster == cluster))
    def source(cluster: ClusterId): IO[Option[MetricsSourcePort[IO]]] = IO.pure(None)
  }

  test("every configured cluster appears, source or not") {
    val profiles = List(SourceProfile(local, "Local", false), SourceProfile(prod, "Prod", true))

    MetricsCapabilities
      .make[IO](new Sources(profiles))
      .report
      .map { report =>
        // A cluster missing from the report reads as "this service has never heard of it", which the
        // browser renders as a service being down rather than as a feature that is off.
        assertEquals(report.keySet, Set(local, prod))
        assert(report.values.forall(_.status == CapabilityState.NotConfigured.status))
      }
  }

  test("the reason distinguishes 'nothing configured' from 'no collector for what you configured'") {
    val profiles = List(SourceProfile(local, "Local", false), SourceProfile(prod, "Prod", true))

    MetricsCapabilities
      .make[IO](new Sources(profiles))
      .report
      .map { report =>
        assert(report(local).reason.exists(_.contains("kui.metrics.sources")), report(local).reason)
        assert(report(prod).reason.exists(_.contains("no collector")), report(prod).reason)
      }
  }

  test("no cluster is reported as available while this build has no collector") {
    val profiles = List(SourceProfile(prod, "Prod", true))

    MetricsCapabilities
      .make[IO](new Sources(profiles))
      .report
      .map { report =>
        // The one answer this service must never give: `available` would put six cards on a dashboard
        // that then draw nothing, which is the state the whole design is written to avoid.
        assert(report.values.forall(_.status != CapabilityState.Available.status))
        assert(report.values.forall(!_.configured))
      }
  }
}
