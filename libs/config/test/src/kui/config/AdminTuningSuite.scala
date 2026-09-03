package kui.config

import java.nio.file.Path

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.cluster.AdminTuning
import kui.testkit.KuiSuite

/** The five per-cluster admin knobs: their defaults, their bounds, and the one rule that spans two of them.
  *
  * The knobs exist because one cluster in a deployment is sometimes not like the others — ten thousand
  * topics, or a broker on the other side of an ocean — and raising a timeout for that one must not change
  * how KUI talks to the healthy ones.
  */
final class AdminTuningSuite extends KuiSuite {

  private def load(
      files: List[Path],
      env: Map[String, String] = Map.empty
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource.loadFrom[IO](Nil, files, env, UrlPolicy.Dev).unsafeRunSync()

  private def clusters(result: Either[ConfigErrors, KuiConfig]): List[ClusterConfig] =
    result.fold(errors => fail(errors.render), _.clusters)

  private def problems(result: Either[ConfigErrors, KuiConfig]): List[ConfigProblem] =
    result match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def oneCluster(adminBlock: String): Path =
    ConfigFixtures.yaml(
      s"""kui:
         |  clusters:
         |    - name: One
         |      bootstrapServers: broker:9092
         |      admin:
         |$adminBlock
         |""".stripMargin
    )

  test("defaultsMatchTheDocumentedTable") {
    val defaults = AdminTuning.default

    assertEquals(defaults.requestTimeout, 30.seconds)
    assertEquals(defaults.apiTimeout, 60.seconds)
    assertEquals(defaults.topicChunkSize, 200)
    assertEquals(defaults.partitionChunkSize, 200)
    assertEquals(defaults.groupChunkSize, 50)
    assertEquals(defaults.parallelism, 4)
  }

  test("aClusterWithNoAdminSectionGetsEveryDefault") {
    val untuned = clusters(load(List(ConfigFixtures.fixture("clusters-admin.yaml")))).last

    assertEquals(untuned.name, "Untuned")
    assertEquals(untuned.admin, AdminTuning.default)
  }

  test("oneConfiguredKnobLeavesTheOtherFourAtTheirDefaults") {
    val tuned = clusters(load(List(ConfigFixtures.fixture("clusters-admin.yaml"))))(1)

    assertEquals(tuned.name, "One Knob")
    assertEquals(tuned.admin, AdminTuning.default.copy(parallelism = 1))
  }

  test("everyKnobIsRead") {
    val tuned = clusters(load(List(ConfigFixtures.fixture("clusters-admin.yaml")))).head

    assertEquals(tuned.admin.requestTimeout, 90.seconds)
    assertEquals(tuned.admin.apiTimeout, 5.minutes)
    assertEquals(tuned.admin.topicChunkSize, 50)
    assertEquals(tuned.admin.partitionChunkSize, 50)
    assertEquals(tuned.admin.groupChunkSize, 25)
    assertEquals(tuned.admin.parallelism, 8)
  }

  test("apiTimeoutBelowRequestTimeoutIsRejectedNamingBothKeysAndBothValues") {
    val found = problems(load(List(oneCluster("        apiTimeout: 5s"))))

    assertEquals(found.map(_.key), List("kui.clusters.0.admin.apiTimeout"))
    val message = found.head.problem
    assert(message.contains("kui.clusters.0.admin.requestTimeout"), message)
    assert(message.contains("30 seconds"), message)
    assert(message.contains("which is the default"), message)
    assert(message.contains("5 seconds"), message)
  }

  test("apiTimeoutEqualToRequestTimeoutIsAccepted") {
    val tuning = clusters(load(List(oneCluster("        apiTimeout: 30s")))).head.admin
    assertEquals(tuning.apiTimeout, 30.seconds)
  }

  test("eachOfTheFiveKnobsIsRejectedOutsideItsDocumentedRange") {
    val outOfRange = List(
      "        requestTimeout: 6m" -> "kui.clusters.0.admin.requestTimeout",
      "        apiTimeout: 16m" -> "kui.clusters.0.admin.apiTimeout",
      "        chunkSize: 1001" -> "kui.clusters.0.admin.chunkSize",
      "        groupChunkSize: 0" -> "kui.clusters.0.admin.groupChunkSize",
      "        parallelism: 33" -> "kui.clusters.0.admin.parallelism"
    )

    outOfRange.foreach { (line, key) =>
      val found = problems(load(List(oneCluster(line))))
      assert(found.exists(_.key == key), s"$line produced ${found.map(_.key).mkString(", ")}")
    }
  }

  test("boundsAreInclusive") {
    val inside = List(
      "        requestTimeout: 1s",
      "        requestTimeout: 5m\n        apiTimeout: 5m",
      "        chunkSize: 1",
      "        chunkSize: 1000",
      "        parallelism: 1",
      "        parallelism: 32"
    )
    inside.foreach(line => clusters(load(List(oneCluster(line)))))

    val outside = List("        requestTimeout: 999ms", "        requestTimeout: 5m1s")
    outside.foreach(line => problems(load(List(oneCluster(line)))))
  }

  test("theEnvironmentCanTuneOneClusterOnly") {
    val loaded = clusters(
      load(
        List(ConfigFixtures.fixture("clusters-admin.yaml")),
        env = Map("KUI_CLUSTERS_1_ADMIN_CHUNKSIZE" -> "7")
      )
    )

    assertEquals(loaded(1).admin.topicChunkSize, 7)
    assertEquals(loaded.head.admin.topicChunkSize, 50)
    assertEquals(loaded.last.admin.topicChunkSize, AdminTuning.default.topicChunkSize)
  }

  test("anUnknownKeyUnderAdminIsRejected") {
    val found = problems(load(List(oneCluster("        parallellism: 8"))))
    assertEquals(found.map(_.key), List("kui.clusters.0.admin.parallellism"))
  }
}
