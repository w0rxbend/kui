package kui.cluster.application

import munit.FunSuite

import kui.cluster.domain.{ClusterRef, LogDirError, TopologyFixtures}
import kui.kernel.{BrokerId, ClusterId}

/** The two rules the broker views keep about numbers they may not have.
  *
  * `BrokerLogDirs.totalBytes` and `usableBytes` are folds over what each directory reported, and a directory
  * that reported nothing must take the whole figure with it: replacing `if reported.isEmpty then None` with
  * `Some(reported.sum)` left `./mill libs.__.test + services.*` at 2633/2633 while turning a disk KUI could
  * not measure into a disk of zero bytes — which the storage meter draws as an empty bar rather than as an em
  * dash, and which reads as a fact.
  *
  * The largest-first ordering of `PartitionSizes` is asserted in `BrokerDetailUseCaseSuite`, where the use
  * case builds one; what is asserted here is the arithmetic underneath, over directories a use case would
  * have to be unlucky to produce.
  */
final class BrokerViewsSuite extends FunSuite {

  private val cluster: ClusterRef = ClusterRef(ClusterId.unsafe("local"), "Local")
  private val broker: BrokerId = BrokerId.unsafe(1)

  private def dirs(
      sizes: List[(String, Option[Long], Option[Long])],
      error: Option[LogDirError] = None
  ): BrokerLogDirs =
    BrokerLogDirs(
      cluster = cluster,
      broker = broker,
      dirs = sizes.map { case (path, total, usable) =>
        TopologyFixtures.logDir(path, Nil, error, total, usable)
      },
      freshness = SnapshotFreshness.Loading
    )

  test("a broker whose disks reported no size at all reports no size, and never zero") {
    val unmeasured = dirs(List(("/mnt/one", None, None), ("/mnt/two", None, None)))

    assertEquals(unmeasured.totalBytes, None)
    assertEquals(unmeasured.usableBytes, None)
  }

  test("a broker whose disks reported sizes reports their sum") {
    // The positive half, so that the case above is not satisfied by a fold that always refuses.
    val measured = dirs(
      List(("/mnt/one", Some(1_000L), Some(400L)), ("/mnt/two", Some(2_000L), Some(600L)))
    )

    assertEquals(measured.totalBytes, Some(3_000L))
    assertEquals(measured.usableBytes, Some(1_000L))
  }

  test("one directory that reported nothing is left out of the sum rather than counted as zero") {
    // Kafka reports a failed directory with no sizes on it. Summing the ones that answered is the honest
    // arithmetic — the total is over the disks KUI could read — and it is `offline` beside it that says
    // the figure is not the whole broker.
    val mixed = dirs(List(("/mnt/one", Some(1_000L), Some(400L)), ("/mnt/broken", None, None)))

    assertEquals(mixed.totalBytes, Some(1_000L))
    assertEquals(mixed.usableBytes, Some(400L))
  }

  test("a broker with no directories at all reports no size") {
    val none = dirs(Nil)

    assertEquals(none.totalBytes, None)
    assertEquals(none.usableBytes, None)
    assertEquals(none.offline, Nil)
  }

  test("an offline directory is listed as offline and still counted among the broker's disks") {
    val broken = dirs(List(("/mnt/broken", Some(1_000L), Some(0L))), Some(LogDirError.Offline))

    assertEquals(broken.offline.map(_.path.value), List("/mnt/broken"))
    // A usable figure of zero that the broker *reported* is a measurement, and the honest one: the disk
    // is full. It is the absent figure above that must not become this number.
    assertEquals(broken.usableBytes, Some(0L))
  }
}
