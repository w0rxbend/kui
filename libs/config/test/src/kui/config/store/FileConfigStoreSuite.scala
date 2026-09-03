package kui.config.store

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import cats.effect.IO
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import kui.testkit.KuiIOSuite

/** That KUI runs with no Kafka store at all: a mounted directory is the whole truth, reads work, writes
  * say `NotConfigured` rather than failing, and one unreadable file costs one key instead of the store.
  *
  * This is the milestone's "with `kui.store.kafka.*` unset, everything else still passes" criterion, and
  * it is also why the port ships with two implementations before it has any consumers — a port with one
  * implementation is a port that has quietly assumed Kafka.
  */
final class FileConfigStoreSuite extends KuiIOSuite {

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def storeAt(root: Path): IO[ConfigStore[IO]] = FileConfigStore.resource[IO](root).allocated.map(_._1)

  private def defaultStore: IO[ConfigStore[IO]] = storeAt(StoreFixtures.fileStoreRoot())

  private val localKey = StoreKey(StoreSection.Cluster, "local")
  private val brokenKey = StoreKey(StoreSection.Cluster, "broken")

  test("readsEveryEnvelopeInTheTree") {
    for {
      store <- defaultStore
      local <- store.get(localKey)
      settings <- store.get(StoreKey.SettingsGlobal)
    } yield {
      assertEquals(local.map(_.version), Some(1L))
      assertEquals(settings.map(_.version), Some(4L))
      assertEquals(settings.flatMap(_.payload.hcursor.get[String]("theme").toOption), Some("dark"))
    }
  }

  test("listReturnsKeyOrderAndSkipsTombstones") {
    val root = StoreFixtures.fileStoreRoot()
    StoreFixtures.writeInto(
      root,
      "cluster/aardvark.json",
      """{"envelopeVersion":1,"key":"cluster/aardvark","version":2,"updatedAt":"2026-09-03T09:00:00Z","updatedBy":"gitops","deleted":false,"payload":{}}"""
    )
    StoreFixtures.writeInto(
      root,
      "cluster/retired.json",
      """{"envelopeVersion":1,"key":"cluster/retired","version":9,"updatedAt":"2026-09-03T09:00:00Z","updatedBy":"gitops","deleted":true,"payload":{}}"""
    )
    for {
      store <- storeAt(root)
      clusters <- store.list(StoreSection.Cluster)
      settings <- store.list(StoreSection.Settings)
    } yield {
      assertEquals(clusters.map(_.key.render), List("cluster/aardvark", "cluster/local"))
      assertEquals(settings.map(_.key.render), List("settings/global"))
    }
  }

  test("missingRootIsAnEmptyStore") {
    // "No directory" and "an empty directory" are the same statement about a deployment, so neither is
    // an error.
    for {
      store <- storeAt(Path.of("/nonexistent/kui/store"))
      clusters <- store.list(StoreSection.Cluster)
      health <- store.health
    } yield {
      assertEquals(clusters, Nil)
      assert(!health.writable)
      assertEquals(health.unreadableKeys, Nil)
    }
  }

  test("brokenFileIsSkippedAndRecorded") {
    for {
      store <- defaultStore
      broken <- store.get(brokenKey)
      local <- store.get(localKey)
      health <- store.health
    } yield {
      assertEquals(broken, None)
      assert(local.isDefined, "one unreadable file must not cost the other keys")
      assertEquals(health.unreadableKeys, List(brokenKey))
    }
  }

  test("fileWhosePathDisagreesWithItsKeyIsSkipped") {
    val root = StoreFixtures.fileStoreRoot(List("settings/global.json"))
    StoreFixtures.writeInto(
      root,
      "cluster/impostor.json",
      """{"envelopeVersion":1,"key":"cluster/somewhere-else","version":1,"updatedAt":"2026-09-03T09:00:00Z","updatedBy":"gitops","deleted":false,"payload":{}}"""
    )
    for {
      store <- storeAt(root)
      clusters <- store.list(StoreSection.Cluster)
      health <- store.health
    } yield {
      assertEquals(clusters, Nil)
      assertEquals(health.unreadableKeys, List(StoreKey(StoreSection.Cluster, "impostor")))
    }
  }

