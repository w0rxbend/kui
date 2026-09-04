package kui.config.store

import scala.concurrent.duration.{Duration, DurationInt}
import scala.jdk.CollectionConverters.*

import java.time.Duration as JavaDuration
import java.util.Properties

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import io.circe.Json
import munit.catseffect.IOFixture
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import kui.config.{StoreConfig, StoreKafkaConfig}
import kui.kernel.cluster.{BootstrapServers, ClusterSecurity}
import kui.kernel.error.ErrorCode
import kui.kernel.PositiveInt
import kui.testkit.KuiIOSuite
import kui.testkit.kafka.{KafkaFixture, KafkaTopology, RunningBroker}

/** The metadata store against a real Kafka broker: STORE-009, and four of M1's exit criteria.
  *
  * Every claim below was previously proved against a driven in-memory log — `StoreStateSuite` for the fold,
  * `WriteWaiterSuite` for read-your-writes, `StoreReplaySuite` for the bounded replay. Those are the right
  * place for the *logic*, and they stay. What none of them can answer is whether the thing works when the
  * log is a compacted Kafka topic, a produce is a real network round trip and a second replica is a second
  * consumer: STORE-006's own deviation note said the answer was "yes against a driven log, not yet against a
  * broker". This is the broker.
  *
  * The criteria checked here, in the roadmap's own words:
  *
  *   - "the service creates `__kui_config` and `__kui_files`, replays them at startup and serves clusters
  *     from the store";
  *   - "a pre-existing `__kui_config` with `cleanup.policy=delete` fails startup with a message naming the
  *     topic, the setting, the expected value and the found value";
  *   - "two cluster-service replicas writing the same cluster key concurrently: one succeeds, the other gets
  *     `KUI-CONFIG-VERSION-CONFLICT`; both converge on the winner's record";
  *   - "a write returns 200 only after the writer has read its own record back from the log tail";
  *   - "secret fields of a stored cluster are unreadable in the raw topic record: a console-consumer dump of
  *     `__kui_config` contains no plaintext password and no JAAS string".
  *
  * The last of those is the reason this suite reads the topic with a plain `KafkaConsumer` and raw byte
  * deserializers rather than through anything in `libs/config`. Asking KUI whether KUI encrypted something
  * is not a check; the bytes on the partition are the only evidence that means anything, and they are what a
  * `kafka-console-consumer` would print.
  */
final class KafkaConfigStoreLiveSuite extends KuiIOSuite {

  override val munitIOTimeout: Duration = 5.minutes

  given LoggerFactory[IO] = NoOpFactory[IO]

  /** One broker for the whole suite. Each test uses a topic prefix of its own, so they cannot see each
    * other's records and none of them has to pay for another container.
    */
  private val broker: IOFixture[RunningBroker] =
    ResourceSuiteLocalFixture(
      "kafka-store",
      Resource.eval(IO(requireDocker())) >> KafkaFixture[IO](KafkaTopology.Plaintext)
    )

  override def munitFixtures = List(broker)

  private def requireDocker(): Unit =
    assume(
      KafkaFixture.dockerAvailable,
      "Docker is not available, so the metadata store was not exercised against a broker and " +
        "M1's store criteria are UNVERIFIED"
    )

  // A 32-byte AES key. Fixed rather than random so that a failure is reproducible, and harmless because
  // the only thing it ever protects is a container that is deleted when the suite ends.
  private val keyMaterial = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

  private def crypto: FieldCrypto[IO] = {
    val key = EncryptionKey.fromBase64("k1", keyMaterial).fold(e => fail(e.message), identity)
    FieldCrypto[IO](EncryptionKeyring.of(List(key), "k1").fold(e => fail(e.message), identity))
  }

  /** A single-broker store configuration. Replication factor and in-sync replicas are one, which is the
    * only thing a one-broker cluster can satisfy; the shipped defaults are three and two, and
    * `StoreConfigSuite` asserts those separately so this suite cannot erode them.
    */
  private def storeConfig(prefix: String): StoreConfig =
    StoreConfig.Default.copy(
      topicPrefix = prefix,
      replicationFactor = 1,
      minInSyncReplicas = PositiveInt.unsafe(1),
      replayTimeout = 30.seconds,
      writeTimeout = 20.seconds
    )

  private def kafkaConfig: StoreKafkaConfig =
    StoreKafkaConfig(
      bootstrapServers = BootstrapServers.unsafe(broker().bootstrapServers),
      security = ClusterSecurity.Plaintext,
      properties = Map.empty
    )

  private def bootstrapped(config: StoreConfig): IO[Unit] =
    StoreClients
      .admin[IO](kafkaConfig, "kui-store-live-bootstrap")
      .use(admin =>
        StoreBootstrap
          .ensureTopics[IO](admin, StoreTopics.of(config), config.replicationFactor, broker().bootstrapServers)
      )
      .flatMap {
        case Right(()) => IO.unit
        case Left(error) => IO.raiseError(new AssertionError(s"the store did not bootstrap: $error"))
      }

