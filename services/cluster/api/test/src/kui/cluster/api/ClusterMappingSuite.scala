package kui.cluster.api

import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.cluster.application.{BrokerListRow, SnapshotFreshness}
import kui.cluster.contract.dto.ClusterProfileDto
import kui.cluster.domain.{ControllerMode, LogDirError, PartitionSummary}
import kui.contracts.cluster.ClusterSummaryDto
import kui.kernel.Secret
import kui.kernel.cluster.*

/** That the mapping tells the truth about a cluster, and never tells anyone a credential.
  *
  * The redaction assertions here are the third layer of R-12: the wire types cannot hold a secret
  * (CLAPI-001), the profile endpoint does not publish one (CLAPI-003), and this is where a *live* profile
  * with real credentials in it becomes a response body.
  */
final class ClusterMappingSuite extends ScalaCheckSuite {

  private val profile = ClusterFixtures.profile()

  test("securityDescribesTheShapeAndNeverTheCredential") {
    val dto = ClusterMapping.security(profile.security)

    assertEquals(dto.protocol, "SASL_SSL")
    assertEquals(dto.mechanism, Some("SCRAM-SHA-512"))
    assertEquals(dto.truststoreConfigured, true)
    assertEquals(dto.keystoreConfigured, true)
    assert(!dto.asJson.noSpaces.contains(ClusterFixtures.Canary), dto.asJson.noSpaces)
  }

  property("noProfileProducesASecurityDtoContainingItsSecrets") {
    val secrets = Gen.oneOf("hunter2", ClusterFixtures.Canary, "s3cr3t", "-----BEGIN CERTIFICATE-----")

    forAll(secrets) { secret =>
      val mechanisms = List(
        SaslMechanism.Plain(secret, Secret(secret)),
        SaslMechanism.ScramSha256(secret, Secret(secret)),
        SaslMechanism.OAuthBearer("https://token", secret, Secret(secret), None)
      )

      mechanisms.foreach { mechanism =>
        val security = ClusterSecurity.Sasl(SaslProtocol.SaslSsl, mechanism, Some(TlsConfig.default))
        val rendered = ClusterMapping.security(security).asJson.noSpaces

        assert(!rendered.contains(secret), rendered)
      }
    }
  }

  test("aProfileBecomesARowWithoutItsCredentials, and with the address an operator needs") {
    val row = ClusterMapping.row(profile, None, SnapshotFreshness.Loading, ClusterFixtures.At)
    val rendered = row.asJson.noSpaces

    assertEquals(row.bootstrapServers, "broker-1.example.com:9093")
    assert(!rendered.contains(ClusterFixtures.Canary), rendered)
    // The override map's *keys* are not on a row at all - only the profile endpoint publishes those.
    assert(!rendered.contains("ssl.truststore.password"), rendered)
  }

  test("theProfileDtoPublishesEverythingAKafkaClientIsBuiltFrom") {
    // The one mapping in this service that does not redact (ADR-046). It is legitimate because the only
    // route that uses it is on `/internal/v1`; `SecretLeakSuite` is what turns that from a claim into a
    // fact, by walking every declared endpoint.
    val dto = ClusterMapping.profile(profile, ClusterFixtures.At)
    val rendered = dto.asJson.noSpaces

    assertEquals(dto.version, 7L)
    assertEquals(dto.properties.keys, Set("ssl.truststore.password"))
    assert(rendered.contains(ClusterFixtures.Canary), rendered)

    // And the round trip a consumer actually performs: the connection it rebuilds is the one the
    // cluster service holds, field for field.
    val decoded = dto.asJson.as[ClusterProfileDto].fold(failure => fail(failure.message), identity)
    assertEquals(ClusterProfileDto.connectionOf(decoded), profile.connection)
  }

  test("aSummaryReportsWhatKafkaSaidAndNothingItDidNot") {
    val summary = ClusterMapping.summary(ClusterFixtures.topology(), ClusterFixtures.At)

    assertEquals(summary.brokerCount, 1)
    assertEquals(summary.controllerKind, ClusterSummaryDto.KRaft)
    assertEquals(summary.controllerId.map(_.value), Some(1))
    assertEquals(summary.version, Some("4.0.0"))
    assertEquals(summary.features, List("log-dirs"))
    // The three numbers M1 cannot produce. `None` renders as an em dash; a 0 would read as a fact.
    assertEquals(summary.onlinePartitionCount, None)
    assertEquals(summary.offlinePartitionCount, None)
    assertEquals(summary.underReplicatedPartitionCount, None)
  }

