package kui.cluster.infrastructure

import kui.cluster.domain as dom
import kui.kafka.admin as adm
import kui.kafka.{BatchResult, SkipReason as KafkaSkipReason}
import kui.kernel.BrokerId
import kui.testkit.KuiSuite
import org.scalacheck.Prop.forAll

/** The seam between `libs/kafka`'s vocabulary and the cluster domain's, asserted without a broker.
  *
  * These are the shapes a real cluster produces that no container can be asked to produce on demand: a
  * controller that is not one of the brokers, a rack that is a blank string, a sensitive setting with no
  * value, an offline disk, a `-1` timestamp meaning "never". Each of them is a case a reference product got
  * wrong at least once, and each is a `NullPointerException` or a nonsense table cell in production.
  */
final class KafkaToDomainSuite extends KuiSuite {

  private val mode = dom.ControllerMode.KRaft

  test("theDescriptionCarriesTheKafkaClusterIdAndEveryBroker") {
    KafkaToDomain.description(KafkaFixtures.description, mode) match {
      case Left(error) => fail(s"a healthy cluster must convert: ${error.message}")
      case Right(described) =>
        assertEquals(described.kafkaClusterId.map(_.value), Some("MkU3OEVBNTcwNTJENDM2Qk"))
        assertEquals(described.brokerCount, 3)
        assertEquals(described.controllerMode, dom.ControllerMode.KRaft)
        // Ascending, because every list screen and every batched admin call wants that order and the source
        // list is deliberately shuffled in the fixture.
        assertEquals(described.brokerIds.toList.map(_.value), List(1, 2, 3))
    }
  }

  test("aBlankRackIsNoneAndNotAnEmptyString") {
    assertEquals(KafkaToDomain.broker(KafkaFixtures.nodeThree).rack, None)
    assertEquals(KafkaToDomain.broker(KafkaFixtures.nodeTwo).rack, None)
    assertEquals(KafkaToDomain.broker(KafkaFixtures.nodeOne).rack.map(_.value), Some("rack-a"))
  }

  test("aControllerThatIsNotOneOfTheBrokersIsLegal") {
    // In KRaft the active controller can be a dedicated node with `process.roles=controller`, which never
    // appears in `nodes()`. A conversion that required the controller to be among the brokers would refuse
    // to render a perfectly ordinary cluster.
    val dedicated = adm.KafkaNode(BrokerId.unsafe(99), "controller-1", 9093, None)
    val raw = KafkaFixtures.description.copy(controller = Some(dedicated))

    KafkaToDomain.description(raw, mode) match {
      case Left(error) => fail(s"a dedicated KRaft controller must convert: ${error.message}")
      case Right(described) =>
        assertEquals(described.controller.map(_.id.value), Some(99))
        assertEquals(described.brokerCount, 3)
    }
  }

  test("aDuplicateBrokerIdIsRefusedAndNamed") {
    val raw = KafkaFixtures.description.copy(nodes = List(KafkaFixtures.nodeOne, KafkaFixtures.nodeOne))

    KafkaToDomain.description(raw, mode) match {
      case Right(described) => fail(s"a duplicate broker id must not convert: $described")
      case Left(error) => assert(error.message.contains("1"), s"the message names the id: ${error.message}")
    }
  }

  test("aClusterWithNoBrokersIsAFailureAndNotAnEmptyList") {
    KafkaToDomain.description(KafkaFixtures.description.copy(nodes = Nil), mode) match {
      case Right(described) => fail(s"a cluster with no brokers is not describable: $described")
      case Left(_) => ()
    }
  }

  test("absentClusterIdAndAbsentOperationsSurvive") {
    val raw = KafkaFixtures.description.copy(kafkaClusterId = None, authorizedOperations = None)

    KafkaToDomain.description(raw, mode) match {
      case Left(error) => fail(error.message)
      case Right(described) =>
        assertEquals(described.kafkaClusterId, None)
        // `None` means the cluster has no authorizer configured. An empty set would say "you may do
        // nothing", which is the opposite.
        assertEquals(described.authorizedOperations, Option.empty[Set[String]])
    }
  }

