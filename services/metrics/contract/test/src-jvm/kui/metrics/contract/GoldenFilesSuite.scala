package kui.metrics.contract

import scala.io.Source
import scala.util.Using

import munit.FunSuite

/** That every golden constant is exactly the file committed beside it.
  *
  * The constants exist because a Scala.js suite cannot read a file (`GoldenDocuments` explains why), and a
  * duplicated contract is only safe if something checks the copies against each other. This is that check,
  * and it is JVM-only because it is the half that touches a filesystem.
  *
  * It carries more weight here than in the other contract modules: the browser reads these **files** rather
  * than the constants, so a constant that drifted from its file would leave the two sides of the wire
  * asserting different documents again — which is the exact failure this module was added to close.
  */
final class GoldenFilesSuite extends FunSuite {

  private def read(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"golden/$name is missing from the test resources")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
      .stripLineEnd

  GoldenDocuments.all.foreach { document =>
    test(s"${document._1} on disk matches the constant the cross-platform suites assert") {
      assertNoDiff(read(document._1), document._2)
    }
  }
}
