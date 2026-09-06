package kui.metrics.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.Id
import munit.FunSuite

import kui.config.*
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.error.KuiError
import kui.metrics.domain.{MetricsSourcePort, ThroughputRange, ThroughputSeries}

/** What this process was configured to measure, and what it will actually do about it.
  *
  * The property worth having a test for is the one that separates the three answers: a cluster with no entry,
  * a cluster whose entry this build can read, and a cluster whose entry names a protocol it cannot. All three
  * have to reach the endpoint distinguishable, because two of them draw a sentence and only one of them is a
  * sentence an operator should act on. Collapsing them is how somebody ends up being told their own YAML is
  * empty.
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

  private def metricsFor(
      kind: MetricsSourceKind = MetricsSourceKind.Prometheus
  )(ids: String*): MetricsConfig =
    MetricsConfig.Default.copy(sources =
      ids
        .map(id =>
          ClusterId.unsafe(id) -> MetricsSourceSettings(
            url = SafeUrl.unsafe("http://exporter:9404/metrics"),
            kind = kind,
            callTimeout = 10.seconds
          )
        )
        .toMap
    )

  test("every configured cluster is listed, source or not, sorted by id") {
    val profiles =
      ConfiguredClusterSources.profilesOf(List(cluster("prod"), cluster("dev")), metricsFor()("prod"))

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

  test("a cluster whose kind is jmx answers the stated refusal and not an exception") {
    val prod = ClusterId.unsafe("prod")
    val jmx = metricsFor(MetricsSourceKind.Jmx)("prod")
    val profiles = ConfiguredClusterSources.profilesOf(List(cluster("prod")), jmx)

    // Configured, and unmeasurable, and neither of those facts is allowed to swallow the other. The
    // sentence has to name the build rather than the operator's YAML: the address is fine, and what is
    // missing is an implementation this repository deliberately does not have (ADR-050).
    assertEquals(profiles.map(_.hasSource), List(true))
    assertEquals(profiles.map(_.isMeasurable), List(false))
    assert(
      profiles.head.unreadableReason.exists(_.contains("Prometheus text exposition only")),
      profiles.toString
    )
    assertEquals(ConfiguredClusterSources.scrapable(List(cluster("prod")), jmx), Nil)

    // And it is a refusal rather than a throw: the lookup answers, and answers `None`.
    assertEquals(new ConfiguredClusterSources[Id](profiles).source(prod), None)
  }

  test("a cluster whose kind is prometheus is scraped, and is the only kind that is") {
    val clusters = List(cluster("prod"), cluster("dev"))
    val configured = metricsFor()("prod")

    assertEquals(
      ConfiguredClusterSources.scrapable(clusters, configured).map((cluster, _) => cluster.value),
      List("prod")
    )
    assertEquals(
      ConfiguredClusterSources.profilesOf(clusters, configured).map(_.isMeasurable),
      List(false, true)
    )
  }

  test("a collector handed in is the one the use case is given, and only for its own cluster") {
    val prod = ClusterId.unsafe("prod")
    val profiles =
      ConfiguredClusterSources.profilesOf(List(cluster("prod"), cluster("dev")), metricsFor()("prod"))
    val sources = new ConfiguredClusterSources[Id](profiles, Map(prod -> new FixedSource))

    // The seam the metrics milestone filled, and the half of it that is not about refusing: a configured
    // cluster now has something to ask, and the cluster beside it still has nothing.
    assert(sources.source(prod).isDefined)
    assertEquals(sources.source(ClusterId.unsafe("dev")), None)
    assertEquals(sources.profile(ClusterId.unsafe("nope")), None)
  }
}

/** A port that answers a full, empty axis. Enough to tell "there is a collector here" from "there is not",
  * which is the only question this suite asks of one.
  */
final class FixedSource extends MetricsSourcePort[Id] {

  def throughput(range: ThroughputRange, endingAt: Instant): Id[Either[KuiError, ThroughputSeries]] =
    Right(ThroughputSeries.absent(range, endingAt))
}
