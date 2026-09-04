package kui.topic.infrastructure

import scala.jdk.CollectionConverters.*

import org.apache.kafka.clients.admin.{ConfigEntry, KuiTopicTestSynonyms}
import org.apache.kafka.common.{Node, TopicPartition, TopicPartitionInfo}

import kui.kernel.{BrokerId, TopicName}
import kui.testkit.KuiSuite
import kui.topic.domain.ConfigSource

/** The conversions from Kafka's vocabulary to the topic domain's.
  *
  * These are the functions where a wrong answer is invisible: a `-1` broker id rendered as the leader of an
  * offline partition, a default that is not a default, an offset attached to a partition that could not have
  * reported one. They are pure, so they are asserted directly rather than through a broker.
  */
final class KafkaTopicAdminSuite extends KuiSuite {

  private val broker1 = new Node(1, "kafka", 9092)
  private val broker2 = new Node(2, "kafka", 9092)
  private val topic = TopicName.unsafe("orders.v1")

  private def info(
      partition: Int,
      leader: Node | Null,
      replicas: List[Node],
      isr: List[Node]
  ): TopicPartitionInfo =
    new TopicPartitionInfo(partition, leader, replicas.asJava, isr.asJava)

  // ---------------------------------------------------------------------------- the leaderless partition

  test("Kafka's two ways of saying 'no leader' both mean no leader") {
    // A `null` leader and `Node.noNode` — whose id is -1 — are the same fact. Code that checks only the
    // null reports broker -1 as the leader of an offline partition, which is a broker that does not exist
    // and an operator who goes looking for it.
    assertEquals(KafkaTopicAdmin.leaderOf(info(0, null, List(broker1), Nil)), None)
    assertEquals(KafkaTopicAdmin.leaderOf(info(0, Node.noNode, List(broker1), Nil)), None)
    assertEquals(KafkaTopicAdmin.leaderOf(info(0, broker1, List(broker1), List(broker1))).map(_.id), Some(1))
  }

  test("a leaderless partition carries no offsets, even when the bounds map holds some") {
    // The domain refuses a leaderless partition that carries offsets, because KUI never asks one for its
    // offsets and a value there would mean something invented it. This is the adapter side of that rule:
    // a stale entry left in the bounds map from when the partition still had a leader must not be used.
    val key = new TopicPartition(topic.value, 0)
    val bounds = KafkaTopicAdmin.OffsetBounds(Map(key -> 0L), Map(key -> 99L))
    val view = KafkaTopicAdmin.partitionView(topic, info(0, null, List(broker1), Nil), bounds)

    assertEquals(view.map(_.leader), Some(None))
    assertEquals(view.flatMap(_.latestOffset), None)
    assertEquals(view.map(_.isLeaderless), Some(true))
  }

  test("a led partition carries the offsets that were read for it") {
    val key = new TopicPartition(topic.value, 3)
    val bounds = KafkaTopicAdmin.OffsetBounds(Map(key -> 10L), Map(key -> 42L))
    val view = KafkaTopicAdmin.partitionView(topic, info(3, broker1, List(broker1, broker2), List(broker1)), bounds)

    assertEquals(view.flatMap(_.earliestOffset), Some(10L))
    assertEquals(view.flatMap(_.latestOffset), Some(42L))
    assertEquals(view.flatMap(_.messageCount), Some(32L))
    assertEquals(view.map(_.leader), Some(Some(BrokerId.unsafe(1))))
    // Broker 2 is a replica and not in the ISR, which is what "under-replicated" means on the list screen.
    assertEquals(view.map(_.outOfSyncReplicas), Some(1))
  }

  test("a partition the broker described impossibly is dropped, not raised") {
    // An in-sync replica that is not a replica is a shape no healthy broker produces and one the domain
    // refuses. The adapter drops the row: a short partition table is a visible problem an operator can act
    // on, and an exception here would blank the whole topic list over one bad row.
    val impossible = info(0, broker1, List(broker1), List(broker1, broker2))

    assertEquals(KafkaTopicAdmin.partitionView(topic, impossible, KafkaTopicAdmin.OffsetBounds.empty), None)
  }

  test("only the partitions that have a leader are asked for their offsets") {
    val description = new org.apache.kafka.clients.admin.TopicDescription(
      topic.value,
      false,
      List(info(0, broker1, List(broker1), List(broker1)), info(1, null, List(broker1), Nil)).asJava
    )

    assertEquals(
      KafkaTopicAdmin.leadPartitions(description),
      List(new TopicPartition(topic.value, 0))
    )
  }

  // ---------------------------------------------------------------------------- configuration

  test("every Kafka config source has a name, and an unknown one does not become a default") {
    import ConfigEntry.ConfigSource as Kafka

    assertEquals(KafkaTopicAdmin.configSource(Kafka.DYNAMIC_TOPIC_CONFIG), ConfigSource.DynamicTopic)
    assertEquals(KafkaTopicAdmin.configSource(Kafka.STATIC_BROKER_CONFIG), ConfigSource.StaticBroker)
    assertEquals(KafkaTopicAdmin.configSource(Kafka.DEFAULT_CONFIG), ConfigSource.Default)
    assertEquals(KafkaTopicAdmin.configSource(Kafka.UNKNOWN), ConfigSource.Unknown)
    // The one that matters: a source KUI does not recognise must not be reported as `Default`, or the
    // Settings tab would show an operator's deliberate override as "unchanged from the default".
    assert(KafkaTopicAdmin.configSource(Kafka.DYNAMIC_BROKER_LOGGER_CONFIG) != ConfigSource.Default)
  }

  test("the default a setting is compared against comes from the broker's own synonyms") {
    // `TopicConfigEntry.defaultValue` derives the default from the synonym whose source is DEFAULT_CONFIG
    // rather than storing it, so this is the adapter's obligation: carry the synonyms through. Dropping
    // them would make every setting look overridden.
    val raw = new ConfigEntry(
      "retention.ms",
      "604800000",
      ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG,
      false,
      false,
      List(
        KuiTopicTestSynonyms
          .synonym("retention.ms", "604800000", ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG),
        KuiTopicTestSynonyms.synonym("log.retention.ms", "-1", ConfigEntry.ConfigSource.DEFAULT_CONFIG)
      ).asJava,
      ConfigEntry.ConfigType.LONG,
      "how long a record is kept"
    )

    val entry = KafkaTopicAdmin.configEntry(raw)

    assertEquals(entry.name, "retention.ms")
    assertEquals(entry.value, Some("604800000"))
    assertEquals(entry.source, ConfigSource.DynamicTopic)
    assertEquals(entry.defaultValue, Some("-1"))
    assert(entry.isOverridden, "a seven-day retention over a default of -1 is an override")
  }

  test("a sensitive setting reports no value and no default") {
    val raw = new ConfigEntry(
      "ssl.key.password",
      null,
      ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG,
      true,
      false,
      List.empty[ConfigEntry.ConfigSynonym].asJava,
      ConfigEntry.ConfigType.PASSWORD,
      null
    )

    val entry = KafkaTopicAdmin.configEntry(raw)

    assertEquals(entry.value, None)
    assertEquals(entry.defaultValue, None)
    assertEquals(entry.documentation, None)
    assert(!entry.isOverridden, "a value KUI cannot see cannot be compared with a default")
  }
}
