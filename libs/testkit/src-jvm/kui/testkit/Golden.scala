package kui.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import io.circe.parser.parse
import io.circe.{Json, Printer}

/** Comparing a document against a committed sample, and rewriting the sample on request.
  *
  * A golden file is how a wire format stops being an accident. The encoder is asserted against a file a
  * reviewer can read, so changing the format means changing a file in a diff — which is a conversation —
  * rather than changing a test's expectation in passing.
  *
  * `KUI_UPDATE_GOLDEN=1` rewrites the files instead of failing. That is the difference between a golden-file
  * suite people maintain and one people delete: updating twelve documents after an intended change is one
  * environment variable and a reviewable diff.
  *
  * JVM only, because it reads and writes files. Cross-compiled suites keep their samples as string constants
  * and prove them equal to the files here.
  */
object Golden {

  private val UpdateFlag: String = "KUI_UPDATE_GOLDEN"

  /** Sorted keys and two-space indentation, so a diff shows what changed rather than what moved. */
  private val printer: Printer = Printer.spaces2SortKeys

  /** Where a module keeps its samples, relative to the module directory. */
  val Directory: String = "test/resources/golden"

  /** Whether this run rewrites samples instead of asserting them. */
  def updateRequested: Boolean =
    sys.env.get(UpdateFlag).exists(value => value == "1" || value == "true")

  /** The path of one sample, resolved against the module directory the suite is run from.
    *
    * Mill runs a test with the module directory as the working directory, so a relative path is enough and no
    * build-time wiring is needed.
    */
  def pathOf(name: String, root: Path = Paths.get(Directory)): Path = root.resolve(name)

  /** Reads a sample. Fails the test with the command to create it when it does not exist yet. */
  def read(name: String, root: Path = Paths.get(Directory))(using munit.Location): Json = {
    val path = pathOf(name, root)
    if !Files.exists(path) then
      munit.Assertions.fail(
        s"golden file $path does not exist; run with $UpdateFlag=1 to create it"
      )
    else
      parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)) match {
        case Right(json) => json
        case Left(failure) =>
          munit.Assertions.fail(s"golden file $path is not valid JSON: ${failure.message}")
      }
  }

  /** Asserts that `actual` is the committed sample, or rewrites the sample when the update flag is set. The
    * diff names the file, so a failure says which contract moved.
    */
  def assertJson(
      actual: Json,
      name: String,
      root: Path = Paths.get(Directory),
      update: Boolean = updateRequested
  )(using munit.Location): Unit = {
    val path = pathOf(name, root)
    val rendered = printer.print(actual)

    if update then write(path, rendered)
    else if !Files.exists(path) then
      munit.Assertions.fail(s"golden file $path does not exist; run with $UpdateFlag=1 to create it")
    else
      munit.Assertions.assertNoDiff(
        rendered,
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8).stripLineEnd,
        clue = s"$path is out of date; run with $UpdateFlag=1 to update it"
      )
  }

  private def write(path: Path, content: String): Unit = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(path, (content + "\n").getBytes(StandardCharsets.UTF_8))
    ()
  }

  /** Every sample a module holds, for a suite that asserts none has been orphaned. */
  def names(root: Path = Paths.get(Directory)): List[String] = {
    val directory = root
    if !Files.isDirectory(directory) then Nil
    else
      Files
        .list(directory)
        .iterator()
        .asScala
        .map(_.getFileName.toString)
        .filter(_.endsWith(".json"))
        .toList
        .sorted
  }
}
