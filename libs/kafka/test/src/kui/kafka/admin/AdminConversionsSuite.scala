package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import org.apache.kafka.clients.admin.KuiTestSynonyms
import org.apache.kafka.clients.admin as jadmin
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.errors.KafkaStorageException
import org.apache.kafka.common.{Node, TopicPartition}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kafka.SkipReason
import kui.kernel.BrokerId
import kui.testkit.KuiSuite

/** Every rule KUI applies at the Kafka boundary, checked one constructor call at a time.
  *
  * These are the assertions that would otherwise need a cluster in a specific state to make: a
  * controller failover, a broker with no rack, a cluster with no authorizer, a disk that has gone
  * offline, a broker too old to report its disk size. The conversions are pure functions precisely
  * so that each of those is a value rather than a fixture.
  */
final class AdminConversionsSuite extends KuiSuite {

  private def node(id: Int, rack: String | Null = null): Node =
    new Node(id, "broker", 9092, rack)

  // ---------------------------------------------------------------- describeCluster

  test("nullControllerIsNone") {
    // Kafka returns `null` for the controller during a KRaft controller failover. That is a normal
    // state of a healthy cluster; the UI renders "electing".
    assertEquals(AdminConversions.controller(null), None)
  }

  test("negativeControllerIdIsNone") {
    // The other spelling of the same thing: Kafka's "no node" sentinel.
    assertEquals(AdminConversions.controller(node(-1)), None)
  }

  test("aRealControllerIsSome") {
    assertEquals(AdminConversions.controller(node(3)).map(_.id), Some(BrokerId.unsafe(3)))
  }

  test("nullClusterIdIsNone") {
    assertEquals(AdminConversions.clusterId(null), None)
    assertEquals(AdminConversions.clusterId(""), None)
    assertEquals(AdminConversions.clusterId("abc").map(_.value), Some("abc"))
  }

  test("nullAuthorizedOperationsIsNoneNotAnEmptySet") {
    // `null` means the cluster has no authorizer configured — ACLs are off — which is a different
    // statement from "you are allowed nothing". Collapsing the two would hide every action on a
    // cluster where the user may in fact do everything.
    assertEquals(AdminConversions.authorizedOperations(null), None)
    assertEquals(
      AdminConversions.authorizedOperations(Set.empty[AclOperation].asJava),
      Some(Set.empty[ClusterOperation])
    )
  }

  test("nullRackIsNone") {
    assertEquals(AdminConversions.node(node(1)).rack, None)
    assertEquals(AdminConversions.node(node(1, "")).rack, None)
    assertEquals(AdminConversions.node(node(1, "eu-west-1a")).rack, Some("eu-west-1a"))
  }

  test("unknownAclOperationBecomesUnknown") {
    // A broker newer than KUI can name an operation this enum does not have. That has to be a
    // value: the alternative is a page that fails because the cluster was upgraded.
    assertEquals(AdminConversions.operation(AclOperation.UNKNOWN), ClusterOperation.Unknown)
    assertEquals(AdminConversions.operation(AclOperation.ANY), ClusterOperation.Unknown)
    assertEquals(AdminConversions.operation(AclOperation.ALTER), ClusterOperation.Alter)
    assertEquals(AdminConversions.operation(AclOperation.ALL), ClusterOperation.All)
  }

  property("nodesAreSortedByBrokerId") {
    forAll(Gen.listOf(Gen.chooseNum(0, 100)).map(_.distinct)) { ids =>
      val described = AdminConversions.clusterDescription(
        "cluster",
        null,
        ids.map(node(_)).asJavaCollection,
        null
      )

      assertEquals(described.nodes.map(_.id.value), ids.sorted)
    }
  }

  // ---------------------------------------------------------------- configuration

  private def entry(
      name: String,
      value: String | Null,
      sensitive: Boolean = false,
      source: jadmin.ConfigEntry.ConfigSource = jadmin.ConfigEntry.ConfigSource.DEFAULT_CONFIG,
      docs: String | Null = null,
      synonyms: List[jadmin.ConfigEntry.ConfigSynonym] = Nil
  ): jadmin.ConfigEntry =
    new jadmin.ConfigEntry(
      name,
      value,
      source,
      sensitive,
      false,
      synonyms.asJava,
      jadmin.ConfigEntry.ConfigType.STRING,
      docs
    )

  test("sensitiveValuesAreNoneAndFlagged") {
    // Kafka sends `null` for the value of a sensitive setting. Rendering that as an empty string
    // would show a password field that looks unset, and an operator would conclude it is missing.
    val converted = AdminConversions.configEntry(entry("ssl.key.password", null, sensitive = true))

    assertEquals(converted.value, None)
    assertEquals(converted.isSensitive, true)
  }

  test("sourcesAreMappedTable") {
    val table = List(
      jadmin.ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG -> ConfigSource.DynamicBrokerConfig,
      jadmin.ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG ->
        ConfigSource.DynamicDefaultBrokerConfig,
      jadmin.ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG -> ConfigSource.DynamicTopicConfig,
      jadmin.ConfigEntry.ConfigSource.DYNAMIC_BROKER_LOGGER_CONFIG ->
        ConfigSource.DynamicBrokerLoggerConfig,
      jadmin.ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG -> ConfigSource.StaticBrokerConfig,
      jadmin.ConfigEntry.ConfigSource.DEFAULT_CONFIG -> ConfigSource.DefaultConfig,
      jadmin.ConfigEntry.ConfigSource.UNKNOWN -> ConfigSource.Unknown
    )

    table.foreach((raw, expected) => assertEquals(AdminConversions.configSource(raw), expected))
  }

