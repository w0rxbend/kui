package kui.metrics.infrastructure

import scala.concurrent.duration.DurationInt

import cats.Id
import munit.FunSuite

import kui.config.*
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.metrics.application.SourceProfile

/** What this process was configured to measure, and what it will actually do about it.
  *
  * Two properties, and the second is the one worth having a test for: this build reports `None` for every
  * cluster whatever the configuration says, and it does so *without* losing the fact that an address was
  * configured. Losing that fact is how an operator ends up being told their own YAML is empty.
  */
final class ConfiguredClusterSourcesSuite extends FunSuite {

  private def cluster(id: String): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe(id),
      name = id.capitalize,
      bootstrapServers = BootstrapServers.unsafe("localhost:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default
    )

  private def metricsFor(ids: String*): MetricsConfig =
    MetricsConfig.Default.copy(sources =
      ids
        .map(id =>
          ClusterId.unsafe(id) -> MetricsSourceSettings(
            url = SafeUrl.unsafe("http://exporter:9404/metrics"),
            kind = MetricsSourceKind.Prometheus,
            callTimeout = 10.seconds
          )
        )
        .toMap
    )

  test("every configured cluster is listed, source or not, sorted by id") {
    val profiles =
      ConfiguredClusterSources.profilesOf(List(cluster("prod"), cluster("dev")), metricsFor("prod"))

    assertEquals(profiles.map(_.cluster.value), List("dev", "prod"))
    assertEquals(profiles.map(_.hasSource), List(false, true))
  }

  test("a cluster with no metrics entry is listed rather than dropped") {
    val profiles = ConfiguredClusterSources.profilesOf(List(cluster("dev")), MetricsConfig.Default)

    // Dropping it would make the capability report silent about that cluster, and a cluster missing from
    // the report reads as "this service has never heard of it" — a service that is down, not a feature
    // that is off.
    assertEquals(profiles.map(_.cluster.value), List("dev"))
    assertEquals(profiles.head.hasSource, false)
  }

  test("no cluster gets a collector in this build, including one that configured an address") {
    val profiles = List(SourceProfile(ClusterId.unsafe("prod"), "Prod", hasSource = true))
    val sources = new ConfiguredClusterSources[Id](profiles)

    // The seam the metrics milestone fills. Until it does, answering `Some(port)` here would be a service
    // promising a measurement nothing in this repository can take.
    assertEquals(sources.source(ClusterId.unsafe("prod")), None)
    assertEquals(sources.profile(ClusterId.unsafe("prod")).map(_.hasSource), Some(true))
    assertEquals(sources.profile(ClusterId.unsafe("nope")), None)
  }
}
