package kui.consumer.infrastructure

import cats.effect.IO

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite

/** What the consumer service knows about a cluster, and what it must not invent about one it does not have.
  *
  * `ConfiguredProfileSource` is the consumer service's copy of `services/message`'s
  * `ConfiguredClusterProfiles` — the two classes make the same choice for the same stated reason — and the
  * message copy has had a suite since wave 6 while this one had none at all. Four mutations were applied here
  * one at a time against `./mill services.consumer.__.test`, and every one left it at 201/201 green:
  * `connectionFor` falling back to `connections.values.headOption`, `readOnly` hard-wired to `false`, `all`
  * truncated to one entry, and the view list reversed. The first two are the ones the message suite closed on
  * the twin; a rule is not gated because its sibling's copy is.
  */
final class ConfiguredProfileSourceSuite extends KuiIOSuite {

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

  /** Deliberately not in sorted order, so a source that merely passed its input through would answer this
    * order rather than the one the screens are drawn in.
    */
  private val source: ConfiguredProfileSource[IO] =
    new ConfiguredProfileSource[IO](
      List(
        config("staging", "staging-broker:9092", readOnly = false),
        config("prod", "prod-broker:9092", readOnly = true),
        config("analytics", "analytics-broker:9092", readOnly = false)
      )
    )

  test("a cluster nobody configured has no connection material, rather than somebody else's") {
    // Not merely a wrong answer: a fallback here opens a Kafka client against a broker the caller never
    // named, with that broker's credentials, on a request that was a typo.
    assertEquals(source.connectionFor(ClusterId.unsafe("typo")), None)
    assertEquals(
      source.connectionFor(ClusterId.unsafe("prod")).map(_.bootstrapServers.value),
      Some("prod-broker:9092")
    )
  }

  test("a cluster marked read-only reaches the application layer as read-only, and a writable one does not") {
    // ADR-047's per-cluster refusal is evaluated from this flag. Wired to a constant, a cluster an
    // operator marked read-only would accept offset resets and group deletions on every screen.
    for {
      locked <- source.profileOf(ClusterId.unsafe("prod"))
      open <- source.profileOf(ClusterId.unsafe("staging"))
    } yield {
      assertEquals(locked.map(_.readOnly), Right(true))
      assertEquals(open.map(_.readOnly), Right(false))
    }
  }

  test("every configured cluster is listed, in one order, because the registry keys its cells on this") {
    // `GroupSnapshots` builds one cell per entry: a list that quietly drops one is a cluster whose groups
    // are never scraped, and whose screen says it is starting for ever. The order is asserted because two
    // answers to one question have to be the same document.
    source.all.map { listed =>
      assertEquals(listed.map(_.cluster.value), List("analytics", "prod", "staging"))
      assertEquals(listed.size, 3)
    }
  }

  test("an unconfigured cluster is a not-found and never an empty answer") {
    source
      .profileOf(ClusterId.unsafe("typo"))
      .map(answer => assertEquals(answer.left.map(_.code), Left(ErrorCode.ClusterNotFound)))
  }
}