  test("unsupportedEnvelopeVersionIsSkippedNotFatal") {
    val root = StoreFixtures.fileStoreRoot(List("settings/global.json"))
    StoreFixtures.writeInto(
      root,
      "cluster/future.json",
      """{"envelopeVersion":99,"key":"cluster/future","version":1,"updatedAt":"2026-09-03T09:00:00Z","updatedBy":"gitops","deleted":false,"payload":{}}"""
    )
    for {
      store <- storeAt(root)
      future <- store.get(StoreKey(StoreSection.Cluster, "future"))
      settings <- store.get(StoreKey.SettingsGlobal)
      health <- store.health
    } yield {
      assertEquals(future, None)
      assert(settings.isDefined)
      assertEquals(health.unreadableKeys, List(StoreKey(StoreSection.Cluster, "future")))
    }
  }

  test("writesReportNotConfigured") {
    for {
      store <- defaultStore
      written <- store.put(localKey, io.circe.Json.obj(), Some(1L), "test")
      deleted <- store.delete(localKey, 1L, "test")
    } yield {
      assertEquals(written.left.map(_.code.wire), Left("KUI-STORE-NOT-CONFIGURED"))
      assertEquals(deleted.left.map(_.code.wire), Left("KUI-STORE-NOT-CONFIGURED"))
    }
  }

  test("changesIsEmptyAndDoesNotTerminateTheConsumer") {
    // A `changes.foreach` written against the Kafka adapter runs for the life of the process. It must
    // behave identically here rather than falling out of its loop the moment the file adapter is used,
    // so the stream is empty *and* never completes: a timeout is the pass condition.
    defaultStore.flatMap(store => store.changes.take(1).compile.drain.timeout(200.millis).attempt).map {
      case Left(_: java.util.concurrent.TimeoutException) => ()
      case other => fail(s"expected the changes stream to hang rather than complete, got $other")
    }
  }

  test("secretMarkerIsReturnedAsAMarker") {
    // Decision 1: the file adapter does not decrypt, because its whole point is running with no key.
    defaultStore.flatMap(_.get(localKey)).map { record =>
      val payload = record.map(_.payload).getOrElse(fail("cluster/local should have been read"))
      assertEquals(SecretJson.plaintextPaths(payload), List("security.password"))
    }
  }

  test("cipherNodeIsLeftAloneAndRecordedAsUnreadable") {
    val root = StoreFixtures.fileStoreRoot(List("settings/global.json"))
    StoreFixtures.writeInto(
      root,
      "cluster/encrypted.json",
      """{"envelopeVersion":1,"key":"cluster/encrypted","version":1,"updatedAt":"2026-09-03T09:00:00Z","updatedBy":"gitops","deleted":false,"payload":{"password":{"$enc":{"alg":"AES-256-GCM","keyId":"k1","iv":"AAAA","ct":"BBBB"}}}}"""
    )
    for {
      store <- storeAt(root)
      encrypted <- store.get(StoreKey(StoreSection.Cluster, "encrypted"))
      health <- store.health
    } yield {
      // Handing a caller a ciphertext as if it were a password defers the failure to a connection
      // attempt that cannot explain itself, so the record is withheld and named instead.
      assertEquals(encrypted, None)
      assertEquals(health.unreadableKeys, List(StoreKey(StoreSection.Cluster, "encrypted")))
    }
  }

  test("emptyStoreSatisfiesTheSameContract") {
    val store = ConfigStore.empty[IO]
    for {
      clusters <- store.list(StoreSection.Cluster)
      got <- store.get(localKey)
      written <- store.put(localKey, io.circe.Json.obj(), None, "test")
      deleted <- store.delete(localKey, 1L, "test")
      health <- store.health
      hangs <- store.changes.take(1).compile.drain.timeout(200.millis).attempt
    } yield {
      assertEquals(clusters, Nil)
      assertEquals(got, None)
      assertEquals(written.left.map(_.code.wire), Left("KUI-STORE-NOT-CONFIGURED"))
      assertEquals(deleted.left.map(_.code.wire), Left("KUI-STORE-NOT-CONFIGURED"))
      assert(!health.writable)
      assert(hangs.isLeft, "the empty store's changes stream must not complete either")
    }
  }

  test("aDirectoryThatIsNotReadableJsonNeverStopsStartup") {
    val root = StoreFixtures.fileStoreRoot()
    val _ = Files.createDirectories(root.resolve("cluster/nested/deeper"))
    storeAt(root).flatMap(_.list(StoreSection.Cluster)).map(records => assertEquals(records.map(_.key.render), List("cluster/local")))
  }
}
