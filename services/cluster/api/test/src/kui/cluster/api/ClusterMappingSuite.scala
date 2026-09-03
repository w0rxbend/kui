package kui.cluster.api

import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.cluster.application.SnapshotFreshness
import kui.cluster.domain.{ControllerMode, LogDirError}
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

  test("theProfileDtoPublishesTheOverrideKeysAndNoneOfTheirValues") {
    val dto = ClusterMapping.profile(profile, ClusterFixtures.At)
    val rendered = dto.asJson.noSpaces

    assertEquals(dto.propertyKeys, List("ssl.truststore.password"))
    assertEquals(dto.version, 7L)
    assert(!rendered.contains(ClusterFixtures.Canary), rendered)
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
