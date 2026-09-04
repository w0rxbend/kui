package kui.kafka

import cats.effect.IO

import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.testkit.KuiIOSuite

/** That the consumer defaults every KUI consumer inherits are the ones the factory promises.
  *
  * The one that earns a suite of its own is `allow.auto.create.topics=false`. Kafka's broker default is
  * `auto.create.topics.enable=true`, and a consumer left at its own default of `allow.auto.create.topics=true`
  * asks the broker for the metadata of the topic it is about to read — which, on such a broker, creates it.
  * The message browser hit this exactly: browsing a topic that did not exist answered
  * `KUI-TOPIC-NOT-FOUND` and left a new empty topic behind on the user's cluster. KUI is a read-only tool in
  * M2/M3 and a mistyped topic name must not change anybody's cluster, so the setting is pinned here rather
  * than left to a default that has already been wrong once.
  */
final class ConsumerFactorySuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private def properties(groupId: Option[String]): IO[Map[String, String]] =
    ConsumerFactory.settings[IO](connection, groupId).use {
      case Left(error) => IO.raiseError(new AssertionError(s"settings failed: $error"))
      case Right(settings) => IO.pure(settings.properties)
    }

  test("autoTopicCreationIsRefused") {
    properties(None).map { props =>
      assertEquals(props.get(ConsumerFactory.AllowAutoCreateTopicsKey), Some("false"))
    }
  }

  test("theOtherThreeDefaultsAreStillThere") {
    properties(Some("kui-probe")).map { props =>
      assertEquals(props.get("enable.auto.commit"), Some("false"))
      assertEquals(props.get("auto.offset.reset"), Some("none"))
      assertEquals(props.get("group.id"), Some("kui-probe"))
    }
  }

  test("noGroupIdWhenNoneIsAskedFor") {
    properties(None).map(props => assertEquals(props.get("group.id"), None))
  }
}
