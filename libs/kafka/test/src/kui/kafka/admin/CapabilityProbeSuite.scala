package kui.kafka.admin

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.common.errors.*

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.*
import kui.testkit.KuiIOSuite

/** The probe, and the one property everything else rests on: it never fails.
  *
  * A capability probe is a diagnostic. A diagnostic that can take the page down with it is worse
  * than no diagnostic, so every test here that drives a failure asserts a *successful* effect with
  * a three-way answer inside it.
  */
final class CapabilityProbeSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod")

  private val connection: ClusterConnection = ClusterConnection(
    id = cluster,
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

  private def nodes(count: Int): List[KafkaNode] =
    (1 to count).toList.map(id => KafkaNode(kui.kernel.BrokerId.unsafe(id), "b", 9092, None))

  private def described(operations: Option[Set[ClusterOperation]]): ClusterDescription =
    ClusterDescription(None, None, nodes(1), operations)

  /** A pool whose every call behaves the way the test says, with no broker anywhere.
    *
    * `run` never touches the `Admin` it is given, so a `null` client is safe here and saves a
    * reflective proxy: what is under test is how the probe reacts to an outcome, not what a Kafka
    * call does.
    */
  private def poolOf(
      behaviour: String => IO[Unit],
      calls: Option[Ref[IO, List[String]]] = None
  ): AdminClientPool[IO] = new AdminClientPool[IO] {
    def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
      calls.fold(IO.unit)(_.update(_ :+ operation)) >> behaviour(operation).asInstanceOf[IO[A]]

    def invalidate(id: ClusterId): IO[Unit] = IO.unit
    def evict(id: ClusterId): IO[Unit] = IO.unit
  }

  private val allSucceed: String => IO[Unit] = _ => IO.unit

  private def probeWith(
      behaviour: String => IO[Unit],
      version: BrokerVersion,
      description: Option[ClusterDescription] = Some(described(Some(Set(ClusterOperation.All)))),
      topicDeletion: Option[Boolean] = Some(true)
  ): IO[ClusterFeatures] =
    CapabilityProbe.probe[IO](poolOf(behaviour), connection, version, description, topicDeletion)

  private def detected(version: KafkaVersion): BrokerVersion =
    BrokerVersion(Some(version), Some(version.render), VersionSource.Features)

  private val undetected: BrokerVersion = BrokerVersion(None, None, VersionSource.Unknown)

  test("resultIsAlwaysTotal") {
    val versions = List(
      undetected,
      detected(KafkaVersion(2, 2, 0)),
      detected(KafkaVersion(2, 8, 0)),
      detected(KafkaVersion(4, 0, 0))
    )

    val behaviours: List[String => IO[Unit]] = List(
      allSucceed,
      _ => IO.raiseError(new UnsupportedVersionException("no")),
      _ => IO.raiseError(new TimeoutException("slow")),
      _ => IO.raiseError(new RuntimeException("odd"))
    )

    val descriptions =
      List(None, Some(described(None)), Some(described(Some(Set(ClusterOperation.Describe)))))

    versions
      .flatMap(v => behaviours.flatMap(b => descriptions.map(d => (v, b, d))))
      .traverse_ { (version, behaviour, description) =>
        probeWith(behaviour, version, description, None).map(features =>
          assert(features.isTotal, s"not total for $version / $description: $features")
        )
      }
  }

  test("neverRaises") {
    val hostile: List[Throwable] = List(
      new TimeoutException("slow"),
      new SaslAuthenticationException("bad credentials"),
      new RuntimeException("odd"),
      new IllegalStateException("stranger"),
      new UnknownServerException("the broker gave up")
    )

    hostile.traverse_ { failure =>
      probeWith(_ => IO.raiseError(failure), detected(KafkaVersion(4, 0, 0)))
        .map(features => assert(features.isTotal))
    }
  }

  test("theDowngradeTable") {
    // The four documented classes mean the cluster answered "no". Everything else means KUI could
    // not ask, which is not the same thing.
    val absentClasses: List[Throwable] = List(
      new UnsupportedVersionException("no"),
      new InvalidRequestException("no"),
      new SecurityDisabledException("no"),
      new ClusterAuthorizationException("no")
    )

    val unknownClasses: List[Throwable] =
      List(new TimeoutException("slow"), new SaslAuthenticationException("bad"))

    val callFeatures =
      Set(ClusterFeature.AclManagement, ClusterFeature.LogDirs, ClusterFeature.KRaftQuorum)

    absentClasses.traverse_ { failure =>
      probeWith(_ => IO.raiseError(failure), detected(KafkaVersion(4, 0, 0))).map { features =>
        assert(callFeatures.subsetOf(features.absent), s"${failure.getClass}: $features")
      }
    } >> unknownClasses.traverse_ { failure =>
      probeWith(_ => IO.raiseError(failure), detected(KafkaVersion(4, 0, 0))).map { features =>
        assert(callFeatures.subsetOf(features.unknown), s"${failure.getClass}: $features")
      }
    }
  }

  test("versionDerivedFeaturesTable") {
    // This table is the specification of ADR-030's gating, and it is the test that fails when
    // somebody changes a bound.
    val expected = List(
      KafkaVersion(2, 2, 0) -> Set.empty[ClusterFeature],
      KafkaVersion(2, 3, 0) -> Set(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.AuthorizedOperations
      ),
      KafkaVersion(2, 6, 0) -> Set(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.AuthorizedOperations,
        ClusterFeature.ConfigDocumentation,
        ClusterFeature.ClientQuotas
      ),
      KafkaVersion(2, 8, 0) -> Set(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.AuthorizedOperations,
        ClusterFeature.ConfigDocumentation,
        ClusterFeature.ClientQuotas,
        ClusterFeature.ProducersAndTransactions
      ),
      KafkaVersion(3, 6, 0) -> Set(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.AuthorizedOperations,
        ClusterFeature.ConfigDocumentation,
        ClusterFeature.ClientQuotas,
        ClusterFeature.ProducersAndTransactions,
        ClusterFeature.TieredStorage
      ),
      KafkaVersion(4, 0, 0) -> Set(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.AuthorizedOperations,
        ClusterFeature.ConfigDocumentation,
        ClusterFeature.ClientQuotas,
        ClusterFeature.ProducersAndTransactions,
        ClusterFeature.TieredStorage,
        ClusterFeature.NewGroupProtocol
      )
    )

    val versionFeatures = Set(
      ClusterFeature.IncrementalAlterConfigs,
      ClusterFeature.AuthorizedOperations,
      ClusterFeature.ConfigDocumentation,
      ClusterFeature.ClientQuotas,
      ClusterFeature.ProducersAndTransactions,
      ClusterFeature.TieredStorage,
      ClusterFeature.NewGroupProtocol
    )

    expected.traverse_ { (version, present) =>
      probeWith(allSucceed, detected(version)).map { features =>
        assertEquals(features.present.intersect(versionFeatures), present, version.render)
        assertEquals(features.absent.intersect(versionFeatures), versionFeatures -- present, version.render)
      }
    }
  }

  test("undetectedVersionMakesEveryVersionFeatureUnknown") {
    // Not absent. The distinction is the whole reason the third set exists: KUI does not get to
    // claim a cluster lacks a feature because KUI could not read the cluster's version.
    probeWith(allSucceed, undetected).map { features =>
      List(
        ClusterFeature.IncrementalAlterConfigs,
        ClusterFeature.ConfigDocumentation,
        ClusterFeature.AuthorizedOperations,
        ClusterFeature.ClientQuotas,
        ClusterFeature.ProducersAndTransactions,
        ClusterFeature.TieredStorage,
        ClusterFeature.NewGroupProtocol
      ).foreach(feature => assert(features.unknown.contains(feature), feature.toString))
    }
  }

  test("aclEditIsUnknownWhenAuthorizedOperationsAreAbsent") {
    // `None` means the cluster has no authorizer configured. "We cannot tell whether you may edit"
    // is the honest answer; asserting that it is not `absent` is the point of this test.
    probeWith(allSucceed, detected(KafkaVersion(4, 0, 0)), Some(described(None))).map { features =>
      assert(features.unknown.contains(ClusterFeature.AclEdit), features.toString)
      assert(!features.absent.contains(ClusterFeature.AclEdit))
    }
  }

  test("aclEditIsAbsentWhenOperationsAreKnownAndLackAlter") {
    probeWith(
      allSucceed,
      detected(KafkaVersion(4, 0, 0)),
      Some(described(Some(Set(ClusterOperation.Describe))))
    ).map(features => assert(features.absent.contains(ClusterFeature.AclEdit), features.toString))
  }

  test("aclEditIsPresentWithAlterAndAWorkingAclCall") {
    probeWith(
      allSucceed,
      detected(KafkaVersion(4, 0, 0)),
      Some(described(Some(Set(ClusterOperation.Alter))))
    ).map(features => assert(features.has(ClusterFeature.AclEdit), features.toString))
  }

  test("aclEditFollowsAclManagementWhenAclsAreNotManagedAtAll") {
    probeWith(_ => IO.raiseError(new SecurityDisabledException("off")), detected(KafkaVersion(4, 0, 0)))
      .map { features =>
        assert(features.absent.contains(ClusterFeature.AclManagement))
        assert(features.absent.contains(ClusterFeature.AclEdit))
      }
  }

  test("topicDeletionFollowsTheBrokerConfig") {
    for {
      enabled <- probeWith(allSucceed, detected(KafkaVersion(4, 0, 0)), topicDeletion = Some(true))
      disabled <- probeWith(allSucceed, detected(KafkaVersion(4, 0, 0)), topicDeletion = Some(false))
      // The managed-service downgrade: the configuration could not be read, so KUI cannot tell.
      unreadable <- probeWith(allSucceed, detected(KafkaVersion(4, 0, 0)), topicDeletion = None)
    } yield {
      assert(enabled.has(ClusterFeature.TopicDeletion))
      assert(disabled.absent.contains(ClusterFeature.TopicDeletion))
      assert(unreadable.unknown.contains(ClusterFeature.TopicDeletion))
    }
  }

  test("probesRunInParallelAndAreBounded") {
    val program = for {
      running <- Ref.of[IO, Int](0)
      peak <- Ref.of[IO, Int](0)
      features <- CapabilityProbe.probe[IO](
        poolOf(_ =>
          running.updateAndGet(_ + 1).flatMap(now => peak.update(_.max(now))) >>
            IO.sleep(1.second) >>
            running.update(_ - 1)
        ),
        connection,
        detected(KafkaVersion(4, 0, 0)),
        Some(described(Some(Set(ClusterOperation.All)))),
        Some(true)
      )
      observed <- peak.get
    } yield (observed, features)

    TestControl.executeEmbed(program.timed).map { (elapsed, result) =>
      val (peak, features) = result

      assert(peak <= AdminTuning.default.parallelism, s"$peak probes were in flight at once")
      // Three call probes, four permitted at a time: one wave, not three.
      assertEquals(elapsed, 1.second)
      assert(features.isTotal)
    }
  }

  test("eachProbeIsBoundedByProbeTimeout") {
    // A probe that would take a whole request timeout is cut short and reported `unknown`. Probing
    // a dozen features must not cost a dozen request timeouts on a slow cluster.
    val budget = CapabilityProbe.probeTimeout(AdminTuning.default)

    val program = CapabilityProbe.probe[IO](
      poolOf(_ => IO.sleep(1.hour)),
      connection,
      detected(KafkaVersion(4, 0, 0)),
      Some(described(Some(Set(ClusterOperation.All)))),
      Some(true)
    )

    TestControl.executeEmbed(program.timed).map { (elapsed, features) =>
      assertEquals(elapsed, budget)
      assert(features.unknown.contains(ClusterFeature.LogDirs), features.toString)
      assert(features.unknown.contains(ClusterFeature.KRaftQuorum))
      assert(features.isTotal)
    }
  }

  test("probeTimeoutIsAQuarterOfTheRequestTimeoutFlooredAtTwoSeconds") {
    assertEquals(CapabilityProbe.probeTimeout(AdminTuning.default), 7500.millis)
    assertEquals(
      CapabilityProbe.probeTimeout(AdminTuning.default.copy(requestTimeout = 4.seconds)),
      2.seconds
    )
  }

  test("probedAtIsTheTimeTheProbeFinished") {
    val program = IO.sleep(5.seconds) >> probeWith(allSucceed, detected(KafkaVersion(4, 0, 0)))

    TestControl.executeEmbed(program).map { features =>
      assertEquals(features.probedAt, Instant.ofEpochMilli(5000L))
    }
  }

  test("aClusterThatCannotBeDescribedProbesNothingAndKnowsNothing") {
    // The acceptance criterion for an address with nothing behind it: total, nothing present, and
    // crucially nothing *absent* — "could not ask" is not "does not have".
    probeWith(
      _ => IO.raiseError(new TimeoutException("no broker")),
      undetected,
      description = None,
      topicDeletion = None
    ).map { features =>
      assert(features.isTotal)
      assertEquals(features.present, Set.empty[ClusterFeature])
      assertEquals(features.absent, Set.empty[ClusterFeature])
    }
  }

  test("unprobedIsEverythingUnknown") {
    val unprobed = ClusterFeatures.unprobed(at)

    assert(unprobed.isTotal)
    assertEquals(unprobed.unknown, ClusterFeature.all)
    assert(!unprobed.isKnown(ClusterFeature.LogDirs))
  }

  test("theCallProbesAreTheThreeDocumentedOnes") {
    val program = for {
      calls <- Ref.of[IO, List[String]](Nil)
      _ <- CapabilityProbe.probe[IO](
        poolOf(allSucceed, Some(calls)),
        connection,
        detected(KafkaVersion(4, 0, 0)),
        Some(described(Some(Set(ClusterOperation.All)))),
        Some(true)
      )
      made <- calls.get
    } yield made.sorted

    program.map(made =>
      assertEquals(made, List("describeAcls", "describeLogDirs", "describeMetadataQuorum"))
    )
  }
}
