package kui.cluster.infrastructure

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}

import kui.cluster.domain.{ClusterAdmin as ClusterAdminPort, ClusterProfile, ControllerMode}
import kui.kafka.admin as adm
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError}
import kui.kernel.BrokerId
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger

/** `ClusterAdminContract` against a stubbed `libs/kafka`, plus the cases a live cluster cannot be asked to
  * produce on cue: a controller failover, ACLs switched off, a managed service refusing a call.
  */
final class ClusterAdminAdapterSuite extends ClusterAdminContract {

  val profile: ClusterProfile = TestProfiles.profile()

  def port: Resource[IO, ClusterAdminPort[IO]] =
    Resource.eval(StubKafkaClusterAdmin()).flatMap(adapterFor)

  private def adapterFor(stub: adm.ClusterAdmin[IO]): Resource[IO, ClusterAdminPort[IO]] =
    for {
      pool <- Resource.eval(RecordingAdminPool())
      logger <- Resource.eval(FakeStructuredLogger[IO])
      clients <- ClusterAdminClients.resource[IO](pool, logger)
      adapter <- Resource.eval(
        ClusterAdminAdapter.create[IO](stub, clients, Telemetry.noop[IO], logger)
      )
    } yield adapter


  /** The adapter under test together with the pool it will ask to invalidate. */
  private def wired(
      stub: adm.ClusterAdmin[IO]
  ): Resource[IO, (ClusterAdminPort[IO], RecordingAdminPool)] =
    for {
      pool <- Resource.eval(RecordingAdminPool())
      logger <- Resource.eval(FakeStructuredLogger[IO])
      clients <- ClusterAdminClients.resource[IO](pool, logger)
      adapter <- Resource.eval(
        ClusterAdminAdapter.create[IO](stub, clients, Telemetry.noop[IO], logger)
      )
    } yield (adapter, pool)

  test("describeClusterWithNoControllerIsAnswered") {
    // The KRaft failover window: `controller()` was `null`. `Right`, with `controller = None`.
    val headless = KafkaFixtures.description.copy(controller = None)

    StubKafkaClusterAdmin(describeCluster = Right(headless))
      .flatMap(stub => adapterFor(stub).use(_.describeCluster(profile)))
      .map {
        case Left(error) => fail(s"a cluster mid-failover must still describe: ${error.code.wire}")
        case Right(description) => assertEquals(description.controller, Option.empty[kui.cluster.domain.Broker])
      }
  }

  test("describeClusterWithNoAuthorizedOperationsIsAnswered") {
    val noAcls = KafkaFixtures.description.copy(authorizedOperations = None)

    StubKafkaClusterAdmin(describeCluster = Right(noAcls))
      .flatMap(stub => adapterFor(stub).use(_.describeCluster(profile)))
      .map {
        case Left(error) => fail(s"a cluster with no authorizer must still describe: ${error.code.wire}")
        // `None` is "ACLs are off", and it must not become an empty set, which would read as "you may do
        // nothing" and hide every action from a user who is in fact allowed everything.
        case Right(description) => assertEquals(description.authorizedOperations, Option.empty[Set[String]])
      }
  }

  test("aBlankRackBecomesNone") {
    // Broker 3's fixture rack is two spaces, which is what one managed service actually sends. A blank cell
    // in the rack column reads as a rendering bug rather than as "this cluster is not rack-aware".
    StubKafkaClusterAdmin()
      .flatMap(stub => adapterFor(stub).use(_.describeCluster(profile)))
      .map {
        case Left(error) => fail(s"describeCluster failed: ${error.code.wire}")
        case Right(description) =>
          assertEquals(description.broker(BrokerId.unsafe(3)).flatMap(_.rack), None)
          assertEquals(description.broker(BrokerId.unsafe(2)).flatMap(_.rack), None)
          assertEquals(
            description.broker(BrokerId.unsafe(1)).flatMap(_.rack).map(_.value),
            Some("rack-a")
          )
      }
  }

  test("theControllerModeComesFromTheQuorumAndIsNeverGuessed") {
    for {
      kraft <- StubKafkaClusterAdmin(quorum = Right(Some(KafkaFixtures.quorum)))
        .flatMap(stub => adapterFor(stub).use(_.describeCluster(profile)))
      refused <- StubKafkaClusterAdmin(quorum = Right(None))
        .flatMap(stub => adapterFor(stub).use(_.describeCluster(profile)))
    } yield {
      assertEquals(kraft.map(_.controllerMode), Right(ControllerMode.KRaft))
      // Not `ZooKeeper`. `libs/kafka` reports "this is a ZooKeeper cluster" and "KUI is not allowed to ask"
      // identically, and announcing ZooKeeper because a call was refused is a guess printed as a fact.
      assertEquals(refused.map(_.controllerMode), Right(ControllerMode.Unknown))
    }
  }

  test("detectVersionReturnsRightNoneWhenTheClusterSaysNothing") {
    val silent = adm.BrokerVersion(None, None, adm.VersionSource.Unknown)

    StubKafkaClusterAdmin(version = Right(silent))
      .flatMap(stub => adapterFor(stub).use(_.detectVersion(profile)))
      .map(result => assertEquals(result, Right(None)))
  }