  private def store(config: StoreConfig, clientId: String): Resource[IO, ConfigStore[IO]] =
    Resource.eval(bootstrapped(config)) >> KafkaConfigStore.resource[IO](config, kafkaConfig, crypto, clientId)

  private def clusterKey(id: String): StoreKey =
    StoreKey.cluster(id).fold(e => fail(e.message), identity)

  /** A cluster record with a SASL password in it, written the way KUI writes one: the secret marked, so the
    * crypto layer is the thing that has to notice it.
    */
  private def clusterWith(password: String, name: String = "Production"): Json =
    Json.obj(
      "name" -> Json.fromString(name),
      "bootstrapServers" -> Json.fromString("broker-1:9092"),
      "security" -> Json.obj(
        "protocol" -> Json.fromString("SASL_PLAINTEXT"),
        "mechanism" -> Json.fromString("SCRAM-SHA-512"),
        "username" -> Json.fromString("kui"),
        "password" -> Json.obj(SecretJson.PlaintextField -> Json.fromString(password))
      )
    )

  /** Every record on the topic's single partition, as raw bytes, read the way `kafka-console-consumer`
    * reads them: no KUI code between the partition and the assertion.
    */
  private def dump(topic: String): IO[List[(String, String)]] =
    IO.blocking {
      val properties = new Properties()
      properties.put("bootstrap.servers", broker().bootstrapServers)
      properties.put("key.deserializer", classOf[StringDeserializer].getName)
      properties.put("value.deserializer", classOf[ByteArrayDeserializer].getName)
      properties.put("auto.offset.reset", "earliest")
      properties.put("enable.auto.commit", "false")

      val consumer = new KafkaConsumer[String, Array[Byte]](properties)
      try {
        val partition = new TopicPartition(topic, 0)
        consumer.assign(List(partition).asJava)
        consumer.seekToBeginning(List(partition).asJava)

        val end = consumer.endOffsets(List(partition).asJava).get(partition).longValue()
        val collected = List.newBuilder[(String, String)]
        var seen = 0L
        var attempts = 0

        while seen < end && attempts < 30 do {
          val batch = consumer.poll(JavaDuration.ofSeconds(1))
          batch.records(partition).asScala.foreach { record =>
            seen += 1
            collected += record.key() -> Option(record.value())
              .map(new String(_, java.nio.charset.StandardCharsets.UTF_8))
              .getOrElse("<tombstone>")
          }
          attempts += 1
        }

        collected.result()
      } finally consumer.close()
    }

  test("theStoreCreatesItsTopicsReplaysThemAndServesWhatWasWritten") {
    val config = storeConfig("livecreate_")

    for {
      written <- store(config, "kui-live-writer").use { first =>
        first.put(clusterKey("prod-eu"), clusterWith("s3cret"), baseVersion = None, updatedBy = "suite")
      }
      record = written.fold(error => fail(s"the write failed: ${error.message}"), identity)
      // A *second* store over the same topics, so what is asserted is what replay reconstructed from the
      // log rather than what the first process happened to have in memory.
      replayed <- store(config, "kui-live-reader").use(_.get(clusterKey("prod-eu")))
    } yield {
      assertEquals(record.version, 1L, "the first write of a key is version 1")
      assertEquals(
        replayed.map(_.version),
        Some(1L),
        "a store that replayed the topic from the beginning must have the record"
      )
      assertEquals(
        replayed.flatMap(_.payload.hcursor.get[String]("name").toOption),
        Some("Production"),
        "and it must have the payload, decrypted"
      )
    }
  }

  test("aStoredSecretIsUnreadableInTheRawTopicRecord") {
    // M1's security criterion, and the one assertion in this repository that is worth more than every unit
    // test of the crypto put together: the bytes actually on the partition.
    val config = storeConfig("livesecret_")
    val password = "correct-horse-battery-staple"

    for {
      _ <- store(config, "kui-live-secret").use(
        _.put(clusterKey("prod-eu"), clusterWith(password), baseVersion = None, updatedBy = "suite")
      )
      records <- dump(config.configTopic)
    } yield {
      assertEquals(records.length, 1, "one write, one record")
      val (key, value) = records.head

      assertEquals(key, "cluster/prod-eu", "the key is readable on purpose: compaction needs it")
      assert(!value.contains(password), s"the password is on the wire in plaintext: $value")
      assert(
        !value.contains(SecretJson.PlaintextField),
        s"an unencrypted secret marker reached the topic: $value"
      )
      assert(
        !value.toLowerCase.contains("jaas") && !value.contains("LoginModule"),
        s"a JAAS string reached the topic: $value"
      )
      assert(
        value.contains(SecretJson.CipherField),
        s"the record should carry a ciphertext marker where the password was: $value"
      )
      // The record is otherwise perfectly readable, which is the design: only the secret fields are
      // encrypted, so an operator can still see which cluster a record is for.
      assert(value.contains("broker-1:9092"), s"non-secret fields stay legible: $value")
    }
  }