  test("operationTokensAreLowercaseHyphenated") {
    assertEquals(KafkaToDomain.operationToken(adm.ClusterOperation.DescribeConfigs), "describe-configs")
    assertEquals(KafkaToDomain.operationToken(adm.ClusterOperation.IdempotentWrite), "idempotent-write")
    assertEquals(KafkaToDomain.operationToken(adm.ClusterOperation.All), "all")
  }

  test("theControllerModeIsKRaftOnlyWhenAQuorumAnswered") {
    assertEquals(KafkaToDomain.controllerMode(Right(Some(KafkaFixtures.quorum))), dom.ControllerMode.KRaft)
    assertEquals(KafkaToDomain.controllerMode(Right(None)), dom.ControllerMode.Unknown)
    assertEquals(
      KafkaToDomain.controllerMode(
        Left(kui.kernel.error.ApplicationError.Forbidden("no DESCRIBE on the cluster"))
      ),
      dom.ControllerMode.Unknown
    )
  }

  test("aVersionIsParsedWithItsSourceAndAnUndetectableOneIsNone") {
    assertEquals(KafkaToDomain.version(KafkaFixtures.version).map(_.short), Some("3.9"))
    assertEquals(
      KafkaToDomain.version(KafkaFixtures.version).map(_.source),
      Some(dom.VersionSource.MetadataVersion)
    )
    assertEquals(KafkaToDomain.version(adm.BrokerVersion(None, None, adm.VersionSource.Unknown)), None)
    // A source of `Unknown` means nothing was established, even if a string came back with it.
    assertEquals(
      KafkaToDomain.version(adm.BrokerVersion(None, Some("3.9"), adm.VersionSource.Unknown)),
      None
    )
  }

  property("aVersionStringIsNeverAnException") {
    forAll { (raw: String) =>
      val parsed = KafkaToDomain.version(adm.BrokerVersion(None, Some(raw), adm.VersionSource.Features))
      parsed.forall(_.raw == raw)
    }
  }

  test("aSensitiveConfigValueStaysAbsent") {
    val converted = KafkaFixtures.configs.map(KafkaToDomain.configEntry)
    val sensitive = converted.find(_.isSensitive)

    assert(sensitive.isDefined)
    assertEquals(sensitive.flatMap(_.value), None)
    assertEquals(converted.head.synonyms.map(_.name), List("log.retention.hours"))
  }

  test("aLoggerConfigSourceBecomesUnknownRatherThanABrokerSetting") {
    assertEquals(
      KafkaToDomain.configSource(adm.ConfigSource.DynamicBrokerLoggerConfig),
      dom.ConfigSource.Unknown
    )
    assertEquals(KafkaToDomain.configSource(adm.ConfigSource.StaticBrokerConfig), dom.ConfigSource.StaticBroker)
  }

  test("anOfflineDirectoryIsCarriedAsDataAndNotDropped") {
    // A single failed disk answers for itself while the broker's other directories answer normally. A model
    // that dropped it would discard a healthy broker's data because one of its disks is down.
    val converted = List(KafkaFixtures.healthyDir, KafkaFixtures.offlineDir).map(KafkaToDomain.logDir)

    assert(converted.forall(_.isRight), s"both directories must convert: $converted")

    val dirs = converted.collect { case Right(dir) => dir }
    assertEquals(dirs.count(_.isHealthy), 1)
    assertEquals(dirs.find(!_.isHealthy).flatMap(_.error), Some(dom.LogDirError.Offline))
    assertEquals(dirs.find(_.isHealthy).map(_.usedByKafkaBytes), Some(512L))
  }

