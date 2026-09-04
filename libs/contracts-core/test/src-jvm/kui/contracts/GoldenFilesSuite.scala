package kui.contracts

import scala.io.Source
import scala.util.Using

import munit.FunSuite

/** That every golden constant is exactly the file committed beside it.
  *
  * The constants exist because a Scala.js suite cannot read a file (`GoldenDocuments` explains why),
  * and a duplicated contract is only safe if something checks the copies against each other. This is
  * that check, and it is JVM-only because it is the half that touches a filesystem.
  */
final class GoldenFilesSuite extends FunSuite {

  private def read(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"golden/$name is missing from the test resources")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
      .stripLineEnd

  (GoldenDocuments.all ++ kui.contracts.cluster.ClusterGoldenDocuments.all ++
    kui.contracts.topic.TopicGoldenDocuments.all).foreach { document =>
    test(s"${document._1} on disk matches the constant the cross-platform suites assert") {
      assertNoDiff(read(document._1), document._2)
    }
  }
}
