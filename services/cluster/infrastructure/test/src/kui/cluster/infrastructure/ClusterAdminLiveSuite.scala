package kui.cluster.infrastructure

import scala.concurrent.duration.{Duration, DurationInt}

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*

import munit.catseffect.IOFixture

import kui.cluster.domain.{ClusterAdmin as ClusterAdminPort, ClusterProfile, ProfileOrigin, ProfileVersion}
import kui.kafka.admin.KafkaClusterAdmin
import kui.kafka.{AdminClientPool, AdminMetrics}
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterSecurity
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger
import kui.testkit.kafka.{ClusterConfigs, KafkaFixture, KafkaTopology, RunningBroker}

/** `ClusterAdminContract` against a real broker in a container, in each of the three security modes.
  *
  * This is M1's first exit criterion, stated in the roadmap as:
  *
  * > Testcontainers suite: PLAINTEXT, SASL_PLAINTEXT/SCRAM and SSL clusters; each yields the same broker
  * > list, configs and log dirs through the contract client.
  *
  * ==Why the same file runs three times==
  *
  * The three subclasses below differ in one value: the topology the container is started in. Everything
  * else — the profile, the adapter, every assertion — is shared, and that sharing is the claim. "The same
  * answers come back over PLAINTEXT, SASL and TLS" is only worth something if it is literally the same
  * assertions running against each; three suites written separately would drift, and the day one of them
  * stopped checking something nobody would notice.
  *
  * ==Why the profile is built from a `ClusterConfig` and not by hand==
  *
  * `ClusterConfigs.forBroker` produces the `kui.clusters[]` entry an operator would have written for the
  * broker that is running, including the SASL credentials the fixture provisioned and the paths of the
  * PKCS12 stores it generated. Driving the adapter from that means the security material travels the route
  * it travels in production — through `ClusterSecurity` and `libs/kafka-auth`'s property renderer — rather
  * than through a hand-built profile that happens to work. A test that assembles its own properties proves
  * the test can talk to Kafka, which is not the question.
  *
  * ==When Docker is not available==
  *
  * Every case is skipped, loudly, naming the mode. A security mode that could not be checked must never be
  * reported as a pass: the criterion is then unverified, which is a different thing from satisfied.
  */
abstract class ClusterAdminLiveSuite(topology: KafkaTopology) extends ClusterAdminContract {

  /** Generous: starting a broker, and for the TLS mode generating a certificate chain first, is slower than
    * anything else in this repository. It is a deadline for a clear failure, not a budget.
    */
  override val munitIOTimeout: Duration = 5.minutes

  private val id: ClusterId = ClusterId.unsafe("live")

  /** The broker, started once for the whole suite rather than once per test.
    *
    * Sixteen cases against sixteen containers would take twenty minutes and assert nothing extra: every
    * case in the contract is a read, so none of them can disturb another.
    */
  private val broker: IOFixture[RunningBroker] =
    ResourceSuiteLocalFixture(
      s"kafka-${topology.securityProtocol}",
      Resource.eval(IO(requireDocker())) >> KafkaFixture[IO](topology)
    )

  override def munitFixtures = List(broker)

  private def requireDocker(): Unit =
    assume(
      KafkaFixture.dockerAvailable,
      s"Docker is not available, so the ${topology.label} broker was not started and this mode is UNVERIFIED"
    )

  def profile: ClusterProfile = {
    val configured = ClusterConfigs.forBroker(broker(), id)

    ClusterProfile
      .from(
        id = configured.id,
        displayName = configured.name,
        bootstrap = configured.bootstrapServers,
        security = configured.security,
        properties = configured.properties,
        admin = configured.admin,
        readOnly = configured.readOnly,
        colour = None,
        version = ProfileVersion.unsafe(0L),
        origin = ProfileOrigin.Static
      )
      .fold(error => fail(s"the fixture produced a cluster the domain rejects: ${error.message}"), identity)
  }

  /** The adapter exactly as `ClusterBootstrap` builds it: a real pool, a real `KafkaClusterAdmin`. */
  def port: Resource[IO, ClusterAdminPort[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      metrics <- Resource.eval(AdminMetrics.otel[IO](Telemetry.noop[IO]))
      pool <- AdminClientPool.resource[IO](metrics)
      clients <- ClusterAdminClients.resource[IO](pool, logger)
      adapter <- Resource.eval(
        ClusterAdminAdapter.create[IO](KafkaClusterAdmin[IO](pool), clients, Telemetry.noop[IO], logger)
      )
    } yield adapter

  test("theBrokerListConfigsAndLogDirsAllAnswerOverThisSecurityMode") {
    // The parity assertion itself, and the reason all three modes exist in this file. It is deliberately
    // one test over the three calls rather than three: what the criterion claims is that a cluster secured
    // one way is *as usable* as one secured another, and a mode where the broker list arrives but the
    // configs do not satisfies neither half of that.
    port.use { admin =>
      val here = profile

      for {
        description <- admin.describeCluster(here)
        brokerId = description.toOption
          .flatMap(_.brokers.toList.headOption.map(_.id))
          .getOrElse(fail(s"${topology.label}: the cluster reported no brokers"))
        configs <- admin.brokerConfigs(here, brokerId, docs = false)
        dirs <- admin.describeLogDirs(here, NonEmptyList.one(brokerId))
      } yield {
        val brokers = description.toOption.toList.flatMap(_.brokers.toList)
        assertEquals(brokers.length, 1, s"${topology.label}: the fixture starts exactly one broker")

        assert(
          configs.exists(_.nonEmpty),
          s"${topology.label}: a broker must report its configuration, got $configs"
        )
        assert(
          configs.toOption.toList.flatten.exists(_.name == "advertised.listeners"),
          s"${topology.label}: advertised.listeners is set on every broker this fixture starts"
        )

        assert(
          dirs.exists(_.get(brokerId).exists(_.nonEmpty)),
          s"${topology.label}: a running broker has at least one log directory, got $dirs"
        )
      }
    }
  }

  test("theSecurityModeReachedTheProfileRatherThanBeingAssumed") {
    // Guards against the way this suite could otherwise pass while proving nothing. If the fixture's
    // security material never reached the profile, a PLAINTEXT client would still reach a PLAINTEXT
    // broker, and two thirds of the matrix would be green for the wrong reason. The secured modes have to
    // be genuinely secured before "the same answers come back" means anything.
    val security = profile.security

    topology match {
      case KafkaTopology.Plaintext =>
        assertEquals(security.securityProtocol, "PLAINTEXT")

      case KafkaTopology.SaslScram =>
        assertEquals(security.securityProtocol, "SASL_PLAINTEXT")
        assertEquals(security.saslMechanism.map(_.wireName), Some("SCRAM-SHA-512"))

      case KafkaTopology.MutualTls =>
        assertEquals(security.securityProtocol, "SSL")
        security match {
          case ClusterSecurity.Ssl(tls) =>
            assert(tls.truststore.isDefined, s"mutual TLS needs a truststore: $tls")
            assert(tls.keystore.isDefined, s"mutual TLS needs a client keystore: $tls")
          case other => fail(s"the TLS topology produced $other")
        }
    }
  }
}

final class ClusterAdminPlaintextLiveSuite extends ClusterAdminLiveSuite(KafkaTopology.Plaintext)

final class ClusterAdminSaslScramLiveSuite extends ClusterAdminLiveSuite(KafkaTopology.SaslScram)

final class ClusterAdminMutualTlsLiveSuite extends ClusterAdminLiveSuite(KafkaTopology.MutualTls)