  test("aReconnectClassFailureInvalidatesTheClient") {
    val broken = Left(InfrastructureError.Unreachable("kafka:local", "TimeoutException"))

    StubKafkaClusterAdmin(describeCluster = broken)
      .flatMap { stub =>
        wired(stub).use { (adapter, pool) =>
          adapter.describeCluster(profile) *> pool.events.get
        }
      }
      .map(events => assert(events.contains("invalidate:local"), s"expected an invalidation, got $events"))
  }

  test("aRequestLevelFailureDoesNotInvalidateTheClient") {
    // An authorization failure says nothing about the socket. Reconnecting on it makes an unauthorized user
    // into a denial of service for everybody sharing the cluster.
    val refused = Left(ApplicationError.Forbidden("no DESCRIBE on the cluster"))

    StubKafkaClusterAdmin(describeCluster = refused)
      .flatMap { stub =>
        wired(stub).use { (adapter, pool) =>
          adapter.describeCluster(profile) *> pool.events.get
        }
      }
      .map(events => assertEquals(events.filter(_.startsWith("invalidate")), Nil))
  }

  test("unsupportedBrokerConfigsStayALeftAndAreNotSwallowedToAnEmptyList") {
    // The assertion that KUI does not repeat the reference product's swallow-to-empty defect: `Right(Nil)`
    // renders a table of no settings, which reads as "this broker has no configuration" and is never true.
    val unsupported = Left(ApplicationError.Unsupported("broker configuration"))

    StubKafkaClusterAdmin(brokerConfigs = unsupported)
      .flatMap(stub => adapterFor(stub).use(_.brokerConfigs(profile, BrokerId.unsafe(1), docs = true)))
      .map {
        case Left(error) => assertEquals(error.code, ErrorCode.Unsupported)
        case Right(entries) => fail(s"a refused call must not become an empty list, got $entries")
      }
  }

  test("brokerConfigsAreSortedByNameAndKeepASensitiveValueAbsent") {
    StubKafkaClusterAdmin()
      .flatMap(stub => adapterFor(stub).use(_.brokerConfigs(profile, BrokerId.unsafe(1), docs = true)))
      .map {
        case Left(error) => fail(s"brokerConfigs failed: ${error.code.wire}")
        case Right(entries) =>
          assertEquals(entries.map(_.name), entries.map(_.name).sorted)
          // Kafka sends `null` for a sensitive value. Rendering it as an empty string would show a password
          // field that looks unset, and an operator would conclude the setting is missing.
          val sensitive = entries.find(_.isSensitive)
          assert(sensitive.isDefined, "the fixture has a sensitive entry")
          assertEquals(sensitive.flatMap(_.value), None)
      }
  }

  test("aTimeoutOnLogDirsStaysALeft") {
    val timedOut = Left(InfrastructureError.Timeout("describeLogDirs", 30000L))

    StubKafkaClusterAdmin(logDirs = timedOut)
      .flatMap(stub =>
        adapterFor(stub).use(_.describeLogDirs(profile, NonEmptyList.of(BrokerId.unsafe(1))))
      )
      .map {
        case Left(error) => assertEquals(error.code, ErrorCode.Timeout)
        case Right(result) => fail(s"a timeout is a retryable failure, not an empty disk: $result")
      }
  }

  test("capabilitiesNeverFails") {
    // The port's own contract. A probe that raises must still produce a value, and that value is
    // "everything unknown" — not "everything absent", which would switch screens off for an hour for a
    // reason that was never true.
    StubKafkaClusterAdmin(capabilities = IO.raiseError(new RuntimeException("the probe exploded")))
      .flatMap(stub => adapterFor(stub).use(_.capabilities(profile)))
      .map { features =>
        assert(features.isTotal)
        assertEquals(features.present, Set.empty)
        assertEquals(features.unknown, kui.cluster.domain.ClusterFeature.All)
      }
  }

  test("aRaisedExceptionBecomesAnUnreachableAndNeverEscapes") {
    // `KafkaErrorMapper` should already have caught this; the adapter catches it as a last resort, and
    // records the exception's *class name* — the message is the field that carries a bootstrap string.
    val exploding = new adm.ClusterAdmin[IO] {
      def describeCluster(c: kui.kernel.cluster.ClusterConnection) =
        IO.raiseError(new IllegalStateException("bootstrap=secret-host:9092"))
      def version(c: kui.kernel.cluster.ClusterConnection) = IO.pure(Right(KafkaFixtures.version))
      def describeQuorum(c: kui.kernel.cluster.ClusterConnection) = IO.pure(Right(None))
      def brokerConfigs(c: kui.kernel.cluster.ClusterConnection, b: BrokerId, d: Boolean) =
        IO.pure(Right(Nil))
      def describeLogDirs(c: kui.kernel.cluster.ClusterConnection, b: Set[BrokerId]) =
        IO.pure(Right(kui.kafka.BatchResult[BrokerId, List[adm.LogDir]](Map.empty, Map.empty)))
      def capabilities(c: kui.kernel.cluster.ClusterConnection) = IO.pure(KafkaFixtures.features)
    }

    adapterFor(exploding).use(_.describeCluster(profile).attempt).map {
      case Left(raised) => fail(s"the adapter must not raise, it raised $raised")
      case Right(Right(description)) => fail(s"expected a failure, got $description")
      case Right(Left(error)) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(!error.message.contains("secret-host"), s"the message leaked the bootstrap string: $error")
    }
  }
}
