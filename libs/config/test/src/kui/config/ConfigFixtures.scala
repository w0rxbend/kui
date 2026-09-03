package kui.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.io.Source
import scala.util.Using

/** Turns a committed test fixture into a real file on disk.
  *
  * The loader takes `java.nio.file.Path`s, because that is what a process is given on its command
  * line, but the fixtures travel with the tests as classpath resources. Copying a resource into a
  * temporary file is the smallest bridge between the two, and it keeps the fixtures visible in the
  * repository next to the suite that reads them.
  */
object ConfigFixtures {

  /** `valid.yaml` and friends, from `libs/config/test/resources/config`. */
  def fixture(name: String): Path = write(name, read(s"/config/$name"))

  /** An ad-hoc YAML document, for the cases where writing the file inline is clearer than adding
    * another committed fixture.
    */
  def yaml(contents: String): Path = write("inline.yaml", contents)

  private def read(resource: String): String =
    Using
      .resource(
        Option(getClass.getResourceAsStream(resource))
          .getOrElse(sys.error(s"$resource is missing from the test resources"))
      )(stream => Source.fromInputStream(stream, "UTF-8").mkString)

  private def write(name: String, contents: String): Path = {
    val directory = Files.createTempDirectory("kui-config")
    val file = directory.resolve(name)
    val _ = Files.write(file, contents.getBytes(StandardCharsets.UTF_8))
    directory.toFile.deleteOnExit()
    file.toFile.deleteOnExit()
    file
  }
}