  test("synonymsArePreservedInOrder") {
    val synonyms = List(
      KuiTestSynonyms.synonym("a", "1", jadmin.ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG),
      KuiTestSynonyms.synonym("b", "2", jadmin.ConfigEntry.ConfigSource.DEFAULT_CONFIG)
    )

    val converted = AdminConversions.configEntry(entry("x", "1", synonyms = synonyms))

    assertEquals(converted.synonyms.map(_.name), List("a", "b"))
    assertEquals(converted.synonyms.map(_.source), List(ConfigSource.StaticBrokerConfig, ConfigSource.DefaultConfig))
  }

  test("documentationIsNoneRatherThanEmpty") {
    assertEquals(AdminConversions.configEntry(entry("x", "1")).documentation, None)
    assertEquals(AdminConversions.configEntry(entry("x", "1", docs = "")).documentation, None)
    assertEquals(
      AdminConversions.configEntry(entry("x", "1", docs = "what it does")).documentation,
      Some("what it does")
    )
  }

  property("entriesAreSortedByName") {
    forAll(Gen.listOf(Gen.identifier).map(_.distinct)) { names =>
      val config = new jadmin.Config(names.map(entry(_, "v")).asJavaCollection)

      assertEquals(AdminConversions.config(config).map(_.name), names.sorted)
    }
  }

  // ---------------------------------------------------------------- log directories

  private def description(
      error: org.apache.kafka.common.errors.ApiException | Null,
      total: Long,
      usable: Long,
      replicas: Map[TopicPartition, jadmin.ReplicaInfo] = Map.empty
  ): jadmin.LogDirDescription =
    new jadmin.LogDirDescription(error, replicas.asJava, total, usable)

  test("aPerDirectoryErrorIsPreservedAndTheDirectoryIsStillListed") {
    // "This disk is down" is the single most important thing this screen can say, and a directory
    // that vanished from the list says nothing at all.
    val converted = AdminConversions.logDir(
      "/var/lib/kafka/broken",
      description(new KafkaStorageException("offline"), 100L, 50L)
    )

    assertEquals(converted.path, "/var/lib/kafka/broken")
    assert(converted.error.isDefined, "the offline disk lost its error")
    assertEquals(
      converted.error.map(_.message),
      Some(SkipReason.Failed(kui.kernel.error.ErrorCode.InvalidState, "the log directory is offline").message)
    )
  }

  test("aHealthyDirectoryHasNoError") {
    assertEquals(AdminConversions.logDir("/data", description(null, 10L, 5L)).error, None)
  }

  test("emptyOptionalTotalsAreNoneAndSoAreMinusOneTotals") {
    // Brokers before 3.3 do not report sizes at all; some report the `-1` sentinel instead of an
    // empty optional. A `-1` rendered as a size is a table cell reading "-1 B".
    assertEquals(AdminConversions.reportedSize(java.util.OptionalLong.empty()), None)
    assertEquals(AdminConversions.reportedSize(java.util.OptionalLong.of(-1L)), None)
    assertEquals(AdminConversions.reportedSize(java.util.OptionalLong.of(0L)), Some(0L))
    assertEquals(AdminConversions.reportedSize(java.util.OptionalLong.of(42L)), Some(42L))
  }

  property("usedByReplicasBytesSumsTheReplicas") {
    forAll(Gen.listOfN(5, Gen.chooseNum(0L, 1000000L))) { sizes =>
      val replicas = sizes.zipWithIndex.map { (size, index) =>
        new TopicPartition("orders", index) -> new jadmin.ReplicaInfo(size, 0L, false)
      }.toMap

      val converted = AdminConversions.logDir("/data", description(null, 0L, 0L, replicas))

      assertEquals(converted.usedByReplicasBytes, sizes.sum)
      assertEquals(converted.replicas.size, replicas.size)
    }
  }

  test("logDirsAreSortedByPath") {
    val dirs = Map(
      "/z" -> description(null, 1L, 1L),
      "/a" -> description(null, 1L, 1L),
      "/m" -> description(null, 1L, 1L)
    )

    assertEquals(AdminConversions.logDirs(dirs.asJava).map(_.path), List("/a", "/m", "/z"))
  }

  property("quorumLagIsFlooredAtZero") {
    // An observer can briefly report an offset ahead of the high watermark it was told about, and a
    // negative lag in a table reads as a bug in KUI rather than as the millisecond of skew it is.
    forAll(Gen.chooseNum(0L, 1000L), Gen.chooseNum(0L, 1000L)) { (watermark, offset) =>
      val voter = QuorumVoter(BrokerId.unsafe(1), offset, None, None)

      assertEquals(voter.lagFrom(watermark), math.max(0L, watermark - offset))
      assert(voter.lagFrom(watermark) >= 0L)
    }
  }
}
