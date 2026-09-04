package kui.cluster.domain

import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.BrokerId
import kui.testkit.{ClusterGenerators, KuiSuite, RedactionAssertions}

/** The arithmetic on top of the model, and the two invariants that keep the model honest.
  *
  * `partitionsAndTopicsAndLeadersAreNoneInM1` is the executable form of "what M1 cannot fill": when the topic
  * service can supply those numbers, this test has to be deleted deliberately rather than discovered by
  * accident.
  */
final class ClusterTopologySuite extends KuiSuite {

  import TopologyFixtures.*

  private val ref: ClusterRef = ClusterRef(kui.kernel.ClusterId.unsafe("prod"), "Production")

  private def loads(counts: Int*): Map[BrokerId, BrokerLoad] =
    counts.zipWithIndex.map((count, index) => BrokerId.unsafe(index + 1) -> load(count)).toMap

  test("skewIsZeroWhenReplicasAreEven") {
    val skewed = BrokerLoad.withSkew(loads(10, 10, 10))

    assertEquals(skewed.values.flatMap(_.skewPercent).toSet, Set(0.0))
  }

  test("skewIsPositiveForTheOverloadedBrokerAndNegativeForTheOthers") {
    val skewed = BrokerLoad.withSkew(loads(20, 5, 5))

    assertEquals(skewed(BrokerId.unsafe(1)).skewPercent, Some(100.0))
    assertEquals(skewed(BrokerId.unsafe(2)).skewPercent, Some(-50.0))
    assertEquals(skewed(BrokerId.unsafe(3)).skewPercent, Some(-50.0))
  }

  test("skewIsNoneWhenThereAreNoReplicas") {
    // An empty cluster must not divide by zero, and must not report 0% either: "perfectly balanced"
    // and "nothing to balance" are different statements.
    assertEquals(BrokerLoad.withSkew(loads(0, 0)).values.flatMap(_.skewPercent).toList, Nil)
    assertEquals(BrokerLoad.withSkew(Map.empty[BrokerId, BrokerLoad]), Map.empty[BrokerId, BrokerLoad])
  }

  property("skewUsesOneDenominatorForEveryBroker") {
    // `withSkew` cannot be replaced by a per-broker computation: recovering each broker's replica
    // count from its skew and one shared mean must reproduce the real total.
    val counts: Gen[List[Int]] = Gen.nonEmptyListOf(Gen.choose(0, 50)).map(_.take(8))

    forAll(counts) { values =>
      val skewed = BrokerLoad.withSkew(loads(values*))
      val total = values.sum

      if total > 0 then {
        val mean = total.toDouble / values.size.toDouble
        val recovered = skewed.values.map(load => load.skewPercent.get / 100.0 * mean + mean).sum

        assert(math.abs(recovered - total.toDouble) < 1.0, s"recovered $recovered from $values")
      }
    }
  }

  test("totalDiskIsNoneWhenNoBrokerReported") {
    val dirs = List(logDir("/data", totalBytes = None, usableBytes = None))
    val built = topology(ref, load = Map(BrokerId.unsafe(1) -> load(1, dirs)))

    assertEquals(built.totalDiskBytes, None)
  }

  test("totalDiskSumsWhatWasReported") {
    val dirs = List(logDir("/data", totalBytes = Some(100L), usableBytes = Some(40L)))
    val built = topology(
      ref,
      load = Map(BrokerId.unsafe(1) -> load(1, dirs), BrokerId.unsafe(2) -> load(1, dirs))
    )

    assertEquals(built.totalDiskBytes, Some(200L))
    assertEquals(built.usableDiskBytes, Some(80L))
  }

