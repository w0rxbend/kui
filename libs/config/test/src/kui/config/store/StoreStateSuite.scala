package kui.config.store

import java.time.Instant

import io.circe.Json
import org.scalacheck.{Gen, Prop}

import kui.testkit.KuiSuite

/** Where the store's design is actually tested.
  *
  * The version rule — a record is applied only when its version is exactly the next one for its key — is
  * what makes concurrent writes from several KUI replicas safe with no lock anywhere. Every replica folds
  * the same ordered partition through this same function, so every replica reaches the same answer about
  * who won a race without any of them talking to each other. The integration suite proves that end to end
  * against a broker; this suite proves it as a property, one layer down, where a counter-example is
  * readable.
  */
final class StoreStateSuite extends KuiSuite {

  private val key = StoreKey(StoreSection.Cluster, "prod-eu")
  private val other = StoreKey(StoreSection.Cluster, "aardvark")
  private val at = Instant.parse("2026-09-03T10:00:00Z")

  private def record(k: StoreKey, version: Long, by: String = "replica-a", deleted: Boolean = false): StoreRecord =
    StoreRecord(1, k, version, at, by, deleted, Json.obj("writer" -> Json.fromString(by)))

  private def fold(state: StoreState, entries: List[(StoreRecord, Long)]): StoreState =
    entries.foldLeft(state)((current, entry) => current.apply(entry._1, entry._2)._1)

  test("acceptsTheNextVersion") {
    val (afterFirst, first) = StoreState.empty.apply(record(key, 1L), 0L)
    assertEquals(first, StoreApplied.Accepted(StoreChange.Upserted(record(key, 1L))))
    assertEquals(afterFirst.get(key).map(_.version), Some(1L))
    val (_, second) = afterFirst.apply(record(key, 2L), 1L)
    assert(second.isInstanceOf[StoreApplied.Accepted])
  }

  test("ignoresAStaleVersion") {
    // The lost-race rule. Two replicas both read version 2 and both produced version 3; the one whose
    // record landed second in the partition lost, and every replica reaches that conclusion alone.
    val state = fold(StoreState.empty, List(record(key, 1L) -> 0L, record(key, 2L) -> 1L, record(key, 3L, "replica-a") -> 2L))
    val (after, applied) = state.apply(record(key, 3L, "replica-b"), 3L)
    assertEquals(applied, StoreApplied.Ignored(key, 3L, 4L))
    assertEquals(after.get(key).flatMap(_.payload.hcursor.get[String]("writer").toOption), Some("replica-a"))
  }

  test("ignoresAFutureVersion") {
    // A gap means records were lost or the log was edited by hand. Accepting it would let a writer skip
    // the conflict check entirely by inventing a large version number.
    val state = fold(StoreState.empty, List(record(key, 1L) -> 0L, record(key, 2L) -> 1L))
    assertEquals(state.apply(record(key, 9L), 2L)._2, StoreApplied.Ignored(key, 9L, 3L))
  }

  property("twoWritersOnOneKeyConvergeWhicheverOrderIsReplayed") {
    // Every replica reads the same partition, so "any interleaving" means any single log order — and for
    // one fixed order, every replica must reach an identical state. This is the milestone's "both
    // converge on the winner's record" criterion as a property.
    val logs: Gen[List[(StoreRecord, Long)]] =
      Gen.listOfN(8, Gen.oneOf("replica-a", "replica-b")).flatMap { writers =>
        Gen.listOfN(8, Gen.choose(1L, 5L)).map { versions =>
          writers.zip(versions).zipWithIndex.map { case ((writer, version), index) =>
            record(key, version, writer) -> index.toLong
          }
        }
      }
    Prop.forAll(logs) { log =>
      val replicaOne = fold(StoreState.empty, log)
      val replicaTwo = fold(StoreState.empty, log)
      replicaOne == replicaTwo && replicaOne.get(key).forall(_.version == replicaOne.nextVersion(key) - 1L)
    }
  }

  test("tombstoneRemovesTheKeyAndKeepsTheVersion") {
    val state = fold(StoreState.empty, List(record(key, 1L) -> 0L, record(key, 2L, deleted = true) -> 1L))
    assertEquals(state.get(key), None)
    // The version keeps counting. If a re-create restarted at 1 it would collide with the version-1
    // record still sitting in the log, and one of them would be silently ignored.
    assertEquals(state.nextVersion(key), 3L)
    assert(state.apply(record(key, 3L), 2L)._2.isInstanceOf[StoreApplied.Accepted])
  }

  test("listSkipsTombstonesAndSortsByKey") {
    val state = fold(
      StoreState.empty,
      List(record(key, 1L) -> 0L, record(other, 1L) -> 1L, StoreRecord.tombstone(key, 2L, "x", at) -> 2L)
    )
    assertEquals(state.list(StoreSection.Cluster).map(_.key.render), List("cluster/aardvark"))
    assertEquals(state.list(StoreSection.Settings), Nil)
  }

  test("unreadableRecordDoesNotAdvanceTheVersion") {
    // KUI cannot know what version an unreadable record put the key at, and guessing would make the next
    // legitimate write look like a lost race.
    val state = fold(StoreState.empty, List(record(key, 1L) -> 0L))
    val (after, applied) = state.markUnreadable(key, 1L, "the field 'password' could not be decrypted")
    assertEquals(applied, StoreApplied.Unreadable(key, "the field 'password' could not be decrypted"))
    assertEquals(after.nextVersion(key), 2L)
    assertEquals(after.unreadableKeys, List(key))
    // A later readable record for the same key clears the mark.
    assertEquals(after.apply(record(key, 2L), 2L)._1.unreadableKeys, Nil)
  }

  property("lastAppliedOffsetIsMonotonic") {
    Prop.forAll(Gen.listOf(Gen.choose(0L, 1000L))) { offsets =>
      val states = offsets.scanLeft(StoreState.empty)((state, offset) => state.apply(record(key, 1L), offset)._1)
      states.map(_.lastAppliedOffset).sliding(2).forall {
        case List(before, after) => after >= before
        case _ => true
      }
    }
  }

  property("applyIsPureAndTotal") {
    val anyRecord: Gen[StoreRecord] = for {
      section <- Gen.oneOf(StoreSection.Cluster, StoreSection.Settings, StoreSection.Other("connect"))
      id <- Gen.oneOf("a", "prod-eu", "x9")
      version <- Gen.choose(-5L, 20L)
      deleted <- Gen.oneOf(true, false)
    } yield StoreRecord(1, StoreKey(section, id), version, at, "gen", deleted, Json.obj())

    Prop.forAll(Gen.listOf(anyRecord)) { records =>
      val finalState = records.zipWithIndex.foldLeft(StoreState.empty) { case (state, (record, index)) =>
        state.apply(record, index.toLong)._1
      }
      // Total: nothing threw, and every stored record is at the version the state agrees it is at.
      finalState.records.forall((k, r) => finalState.nextVersion(k) == r.version + 1L)
    }
  }

  test("theEmptyStateReportsNoLog") {
    assertEquals(StoreState.empty.lastAppliedOffset, -1L)
    assertEquals(StoreState.empty.nextVersion(key), 1L)
    assertEquals(StoreState.empty.get(key), None)
  }
}
