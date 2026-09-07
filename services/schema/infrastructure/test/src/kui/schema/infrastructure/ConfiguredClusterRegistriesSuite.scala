package kui.schema.infrastructure

import cats.data.NonEmptyList
import cats.effect.IO

import kui.config.{ClusterConfig, RegistryAuthConfig, SafeUrl, SchemaRegistrySettings}
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.schema.application.RegistryProfile
import kui.testkit.KuiIOSuite

/** What `kui.clusters[]` becomes from this service's point of view.
  *
  * Two rules live in nine lines of `profilesOf` and neither had a case. `hasRegistry` is the fact the whole
  * service is shaped around — it is what makes the browser hide the Schemas tab for a cluster that never had
  * a registry, instead of showing a panel that stays red forever — and it can be hard-coded to `true` with
  * every other case in this service green. The sort is what stops two deployments of the same product from
  * listing their clusters differently for no reason.
  */
final class ConfiguredClusterRegistriesSuite extends KuiIOSuite {

  private def cluster(id: String, name: String, registry: Boolean, readOnly: Boolean = false): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe(id),
      name = name,
      bootstrapServers = BootstrapServers.unsafe("broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = readOnly,
      admin = AdminTuning.default,
      schemaRegistry = Option.when(registry)(
        SchemaRegistrySettings(
          urls = NonEmptyList.one(SafeUrl.unsafe("http://registry:8081")),
          auth = RegistryAuthConfig.Anonymous
        )
      )
    )

  test("a cluster with no schemaRegistry block is listed, and listed as having none") {
    // Both halves. Leaving it out of the list would make the capability report say nothing about it, and
    // a cluster missing from the report reads as "this service has never heard of it" — which the browser
    // renders as a service being down rather than as a feature that is off for this cluster.
    val profiles = ConfiguredClusterRegistries.profilesOf(
      List(cluster("with", "With a registry", registry = true), cluster("without", "Bare", registry = false))
    )

    assertEquals(profiles.map(_.cluster.value), List("with", "without"))
    assertEquals(profiles.map(_.hasRegistry), List(true, false))
    assertEquals(
      profiles.find(_.cluster.value == "without"),
      Some(RegistryProfile(ClusterId.unsafe("without"), "Bare", hasRegistry = false, readOnly = false))
    )
  }

  test("the clusters come back sorted by id, whatever order the configuration file listed them in") {
    // An order that depends on the file makes two deployments of the same product look different, and the
    // capability report, the startup log and every diagnostic read this one list.
    val profiles = ConfiguredClusterRegistries.profilesOf(
      List(
        cluster("zulu", "Zulu", registry = true),
        cluster("alpha", "Alpha", registry = false),
        cluster("mike", "Mike", registry = true)
      )
    )

    assertEquals(profiles.map(_.cluster.value), List("alpha", "mike", "zulu"))
  }

  test("read-only travels from the configuration, because it is what refuses the two writes") {
    val profiles =
      ConfiguredClusterRegistries.profilesOf(
        List(cluster("frozen", "Frozen", registry = true, readOnly = true))
      )

    assertEquals(profiles.map(_.readOnly), List(true))
  }

  test("a cluster with no port built answers None for its registry and still has a profile") {
    // The two questions are one interface for exactly this state: "I have never heard of this cluster"
    // and "I know it and it has no registry" are a 404 and a KUI-UNSUPPORTED, and a lookup that returned
    // one `Option` would collapse them into one screen.
    val profiles = ConfiguredClusterRegistries.profilesOf(List(cluster("bare", "Bare", registry = false)))
    val registries = new ConfiguredClusterRegistries[IO](profiles, Map.empty)

    for {
      known <- registries.profile(ClusterId.unsafe("bare"))
      port <- registries.registry(ClusterId.unsafe("bare"))
      unknown <- registries.profile(ClusterId.unsafe("never-heard-of-it"))
    } yield {
      assertEquals(known.map(_.displayName), Some("Bare"))
      assertEquals(port.isDefined, false)
      assertEquals(unknown, None)
    }
  }
}