  test("kafkaDiskUsageIsTheReplicasAndNotTheFilesystemsUsedSpace") {
    // The defect this pins: the cluster and broker screens both label a number "Disk", and both used to
    // answer with the filesystem's - `totalBytes` on the cluster list and `totalBytes - usableBytes` on the
    // broker list. On the quickstart, whose broker holds about a hundred records, those read 468.8 GiB and
    // 184.2 GiB. What an operator means by a Kafka broker's disk is what Kafka's data occupies, which is the
    // sum of the replica sizes the broker reports for its log directories - 300 bytes here, not 999,400.
    val dirs = List(
      logDir(
        "/data",
        replicas = List(replica("orders", 0, 200L), replica("orders", 1, 100L)),
        totalBytes = Some(1_000_000L),
        usableBytes = Some(600L)
      )
    )
    val built = topology(ref, load = Map(BrokerId.unsafe(1) -> load(2, dirs)))

    assertEquals(built.usedByKafkaBytes, Some(300L))
    assertEquals(built.totalDiskBytes, Some(1_000_000L))
  }

  test("kafkaDiskUsageIsNoneWhenTheBrokerReportedNoLogDirectories") {
    // Zero would be a claim - "this broker is holding nothing" - and a broker older than Kafka 3.3 reports
    // no directories at all. The screens render `None` as an em dash and zero as "0 B"; only one of those is
    // honest about a broker that was never asked.
    val built = topology(ref, load = Map(BrokerId.unsafe(1) -> load(0, Nil).copy(logDirs = Nil)))

    assertEquals(built.usedByKafkaBytes, None)
  }

  test("offlineLogDirCountCountsAcrossBrokers") {
    val offline = List(logDir("/data", error = Some(LogDirError.Offline)), logDir("/data2"))
    val built = topology(
      ref,
      load = Map(BrokerId.unsafe(1) -> load(1, offline), BrokerId.unsafe(2) -> load(1, offline))
    )

    assertEquals(built.offlineLogDirCount, 2)
  }

  test("partitionsAndTopicsAndLeadersAreNoneInM1") {
    val built = topology(ref, load = Map(BrokerId.unsafe(1) -> load(3)))

    assertEquals(built.partitions, None)
    assertEquals(built.topics, None)
    assertEquals(built.load(BrokerId.unsafe(1)).leaders, None)
  }

  test("belowMinimumVersionDrivesTheBanner") {
    def at(raw: String) = KafkaVersion.parse(raw, VersionSource.MetadataVersion).toOption

    assert(topology(ref, version = at("2.7")).belowMinimumVersion)
    assert(!topology(ref, version = at("3.9")).belowMinimumVersion)
    // An undetected version is an unknown, not a warning: warning about it would fire on every
    // managed service that does not report one.
    assert(!topology(ref, version = None).belowMinimumVersion)
  }

  test("featuresAreAlwaysTotalAndDisjoint") {
    assert(ClusterFeatures.unprobed(ProbedAt).isTotal)
    assert(features(Set(ClusterFeature.LogDirs), Set(ClusterFeature.KRaftQuorum)).isTotal)

    val overlapping = features(Set(ClusterFeature.LogDirs), Set(ClusterFeature.LogDirs))

    assert(overlapping.isTotal, "`of` must resolve an overlap rather than produce an impossible value")
    assert(overlapping.has(ClusterFeature.LogDirs))
    assert(!overlapping.isAbsent(ClusterFeature.LogDirs))
  }

  test("anUnprobedFeatureIsUnknownAndNotAbsent") {
    // The bug the third set exists to prevent: a probe that timed out must not switch a working
    // screen off for an hour.
    val unprobed = ClusterFeatures.unprobed(ProbedAt)

    assert(unprobed.isUnknown(ClusterFeature.BrokerConfigs))
    assert(!unprobed.isAbsent(ClusterFeature.BrokerConfigs))
    assert(!unprobed.has(ClusterFeature.BrokerConfigs))
  }

  property("topologyHoldsNoProfileAndNoSecret") {
    // `ClusterTopology` holds a `ClusterRef`, so there is no path from a snapshot to a bootstrap
    // string or a password. That is asserted structurally by the fact that this compiles; the
    // property below asserts the rendering as well.
    given Arbitrary[ClusterProfile] = ClusterProfileFixtures.arbitraryProfile

    forAll { (profile: ClusterProfile) =>
      val built = topology(profile.ref)

      ClusterGenerators.secretsOfSecurity(profile.security).filter(_.nonEmpty).foreach { secret =>
        RedactionAssertions.assertNoLeak(built.toString, secret)
      }
    }
  }
}
