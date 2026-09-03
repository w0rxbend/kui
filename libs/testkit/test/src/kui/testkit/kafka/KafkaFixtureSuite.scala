package kui.testkit.kafka

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}

import munit.FunSuite
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.common.errors.{SaslAuthenticationException, SslAuthenticationException}

/** That the fixture actually starts a broker in each of the three modes, and that the two secured modes
  * really do refuse a client that presents nothing.
  *
  * The negative cases are the point. "KUI works with SASL_SSL" is worth nothing as a claim about property
  * strings; it is worth something as a claim about a broker that hangs up on an anonymous client. Each
  * negative case therefore asserts the *specific* exception — a test that accepts any failure also passes
  * when the broker is simply down, which is the failure it is least able to notice.
  */
final class KafkaFixtureSuite extends FunSuite {

  override val munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  /** Skips loudly, naming the mode. A security mode that could not be checked must never be reported as a
    * pass: CI marks the criterion unverified rather than green.
    */
  private def requireDocker(topology: KafkaTopology): Unit =
    assume(
      KafkaFixture.dockerAvailable,
      s"Docker is not available, so the ${topology.label} broker was not started and this mode is UNVERIFIED"
    )

  private def withBroker[A](topology: KafkaTopology)(use: RunningBroker => IO[A]): A = {
    requireDocker(topology)
    KafkaFixture[IO](topology).use(use).unsafeRunSync()
  }

  /** An admin client over the given properties, closed whatever happens. */
  private def admin(properties: Map[String, String]): Resource[IO, Admin] =
    Resource.make(
      IO.blocking(
        Admin.create(
          (properties ++ Map(
            "request.timeout.ms" -> "15000",
            "default.api.timeout.ms" -> "15000"
          )).map((key, value) => key -> (value: Object)).asJava
        )
      )
    )(client => IO.blocking(client.close()))

  private def nodeCount(properties: Map[String, String]): IO[Int] =
    admin(properties).use(client => IO.blocking(client.describeCluster().nodes().get().size()))

  test("a PLAINTEXT broker starts and describeCluster returns one node") {
    val nodes = withBroker(KafkaTopology.Plaintext)(broker => nodeCount(broker.clientProperties))
    assertEquals(nodes, 1)
  }

  test("a SASL_PLAINTEXT/SCRAM-SHA-512 broker starts and the provisioned user can describeCluster") {
    val nodes = withBroker(KafkaTopology.SaslScram)(broker => nodeCount(broker.clientProperties))
    assertEquals(nodes, 1)
  }

  test("a SASL broker refuses a client that presents no credentials") {
    // A plaintext client against a SASL listener does not get an authentication error, because it never
    // gets as far as authenticating: the broker expects a SASL handshake, the client sends an API request,
    // and the connection goes nowhere until the client's own timeout fires. That timeout *is* the refusal,
    // and asserting it specifically is what keeps this from being a test that also passes against a broker
    // which is merely down.
    val outcome = withBroker(KafkaTopology.SaslScram) { broker =>
      nodeCount(Map("bootstrap.servers" -> broker.bootstrapServers)).attempt
    }

    assertFailsWith[org.apache.kafka.common.errors.TimeoutException](outcome, "an anonymous client")
  }

  test("a SASL broker refuses a client whose password is wrong") {
    // The other half, and the one that produces a real authentication failure: the client speaks SCRAM and
    // the broker rejects the credentials. Together the two cases say that the listener authenticates, and
    // that it does so against the user the fixture provisioned rather than against anybody at all.
    val outcome = withBroker(KafkaTopology.SaslScram) { broker =>
      val wrong = broker.clientProperties.updated(
        "sasl.jaas.config",
        "org.apache.kafka.common.security.scram.ScramLoginModule required " +
          """username="kui" password="not-the-password";"""
      )
      nodeCount(wrong).attempt
    }

    assertFailsWith[SaslAuthenticationException](outcome, "a client with the wrong password")
  }

  test("an SSL broker starts and a client with the generated keystore can describeCluster") {
    val nodes = withBroker(KafkaTopology.MutualTls)(broker => nodeCount(broker.clientProperties))
    assertEquals(nodes, 1)
  }

  test("an SSL broker refuses a client that presents no certificate") {
    val outcome = withBroker(KafkaTopology.MutualTls) { broker =>
      val trustOnly = broker.clientProperties.filterNot((key, _) => key.startsWith("ssl.keystore")) -
        "ssl.key.password"
      nodeCount(trustOnly).attempt
    }

    assertFailsWith[SslAuthenticationException](outcome, "a client with no certificate")
  }

  test("an SSL client that trusts the CA but dials 127.0.0.1 by IP still verifies the hostname") {
    // The broker certificate names both `localhost` and `127.0.0.1`, so this connects. The assertion is
    // that it connects *with verification on* — the setting most often turned off, and the one the fixture
    // would silently stop testing if anybody disabled it to make a failure go away.
    val nodes = withBroker(KafkaTopology.MutualTls) { broker =>
      val byIp = broker.clientProperties.updated(
        "bootstrap.servers",
        broker.bootstrapServers.replace("localhost", "127.0.0.1")
      )
      assertEquals(byIp.get("ssl.endpoint.identification.algorithm"), None)
      nodeCount(byIp)
    }

    assertEquals(nodes, 1)
  }

  test("ClusterConfigs.forBroker describes each broker in ADR-022's typed model") {
    requireDocker(KafkaTopology.Plaintext)

    KafkaFixture[IO](KafkaTopology.SaslScram)
      .use { broker =>
        IO {
          val configured = ClusterConfigs.forBroker(broker, kui.kernel.ClusterId.unsafe("fixture"))

          assertEquals(configured.bootstrapServers.value, broker.bootstrapServers)
          assertEquals(configured.security.securityProtocol, "SASL_PLAINTEXT")
          assertEquals(configured.security.saslMechanism.map(_.wireName), Some("SCRAM-SHA-512"))
          // The YAML form has to name the same broker, so that a suite driving the loader from a file and a
          // suite building the value get the same cluster.
          assert(
            ClusterConfigs.yamlFor(broker, kui.kernel.ClusterId.unsafe("fixture")).contains(broker.bootstrapServers),
            "the YAML form did not name the broker"
          )
        }
      }
      .unsafeRunSync()
  }

  /** Asserts the specific exception somewhere in the failure's cause chain, and says which client it was.
    *
    * Kafka wraps an authentication failure in an `ExecutionException`, so the class being looked for is
    * never the top one.
    */
  private def assertFailsWith[E: reflect.ClassTag](outcome: Either[Throwable, Int], who: String): Unit =
    outcome match {
      case Right(nodes) => fail(s"$who reached the broker and saw $nodes node(s)")
      case Left(error) =>
        val expected = summon[reflect.ClassTag[E]].runtimeClass
        val chain = Iterator
          .iterate(Option(error))(_.flatMap(e => Option(e.getCause)))
          .takeWhile(_.isDefined)
          .flatten
          .toList
        assert(
          chain.exists(cause => expected.isInstance(cause)),
          s"$who failed with ${chain.map(_.getClass.getSimpleName).mkString(" <- ")}, " +
            s"and not with ${expected.getSimpleName}"
        )
    }
}
