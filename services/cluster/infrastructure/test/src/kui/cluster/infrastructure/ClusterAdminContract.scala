package kui.cluster.infrastructure

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}

import kui.cluster.domain.{ClusterAdmin as ClusterAdminPort, ClusterProfile}
import kui.testkit.KuiIOSuite

/** What *any* `kui.cluster.domain.ClusterAdmin[IO]` must do, as executable assertions.
  *
  * `ClusterAdminAdapterSuite` runs it against a stubbed `libs/kafka`; `ClusterAdminLiveSuite` runs the same
  * file against a real broker in a container. That is the point: the M0 review's central finding was that
  * nothing tested the seams between components, and a contract asserted on one side only is a contract
  * asserted against the test double.
  *
  * Every case here has to be true of a healthy cluster of any shape, because a container cannot be asked to
  * lose its controller on cue. The cases that need a cluster in a specific, awkward state — no controller
  * during a failover, ACLs switched off, a call the broker refuses — are asserted in the stub-driven suite,
  * where that state can be constructed; `KafkaToDomainSuite` covers the same shapes at the conversion
  * level, which is where the actual logic lives.
  */
abstract class ClusterAdminContract extends KuiIOSuite {

  /** The port under test, in whatever scope the implementation needs. */
  def port: Resource[IO, ClusterAdminPort[IO]]

  /** A profile addressing the cluster `port` talks to. */
  def profile: ClusterProfile

  private def withPort[A](use: ClusterAdminPort[IO] => IO[A]): IO[A] = port.use(use)

  test("describeClusterReturnsTheClusterIdAndTheNodeList") {
    withPort(_.describeCluster(profile)).map {
      case Left(error) => fail(s"describeCluster failed: ${error.code.wire} ${error.message}")
      case Right(description) =>
        assert(description.brokers.toList.nonEmpty, "a cluster must report at least one broker")
        assertEquals(
          description.brokers.toList.map(_.id.value).distinct.size,
          description.brokerCount,
          "broker ids must be unique"
        )
        assertEquals(
          description.brokerIds.toList,
          description.brokerIds.toList.sortBy(_.value),
          "broker ids come back in ascending order"
        )
    }
  }

  test("describeClusterWithNoControllerIsRepresentable") {
    // `describeCluster().controller()` is `null` during a controller failover, and in KRaft the controller
    // can be a node that never appears in `nodes()`. Both are ordinary states of a healthy cluster, so the
    // port must answer `Right` whatever the controller turns out to be — never throw, never `Left`, and
    // never require the controller to be one of the brokers.
    withPort(_.describeCluster(profile)).map {
      case Left(error) => fail(s"describeCluster failed: ${error.code.wire}")
      case Right(description) =>
        description.controller.foreach { controller =>
          assert(controller.port.value > 0, "a reported controller has a usable address")
        }
    }
  }

  test("describeClusterWithNoAuthorizedOperationsIsRepresentable") {
    // `authorizedOperations()` is `null` when the cluster has no authorizer configured. That means "ACLs are
    // off", not "you may do nothing", and the two must not collapse: an empty set would hide every action on
    // a cluster where the user is in fact allowed everything.
    withPort(_.describeCluster(profile)).map {
      case Left(error) => fail(s"describeCluster failed: ${error.code.wire}")
      case Right(description) =>
        description.authorizedOperations.foreach { operations =>
          assert(operations.forall(_.trim.nonEmpty), "an operation token is never blank")
        }
    }
  }

  test("aBrokerWithNoRackIsNoneAndNotAnEmptyString") {
    // `Node.rack()` is nullable, and some managed services report a blank string. Both have to arrive as
    // `None`: a blank cell in the rack column reads as a rendering bug rather than as "this cluster is not
    // rack-aware".
    withPort(_.describeCluster(profile)).map {
      case Left(error) => fail(s"describeCluster failed: ${error.code.wire}")
      case Right(description) =>
        description.brokers.toList.foreach { broker =>
          broker.rack.foreach(rack => assert(rack.value.trim.nonEmpty, s"broker ${broker.id.value} has a blank rack"))
        }
    }
  }

  test("detectVersionReturnsNoneWhenNeitherSourceAnswers") {
    // A cluster that reveals no version is not a broken cluster. `Right(None)` is the answer, and a `Left`
    // would take a working screen down over a number nobody needs.
    withPort(_.detectVersion(profile)).map {
      case Left(error) => fail(s"detectVersion must not fail on a reachable cluster: ${error.code.wire}")
      case Right(version) => assert(version.forall(_.raw.trim.nonEmpty), "a detected version keeps what the broker said")
    }
  }

  test("quorumIsSomeOnAKRaftBrokerAndNoneIsNotAFailure") {
    withPort(_.describeQuorum(profile)).map {
      case Left(error) => fail(s"describeQuorum must not fail on a reachable cluster: ${error.code.wire}")
      case Right(None) => ()
      case Right(Some(quorum)) =>
        assert(quorum.voters.toList.exists(_.replicaId == quorum.leaderId), "the leader is one of the voters")
        quorum.voters.toList.foreach(state => assert(quorum.lagOf(state) >= 0L, "lag is never negative"))
    }
  }

  test("logDirsForAnUnknownBrokerIsASkippedKeyNotAMissingOne") {
    // The requested key set is what the answer is built from, so a broker that did not answer is a `skipped`
    // entry with a reason and never an absence. A silent drop is the defect DC-D5 exists to prevent.
    val phantom = kui.kernel.BrokerId.unsafe(9999)

    withPort(_.describeLogDirs(profile, NonEmptyList.of(phantom))).map {
      case Left(_) => () // a cluster that offers no log-directory information at all is a legitimate answer
      case Right(result) =>
        assert(result.keys.contains(phantom), "a requested broker appears in exactly one of the two maps")
    }
  }

  test("capabilitiesAreTotalAndNeverRaise") {
    // The three sets partition every feature. A feature in none of them would have `has` and `isUnknown`
    // both answering `false`, and the screen gated on it would render "not supported" for something nobody
    // ever asked about.
    withPort(_.capabilities(profile)).map { features =>
      assert(features.isTotal, s"the feature sets do not partition ClusterFeature.All: $features")
    }
  }

  test("everyFailureIsALeftAndNeverAThrownException") {
    // Not "these calls succeed" — "these calls answer". Every method returns a value even when the thing it
    // asked about is refused, and the effect never raises.
    withPort { admin =>
      for {
        described <- admin.describeCluster(profile).attempt
        version <- admin.detectVersion(profile).attempt
        quorum <- admin.describeQuorum(profile).attempt
        configs <- admin.brokerConfigs(profile, kui.kernel.BrokerId.unsafe(1), docs = false).attempt
        dirs <- admin.describeLogDirs(profile, NonEmptyList.of(kui.kernel.BrokerId.unsafe(1))).attempt
        features <- admin.capabilities(profile).attempt
      } yield {
        assert(described.isRight, s"describeCluster raised: $described")
        assert(version.isRight, s"detectVersion raised: $version")
        assert(quorum.isRight, s"describeQuorum raised: $quorum")
        assert(configs.isRight, s"brokerConfigs raised: $configs")
        assert(dirs.isRight, s"describeLogDirs raised: $dirs")
        assert(features.isRight, s"capabilities raised: $features")
      }
    }
  }
}
