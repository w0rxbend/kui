package kui.kernel.cluster

import scala.concurrent.duration.*

import kui.testkit.KuiSuite

/** The defaults, asserted one by one.
  *
  * Every number here comes from `research/kafka/admin-capabilities.md` §0 or `ARCHITECTURE.md` §9.
  * The table exists so that changing one is a deliberate act that fails a test, rather than a
  * one-character edit nobody reviews.
  */
final class AdminTuningSuite extends KuiSuite {

  test("defaultsMatchTheResearchNumbers") {
    val d = AdminTuning.default

    assertEquals(d.requestTimeout, 30.seconds)
    assertEquals(d.apiTimeout, 60.seconds)
    assertEquals(d.topicChunkSize, 200)
    assertEquals(d.partitionChunkSize, 200)
    assertEquals(d.groupChunkSize, 50)
    assertEquals(d.parallelism, 4)
    assertEquals(d.metadataRefresh, 30.seconds)
    assertEquals(d.capabilityRefresh, 1.hour)
  }

  test("theDefaultsAreValid") {
    assertEquals(AdminTuning.default.validate.map(_.parallelism), Right(4))
  }

  test("validateRejectsNonPositiveChunkSize") {
    val problems = AdminTuning.default.copy(topicChunkSize = 0).validate.left.map(_.toList.map(_.fieldName))

    assertEquals(problems, Left(List("admin.topicChunkSize")))
  }

  test("validateRejectsNonPositiveParallelism") {
    val problems = AdminTuning.default.copy(parallelism = -1).validate.left.map(_.toList.map(_.fieldName))

    assertEquals(problems, Left(List("admin.parallelism")))
  }

  test("validateRejectsRequestTimeoutLargerThanApiTimeout") {
    val problems = AdminTuning.default
      .copy(requestTimeout = 90.seconds)
      .validate
      .left
      .map(_.toList.map(_.fieldName))

    assertEquals(problems, Left(List("admin.requestTimeout")))
  }

  test("validateReportsEveryProblemAtOnce") {
    val problems = AdminTuning.default
      .copy(topicChunkSize = 0, groupChunkSize = 0, parallelism = 0)
      .validate
      .left
      .map(_.toList.map(_.fieldName))

    assertEquals(
      problems,
      Left(List("admin.topicChunkSize", "admin.groupChunkSize", "admin.parallelism"))
    )
  }
}
