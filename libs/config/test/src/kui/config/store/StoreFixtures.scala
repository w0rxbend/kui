package kui.config.store

import scala.io.Source
import scala.util.Using

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.circe.Json
import io.circe.parser.parse

/** Reads a committed golden file out of `libs/config/test/resources/store` and parses it.
  *
  * Golden files are compared as parsed JSON rather than as text. Comparing text would make the suite fail
  * on a trailing newline or a reordered field, which is noise; comparing parsed JSON fails on exactly the
  * thing that matters, which is a changed format.
  */
object StoreFixtures {

  def golden(name: String): Json = {
    val text = read(s"/store/$name")
    parse(text).fold(failure => sys.error(s"/store/$name is not valid JSON: ${failure.message}"), identity)
  }

  /** Copies the committed `filestore` fixtures out of the classpath into a real directory.
    *
    * `FileConfigStore` takes a `java.nio.file.Path`, because that is what an operator mounts, and a
    * classpath resource is not one. Copying the fixtures is the smallest bridge, and it keeps them
    * visible in the repository next to the suite that reads them.
    */
  def fileStoreRoot(files: List[String] = defaultFileStore): Path = {
    val root = Files.createTempDirectory("kui-filestore")
    root.toFile.deleteOnExit()
    files.foreach { relative =>
      val target = root.resolve(relative)
      val _ = Files.createDirectories(target.getParent)
      write(target, read(s"/store/filestore/$relative"))
    }
    root
  }

  val defaultFileStore: List[String] = List("cluster/local.json", "cluster/broken.json", "settings/global.json")

  /** Writes an extra file into an existing root, for the cases where an inline document is clearer than
    * another committed fixture.
    */
  def writeInto(root: Path, relative: String, contents: String): Unit = {
    val target = root.resolve(relative)
    val _ = Files.createDirectories(target.getParent)
    write(target, contents)
  }

  private def read(resource: String): String =
    Using.resource(
      Option(getClass.getResourceAsStream(resource))
        .getOrElse(sys.error(s"$resource is missing from the test resources"))
    )(stream => Source.fromInputStream(stream, "UTF-8").mkString)

  private def write(target: Path, contents: String): Unit = {
    val _ = Files.write(target, contents.getBytes(StandardCharsets.UTF_8))
    target.toFile.deleteOnExit()
  }
}