  test("aBrokerReportsTheReplicasItHoldsAndClaimsNothingAboutWhichAreInSync") {
    // The case every other test in this repository misses, because every other one describes a healthy
    // cluster where the total replica count and the in-sync count are the same number.
    //
    // Here they differ: the broker holds 147 replicas and only 96 of them are caught up, which is what a
    // three-broker cluster with one broker stopped looks like from a survivor. Until 2026-09-04 the wire
    // field was called `inSyncReplicaCount` and was filled from `row.replicas` - this same 147 - so the
    // response said "147 replicas in sync" at the exact moment 51 of them were not, and kept saying it for
    // as long as the outage lasted.
    //
    // Nothing in `describeCluster` or `describeLogDirs` carries the ISR: a log directory lists the replicas
    // stored on this disk, in sync or not (`research/kafka/admin-capabilities.md`). Only `describeTopics`
    // knows, and the cluster service does not sweep topics. So the assertion is in two halves - the number
    // is the total, and no field on the wire offers an in-sync count for anyone to read the total as.
    val hosted = 147
    val actuallyInSync = 96
    assertNotEquals(hosted, actuallyInSync)

    val row = BrokerListRow(
      broker = ClusterFixtures.broker(1),
      isController = true,
      replicas = Some(hosted),
      leaders = Some(50),
      skewPercent = Some(0.0d),
      totalBytes = None,
      usableBytes = None,
      usedByKafkaBytes = Some(1024L),
      offlineDirCount = 0
    )

    val dto = ClusterMapping.broker(row)
    assertEquals(dto.replicaCount, Some(hosted))

    val rendered = dto.asJson.noSpaces
    assert(rendered.contains(""""replicaCount":147"""), rendered)
    assert(!rendered.toLowerCase.contains("insync"), rendered)

    // Where under-replication *is* honestly reported: the cluster summary, from a count Kafka gives us.
    val topology = ClusterFixtures.topology()
    val summary = ClusterMapping.summary(
      topology.copy(partitions = Some(PartitionSummary(online = 200, offline = 0, underReplicated = 51))),
      ClusterFixtures.At
    )
    assertEquals(summary.underReplicatedPartitionCount, Some(hosted - actuallyInSync))
  }

  test("controllerKindIsTheWireWordAndNotTheEnumName") {
    assertEquals(ClusterMapping.controllerKind(ControllerMode.KRaft), "kraft")
    assertEquals(ClusterMapping.controllerKind(ControllerMode.ZooKeeper), "zookeeper")
    assertEquals(ClusterMapping.controllerKind(ControllerMode.Unknown), "unknown")
  }

  test("anOfflineLogDirectoryCarriesItsOwnErrorRatherThanDisappearing") {
    // A broker with one failed disk and three good ones is a good answer with a warning in it. Dropping
    // the directory would hide exactly the fact the operator opened the page to find.
    val dir = ClusterMapping.logDir(
      kui.kernel.BrokerId.unsafe(1),
      ClusterFixtures.logDir("/mnt/broken", Some(LogDirError.Offline))
    )

    assertEquals(dir.path, "/mnt/broken")
    assertEquals(dir.error, Some(LogDirError.Offline.describe))
    assertEquals(dir.partitionCount, 1)
    assertEquals(dir.topicCount, 1)
  }

  test("skewPercentIsHandComputableAndRoundsToTwoDecimals") {
    assertEquals(ClusterMapping.skewPercent(3, 2.0d), Some(50.0d))
    assertEquals(ClusterMapping.skewPercent(1, 2.0d), Some(-50.0d))
    assertEquals(ClusterMapping.skewPercent(2, 2.0d), Some(0.0d))
    assertEquals(ClusterMapping.skewPercent(1, 3.0d), Some(-66.67d))
  }

  test("skewPercentIsNoneWhenThereIsNoMeanToDivergeFrom") {
    // Not 0.0: "perfectly balanced" and "there is nothing to measure" are different statements, and a
    // cluster with no partitions must render an em dash rather than claim balance.
    assertEquals(ClusterMapping.skewPercent(0, 0.0d), None)
    assertEquals(ClusterMapping.skewPercent(5, -1.0d), None)
  }

  property("skewPercentNeverProducesANonFiniteNumber") {
    forAll(Gen.choose(0, 100000), Gen.choose(0.0d, 100000.0d)) { (value, mean) =>
      ClusterMapping.skewPercent(value, mean).foreach(skew => assert(skew.isFinite, s"$value / $mean"))
    }
  }
}
