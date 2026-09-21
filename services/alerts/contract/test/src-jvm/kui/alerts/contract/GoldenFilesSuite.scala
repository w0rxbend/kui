package kui.alerts.contract

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.circe.parser.parse
import munit.FunSuite

import kui.alerts.contract.dto.AlertChangeDto

/** The committed directory itself: which files are in it, and that each is what its encoder renders.
  *
  * ==Why this is not `AlertResponsesSuite`==
  *
  * That suite reads the goldens off the **classpath**, which is a copy Mill made of this directory. It
  * therefore proves that a document with a case is correct, and it cannot see the two failures that cost a
  * cross-language wire: a file committed here that no case reads, and a file read from a path nobody kept.
  * `frontend/packages/kernel` decodes these documents by **repository path**, not by classpath, so the path
  * is the contract and it is asserted here against the real directory.
  *
  * JVM-only for the reason `services/metrics`' twin is: it is the half that touches a filesystem.
  *
  * ==What a red here means==
  *
  * A new document was committed without a case, or a case names a document that is not committed. Both are
  * the shape that let wave 5 ship two sides of one wire against two hand-written literals: each side was
  * green against something, and no list said what the pair was supposed to be.
  */
final class GoldenFilesSuite extends FunSuite {

  /** The path both languages read. Stated as a string because the browser suite that reads the same files
    * states it too, and a path written twice is only a contract if a red says so when the two differ.
    */
  private val goldenDirectory = "services/alerts/contract/test/resources/golden"

  /** The repository root, found by walking up to the directory holding `build.mill`.
    *
    * A test runs in a sandbox directory, so a relative path means nothing — `ShippedConfigurationSuite`
    * establishes the pattern and the reason.
    */
  private lazy val golden: Path =
    Iterator
      .iterate(Option(Path.of("").toAbsolutePath))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .map(_.resolve(goldenDirectory))
      .getOrElse(fail("no build.mill above the working directory, so the repository root is not findable"))

  private lazy val committed: List[String] =
    Using.resource(Files.list(golden))(files =>
      files.iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".json")).toList.sorted
    )

  test("every committed document has a case, and every case has a committed document") {
    assertEquals(committed, AlertDocuments.all.map(_._1).sorted)
  }

  test("each committed document is what its encoder renders, at the path the browser reads it from") {
    AlertDocuments.all.foreach { (name, encoded) =>
      val file = golden.resolve(name)

      assert(Files.exists(file), s"$goldenDirectory/$name is named by a case and is not committed")

      val onDisk = parse(Files.readString(file))
        .fold(failure => fail(s"$name is not JSON: ${failure.message}"), identity)

      assertNoDiff(onDisk.spaces2, encoded.spaces2)
    }
  }

  test("the stream frame carries the event name the browser registers its listener under") {
    // The one field of this wire that is not a DTO, and therefore the one nothing could check. The
    // browser's `ALERTS_EVENT_NAME` is asserted against this file's `event`; this case is the other end
    // of it, and the expectation is a literal rather than `AlertChangeDto.EventName` on purpose —
    // comparing the constant with itself is what the assertion it replaces did.
    assertEquals(AlertDocuments.streamFrame.hcursor.get[String]("event"), Right("alerts"))
    assertEquals(AlertChangeDto.EventName, "alerts")
    assertEquals(AlertsStreamEndpoint.EventName, "alerts")
  }
}
