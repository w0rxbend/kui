package kui.cluster.domain

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.testkit.KuiSuite

/** What a log directory may and may not hold.
  *
  * Tests 4 and 5 are deliberately about the same data and assert opposite things: during a replica move the
  * disk really is holding two copies of the partition, so `usedByKafkaBytes` counts both while
  * `currentReplicas` counts one. Asserting both is what pins the distinction.
  */
final class LogDirSuite extends KuiSuite {

  import TopologyFixtures.*

  private val path: LogDirPath = LogDirPath.unsafe("/var/lib/kafka/data")

  private def build(
      replicas: List[ReplicaInfo] = Nil,
      total: Option[Long] = Some(100L),
      usable: Option[Long] = Some(10L)
  ) = LogDir.from(path, None, total, usable, replicas)

  test("rejectsNegativeSize") {
    assert(build(replicas = List(replica("orders", 0, -1L))).isLeft)
  }

  test("rejectsNegativeOffsetLag") {
    val bad = replica("orders", 0, 10L).copy(offsetLag = -5L)

    assert(build(replicas = List(bad)).isLeft)
  }

  test("rejectsUsableAboveTotal") {
    assert(build(total = Some(10L), usable = Some(11L)).isLeft)
    assert(build(total = Some(10L), usable = Some(10L)).isRight)
  }

  test("futureReplicasAreExcludedFromCurrentReplicas") {
    val dir = logDir(
      "/data",
      List(replica("orders", 0, 100L), replica("orders", 0, 100L, isFuture = true))
    )

    assertEquals(dir.currentReplicas.size, 1)
  }

  test("usedByKafkaSumsEveryReplicaIncludingFuture") {
    val dir = logDir(
      "/data",
      List(replica("orders", 0, 100L), replica("orders", 0, 100L, isFuture = true))
    )

    assertEquals(dir.usedByKafkaBytes, 200L)
  }

  test("offlineDirIsNotHealthyAndKeepsItsReplicas") {
    val dir = logDir("/data", List(replica("orders", 0, 100L)), error = Some(LogDirError.Offline))

    assert(!dir.isHealthy)
    assertEquals(dir.replicas.size, 1, "an offline directory still lists what it held")
  }

  property("otherErrorCarriesAClassNameAndNoMessage") {
    // The only constructor takes a class name, and anything that is not one is refused rather than
    // rendered: an exception message routinely carries a path and sometimes a host.
    forAll(Gen.oneOf("/var/lib/kafka: no space left", "root@host: broken", "a.b.C")) { raw =>
      val rendered = LogDirError.other(raw) match {
        case LogDirError.Other(name) => name
        case LogDirError.Offline => "offline"
      }

      assert(
        rendered.matches("^[A-Za-z0-9.$]+$"),
        s"'$rendered' must not be able to carry a message"
      )
    }
  }

  test("totalBytesIsNoneWhenNoDirectoryReportedOne") {
    // The pre-3.3 broker case: KIP-827's size fields simply are not there.
    val load = BrokerLoad(1, None, None, List(logDir("/data", totalBytes = None, usableBytes = None)))

    assertEquals(load.totalBytes, None)
    assertEquals(load.usableBytes, None)
  }
}
