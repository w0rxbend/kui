package kui.ui.clusters.brokers

import munit.FunSuite

import kui.contracts.cluster.{LogDirDto, LogDirReplicaDto}
import kui.ui.clusters.dashboard.ClusterFixtures

class LogDirViewSuite extends FunSuite {

  private def dto(
      path: String,
      broker: Int = 1,
      error: Option[String] = None,
      total: Option[Long] = Some(1000L),
      usable: Option[Long] = Some(400L),
      topics: Int = 3,
      partitions: Int = 12,
      replicas: List[LogDirReplicaDto] = Nil
  ): LogDirDto =
    LogDirDto(ClusterFixtures.brokerId(broker), path, error, total, usable, topics, partitions, replicas)

  test("usedIsWhatTheDiskHoldsRatherThanWhatTheBrokerReported") {
    // Kafka reports the disk's size and what is free; what is *on* it is the difference, and that is the
    // number an operator is looking for.
    assertEquals(LogDirView.of(List(dto("/data"))).head.usedBytes, Some(600L))
  }

  test("aDirectoryWithAnErrorKeepsItsPathAndReportsNoSizes") {
    // Kafka attaches the error to the directory and reports nothing else for it, so the page shows the
    // path and the error where the figures would have been.
    val view = LogDirView.of(List(dto("/broken", error = Some("KafkaStorageException"), total = None, usable = None))).head
    assertEquals(view.path, "/broken")
    assertEquals(view.error, Some("KafkaStorageException"))
    assertEquals(view.usedBytes, None)
    assertEquals(view.usedFraction, None)
  }

  test("anUnmeasuredDiskHasNoFractionRatherThanAnEmptyBar") {
    // A broker older than 3.3 reports neither figure. An empty bar would read as a disk with room on it,
    // which is the opposite of a safe thing to leave on an operator's screen.
    assertEquals(LogDirView.of(List(dto("/data", total = None, usable = None))).head.usedFraction, None)
  }

  test("aZeroSizedDiskProducesNoFractionAndNoNaN") {
    val fraction = LogDirView.of(List(dto("/data", total = Some(0L), usable = Some(0L)))).head.usedFraction
    assertEquals(fraction, None)
  }

  test("theFractionIsClampedToTheTrack") {
    // A broker that reports more free space than the disk has is nonsense, and a bar of negative width is
    // a rendering bug; clamping makes it merely "empty".
    assertEquals(LogDirView.of(List(dto("/data", total = Some(100L), usable = Some(500L)))).head.usedFraction, Some(0.0))
  }

  test("onlyTheNamedBrokersDirectoriesAreShown") {
    // The endpoint answers for a whole cluster when no broker is named. Filtering here as well means a
    // response carrying more than was asked for cannot put another machine's disks under this heading.
    val dirs = List(dto("/one", broker = 1), dto("/two", broker = 2))
    assertEquals(LogDirView.forBroker(dirs, ClusterFixtures.brokerId(1)).map(_.path), List("/one"))
  }

  test("directoriesKeepTheOrderTheBrokerReportedThemIn") {
    assertEquals(LogDirView.of(List(dto("/b"), dto("/a"))).map(_.path), List("/b", "/a"))
  }
}
