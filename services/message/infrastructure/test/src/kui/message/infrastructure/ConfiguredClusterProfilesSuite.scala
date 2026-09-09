package kui.message.infrastructure

import cats.effect.IO

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite

/** What the message service knows about a cluster, and what it must not invent about one it does not have.
  *
  * Two rules, both ungated when measured against `./mill services.message.__.test + apps.allinone.test`,
  * which stayed at 230/230 green for each:
  *
  *   - `connectionFor` falling back to `byId.values.headOption`, so a browse against a mistyped cluster id
  *     opens a Kafka consumer with **another cluster's** bootstrap list and credentials;
  *   - the `readOnly` flag hard-wired to `false`, so ADR-047's per-cluster refusal never fires in this
  *     service and a cluster an operator marked read-only accepts produces and purges.
  */
final class ConfiguredClusterProfilesSuite extends KuiIOSuite {

  private def config(id: String, bootstrap: String, readOnly: Boolean): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe(id),
      name = s"Cluster $id",
      bootstrapServers = BootstrapServers.unsafe(bootstrap),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = readOnly,
      admin = AdminTuning.default
    )

  private val profiles = ConfiguredClusterProfiles.of[IO](
    List(
      config("prod", "prod-broker:9092", readOnly = true),
      config("staging", "staging-broker:9092", readOnly = false)
    )
  )

  test("a cluster nobody configured has no connection material, rather than somebody else's") {
    // A fallback here does not merely answer the wrong thing: it opens a Kafka client against a broker
    // the caller never named, with that broker's credentials, on a request that was a typo.
    assertEquals(profiles.connectionFor(ClusterId.unsafe("typo")), None)
  }

  test("a configured cluster gets its own bootstrap list and no other") {
    assertEquals(
      profiles.connectionFor(ClusterId.unsafe("staging")).map(_.bootstrapServers.value),
      Some("staging-broker:9092")
    )
    assertEquals(
      profiles.connectionFor(ClusterId.unsafe("prod")).map(_.bootstrapServers.value),
      Some("prod-broker:9092")
    )
  }

  test("a cluster marked read-only reaches the domain as read-only, and a writable one does not") {
    for {
      locked <- profiles.cluster(ClusterId.unsafe("prod"))
      open <- profiles.cluster(ClusterId.unsafe("staging"))
    } yield {
      assertEquals(locked.map(_.readOnly), Right(true))
      assertEquals(open.map(_.readOnly), Right(false))
    }
  }

  test("an unconfigured cluster is a not-found and never an empty answer") {
    // "KUI has never heard of this cluster" and "this cluster has no records" are different sentences
    // with different remedies, and a browse that answered an empty page for a typo would send the user
    // to look at Kafka instead of at their URL.
    profiles
      .cluster(ClusterId.unsafe("typo"))
      .map(answer => assertEquals(answer.left.map(_.code), Left(ErrorCode.ClusterNotFound)))
  }

  test("the id list is what a per-cluster component is built from, in configuration order") {
    assertEquals(profiles.ids.map(_.value), List("prod", "staging"))
  }
}