  test("twoReplicasWritingTheSameKeyProduceOneWinnerAndOneVersionConflict") {
    // Two independent stores over one topic, which is exactly two cluster-service replicas. Both are told
    // the key is at version 1, so one of them is wrong by the time it produces.
    val config = storeConfig("liveconflict_")
    val key = clusterKey("contested")

    (store(config, "kui-replica-a"), store(config, "kui-replica-b")).tupled.use { (a, b) =>
      for {
        seeded <- a.put(key, clusterWith("first", name = "Seed"), baseVersion = None, updatedBy = "suite")
        base = seeded.fold(error => fail(s"the seed write failed: ${error.message}"), identity).version
        // `parTupled`, not two sequential writes: the race is the point, and a sequential pair would pass
        // even if the store serialised nothing.
        outcomes <- (
          a.put(key, clusterWith("from-a", name = "A"), Some(base), "replica-a"),
          b.put(key, clusterWith("from-b", name = "B"), Some(base), "replica-b")
        ).parTupled
        (fromA, fromB) = outcomes
        // Both replicas' view of the key, after the dust settles.
        settledA <- a.get(key)
        settledB <- b.get(key)
      } yield {
        val winners = List(fromA, fromB).collect { case Right(record) => record }
        val losers = List(fromA, fromB).collect { case Left(error) => error }

        assertEquals(winners.length, 1, s"exactly one write may win: $fromA / $fromB")
        assertEquals(losers.length, 1, s"exactly one write must lose: $fromA / $fromB")
        assertEquals(
          losers.head.code,
          ErrorCode.ConfigVersionConflict,
          s"a lost race is a version conflict and not something the caller has to guess about: ${losers.head}"
        )

        val winner = winners.head
        assertEquals(winner.version, base + 1L, "the winner advanced the version by exactly one")

        // Convergence, which is the half of the criterion that a conflict code alone does not give you.
        assertEquals(settledA.map(_.version), Some(winner.version), "replica a converged on the winner")
        assertEquals(settledB.map(_.version), Some(winner.version), "replica b converged on the winner")
        assertEquals(
          settledA.map(_.payload),
          settledB.map(_.payload),
          "both replicas hold the same record, byte for byte"
        )
      }
    }
  }

  test("aSuccessfulWriteHasAlreadyBeenReadBackFromTheLog") {
    // ADR-042 §3's read-your-writes contract, and the reason the API can answer 200 rather than 202. The
    // assertion is that `get` needs no retry, no sleep and no polling: if `put` returned before its record
    // came back round the log, this is where that would show up as an occasional `None`.
    val config = storeConfig("liveryw_")

    store(config, "kui-live-ryw").use { configStore =>
      (1 to 5).toList
        .traverse { attempt =>
          val key = clusterKey(s"ryw-$attempt")

          for {
            written <- configStore.put(key, clusterWith(s"p$attempt"), None, "suite")
            immediately <- configStore.get(key)
          } yield {
            val version = written.fold(error => fail(s"write $attempt failed: ${error.message}"), _.version)
            assertEquals(
              immediately.map(_.version),
              Some(version),
              s"write $attempt returned before its own record was readable"
            )
          }
        }
        .void
    }
  }

  test("aPreExistingTopicWithTheWrongCleanupPolicyRefusesToStart") {
    // The criterion names four things the message must carry, and each is here for a reason: the topic
    // because a deployment has several, the setting because an operator has to know which one to change,
    // and the expected and found values because "cleanup.policy is wrong" sends somebody to read
    // documentation while "expected compact, found delete" is an instruction.
    val config = storeConfig("livewrong_")

    for {
      _ <- StoreClients
        .admin[IO](kafkaConfig, "kui-live-wrongtopic")
        .use { admin =>
          admin.createTopic(
            new org.apache.kafka.clients.admin.NewTopic(config.configTopic, 1, 1.toShort)
              .configs(Map("cleanup.policy" -> "delete").asJava)
          )
        }
      outcome <- bootstrapped(config).attempt
    } yield {
      val message = outcome match {
        case Left(error) => error.getMessage
        case Right(()) => fail("a topic with cleanup.policy=delete must not be accepted")
      }

      assert(message.contains(config.configTopic), s"the message must name the topic: $message")
      assert(message.contains("cleanup.policy"), s"the message must name the setting: $message")
      assert(message.contains("compact"), s"the message must say what was expected: $message")
      assert(message.contains("delete"), s"the message must say what was found: $message")
    }
  }
}