  test("aRequestedBrokerThatAnsweredNothingIsASkippedKeyNotAMissingOne") {
    val requested = Set(BrokerId.unsafe(1), BrokerId.unsafe(2), BrokerId.unsafe(3), BrokerId.unsafe(4))
    val result = KafkaToDomain.logDirsByBroker(requested, KafkaFixtures.logDirs)

    assertEquals(result.keys, requested)
    assertEquals(result.values.keySet, Set(BrokerId.unsafe(1), BrokerId.unsafe(2)))
    // Broker 3 refused; broker 4 was never mentioned by the batch at all. Both have to be accounted for.
    assertEquals(result.skipped.get(BrokerId.unsafe(3)), Some(dom.SkipReason.Unauthorized))
    assert(result.skipped.contains(BrokerId.unsafe(4)), "a key in neither map is filled in as a failure")
  }

  property("noRequestedBrokerEverDisappears") {
    forAll(org.scalacheck.Gen.listOf(org.scalacheck.Gen.chooseNum(0, 20))) { (ids: List[Int]) =>
      val requested = ids.map(BrokerId.unsafe).toSet
      val batch = BatchResult[BrokerId, List[adm.LogDir]](
        values = requested.take(2).map(_ -> List(KafkaFixtures.healthyDir)).toMap,
        skipped = requested.drop(2).take(2).map(_ -> KafkaSkipReason.NoLeader).toMap
      )

      KafkaToDomain.logDirsByBroker(requested, batch).keys == requested
    }
  }

  test("aQuorumConvertsAndNeverMeansThatAFollowerIsAheadOfTheTruth") {
    KafkaToDomain.quorum(KafkaFixtures.quorum) match {
      case Left(error) => fail(error.message)
      case Right(quorum) =>
        assertEquals(quorum.voters.length, 2)
        assertEquals(quorum.leader.map(_.replicaId.value), Some(1))
        quorum.voters.toList.foreach(state => assert(quorum.lagOf(state) >= 0L))
        // `-1` is Kafka's "never fetched". Rendered as an instant it becomes 1969, which reads as data
        // corruption rather than as "this observer has not caught up yet".
        assertEquals(quorum.voters.toList.last.lastFetch, None)
    }
  }

  test("aQuorumWhoseLeaderIsNotAVoterIsRefused") {
    val impossible = KafkaFixtures.quorum.copy(leaderId = BrokerId.unsafe(42))

    assert(KafkaToDomain.quorum(impossible).isLeft)
  }

  test("theThreeFeatureSetsSurviveTheCrossing") {
    val converted = KafkaToDomain.features(KafkaFixtures.features)

    assert(converted.isTotal, s"the sets must partition ClusterFeature.All: $converted")
    assert(converted.has(dom.ClusterFeature.LogDirs))
    assert(converted.has(dom.ClusterFeature.KRaftQuorum))
    assertEquals(converted.probedAt, KafkaFixtures.probedAt)
    // The domain models a feature `libs/kafka` never probes. It has to be `unknown` — "KUI has established
    // nothing here" — and not `absent`, which would switch a working screen off.
    assert(converted.isUnknown(dom.ClusterFeature.BrokerConfigs))
  }

  test("everyLibraryFeatureMapsOrIsDeliberatelyDropped") {
    // The exhaustive match is the compiler-checked half of the duplication the gate review accepted. This is
    // the other half: a feature added to `libs/kafka` and forgotten here would silently vanish, and the
    // assertion below says which of the twelve are expected to.
    val mapped = adm.ClusterFeature.all.flatMap(KafkaToDomain.feature)

    assertEquals(
      mapped,
      Set(
        dom.ClusterFeature.IncrementalAlterConfigs,
        dom.ClusterFeature.ConfigDocumentation,
        dom.ClusterFeature.AuthorizedOperations,
        dom.ClusterFeature.LogDirs,
        dom.ClusterFeature.KRaftQuorum
      )
    )
  }

  test("anUnprobedClusterIsEverythingUnknown") {
    val unprobed = KafkaToDomain.features(adm.ClusterFeatures.unprobed(KafkaFixtures.probedAt))

    assertEquals(unprobed.present, Set.empty[dom.ClusterFeature])
    assertEquals(unprobed.absent, Set.empty[dom.ClusterFeature])
    assertEquals(unprobed.unknown, dom.ClusterFeature.All)
  }
}
